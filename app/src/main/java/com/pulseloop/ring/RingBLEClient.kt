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
        /** Exact catalog model of the active connection (iOS #49), resolved at connect time. */
        val activeWearableModelID: String? = null,
        val activeCapabilities: Set<WearableCapability> = emptySet(),
        val firmwareVersion: String? = null,
    )

    data class DiscoveredRing(
        val id: String,
        val name: String,
        val rssi: Int,
        val isLikelyRing: Boolean,
        val deviceType: RingDeviceType?,
        /** Exact catalog model inferred from the Bluetooth local name, when recognizable. */
        val wearableModelID: String? = null,
    )

    private val _state = MutableStateFlow(BLEState())
    val state: StateFlow<BLEState> = _state.asStateFlow()

    var onConnected: (suspend () -> Unit)? = null
    var onFirmwareRead: ((String) -> Unit)? = null
    val syncEngine: RingSyncEngine? get() = activeSyncEngine
    private var keepaliveJob: Job? = null

    // Official-app-style reconnect throttling (BleBaseControl: maxReconnect + count%3
    // strategy alternation). Reset on success, user action, or app foreground.
    private var reconnectAttempts = 0
    private var reconnectScanPending = false
    private var sightlessScans = 0

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
    // Advertised name of the connection being established, for exact-model resolution + events.
    private var activeAdvertisedName: String? = null

    // Set while a "Forget" is waiting for the ring's UNBOND_ACK (0x4B) before teardown.
    private var forgetPending = false
    private var forgetJob: Job? = null

    // MARK: GATT operation serialization
    //
    // Android's BLE stack permits exactly ONE outstanding GATT operation at a time.
    // Issuing a read / characteristic-write / descriptor-write while another is still
    // in flight makes the new call return false and be silently dropped — the classic
    // cause of a ring that connects and discovers services but never finishes enabling
    // notifications, so it hangs forever on "Connecting…". (Colmi rings — R02 and R10
    // alike — expose the 0x180A DIS service, so a firmware read used to be issued ahead
    // of the CCCD descriptor write that gates the CONNECTED transition and silently
    // dropped it.)
    //
    // Every GATT operation is funnelled through this single FIFO queue so they run
    // strictly one-at-a-time, each retired by its matching callback (onCharacteristicWrite
    // / onCharacteristicRead / onDescriptorWrite → completeOp) which pumps the next one.
    //
    // The queue is touched from two threads — the Bluetooth binder thread (GATT callbacks)
    // and the client's Main-dispatcher coroutines (keepalive, timeouts, sync-engine sends) —
    // so every access to [opQueue]/[inFlightOp] is guarded by [opLock]. Without the lock,
    // both threads can observe "nothing in flight" and issue two concurrent GATT ops, the
    // second of which the stack silently rejects.

    private sealed class GattOp {
        /** Issue attempts so far; a stack-rejected op is retried before being dropped. */
        var attempts = 0

        /** A command/keepalive payload for the write (or command) channel. */
        class CommandWrite(val data: ByteArray, val useCommandChannel: Boolean) : GattOp()
        class Read(val characteristic: BluetoothGattCharacteristic) : GattOp()
        class DescriptorWrite(val descriptor: BluetoothGattDescriptor, val value: ByteArray) : GattOp()
    }

    private val opLock = Any()
    private val opQueue = ArrayDeque<GattOp>()
    private var inFlightOp: GattOp? = null

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
        // A user-initiated scan (pairing screen) must never be hijacked by a leftover
        // reconnect flag — sighting the known ring mid-pairing would auto-connect it.
        reconnectScanPending = false
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
        reconnectScanPending = false
        scanner?.stopScan(scanCallback)
        if (_state.value.connectionState == RingConnectionState.SCANNING) {
            updateState { copy(connectionState = RingConnectionState.IDLE) }
        }
    }

    fun connectTo(id: String, selectedModelID: String? = null) {
        val target = discoveredPeripherals[id] ?: run {
            try { bluetoothAdapter.getRemoteDevice(id) } catch (_: Exception) { null }
        } ?: run {
            updateState { copy(lastError = "Ring no longer available; scan again.") }
            return
        }
        val discoveredRing = _state.value.discovered.firstOrNull { it.id == id }
        connectingName = discoveredRing?.name ?: target.name
        resetReconnectBackoff()
        // Discovery's name-derived model wins over the carousel choice (iOS connect(to:selectedModelID:)).
        beginConnect(
            target,
            discoveredRing?.deviceType,
            selectedModelID = discoveredRing?.wearableModelID ?: selectedModelID,
            advertisedName = discoveredRing?.name ?: target.name,
        )
    }

    /**
     * Reconnect to the stored ring, alternating strategies like the official QRing app
     * (BleBaseControl: `count % 3` picks direct-connect vs scan-then-connect): a direct
     * connect is fastest right after a drop, but parks uselessly against a ring that
     * isn't advertising — a scan first proves reachability. Attempts are capped
     * (official: maxReconnect = 10); the cap resets on user action or app foreground.
     */
    fun connectLastKnown() {
        if (!bluetoothAdapter.isEnabled) return
        val lastId = lastKnownIdentifier ?: return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) return
        // A still-pending reconnect scan means the last attempt never sighted the ring.
        if (reconnectScanPending) {
            reconnectScanPending = false
            scanner?.stopScan(scanCallback)
            sightlessScans++
            if (sightlessScans >= SIGHTLESS_SCANS_FOR_HINT) {
                // The classic wedge: the ring still thinks it's connected (stale ACL in
                // the phone's stack) so it never advertises. Only the user can fix that.
                updateState { copy(lastError =
                    "Ring not found in scans. It may be held by a stale Bluetooth connection — try toggling Bluetooth off and on.") }
            }
        }
        reconnectAttempts++
        val device = try {
            bluetoothAdapter.getRemoteDevice(lastId)
        } catch (_: Exception) { null }
        if (device == null) {
            startScanning()
            return
        }
        if (reconnectAttempts % 3 == 1) {
            beginConnect(
                device,
                lastKnownDeviceType,
                selectedModelID = lastKnownWearableModelID,
                advertisedName = try { device.name } catch (_: Exception) { null },
            )
        } else {
            startScanning()
            // Set AFTER startScanning (which clears it to protect pairing scans).
            reconnectScanPending = true
        }
    }

    /** Clear the reconnect throttling — called on user action and app foreground. */
    private fun resetReconnectBackoff() {
        reconnectAttempts = 0
        sightlessScans = 0
        reconnectScanPending = false
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
        // Fresh user attention = fresh retry budget (the cap exists to stop unattended
        // background churn, not to block a user actively waiting on the connection).
        resetReconnectBackoff()
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
        // Time out a hung CONNECTING attempt FIRST — before the last-known-ring guard below —
        // so it also covers a first-ever pairing (no stored ring yet). The official QRing app
        // arms this timeout on every connect (mTimeoutRunnable). Without it, a first pair that
        // stalls after connectGatt (CCCD write dropped, GATT-133, or a link the firmware won't
        // hold without a bond) sits on "Connecting…" forever with no exit.
        if (_state.value.connectionState == RingConnectionState.CONNECTING) {
            if (connectingStartedAt > 0 &&
                System.currentTimeMillis() - connectingStartedAt > CONNECT_TIMEOUT_MS) {
                Log.w("RingBLEClient", "Connect attempt hung >${CONNECT_TIMEOUT_MS}ms")
                // A reconnect can retry the stored ring; a first pair has nothing to retry, so
                // fail the attempt cleanly and surface an error rather than spinning.
                if (lastKnownIdentifier != null) forceReconnect()
                else failConnectAttempt(
                    "Couldn't connect to the ring. Make sure it's nearby and awake, then try again.")
            }
            return
        }
        if (lastKnownIdentifier == null) return
        when (_state.value.connectionState) {
            RingConnectionState.CONNECTED -> {
                if (activeCoordinator?.deviceType == RingDeviceType.JRING) {
                    // Jring: the 15s keepalive guarantees regular GATT activity, so a
                    // silent link is provably dead.
                    val idleFor = System.currentTimeMillis() - lastActivityAt
                    if (lastActivityAt > 0 && idleFor > LINK_STALE_MS) {
                        Log.w("RingBLEClient", "Link stale (${idleFor}ms, no GATT activity) — forcing reconnect")
                        forceReconnect()
                    }
                } else {
                    // Colmi: no keepalive traffic (matching the official app), so idle
                    // silence is normal. Probe the OS's own GATT profile state instead —
                    // it knows when the ACL is gone even if our callback never fired.
                    val dev = lastKnownIdentifier?.let {
                        try { bluetoothAdapter.getRemoteDevice(it) } catch (_: Exception) { null }
                    }
                    val live = dev != null &&
                        bluetoothManager.getConnectionState(dev, BluetoothProfile.GATT) ==
                            BluetoothProfile.STATE_CONNECTED
                    if (!live) {
                        Log.w("RingBLEClient", "OS reports GATT not connected — forcing reconnect")
                        forceReconnect()
                    }
                }
            }
            RingConnectionState.DISCONNECTED,
            RingConnectionState.FAILED,
            RingConnectionState.IDLE -> connectLastKnown()
            // CONNECTING is handled above (before the last-known-ring guard).
            else -> {
                // SCANNING: a pairing scan proceeds untouched, but a reconnect scan that
                // ran a full watchdog interval without sighting the ring moves on to the
                // next attempt (connectLastKnown counts the miss and alternates strategy).
                if (reconnectScanPending) connectLastKnown()
            }
        }
    }

    /** Hard reset: drop the (possibly zombie) GATT and start a fresh connection. */
    private fun forceReconnect() {
        // beginConnect (via connectLastKnown) closes the stale GATT before opening a new one.
        connectLastKnown()
    }

    /**
     * Abort a stalled connect attempt that has no stored ring to retry (a first pairing that
     * hung). Tears the half-open GATT down and surfaces an error so the pairing UI leaves the
     * "Connecting…" state instead of spinning forever.
     */
    private fun failConnectAttempt(reason: String) {
        val gatt = bluetoothGatt
        bluetoothGatt = null
        writeChar = null; commandChar = null; notifyChars.clear(); batteryChar = null
        resetOpQueue()
        if (gatt != null) {
            try { gatt.disconnect() } catch (_: Exception) {}
            closeGattQuietly(gatt)
        }
        connectingStartedAt = 0
        updateState { copy(connectionState = RingConnectionState.FAILED, lastError = reason) }
        PulseEventBus.publishBlocking(
            PulseEvent.DeviceStateChanged(RingConnectionState.DISCONNECTED, null)
        )
    }

    /**
     * Graceful disconnect (user navigating away or app backgrounding without the
     * background-sync service). Releases the GATT client fully — QRing-style
     * disconnect → 500 ms → refresh → close — so the Android stack drops the ACL and
     * the ring goes back to advertising. A half-open GATT here is what wedged rings
     * into "connected-but-invisible" (won't appear in scans until Bluetooth is
     * toggled). Keeps the stored identity so [connectLastKnown] can reconnect later.
     */
    fun disconnect() {
        stopKeepalive()
        scanner?.stopScan(scanCallback)
        val gatt = bluetoothGatt
        bluetoothGatt = null
        writeChar = null; commandChar = null; notifyChars.clear(); batteryChar = null
        resetOpQueue()
        if (gatt != null) {
            try { gatt.disconnect() } catch (_: Exception) {}
            // The official QRing app waits ~500 ms between disconnect() and close() so
            // the stack finishes the LL teardown before the client handle disappears.
            scope.launch {
                delay(GATT_CLOSE_DELAY_MS)
                closeGattQuietly(gatt)
            }
        }
        updateState { copy(connectionState = RingConnectionState.DISCONNECTED) }
    }

    /** Cache-refresh + close, swallowing the reflection/stack exceptions. */
    private fun closeGattQuietly(gatt: BluetoothGatt) {
        try { gatt::class.java.getMethod("refresh").invoke(gatt) } catch (_: Exception) {}
        try { gatt.close() } catch (_: Exception) {}
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
        prefs.edit()
            .remove(LAST_PERIPHERAL_KEY)
            .remove(LAST_DEVICE_TYPE_KEY)
            .remove(LAST_WEARABLE_MODEL_KEY)
            .apply()

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
        resetOpQueue()
        prefs.edit()
            .remove(LAST_PERIPHERAL_KEY)
            .remove(LAST_DEVICE_TYPE_KEY)
            .remove(LAST_WEARABLE_MODEL_KEY)
            .apply()
        activeAdvertisedName = null
        updateState {
            copy(
                connectionState = RingConnectionState.IDLE,
                activeDeviceType = null,
                activeWearableModelID = null,
                activeCapabilities = emptySet(),
            )
        }
        PulseEventBus.publishBlocking(PulseEvent.DeviceForgotten)
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

    /** True when the phone's Bluetooth adapter is on — gates the pairing UI (iOS `isBluetoothReady`). */
    val isBluetoothEnabled: Boolean
        get() = try { bluetoothAdapter.isEnabled } catch (_: Exception) { false }

    /** A previously connected ring identity is stored — enables "Reconnect last ring". */
    val hasLastKnownRing: Boolean
        get() = lastKnownIdentifier != null

    private val lastKnownIdentifier: String?
        get() = prefs.getString(LAST_PERIPHERAL_KEY, null)
    private val lastKnownDeviceType: RingDeviceType?
        get() = prefs.getString(LAST_DEVICE_TYPE_KEY, null)?.let { type ->
            try { RingDeviceType.valueOf(type) } catch (_: Exception) { null }
        }
    private val lastKnownWearableModelID: String?
        get() = prefs.getString(LAST_WEARABLE_MODEL_KEY, null)

    // MARK: Internal

    private fun beginConnect(
        target: BluetoothDevice,
        deviceType: RingDeviceType?,
        selectedModelID: String? = null,
        advertisedName: String? = null,
    ) {
        scanner?.stopScan(scanCallback)
        // Close any stale GATT from a previous (now-dead) connection before opening a new
        // one. Reconnect attempts after an idle drop would otherwise leak GATT clients and
        // can collide with the orphaned handle. A fresh GATT mirrors the proven
        // force-close-and-reopen recovery path.
        bluetoothGatt?.let { old ->
            try { old.disconnect() } catch (_: Exception) {}
            closeGattQuietly(old)  // refresh + close, official-app teardown discipline
        }
        bluetoothGatt = null
        writeChar = null; commandChar = null; notifyChars.clear(); batteryChar = null
        resetOpQueue()
        val coordinator = coordinators.firstOrNull { it.deviceType == deviceType } ?: JringCoordinator
        // Resolve the exact catalog model for this connection: Bluetooth identity wins over the
        // user's carousel selection; family mismatches are rejected (iOS #49 beginConnect).
        activeAdvertisedName = advertisedName
        val resolvedModelID = com.pulseloop.wearables.WearableModel.resolve(
            advertisedName = advertisedName,
            selectedModelID = selectedModelID,
            family = coordinator.deviceType,
        )?.id
        installDriver(coordinator)
        updateState { copy(activeWearableModelID = resolvedModelID) }
        connectingStartedAt = System.currentTimeMillis()
        updateState { copy(connectionState = RingConnectionState.CONNECTING) }
        // Mirror the attempt to the persisted state so the Today/Settings views show
        // "Connecting…" rather than a stale "Connected" while autoConnect is pending.
        PulseEventBus.publishBlocking(
            PulseEvent.DeviceStateChanged(RingConnectionState.CONNECTING, target.address)
        )

        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // autoConnect=false matches the official QRing app (BleBaseControl:
            // connectGatt(ctx, false, cb, TRANSPORT_LE)) — attempts complete or fail
            // fast with a status code, and reconnection is owned by the watchdog's
            // alternating direct/scan strategy instead of a parked pending connect
            // that never resolves against a non-advertising ring.
            target.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            target.connectGatt(context, false, gattCallback)
        }
    }

    /**
     * Create an OS-level bond with the connected ring — invoked by the sync engine only when
     * the ring's device-support reply advertises `supportBlePair` (Colmi R09 and newer). The
     * official QRing app does exactly this, which is why its rings appear in the phone's
     * paired-devices list and hold a stable link; connecting GATT-only (no bond) is what left
     * PulseLoop's users stuck on "Connecting" and cycling Bluetooth to recover.
     *
     * Idempotent: skipped when already bonded/bonding, so it fires at most once per ring. Runs
     * after service discovery (the reply that triggers it arrives post-CONNECTED), so it does
     * not race discoverServices()/requestMtu().
     *
     * It also waits for the GATT op queue to fall idle before calling createBond(). The
     * device-support (0x3C) reply lands mid-startup, while the battery/pref reads, seeding
     * writes, and the first history-sync request are still queued or in flight. createBond()
     * on a busy link can force a transient disconnect/re-encrypt on some firmware, dropping
     * those in-flight ops — the very churn bonding is meant to end. Bonding in a quiet gap
     * (no op in flight) avoids interrupting an active transfer; the wait is bounded, so a
     * long-running sync still bonds after [awaitOpsFlushed]'s timeout rather than never.
     */
    private fun bondActiveDevice() {
        if (!hasPermissions()) return
        scope.launch {
            awaitOpsFlushed()
            // The link may have dropped while we waited for the queue to settle.
            if (!hasPermissions()) return@launch
            val device = bluetoothGatt?.device ?: return@launch
            try {
                if (device.bondState == BluetoothDevice.BOND_NONE) {
                    Log.i("RingBLEClient", "Ring advertises supportBlePair — creating OS bond")
                    device.createBond()
                }
            } catch (e: Exception) {
                Log.w("RingBLEClient", "createBond() failed: ${e.message}")
            }
        }
    }

    /**
     * Send a keepalive ping every 15s to prevent the ring's ~20s idle timeout.
     * **Jring (56ff) only.** 0x3A is that SDK's lightweight ping/pong command; on the
     * Colmi/QRing protocol the same opcode is CMD_DEVICE_SUGAR_LIPIDS — not a ping —
     * and the official QRing app sends no keepalive at all (verified in the decompiled
     * sources: no periodic send anywhere in its BLE layer). Pinging a Colmi ring both
     * speaks the wrong command and holds the link open 24/7, which keeps the ring from
     * advertising and drains its battery.
     */
    private fun startKeepalive() {
        keepaliveJob?.cancel()
        if (activeCoordinator?.deviceType != RingDeviceType.JRING) return
        keepaliveJob = scope.launch {
            while (isActive) {
                delay(15_000)
                val cmd = ByteArray(20)
                cmd[0] = 0x3A.toByte()  // CMD_KEEPALIVE_PING (Jring)
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
        // Capability-gated bonding: the engine fires this only when the ring's device-support
        // reply advertises supportBlePair (Colmi R09 and newer). See docs/qring-ble-adoption.md.
        activeSyncEngine?.setOnBondRequested { bondActiveDevice() }
        updateState {
            copy(
                activeDeviceType = coordinator.deviceType,
                activeCapabilities = coordinator.capabilities,
            )
        }
    }

    private fun enqueueOp(op: GattOp) {
        synchronized(opLock) { opQueue.addLast(op) }
        pumpOps()
    }

    /**
     * Retire the in-flight op and pump the next. [matches] guards against a stale or
     * foreign callback (e.g. an ACK arriving after the op already timed out and the next
     * op was issued) retiring the WRONG op: only complete when the callback corresponds
     * to what is actually in flight.
     */
    private fun completeOp(matches: (GattOp) -> Boolean = { true }) {
        synchronized(opLock) {
            val current = inFlightOp ?: return
            if (!matches(current)) return
            inFlightOp = null
        }
        pumpOps()
    }

    /** Drop everything queued or in flight (connection reset / teardown). Any pending
     *  op timeout self-invalidates because its captured op is no longer [inFlightOp]. */
    private fun resetOpQueue() {
        synchronized(opLock) {
            inFlightOp = null
            opQueue.clear()
        }
    }

    private fun pumpOps() {
        while (true) {
            val gatt = bluetoothGatt ?: return
            val op: GattOp
            synchronized(opLock) {
                if (inFlightOp != null || opQueue.isEmpty()) return
                if (opQueue.first() is GattOp.CommandWrite && writeChar == null) {
                    // Write channel not bound yet (services not discovered) — leave the op
                    // queued; the enqueues in onServicesDiscovered pump again once bound.
                    return
                }
                op = opQueue.removeFirst()
                inFlightOp = op
            }

            val issued: Boolean = when (op) {
                is GattOp.CommandWrite -> {
                    val wChar = writeChar
                    if (wChar == null) {
                        false
                    } else {
                        val target = if (op.useCommandChannel) commandChar ?: wChar else wChar
                        target.value = op.data
                        PulseEventBus.publishBlocking(
                            PulseEvent.RawPacket(PacketDirection.OUTGOING, op.data,
                                RingDecodedEvent.CommandAck(commandId = if (op.data.isNotEmpty()) op.data[0].toUByte() else 0u))
                        )
                        gatt.writeCharacteristic(target)
                    }
                }
                is GattOp.Read -> gatt.readCharacteristic(op.characteristic)
                is GattOp.DescriptorWrite -> {
                    op.descriptor.value = op.value
                    gatt.writeDescriptor(op.descriptor)
                }
            }

            if (issued) {
                // Guard against a missing completion callback. If the ACK never comes,
                // inFlightOp would stay set forever and the entire queue (history queries,
                // keepalive, notification setup, …) would deadlock — exactly the "one
                // command then silence" failure. Time the op out and unblock the queue.
                scope.launch {
                    delay(OP_TIMEOUT_MS)
                    if (inFlightOp === op) {
                        Log.w("RingBLEClient", "GATT op ACK timed out — unblocking queue")
                        completeOp { it === op }  // never retire a successor issued meanwhile
                    }
                }
                return
            }

            // The stack rejected the op at issue time (transient busy state, or a
            // characteristic that went away). Retry a couple of times after a short
            // pause before giving up — dropping outright could lose a CCCD write that
            // gates CONNECTED, or a queued factory-reset/unbind command.
            synchronized(opLock) { if (inFlightOp === op) inFlightOp = null }
            if (op.attempts < MAX_OP_ATTEMPTS - 1) {
                op.attempts++
                synchronized(opLock) { opQueue.addFirst(op) }
                Log.w("RingBLEClient", "GATT op rejected at issue — retrying: ${op::class.simpleName}")
                scope.launch {
                    delay(OP_RETRY_DELAY_MS)
                    pumpOps()
                }
                return
            }
            Log.w("RingBLEClient", "GATT op dropped after $MAX_OP_ATTEMPTS attempts: ${op::class.simpleName}")
            // Loop on to the next queued op.
        }
    }

    /**
     * Wait (bounded) for every queued GATT op to be issued and acknowledged. Used before
     * destructive teardowns so an enqueued command (e.g. factory reset, unbind) isn't
     * still sitting in the queue when it gets cleared.
     */
    suspend fun awaitOpsFlushed(timeoutMs: Long = OPS_FLUSH_TIMEOUT_MS) {
        withTimeoutOrNull(timeoutMs) {
            while (synchronized(opLock) { inFlightOp != null || opQueue.isNotEmpty() }) delay(50)
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
            val matchedModel = com.pulseloop.wearables.WearableModel.modelForAdvertisedName(name)
            discoveredPeripherals[device.address] = device

            // Reconnect scan: the known ring is advertising again — grab it now.
            if (reconnectScanPending && device.address == lastKnownIdentifier) {
                reconnectScanPending = false
                sightlessScans = 0
                scanner?.stopScan(this)
                connectingName = name
                beginConnect(
                    device,
                    matchedType ?: lastKnownDeviceType,
                    selectedModelID = lastKnownWearableModelID,
                    advertisedName = name,
                )
                return
            }

            val ring = DiscoveredRing(
                id = device.address,
                name = name,
                rssi = result.rssi,
                isLikelyRing = matchedType != null,
                deviceType = matchedType,
                // Only trust the name-derived model when it agrees with the matched family.
                wearableModelID = matchedModel?.id?.takeIf { matchedModel.family == matchedType },
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
                        // Do NOT bond *here*. Bonding at connect time raced
                        // requestMtu()/discoverServices() — a known Android instability that
                        // caused frequent disconnects. Instead we bond later, and only when the
                        // ring asks: the Colmi engine reads the device-support bitfield during
                        // startup and, if supportBlePair is set (R09 and newer), calls back into
                        // bondActiveDevice() — well after discovery, matching the official QRing
                        // app (which also bonds post-discovery, gated on supportBlePair). Rings
                        // that don't advertise the bit (jring 56ff, older R02) are never bonded,
                        // preserving prior behaviour. See docs/qring-ble-adoption.md §Pairing.
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
                        // Replace (never append) the diagnostic suffix: appending once per
                        // discovery grew the row by one "|services:…" per reconnect — a
                        // user export showed ~98 of them.
                        db.deviceDao().upsert(device.copy(
                            capabilitiesRaw = device.capabilitiesRaw.substringBefore("|services:") +
                                "|services:" + serviceUuids.joinToString(","),
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
            if (gatt !== bluetoothGatt) return  // late callback from a superseded connection
            lastActivityAt = System.currentTimeMillis()  // GATT read completed — link is alive
            // Retire the in-flight op regardless of payload, before any early return —
            // but only if it IS the read this callback answers (a stale post-timeout ACK
            // must not retire the op that was issued after it).
            completeOp { it is GattOp.Read && it.characteristic === characteristic }
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
            if (gatt !== bluetoothGatt) return  // late callback from a superseded connection
            lastActivityAt = System.currentTimeMillis()  // GATT ACK — link is alive
            completeOp { it is GattOp.CommandWrite }
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

            // Raw seam: reply payloads the decoded-event stream doesn't carry
            // (e.g. Colmi pref-read replies seeding the measurement config).
            if (!forgetPending) activeSyncEngine?.handleRawNotify(value)

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
            if (gatt !== bluetoothGatt) return  // late callback from a superseded connection
            lastActivityAt = System.currentTimeMillis()  // descriptor ACK — link is alive
            // Retire the in-flight op before any early return, so the queue drains.
            completeOp { it is GattOp.DescriptorWrite && it.descriptor === descriptor }

            // Notification enabled — fire onConnected once at least one notify is live
            val driver = activeDriver ?: return
            val ch = descriptor.characteristic
            if (!driver.notifyUUIDs.any { it == ch.uuid.toString() }) return
            if (_state.value.connectionState == RingConnectionState.CONNECTED) return

            updateState { copy(connectionState = RingConnectionState.CONNECTED) }
            lastActivityAt = System.currentTimeMillis()  // fresh link — start the staleness clock
            resetReconnectBackoff()
            startKeepalive()  // Jring only — Colmi links idle without pings (official behavior)
            val device = gatt.device
            // Resolution can come back null (e.g. re-pairing while the carousel sits on a
            // wrong-family model — resolve() rejects family mismatches). Keep a previously
            // identified exact model when it belongs to the connected family rather than
            // erasing it; a different-family leftover is stale and still clears.
            val resolvedID = _state.value.activeWearableModelID
            val modelID = resolvedID ?: lastKnownWearableModelID?.takeIf { prev ->
                com.pulseloop.wearables.WearableModel.model(prev)?.family ==
                    activeCoordinator?.deviceType
            }
            if (modelID != resolvedID) updateState { copy(activeWearableModelID = modelID) }
            val editor = prefs.edit()
                .putString(LAST_PERIPHERAL_KEY, device.address)
                .putString(LAST_DEVICE_TYPE_KEY, activeCoordinator?.deviceType?.name)
            if (modelID != null) editor.putString(LAST_WEARABLE_MODEL_KEY, modelID)
            else editor.remove(LAST_WEARABLE_MODEL_KEY)
            editor.apply()

            PulseEventBus.publishBlocking(
                PulseEvent.DeviceStateChanged(
                    RingConnectionState.CONNECTED, device.address, name = device.name ?: connectingName
                )
            )
            activeCoordinator?.let { coord ->
                PulseEventBus.publishBlocking(
                    PulseEvent.DeviceIdentified(
                        deviceType = coord.deviceType,
                        wearableModelID = modelID,
                        advertisedName = activeAdvertisedName ?: connectingName,
                        capabilities = coord.capabilities,
                    )
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
            closeGattQuietly(gatt)
            return
        }
        // Duplicate callback for a GATT we already tore down (bluetoothGatt is null
        // after teardown): don't re-publish DISCONNECTED or re-close.
        if (bluetoothGatt == null &&
            _state.value.connectionState == RingConnectionState.DISCONNECTED) {
            return
        }
        resetOpQueue()
        stopKeepalive()

        // Release the dead client immediately (the link is already down, so no
        // disconnect/delay needed): refresh the GATT cache and close, matching the
        // official QRing app's teardown. Holding the old handle for autoConnect is
        // what left zombie clients in the stack — the watchdog now owns reconnection
        // and always starts from a fresh connectGatt.
        bluetoothGatt = null
        writeChar = null; commandChar = null; notifyChars.clear(); batteryChar = null
        closeGattQuietly(gatt)

        PulseEventBus.publishBlocking(
            PulseEvent.DeviceStateChanged(RingConnectionState.DISCONNECTED, null)
        )

        updateState { copy(connectionState = RingConnectionState.DISCONNECTED) }
    }

    fun destroy() {
        watchdogJob?.cancel()
        stopKeepalive()
        scanner?.stopScan(scanCallback)
        // The scope is about to die, so the graceful delayed close in disconnect()
        // would never run — tear the GATT down synchronously instead.
        bluetoothGatt?.let { gatt ->
            try { gatt.disconnect() } catch (_: Exception) {}
            closeGattQuietly(gatt)
        }
        bluetoothGatt = null
        scope.cancel()
        updateState { copy(connectionState = RingConnectionState.DISCONNECTED) }
    }

    companion object {
        private const val LAST_PERIPHERAL_KEY = "ring.lastPeripheralIdentifier"
        private const val LAST_DEVICE_TYPE_KEY = "ring.lastDeviceType"
        private const val LAST_WEARABLE_MODEL_KEY = "ring.lastWearableModel"
        /** How often the liveness watchdog runs. */
        private const val WATCHDOG_INTERVAL_MS = 15_000L
        /** No GATT activity for this long while CONNECTED ⇒ zombie link ⇒ reconnect.
         *  Comfortably longer than the 15s keepalive so a single missed ACK won't trip it. */
        private const val LINK_STALE_MS = 50_000L
        /** A CONNECTING attempt that hasn't completed in this long is retried from scratch. */
        private const val CONNECT_TIMEOUT_MS = 30_000L
        /** Unattended reconnect attempts before giving up until user action / app foreground
         *  (official QRing app: maxReconnect = 10). */
        private const val MAX_RECONNECT_ATTEMPTS = 10
        /** Reconnect scans that sight nothing before we suggest a Bluetooth toggle. */
        private const val SIGHTLESS_SCANS_FOR_HINT = 2
        /** Pause between gatt.disconnect() and close(), matching the official app's
         *  teardown so the stack finishes the LL disconnect before the handle vanishes. */
        private const val GATT_CLOSE_DELAY_MS = 500L
        /** Max wait for any GATT op's completion callback before unblocking the queue
         *  (prevents a stuck in-flight op stranding every subsequent operation). */
        private const val OP_TIMEOUT_MS = 4_000L
        /** Total issue attempts for an op the stack rejects before it is dropped. */
        private const val MAX_OP_ATTEMPTS = 3
        /** Pause before re-issuing a stack-rejected op (lets a transient busy state clear). */
        private const val OP_RETRY_DELAY_MS = 150L
        /** Default bound for [awaitOpsFlushed]. */
        private const val OPS_FLUSH_TIMEOUT_MS = 10_000L
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
