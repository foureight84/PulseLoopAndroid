package com.pulseloop.ring

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Ported from [RingBLEClient] in RingBLEClient.swift.
 * Device-agnostic Android BLE client for any supported wearable.
 *
 * The client owns BluetoothLeScanner + BluetoothGatt plumbing — scanning, connecting,
 * discovering services/characteristics, serializing writes, and fanning out notifications.
 * Protocol-specific decisions are delegated to [WearableDriver] / [RingSyncEngine].
 *
 * Registry: walk [coordinators]; first whose [matches] claims a peripheral wins.
 * Adding a new wearable = append one entry.
 */
@SuppressLint("MissingPermission")
class RingBLEClient(private val context: Context) {

    /** Registry of supported wearables. Adding a wearable = append one entry. */
    private val coordinators: List<WearableCoordinator> = listOf(
        JringCoordinator,
        ColmiCoordinator,
    )

    // MARK: Observable state

    data class BLEState(
        val connectionState: RingConnectionState = RingConnectionState.IDLE,
        val discovered: List<DiscoveredRing> = emptyList(),
        val batteryPercent: Int? = null,
        val isBluetoothReady: Boolean = false,
        val lastError: String? = null,
        val activeDeviceType: RingDeviceType? = null,
        val activeCapabilities: Set<WearableCapability> = emptySet(),
        val firmwareVersion: String? = null,
    )

    data class DiscoveredRing(
        val id: String,
        val name: String,
        val rssi: Int,
        val isLikelyRing: Boolean,
        val deviceType: RingDeviceType?,
    )

    private val _state = MutableStateFlow(BLEState())
    val state: StateFlow<BLEState> = _state.asStateFlow()

    var onConnected: (suspend () -> Unit)? = null
    var onFirmwareRead: ((String) -> Unit)? = null
    val syncEngine: RingSyncEngine? get() = activeSyncEngine
    private var keepaliveJob: Job? = null

    // MARK: Bluetooth infrastructure

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter = bluetoothManager.adapter
    private val scanner: BluetoothLeScanner? = bluetoothAdapter.bluetoothLeScanner

    private var bluetoothGatt: BluetoothGatt? = null
    private var discoveredPeripherals: MutableMap<String, BluetoothDevice> = mutableMapOf()
    // Advertised name of the device we're connecting to, captured at connect time so it can be
    // persisted as the device's display name (the connect callbacks otherwise only know the MAC).
    private var connectingName: String? = null

    // Characteristics
    private var writeChar: BluetoothGattCharacteristic? = null
    private var commandChar: BluetoothGattCharacteristic? = null
    private var notifyChars: MutableMap<UUID, BluetoothGattCharacteristic> = mutableMapOf()
    private var batteryChar: BluetoothGattCharacteristic? = null

    // MARK: Active driver/engine

    private var activeCoordinator: WearableCoordinator? = null
    private var activeDriver: WearableDriver? = null
    private var activeSyncEngine: RingSyncEngine? = null

    // Set while a "Forget" is waiting for the ring's UNBOND_ACK (0x4B) before teardown.
    private var forgetPending = false
    private var forgetJob: Job? = null

    // MARK: GATT operation serialization
    //
    // Android's BLE stack permits exactly ONE outstanding GATT operation at a time.
    // Issuing a read / characteristic-write / descriptor-write while another is still
    // in flight makes the new call return false and be silently dropped — the classic
    // cause of a ring that connects and discovers services but never finishes enabling
    // notifications, so it hangs forever on "Connecting…". (Seen on the Colmi R10, which
    // — unlike the R02 — exposes a 0x180A DIS service, so a firmware read was issued and
    // blocked the CCCD descriptor write that gates the CONNECTED transition.)
    //
    // Every GATT operation is funnelled through this single FIFO queue so they run
    // strictly one-at-a-time, each retired by its matching callback (onCharacteristicWrite
    // / onCharacteristicRead / onDescriptorWrite → completeOp) which pumps the next one.

    private sealed interface GattOp {
        /** A command/keepalive payload for the write (or command) channel. */
        class CommandWrite(val data: ByteArray, val useCommandChannel: Boolean) : GattOp
        class Read(val characteristic: BluetoothGattCharacteristic) : GattOp
        class DescriptorWrite(val descriptor: BluetoothGattDescriptor, val value: ByteArray) : GattOp
    }

    private val opQueue = ArrayDeque<GattOp>()
    private var opInFlight = false
    private var opSeq = 0

    // MARK: Connection state

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val prefs: SharedPreferences =
        context.getSharedPreferences("ring_ble", Context.MODE_PRIVATE)

    // MARK: Liveness watchdog
    //
    // The ring does not echo the 0x3A keepalive, so the only proof the link is alive is a
    // GATT-level write ACK or an inbound notification. When the OS drops the link during
    // Doze it often never delivers STATE_DISCONNECTED, leaving a "zombie" GATT: state stays
    // CONNECTED, our writes go nowhere, and nothing ever syncs. The watchdog detects this
    // (no GATT activity for too long) and forces a fresh reconnect.
    @Volatile private var lastActivityAt: Long = 0L
    private var connectingStartedAt: Long = 0L
    private var watchdogJob: Job? = null

    init { startConnectionWatchdog() }

    // MARK: Public API

    /** Check if required BLE permissions are granted. */
    fun hasPermissions(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    fun startScanning() {
        if (!hasPermissions()) {
            updateState { copy(lastError = "BLE permissions not granted") }
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            updateState { copy(lastError = "Bluetooth is not powered on") }
            return
        }
        updateState {
            copy(
                connectionState = RingConnectionState.SCANNING,
                discovered = emptyList(),
                lastError = null,
            )
        }
        discoveredPeripherals.clear()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(null, scanSettings, scanCallback)
    }

    fun stopScanning() {
        scanner?.stopScan(scanCallback)
        if (_state.value.connectionState == RingConnectionState.SCANNING) {
            updateState { copy(connectionState = RingConnectionState.IDLE) }
        }
    }

    fun connectTo(id: String) {
        val target = discoveredPeripherals[id] ?: run {
            try { bluetoothAdapter.getRemoteDevice(id) } catch (_: Exception) { null }
        } ?: run {
            updateState { copy(lastError = "Ring no longer available; scan again.") }
            return
        }
        val matchedType = _state.value.discovered.firstOrNull { it.id == id }?.deviceType
        connectingName = _state.value.discovered.firstOrNull { it.id == id }?.name ?: target.name
        beginConnect(target, matchedType)
    }

    fun connectLastKnown() {
        if (!bluetoothAdapter.isEnabled) return
        val lastId = lastKnownIdentifier ?: return
        val device = try {
            bluetoothAdapter.getRemoteDevice(lastId)
        } catch (_: Exception) { null }
        if (device != null) {
            beginConnect(device, lastKnownDeviceType)
        } else {
            startScanning()
        }
    }

    /**
     * Re-establish the link if it has silently dropped — e.g. the OS tore the GATT
     * down while the phone was idle (Doze) and autoConnect never recovered. Safe to
     * call repeatedly (e.g. every time the app returns to the foreground): a live or
     * in-progress connection is left untouched.
     */
    fun reconnectIfNeeded() {
        if (!bluetoothAdapter.isEnabled || !hasPermissions()) return
        val lastId = lastKnownIdentifier ?: return
        when (_state.value.connectionState) {
            RingConnectionState.CONNECTING, RingConnectionState.SCANNING -> return
            RingConnectionState.CONNECTED -> {
                // Trust but verify — the OS can drop the GATT during Doze before the
                // disconnect callback is delivered, leaving our state stale-CONNECTED.
                // Confirm against the OS profile state before deciding to do nothing.
                val dev = try { bluetoothAdapter.getRemoteDevice(lastId) } catch (_: Exception) { null }
                val live = dev != null &&
                    bluetoothManager.getConnectionState(dev, BluetoothProfile.GATT) ==
                        BluetoothProfile.STATE_CONNECTED
                if (live) return
            }
            else -> {}
        }
        connectLastKnown()
    }

    /**
     * Periodic liveness check. While CONNECTED, the keepalive writes every 15s and each
     * ACK refreshes [lastActivityAt]; if no GATT activity (write ACK or inbound notify)
     * arrives for [LINK_STALE_MS], the link is a zombie → tear it down and reconnect.
     * While DISCONNECTED with a known ring, retry the connection so a failed silent
     * reconnect doesn't leave us stuck offline.
     */
    private fun startConnectionWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                try { connectionWatchdogTick() } catch (_: Exception) {}
            }
        }
    }

    private fun connectionWatchdogTick() {
        if (!bluetoothAdapter.isEnabled || !hasPermissions()) return
        if (lastKnownIdentifier == null) return
        when (_state.value.connectionState) {
            RingConnectionState.CONNECTED -> {
                val idleFor = System.currentTimeMillis() - lastActivityAt
                if (lastActivityAt > 0 && idleFor > LINK_STALE_MS) {
                    Log.w("RingBLEClient", "Link stale (${idleFor}ms, no GATT activity) — forcing reconnect")
                    forceReconnect()
                }
            }
            RingConnectionState.DISCONNECTED,
            RingConnectionState.FAILED,
            RingConnectionState.IDLE -> connectLastKnown()
            RingConnectionState.CONNECTING -> {
                // A reconnect can hang indefinitely (autoConnect pending against a device
                // that never re-advertises). Time it out and retry with a fresh GATT.
                if (connectingStartedAt > 0 &&
                    System.currentTimeMillis() - connectingStartedAt > CONNECT_TIMEOUT_MS) {
                    Log.w("RingBLEClient", "Connect attempt hung >${CONNECT_TIMEOUT_MS}ms — retrying")
                    forceReconnect()
                }
            }
            else -> {}  // SCANNING — let the scan proceed
        }
    }

    /** Hard reset: drop the (possibly zombie) GATT and start a fresh connection. */
    private fun forceReconnect() {
        // beginConnect (via connectLastKnown) closes the stale GATT before opening a new one.
        connectLastKnown()
    }

    /**
     * Graceful disconnect initiated by the user (e.g., navigating away).
     * Does NOT clear bond or GATT cache — keeps pairing intact for silent reconnect.
     * With autoConnect=true, Android will automatically reconnect when the ring
     * comes back in range.
     */
    fun disconnect() {
        stopKeepalive()
        scanner?.stopScan(scanCallback)
        bluetoothGatt?.disconnect()
        // Do NOT close the GATT or clear bond — let autoConnect handle reconnection.
        // The official app only closes GATT/clears bond on explicit "Forget Ring".
        updateState { copy(connectionState = RingConnectionState.DISCONNECTED) }
    }

    /**
     * Explicit "Forget Ring" action. Mirrors the official app: send the ring-side
     * UNBOND (0x4B 05) and wait for the ring's UNBOND_ACK before tearing down, so
     * the ring drops its binding to us and re-advertises for other apps. Falls back
     * to an unconditional teardown if the ring is offline or never acks.
     */
    fun forget() {
        // Clear the known-ring id up front so the watchdog/auto-reconnect can't grab
        // the ring back during or after the unbind window.
        prefs.edit().remove(LAST_PERIPHERAL_KEY).remove(LAST_DEVICE_TYPE_KEY).apply()

        val gatt = bluetoothGatt
        if (gatt != null && writeChar != null &&
            _state.value.connectionState == RingConnectionState.CONNECTED) {
            forgetPending = true
            enqueueWrite(RingEncoder.makeUnbindCommand())  // 0x4B 05 00 01
            forgetJob?.cancel()
            forgetJob = scope.launch {
                delay(UNBIND_ACK_TIMEOUT_MS)
                if (forgetPending) {
                    Log.w("RingBLEClient", "Unbind ACK not received in ${UNBIND_ACK_TIMEOUT_MS}ms — forcing teardown")
                    finalizeForget()
                }
            }
        } else {
            finalizeForget()
        }
    }

    /** Tear down the link: clear the GATT cache, remove any OS bond, close the GATT. */
    private fun finalizeForget() {
        forgetPending = false
        forgetJob?.cancel(); forgetJob = null
        stopKeepalive()
        scanner?.stopScan(scanCallback)
        bluetoothGatt?.let { gatt ->
            try { gatt::class.java.getMethod("refresh").invoke(gatt) } catch (_: Exception) {}
            try { gatt.device::class.java.getMethod("removeBond").invoke(gatt.device) } catch (_: Exception) {}
            gatt.disconnect()
            gatt.close()
        }
        bluetoothGatt = null
        writeChar = null; commandChar = null; notifyChars.clear(); batteryChar = null
        opInFlight = false; opQueue.clear()
        prefs.edit().remove(LAST_PERIPHERAL_KEY).remove(LAST_DEVICE_TYPE_KEY).apply()
        updateState { copy(connectionState = RingConnectionState.IDLE, activeDeviceType = null, activeCapabilities = emptySet()) }
    }

    fun enqueueWrite(data: ByteArray) {
        val framed = activeDriver?.frame(data) ?: data
        val useCommand = activeDriver?.usesCommandChannel(framed) ?: false
        enqueueOp(GattOp.CommandWrite(framed, useCommand))
    }

    fun readBattery() {
        val ch = batteryChar ?: return
        enqueueOp(GattOp.Read(ch))
    }

    private val lastKnownIdentifier: String?
        get() = prefs.getString(LAST_PERIPHERAL_KEY, null)
    private val lastKnownDeviceType: RingDeviceType?
        get() = prefs.getString(LAST_DEVICE_TYPE_KEY, null)?.let { type ->
            try { RingDeviceType.valueOf(type) } catch (_: Exception) { null }
        }

    // MARK: Internal

    private fun beginConnect(target: BluetoothDevice, deviceType: RingDeviceType?) {
        scanner?.stopScan(scanCallback)
        // Close any stale GATT from a previous (now-dead) connection before opening a new
        // one. Reconnect attempts after an idle drop would otherwise leak GATT clients and
        // can collide with the orphaned handle. A fresh GATT mirrors the proven
        // force-close-and-reopen recovery path.
        bluetoothGatt?.let { old ->
            try { old.disconnect() } catch (_: Exception) {}
            try { old.close() } catch (_: Exception) {}
        }
        bluetoothGatt = null
        writeChar = null; commandChar = null; notifyChars.clear(); batteryChar = null
        opInFlight = false; opQueue.clear()
        val coordinator = coordinators.firstOrNull { it.deviceType == deviceType } ?: JringCoordinator
        installDriver(coordinator)
        connectingStartedAt = System.currentTimeMillis()
        updateState { copy(connectionState = RingConnectionState.CONNECTING) }
        // Mirror the attempt to the persisted state so the Today/Settings views show
        // "Connecting…" rather than a stale "Connected" while autoConnect is pending.
        PulseEventBus.publishBlocking(
            PulseEvent.DeviceStateChanged(RingConnectionState.CONNECTING, target.address)
        )

        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // autoConnect=true matches the official app — connects silently
            // in the background without showing the Bluetooth status bar icon
            target.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            target.connectGatt(context, true, gattCallback)
        }
    }

    /**
     * Send a keepalive ping every 15s to prevent the ring's ~20s idle timeout.
     * Uses 0x3A (CMD_KEEPALIVE_PING) — the official SDK's lightweight ping/pong
     * command. The ring responds with 0x3A which also triggers setAppId().
     */
    private fun startKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            while (isActive) {
                delay(15_000)
                val cmd = ByteArray(20)
                cmd[0] = 0x3A.toByte()  // CMD_KEEPALIVE_PING
                enqueueWrite(cmd)
            }
        }
    }

    private fun stopKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = null
    }

    private fun installDriver(coordinator: WearableCoordinator) {
        val driver = coordinator.makeDriver { enqueueWrite(it) }
        activeCoordinator = coordinator
        activeDriver = driver
        activeSyncEngine = driver.makeSyncEngine()
        updateState {
            copy(
                activeDeviceType = coordinator.deviceType,
                activeCapabilities = coordinator.capabilities,
            )
        }
    }

    private fun enqueueOp(op: GattOp) {
        opQueue.addLast(op)
        pumpOps()
    }

    /** Retire the in-flight op (from a GATT callback or the timeout) and pump the next. */
    private fun completeOp() {
        opInFlight = false
        pumpOps()
    }

    private fun pumpOps() {
        val gatt = bluetoothGatt ?: return
        if (opInFlight || opQueue.isEmpty()) return

        val op = opQueue.first()
        val issued: Boolean = when (op) {
            is GattOp.CommandWrite -> {
                val wChar = writeChar
                if (wChar == null) {
                    // Write channel not bound yet (services not discovered) — leave the op
                    // queued; onServicesDiscovered will pump again once chars are wired up.
                    return
                }
                opQueue.removeFirst()
                val target = if (op.useCommandChannel) commandChar ?: wChar else wChar
                target.value = op.data
                PulseEventBus.publishBlocking(
                    PulseEvent.RawPacket(PacketDirection.OUTGOING, op.data,
                        RingDecodedEvent.CommandAck(commandId = if (op.data.isNotEmpty()) op.data[0].toUByte() else 0u))
                )
                gatt.writeCharacteristic(target)
            }
            is GattOp.Read -> {
                opQueue.removeFirst()
                gatt.readCharacteristic(op.characteristic)
            }
            is GattOp.DescriptorWrite -> {
                opQueue.removeFirst()
                op.descriptor.value = op.value
                gatt.writeDescriptor(op.descriptor)
            }
        }

        if (!issued) {
            // The stack rejected the op at issue time (characteristic not readable/writable,
            // or a transient busy state). Don't strand the queue — log and move to the next.
            Log.w("RingBLEClient", "GATT op rejected at issue: ${op::class.simpleName}")
            pumpOps()
            return
        }

        opInFlight = true

        // Guard against a missing completion callback. If the ACK never comes, opInFlight
        // would stay true forever and the entire queue (history queries, keepalive,
        // notification setup, …) would deadlock — exactly the "one command then silence"
        // failure. Time the op out and unblock the queue.
        val seq = ++opSeq
        scope.launch {
            delay(OP_TIMEOUT_MS)
            if (opInFlight && seq == opSeq) {
                Log.w("RingBLEClient", "GATT op ACK timed out — unblocking queue")
                completeOp()
            }
        }
    }

    private fun matchDeviceType(name: String?, scanRecord: ScanRecord?): RingDeviceType? {
        val serviceUUIDs = scanRecord?.serviceUuids?.map { it.uuid.toString() } ?: emptyList()
        // Iterate all manufacturer-specific data entries to find a match
        var mfg: ByteArray? = null
        scanRecord?.manufacturerSpecificData?.let { data ->
            if (data.size() > 0) mfg = data.valueAt(0)
        }
        val info = AdvertisementInfo(serviceUUIDs, mfg)
        return coordinators.firstOrNull { it.matches(name, info) }?.deviceType
    }

    private inline fun updateState(crossinline update: BLEState.() -> BLEState) {
        _state.value = _state.value.update()
    }

    // MARK: Scan callback

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val scanRecord = result.scanRecord ?: return
            val name = scanRecord.deviceName ?: device.name ?: return
            if (name.isEmpty()) return

            val matchedType = matchDeviceType(name, scanRecord)
            discoveredPeripherals[device.address] = device

            val ring = DiscoveredRing(
                id = device.address,
                name = name,
                rssi = result.rssi,
                isLikelyRing = matchedType != null,
                deviceType = matchedType,
            )

            updateState {
                val updated = discovered.toMutableList()
                val idx = updated.indexOfFirst { it.id == ring.id }
                if (idx >= 0) updated[idx] = ring else updated.add(ring)
                updated.sortWith(compareByDescending<DiscoveredRing> { it.isLikelyRing }.thenByDescending { it.rssi })
                copy(discovered = updated)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            updateState { copy(lastError = "Scan failed: $errorCode") }
        }
    }

    // MARK: GATT callback

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        bluetoothGatt = gatt
                        // Do NOT create an OS-level bond. The 56ff protocol is unencrypted
                        // (verified in the official SDK's SampleGattAttributes — no auth on
                        // 33f3/33f4), so bonding is not required to read/write characteristics.
                        // We intentionally skip it because bonding:
                        //   (a) makes the OS treat the ring as a connected BT device, which is
                        //       what lights up the phone's status-bar Bluetooth icon, and
                        //   (b) previously ran here racing requestMtu()/discoverServices(),
                        //       a known Android instability that caused the frequent disconnects.
                        // The link is kept alive by autoConnect=true + the 0x3A keepalive.
                        // Request a high-priority connection interval, matching the official app
                        // (BluetoothLeService.requestConnectionPriority(1) on connect).
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        gatt.requestMtu(512)
                        gatt.discoverServices()
                    } else {
                        updateState { copy(lastError = "GATT connect failed: $status") }
                        handleDisconnect(gatt)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    handleDisconnect(gatt)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            // Log all discovered service UUIDs for diagnostics
            val serviceUuids = gatt.services.map { it.uuid.toString() }
            android.util.Log.i("RingBLEClient", "Services: ${serviceUuids.joinToString(", ")}")

            // Store service list in a log event for export
            scope.launch(Dispatchers.IO) {
                try {
                    val db = com.pulseloop.data.PulseLoopDatabase.getInstance(context.applicationContext)
                    val device = db.deviceDao().current()
                    if (device != null) {
                        db.deviceDao().upsert(device.copy(
                            capabilitiesRaw = device.capabilitiesRaw + "|services:" + serviceUuids.joinToString(","),
                            updatedAt = System.currentTimeMillis()
                        ))
                    }
                } catch (_: Exception) {}
            }

            val driver = activeDriver ?: return

            // Bind the ring's own service first and enable its notifications BEFORE any
            // other GATT work. The CONNECTED transition is gated on a notify-CCCD descriptor
            // write completing (see onDescriptorWrite), and every GATT op now runs strictly
            // one-at-a-time via the op queue — so queuing the notify writes first means the
            // ring becomes usable as soon as possible instead of waiting behind firmware/
            // battery reads. (This is the R10 fix: its 0x180A DIS firmware read used to be
            // issued ahead of the CCCD write and silently dropped it, so it never connected.)
            for (service in gatt.services) {
                val svcUuid = service.uuid.toString()
                val isRingSvc = driver.serviceUUIDs.any { it == svcUuid }
                val isBatterySvc = driver.batteryServiceUUID != null && svcUuid == driver.batteryServiceUUID
                if (!isRingSvc && !isBatterySvc) continue

                for (ch in service.characteristics) {
                    val uuid = ch.uuid.toString()
                    when {
                        uuid == driver.writeUUID -> writeChar = ch
                        uuid == driver.commandUUID -> commandChar = ch
                        driver.notifyUUIDs.any { it == uuid } -> {
                            notifyChars[ch.uuid] = ch
                            gatt.setCharacteristicNotification(ch, true)
                            ch.getDescriptor(CCCD_UUID)?.let { desc ->
                                enqueueOp(GattOp.DescriptorWrite(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE))
                            }
                        }
                        uuid == driver.batteryCharUUID -> batteryChar = ch
                    }
                }
            }

            // Standard BLE health services — blood pressure (0x1810) + glucose (0x1808).
            val bpServiceUuid = java.util.UUID.fromString("00001810-0000-1000-8000-00805f9b34fb")
            val bpMeasureUuid = java.util.UUID.fromString("00002a35-0000-1000-8000-00805f9b34fb")
            val glucoseServiceUuid = java.util.UUID.fromString("00001808-0000-1000-8000-00805f9b34fb")
            val glucoseMeasureUuid = java.util.UUID.fromString("00002a18-0000-1000-8000-00805f9b34fb")
            for (service in gatt.services) {
                val measureUuid = when (service.uuid) {
                    bpServiceUuid -> bpMeasureUuid
                    glucoseServiceUuid -> glucoseMeasureUuid
                    else -> null
                } ?: continue
                service.getCharacteristic(measureUuid)?.let { ch ->
                    gatt.setCharacteristicNotification(ch, true)
                    ch.getDescriptor(CCCD_UUID)?.let { desc ->
                        enqueueOp(GattOp.DescriptorWrite(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE))
                    }
                }
            }

            // Informational reads come last so they never block the notify-CCCD writes that
            // gate CONNECTED. Battery first, then firmware (scan ALL services for 0x2A26/0x2A28;
            // the 56ff ring exposes these even without advertising the 0x180A DIS service).
            batteryChar?.let { enqueueOp(GattOp.Read(it)) }

            val fwUuid = java.util.UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
            val swUuid = java.util.UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb")
            for (service in gatt.services) {
                if (service.uuid == DIS_SERVICE_UUID) {
                    service.getCharacteristic(FW_REV_UUID)?.let { enqueueOp(GattOp.Read(it)) }
                }
                service.getCharacteristic(fwUuid)?.let { enqueueOp(GattOp.Read(it)) }
                service.getCharacteristic(swUuid)?.let { enqueueOp(GattOp.Read(it)) }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            lastActivityAt = System.currentTimeMillis()  // GATT read completed — link is alive
            completeOp()  // retire the in-flight op regardless of payload, before any early return
            if (status != BluetoothGatt.GATT_SUCCESS) return
            if (characteristic.uuid.toString() == activeDriver?.batteryCharUUID) {
                val value = characteristic.value
                if (value != null && value.isNotEmpty()) {
                    val pct = value[0].toInt() and 0xFF
                    updateState { copy(batteryPercent = pct) }
                    PulseEventBus.publishBlocking(PulseEvent.BatteryLevel(pct))
                }
            } else if (characteristic.uuid == FW_REV_UUID ||
                       characteristic.uuid.toString().startsWith("00002a26") ||
                       characteristic.uuid.toString().startsWith("00002a28")) {
                val fw = characteristic.value?.let { String(it) }?.trim()
                if (fw != null && fw.isNotEmpty()) {
                    updateState { copy(firmwareVersion = fw) }
                    onFirmwareRead?.invoke(fw)
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            lastActivityAt = System.currentTimeMillis()  // GATT ACK — link is alive
            completeOp()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic
        ) {
            lastActivityAt = System.currentTimeMillis()  // inbound notify — link is alive
            val value = characteristic.value ?: return
            val uuid = characteristic.uuid.toString()

            // Standard BLE health services — read before the ring-service guard
            if (uuid.startsWith("00002a35")) {
                // Blood Pressure Measurement — IEEE 11073 SFLOAT
                if (value.size >= 7) {
                    val systolic = decodeSFLOAT(value[1], value[2])
                    val diastolic = decodeSFLOAT(value[3], value[4])
                    PulseEventBus.publishBlocking(PulseEvent.HistoryMeasurement(MeasurementKind.BLOOD_PRESSURE_SYSTOLIC, systolic, java.time.Instant.now()))
                    PulseEventBus.publishBlocking(PulseEvent.HistoryMeasurement(MeasurementKind.BLOOD_PRESSURE_DIASTOLIC, diastolic, java.time.Instant.now()))
                }
                return
            }
            if (uuid.startsWith("00002a18")) {
                // Glucose Measurement — IEEE 11073 SFLOAT in kg/L → mg/dL
                if (value.size >= 12) {
                    val glucoseKgL = decodeSFLOAT(value[10], value[11])
                    PulseEventBus.publishBlocking(PulseEvent.HistoryMeasurement(MeasurementKind.BLOOD_SUGAR, glucoseKgL * 100000.0, java.time.Instant.now()))
                }
                return
            }

            val driver = activeDriver ?: return
            if (!driver.notifyUUIDs.any { it == characteristic.uuid.toString() }) return

            for (decoded in driver.ingest(value, characteristic.uuid.toString())) {
                // A forget is in flight: don't persist any more data or re-publish a
                // "connected" device state (which would re-create the row we're clearing).
                // Just watch for the ring's unbind ack (6 = UNBOND_ACK, 3 = ACK_CANCEL).
                if (forgetPending) {
                    if (decoded is RingDecodedEvent.BindNotify &&
                        (decoded.action == 6 || decoded.action == 3)) {
                        finalizeForget()
                    }
                    continue
                }
                PulseEventBus.publishBlocking(
                    PulseEvent.RawPacket(PacketDirection.INCOMING, value, decoded)
                )
                for (event in RingEventBridge.eventsFor(decoded)) {
                    PulseEventBus.publishBlocking(event)
                }
                activeSyncEngine?.handle(decoded)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
        ) {
            lastActivityAt = System.currentTimeMillis()  // descriptor ACK — link is alive
            completeOp()  // retire the in-flight op before any early return, so the queue drains

            // Notification enabled — fire onConnected once at least one notify is live
            val driver = activeDriver ?: return
            val ch = descriptor.characteristic
            if (!driver.notifyUUIDs.any { it == ch.uuid.toString() }) return
            if (_state.value.connectionState == RingConnectionState.CONNECTED) return

            updateState { copy(connectionState = RingConnectionState.CONNECTED) }
            lastActivityAt = System.currentTimeMillis()  // fresh link — start the staleness clock
            startKeepalive()  // ping ring every 15s to prevent idle disconnect
            val device = gatt.device
            prefs.edit()
                .putString(LAST_PERIPHERAL_KEY, device.address)
                .putString(LAST_DEVICE_TYPE_KEY, activeCoordinator?.deviceType?.name)
                .apply()

            PulseEventBus.publishBlocking(
                PulseEvent.DeviceStateChanged(
                    RingConnectionState.CONNECTED, device.address, name = device.name ?: connectingName
                )
            )
            activeCoordinator?.let { coord ->
                PulseEventBus.publishBlocking(
                    PulseEvent.DeviceIdentified(coord.deviceType, coord.capabilities)
                )
            }
            readBattery()

            scope.launch { onConnected?.invoke() }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            // No-op for Phase 2; MTU negotiation may be added later
        }
    }

    private fun handleDisconnect(gatt: BluetoothGatt) {
        // Ignore late callbacks from a GATT we already superseded during a reconnect
        // (we close the old handle in beginConnect). Acting on them would clobber the
        // CONNECTING state of the fresh attempt with a spurious DISCONNECTED.
        if (bluetoothGatt != null && gatt !== bluetoothGatt) {
            try { gatt.close() } catch (_: Exception) {}
            return
        }
        opInFlight = false; opQueue.clear()
        stopKeepalive()

        PulseEventBus.publishBlocking(
            PulseEvent.DeviceStateChanged(RingConnectionState.DISCONNECTED, null)
        )

        // autoConnect=true will automatically reconnect when the ring comes back.
        // Do NOT close the GATT — that would permanently kill the auto-reconnect.
        // The official app keeps the GATT alive on passive disconnect.
        updateState { copy(connectionState = RingConnectionState.DISCONNECTED) }
    }

    fun destroy() {
        watchdogJob?.cancel()
        scope.cancel()
        disconnect()
    }

    companion object {
        private const val LAST_PERIPHERAL_KEY = "ring.lastPeripheralIdentifier"
        private const val LAST_DEVICE_TYPE_KEY = "ring.lastDeviceType"
        /** How often the liveness watchdog runs. */
        private const val WATCHDOG_INTERVAL_MS = 15_000L
        /** No GATT activity for this long while CONNECTED ⇒ zombie link ⇒ reconnect.
         *  Comfortably longer than the 15s keepalive so a single missed ACK won't trip it. */
        private const val LINK_STALE_MS = 50_000L
        /** A CONNECTING attempt that hasn't completed in this long is retried from scratch. */
        private const val CONNECT_TIMEOUT_MS = 30_000L
        /** Max wait for any GATT op's completion callback before unblocking the queue
         *  (prevents a stuck opInFlight stranding every subsequent operation). */
        private const val OP_TIMEOUT_MS = 4_000L
        /** Max wait for the ring's UNBOND_ACK after a forget before forcing teardown. */
        private const val UNBIND_ACK_TIMEOUT_MS = 1_500L
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val DIS_SERVICE_UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        private val FW_REV_UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")

        /** Decode IEEE 11073 SFLOAT: exponent (4-bit signed) + mantissa (12-bit signed). */
        fun decodeSFLOAT(b0: Byte, b1: Byte): Double {
            val raw = ((b1.toInt() and 0xFF) shl 8) or (b0.toInt() and 0xFF)
            val exponent = ((raw shr 12) and 0x0F).let { if (it >= 8) it - 16 else it }
            val mantissa = (raw and 0x0FFF).let { if (it >= 0x0800) it - 0x1000 else it }
            return mantissa * Math.pow(10.0, exponent.toDouble())
        }
    }
}
