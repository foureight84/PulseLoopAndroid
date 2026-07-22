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
import kotlinx.coroutines.flow.update
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
class RingBLEClient(
    private val context: Context,
    private val transientOwner: Boolean = false,
) {

    /** Registry of supported wearables. Adding a wearable = append one entry.
     *
     * The order is load-bearing at exactly two places:
     *   - `ColmiSmartHealthCoordinator` must precede `TK5Coordinator`. A SmartHealth-Colmi's
     *     advertised name would otherwise also satisfy TK5's looser manufacturer-data fallback (see
     *     `ColmiSmartHealthCoordinator.matches()` doc for why order here matters).
     *   - `LuckRingCoordinator` must precede `TK5Coordinator`. LuckRing matches strong,
     *     family-exclusive signals (the `F618` service, the `0xFF64` company ID) that no other
     *     coordinator claims; ordering it ahead of TK5 is defensive, so TK5's weak `TK5`-name prefix
     *     could never shadow a hypothetical `TK5x`-named LuckRing sibling. */
    private val coordinators: List<WearableCoordinator> = listOf(
        JringCoordinator,
        YCBTCoordinator,
        ColmiCoordinator,
        ColmiSmartHealthCoordinator,
        LuckRingCoordinator,
        TK5Coordinator,
        // CRP matches only its family-exclusive `fdda` service (or an explicit CRP carousel pick),
        // so its position is not load-bearing. Like the CRP R11, it's usually reached by the
        // post-connect re-route below rather than by a scan match.
        CRPCoordinator,
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
        /** Bounded, persistent-for-this-process BLE trace for hardware diagnosis. */
        val diagnostics: List<String> = emptyList(),
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

    // @Volatile: written on the binder thread (onConnectionStateChange) and on Main (connect
    // teardown), and read from both — including the connect-handshake identity guards
    // (gatt === bluetoothGatt) that gate MTU-fallback and service discovery. Volatile keeps
    // those cross-thread reads from seeing a stale reference.
    @Volatile private var bluetoothGatt: BluetoothGatt? = null
    private var discoveredPeripherals: MutableMap<String, BluetoothDevice> = mutableMapOf()
    // Advertised name of the device we're connecting to, captured at connect time so it can be
    // persisted as the device's display name (the connect callbacks otherwise only know the MAC).
    private var connectingName: String? = null

    // Characteristics
    private var writeChar: BluetoothGattCharacteristic? = null
    private var commandChar: BluetoothGattCharacteristic? = null
    private var notifyChars: MutableMap<UUID, BluetoothGattCharacteristic> = mutableMapOf()
    private var batteryChar: BluetoothGattCharacteristic? = null
    private var subscriptionGate: SubscriptionSetupGate? = null

    // MARK: Active driver/engine

    private var activeCoordinator: WearableCoordinator? = null
    private var activeDriver: WearableDriver? = null
    private var activeSyncEngine: RingSyncEngine? = null
    // Advertised name of the connection being established, for exact-model resolution + events.
    private var activeAdvertisedName: String? = null

    // Set while a "Forget" is waiting for the ring's UNBOND_ACK (0x4B) before teardown.
    private val forgetLock = Any()
    @Volatile private var forgetPending = false
    @Volatile private var forgetFinalizing = false
    @Volatile private var forgetGeneration = 0L
    private var forgetJob: Job? = null
    private var forgetCompletion: CompletableDeferred<Unit>? = null

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

    // Consecutive GATT ops that failed to issue (writeCharacteristic == false) or never got a
    // completion callback. A run of these means the framework's single-operation slot is wedged
    // — retrying into it just spins — so once the run crosses OP_FAILURE_RECONNECT_THRESHOLD we
    // force a reconnect, the only thing that clears mDeviceBusy. Reset on any successful
    // completion. Guarded by opLock. See docs/colmi-sleep-sync-diagnosis.md.
    private var consecutiveOpFailures = 0

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
    private var ownershipRetryJob: Job? = null
    @Volatile private var watchdogReconnectPaused = false

    // MARK: Service-discovery gate
    //
    // Android permits exactly ONE outstanding GATT operation. requestMtu() starts an ATT
    // MTU exchange; issuing discoverServices() before that exchange's callback returns makes
    // discoverServices() come back false and be silently dropped on strict stacks (observed
    // on realme/Android 16 with the jring 56ff: MTU negotiated to 512, but services were
    // never discovered, the notify-CCCD write that gates CONNECTED never ran, and the connect
    // hung until the 30s watchdog killed it). The connect handshake runs OUTSIDE the op queue,
    // so it needs its own serialization: request the MTU, then kick off discovery from
    // onMtuChanged (with a fallback in case that callback never arrives). This mirrors the
    // official QRing app (BleBaseControl), which settles ~500ms then discovers, retries once
    // after ~1000ms if rejected, and never overlaps discovery with another GATT op.
    private val serviceDiscoveryStarted = java.util.concurrent.atomic.AtomicBoolean(false)

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
        // Pairing/connecting a ring is an explicit user intent — clear any stay-off flag from a
        // prior Disconnect so [connectLastKnown] isn't suppressed for this or the next session.
        prefs.edit().remove(USER_DISCONNECTED_KEY).apply()
        watchdogReconnectPaused = false
        resetReconnectBackoff()

        // Normally discovery's name-derived family wins over the carousel choice
        // (iOS connect(to:selectedModelID:)). The exception (issue #29): several Colmi/Yawell
        // rings — notably the R11 — advertise the generic BLE name "SMART_RING", which the
        // scanner can only classify as JRING because that name isn't exclusive to jring hardware
        // and they don't advertise their Colmi service UUID pre-connect. When the user has
        // explicitly picked a NON-jring model in the pairing carousel, trust that pick over a
        // generic-JRING detection: drive the connection with the selected model's coordinator
        // (and, for the R11, its OS bond) instead of the jring driver, which can't find its
        // 000056ff characteristics on a Colmi ring and hangs the connect forever. A *confident*
        // scan match (a specific Colmi name pattern, or the 000056ff jring service UUID) still
        // resolves to its own family and is unaffected — we only override the JRING fallback.
        val detectedType = discoveredRing?.deviceType
        val selectedModel = com.pulseloop.wearables.WearableModel.model(selectedModelID)
        val honorSelection = detectedType == RingDeviceType.JRING &&
            selectedModel != null && selectedModel.family != RingDeviceType.JRING
        if (honorSelection) {
            Log.i("RingBLEClient", "Ring detected as JRING (generic \"SMART_RING\" name) but user " +
                "selected ${selectedModel!!.displayName} — honoring the carousel choice")
        }
        beginConnect(
            target,
            if (honorSelection) selectedModel!!.family else detectedType,
            selectedModelID = if (honorSelection) selectedModelID
                else discoveredRing?.wearableModelID ?: selectedModelID,
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
        watchdogReconnectPaused = false
        connectLastKnownInternal()
    }

    private fun connectLastKnownInternal() {
        if (!bluetoothAdapter.isEnabled) return
        // Honor a user-initiated Disconnect: stay off until the user reconnects ([userConnect])
        // or pairs a new ring ([connectTo]). Every auto-reconnect path — foreground
        // reconnectIfNeeded, the watchdog, and the background RingSyncWorker — funnels through
        // here, so this one guard makes "Disconnect" actually stick.
        if (prefs.getBoolean(USER_DISCONNECTED_KEY, false)) return
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
        if (watchdogReconnectPaused) return
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
            RingConnectionState.IDLE -> connectLastKnownInternal()
            // CONNECTING is handled above (before the last-known-ring guard).
            else -> {
                // SCANNING: a pairing scan proceeds untouched, but a reconnect scan that
                // ran a full watchdog interval without sighting the ring moves on to the
                // next attempt (connectLastKnown counts the miss and alternates strategy).
                if (reconnectScanPending) connectLastKnownInternal()
            }
        }
    }

    /** Hard reset: drop the (possibly zombie) GATT and start a fresh connection. */
    private fun forceReconnect() {
        // beginConnect (via connectLastKnown) closes the stale GATT before opening a new one.
        connectLastKnownInternal()
    }

    /**
     * Abort a stalled connect attempt that has no stored ring to retry (a first pairing that
     * hung). Tears the half-open GATT down and surfaces an error so the pairing UI leaves the
     * "Connecting…" state instead of spinning forever.
     */
    private fun failConnectAttempt(reason: String) {
        val gatt = bluetoothGatt
        bluetoothGatt = null
        activeDriver?.connectionDidEnd()
        writeChar = null; commandChar = null; notifyChars.clear(); batteryChar = null
        subscriptionGate = null
        resetOpQueue()
        if (gatt != null) {
            try { gatt.disconnect() } catch (_: Exception) {}
            closeGattQuietly(gatt)
        }
        releaseConnectionOwnership()
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
        // Lifecycle/worker teardown is intentional. Leave genuine link-loss callbacks eligible
        // for watchdog recovery, but do not let this client undo an explicit transient teardown.
        watchdogReconnectPaused = true
        ownershipRetryJob?.cancel(); ownershipRetryJob = null
        val shouldPublishDisconnect =
            _state.value.connectionState != RingConnectionState.DISCONNECTED
        stopKeepalive()
        scanner?.stopScan(scanCallback)
        val gatt = bluetoothGatt
        bluetoothGatt = null
        activeDriver?.connectionDidEnd()
        writeChar = null; commandChar = null; notifyChars.clear(); batteryChar = null
        resetOpQueue()
        if (gatt != null) {
            try { gatt.disconnect() } catch (_: Exception) {}
            // The official QRing app waits ~500 ms between disconnect() and close() so
            // the stack finishes the LL teardown before the client handle disappears.
            scope.launch {
                delay(GATT_CLOSE_DELAY_MS)
                closeGattQuietly(gatt)
                releaseConnectionOwnership()
            }
        } else {
            releaseConnectionOwnership()
        }
        updateState { copy(connectionState = RingConnectionState.DISCONNECTED) }
        if (shouldPublishDisconnect) {
            PulseEventBus.publishBlocking(
                PulseEvent.DeviceStateChanged(RingConnectionState.DISCONNECTED, null)
            )
        }
    }

    /**
     * User-initiated Disconnect (the Settings button). Unlike the transient [disconnect] used by
     * the background worker and lifecycle, this makes the disconnect STICK: it sets a persisted
     * flag that suppresses every auto-reconnect path until the user reconnects ([userConnect]) or
     * pairs a new ring. For a model we OS-bond (see [activeModelRequiresBond]) it also removes
     * the bond, since a bonded link can otherwise be held connected by the OS — the ring
     * re-bonds (with the OS prompt) on the next user reconnect. Non-bonded rings just drop the
     * link, matching the iOS "Disconnect" experience. The stored ring identity is kept either
     * way, so the ring stays listed and one tap reconnects it; use [forget] to remove it entirely.
     */
    fun userDisconnect() {
        prefs.edit().putBoolean(USER_DISCONNECTED_KEY, true).apply()
        if (activeModelRequiresBond() && hasPermissions()) {
            try {
                bluetoothGatt?.device?.let { dev ->
                    if (dev.bondState != BluetoothDevice.BOND_NONE) {
                        Log.i("RingBLEClient", "User disconnect on a bonded ring — removing OS bond")
                        dev::class.java.getMethod("removeBond").invoke(dev)
                    }
                }
            } catch (e: Exception) {
                Log.w("RingBLEClient", "removeBond on user disconnect failed: ${e.message}")
            }
        }
        disconnect()
    }

    /** User-initiated reconnect (the "Reconnect last ring" button): clear the stay-off flag set
     *  by [userDisconnect] and reconnect the stored ring. */
    fun userConnect() {
        prefs.edit().remove(USER_DISCONNECTED_KEY).apply()
        resetReconnectBackoff()
        connectLastKnown()
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
        startForget()
    }

    suspend fun forgetAndWait() {
        startForget().await()
    }

    private fun startForget(): CompletableDeferred<Unit> {
        ownershipRetryJob?.cancel(); ownershipRetryJob = null
        val completion: CompletableDeferred<Unit>
        val forgetRequestGeneration: Long
        synchronized(forgetLock) {
            val inFlight = forgetCompletion
            if ((forgetPending || forgetFinalizing) && inFlight != null) return inFlight
            completion = CompletableDeferred()
            forgetCompletion = completion
            forgetGeneration++
            forgetPending = true
            forgetFinalizing = false
            forgetRequestGeneration = forgetGeneration
        }
        // Clear the known-ring id up front so the watchdog/auto-reconnect can't grab
        // the ring back during or after the unbind window.
        prefs.edit()
            .remove(LAST_PERIPHERAL_KEY)
            .remove(LAST_DEVICE_TYPE_KEY)
            .remove(LAST_WEARABLE_MODEL_KEY)
            .remove(USER_DISCONNECTED_KEY)
            .apply()

        val gatt = bluetoothGatt
        if (gatt != null && writeChar != null &&
            _state.value.connectionState == RingConnectionState.CONNECTED) {
            enqueueWrite(RingEncoder.makeUnbindCommand())  // 0x4B 05 00 01
            forgetJob?.cancel()
            forgetJob = scope.launch {
                delay(UNBIND_ACK_TIMEOUT_MS)
                if (synchronized(forgetLock) { forgetPending && !forgetFinalizing }) {
                    Log.w("RingBLEClient", "Unbind ACK not received in ${UNBIND_ACK_TIMEOUT_MS}ms — forcing teardown")
                    finalizeForget(expectedGeneration = forgetRequestGeneration)
                }
            }
        } else {
            finalizeForget(expectedGeneration = forgetRequestGeneration)
        }
        return completion
    }

    /** Tear down the link: clear the GATT cache, remove any OS bond, close the GATT. */
    private fun finalizeForget(expectedGeneration: Long? = null) {
        val completion = synchronized(forgetLock) {
            if (expectedGeneration != null && expectedGeneration != forgetGeneration) return
            if (forgetFinalizing) return
            forgetFinalizing = true
            forgetGeneration++
            forgetJob?.cancel(); forgetJob = null
            forgetCompletion
        }
        try {
            stopKeepalive()
            scanner?.stopScan(scanCallback)
            activeDriver?.connectionDidEnd()
            bluetoothGatt?.let { gatt ->
                try { gatt::class.java.getMethod("refresh").invoke(gatt) } catch (_: Exception) {}
                try { gatt.device::class.java.getMethod("removeBond").invoke(gatt.device) } catch (_: Exception) {}
                try { gatt.disconnect() } catch (_: Exception) {}
                try { gatt.close() } catch (_: Exception) {}
            }
            bluetoothGatt = null
            releaseConnectionOwnership()
            writeChar = null; commandChar = null; notifyChars.clear(); batteryChar = null
            resetOpQueue()
            prefs.edit()
                .remove(LAST_PERIPHERAL_KEY)
                .remove(LAST_DEVICE_TYPE_KEY)
                .remove(LAST_WEARABLE_MODEL_KEY)
                .remove(USER_DISCONNECTED_KEY)
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
        } finally {
            synchronized(forgetLock) {
                if (forgetCompletion === completion) forgetCompletion = null
                forgetPending = false
                forgetFinalizing = false
            }
            completion?.complete(Unit)
        }
    }

    private fun acceptsCallback(generation: Long): Boolean = synchronized(forgetLock) {
        !forgetPending && generation == forgetGeneration
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
        if (watchdogReconnectPaused) return
        if (synchronized(forgetLock) { forgetPending || forgetFinalizing }) return
        if (!claimConnectionOwnership()) {
            Log.i("RingBLEClient", "Connection skipped: another PulseLoop BLE client owns GATT")
            updateState { copy(connectionState = RingConnectionState.DISCONNECTED) }
            if (!transientOwner) {
                ownershipRetryJob?.cancel()
                ownershipRetryJob = scope.launch {
                    delay(PROCESS_OWNER_RETRY_MS)
                    ownershipRetryJob = null
                    beginConnect(target, deviceType, selectedModelID, advertisedName)
                }
            }
            return
        }
        ownershipRetryJob = null
        scanner?.stopScan(scanCallback)
        // Close any stale GATT from a previous (now-dead) connection before opening a new
        // one. Reconnect attempts after an idle drop would otherwise leak GATT clients and
        // can collide with the orphaned handle. A fresh GATT mirrors the proven
        // force-close-and-reopen recovery path.
        bluetoothGatt?.let { old ->
            activeDriver?.connectionDidEnd()
            try { old.disconnect() } catch (_: Exception) {}
            closeGattQuietly(old)  // refresh + close, official-app teardown discipline
        }
        bluetoothGatt = null
        writeChar = null; commandChar = null; notifyChars.clear(); batteryChar = null
        subscriptionGate = null
        resetOpQueue()
        serviceDiscoveryStarted.set(false)
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
        updateState { copy(activeWearableModelID = resolvedModelID, diagnostics = emptyList()) }
        recordDiagnostic("connect ${advertisedName ?: "unknown"}")
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

    /** True only when the connected model is one we deliberately OS-bond (currently the R09 and
     *  the R11/Yawell R11). See [com.pulseloop.wearables.WearableModel.requiresOsBond]. */
    private fun activeModelRequiresBond(): Boolean =
        com.pulseloop.wearables.WearableModel.model(_state.value.activeWearableModelID)
            ?.requiresOsBond == true

    /**
     * Create an OS-level bond with the connected ring — invoked by the sync engine only when
     * the ring's device-support reply advertises `supportBlePair` *and* the resolved model is on
     * the [activeModelRequiresBond] allowlist (currently the R09 and R11/Yawell R11 — the models
     * with demonstrated GATT-only fragility, R09 can't re-sync after the first connect, R11 gets
     * stuck on "Connecting", issue #29). The official QRing app bonds *every* ring reporting the
     * bit, with no per-model check at all (confirmed against the decompiled sources,
     * `DeviceCmdInit.init` — `if (deviceSupportFunctionRsp.supportBlePair) { ...; bleCreateBond()
     * }`). We deliberately don't copy that blanket behavior: it makes the OS pairing dialog pop
     * for every `supportBlePair` model, including the R10, which doesn't need a bond to hold a
     * stable link and where the prompt is a pure UX regression — see docs/qring-ble-adoption.md
     * §5a. Expand the allowlist only when a specific model is shown to need it.
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
        if (!activeModelRequiresBond()) {
            Log.i("RingBLEClient", "Ring reports supportBlePair but this model works GATT-only — skipping bond")
            return
        }
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
        synchronized(forgetLock) {
            forgetGeneration++
            forgetPending = false
            forgetFinalizing = false
        }
        val driver = coordinator.makeDriver { enqueueWrite(it) }
        activeCoordinator = coordinator
        activeDriver = driver
        driver.connectionDidStart()
        subscriptionGate = SubscriptionSetupGate(
            notifyUUIDs = driver.notifyUUIDs,
            requiredSubscriptions = driver.requiredSubscriptionsBeforeConnected,
        )
        activeSyncEngine = driver.makeSyncEngine()
        // The engine requests bonding from the device-support bit; bondActiveDevice applies the
        // hardware-validated per-model allowlist before showing any OS pairing prompt.
        activeSyncEngine?.setOnBondRequested { bondActiveDevice() }
        updateState {
            copy(
                activeDeviceType = coordinator.deviceType,
                activeCapabilities = coordinator.capabilities,
            )
        }
    }

    /**
     * Additive-only capability refinement from the connected unit's own reported bitmap (YCBT
     * `02 01` SupportFunction; see [WearableCoordinator.bitmapGatedCapabilities]). Intersecting
     * with the coordinator's gate-able set means a device can never claim a capability its family
     * doesn't offer as gate-able, and unioning into the current set means this can only ever *add*
     * — never take back a baseline promise.
     */
    private fun refineActiveCapabilities(reported: Set<WearableCapability>) {
        val coordinator = activeCoordinator ?: return
        val granted = reported.intersect(coordinator.bitmapGatedCapabilities)
        if (granted.isEmpty() || granted.all { it in _state.value.activeCapabilities }) return
        updateState { copy(activeCapabilities = activeCapabilities + granted) }
        PulseEventBus.publishBlocking(
            PulseEvent.DeviceIdentified(
                deviceType = coordinator.deviceType,
                wearableModelID = _state.value.activeWearableModelID,
                advertisedName = activeAdvertisedName ?: connectingName,
                capabilities = _state.value.activeCapabilities,
            )
        )
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
    private fun retireOp(matches: (GattOp) -> Boolean = { true }): Boolean {
        return synchronized(opLock) {
            val current = inFlightOp ?: return@synchronized false
            if (!matches(current)) return@synchronized false
            inFlightOp = null
            true
        }
    }

    private fun completeOp(matches: (GattOp) -> Boolean = { true }) {
        if (retireOp(matches)) pumpOps()
    }

    /** Place a protocol-ready handshake ahead of reads/optional CCCDs already in the queue. */
    private fun prependCommandWrites(commands: List<ByteArray>) {
        val driver = activeDriver ?: return
        val ops = commands.map { command ->
            val framed = driver.frame(command)
            GattOp.CommandWrite(framed, driver.usesCommandChannel(framed))
        }
        synchronized(opLock) {
            for (op in ops.asReversed()) opQueue.addFirst(op)
        }
    }

    /** Drop everything queued or in flight (connection reset / teardown). Any pending
     *  op timeout self-invalidates because its captured op is no longer [inFlightOp]. */
    private fun resetOpQueue() {
        synchronized(opLock) {
            inFlightOp = null
            opQueue.clear()
        }
    }

    /** A log label that survives R8 minification (release logs showed only the obfuscated "J").
     *  For a command write it embeds the command byte, so a dropped sleep request reads
     *  `cmd:0x27` instead of an opaque class name. */
    private fun opLabel(op: GattOp): String = when (op) {
        is GattOp.CommandWrite -> "cmd:0x%02x".format(if (op.data.isNotEmpty()) op.data[0].toInt() and 0xFF else 0)
        is GattOp.Read -> "read"
        is GattOp.DescriptorWrite -> "cccd"
    }

    /** Record a dropped/timed-out op and, if the run of failures means the GATT slot is wedged,
     *  kick a reconnect. Returns true when recovery was triggered — the caller must then stop
     *  pumping, since the queue is being torn down. */
    private fun noteOpFailureAndMaybeRecover(): Boolean {
        val wedged = synchronized(opLock) {
            consecutiveOpFailures++
            if (consecutiveOpFailures >= OP_FAILURE_RECONNECT_THRESHOLD) {
                consecutiveOpFailures = 0  // reset in-lock so a concurrent failure can't re-trigger
                true
            } else false
        }
        if (wedged) recoverWedgedLink()
        return wedged
    }

    /** Clear the failure run after any successful GATT completion (the stack is responsive). */
    private fun resetOpFailures() = synchronized(opLock) { consecutiveOpFailures = 0 }

    /** The single-op slot is wedged: drop the queue and reconnect from scratch to clear the
     *  Android stack's stuck busy flag, instead of endlessly dropping commands into it. Mirrors
     *  the official SDKs, which reset the link on terminal write failure. */
    private fun recoverWedgedLink() {
        Log.w("RingBLEClient", "GATT op slot wedged — forcing reconnect to clear it")
        resetOpQueue()
        scope.launch { forceReconnect() }
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
                        // The write type must come from the characteristic, not the framework
                        // default. A characteristic that only supports write-without-response (the
                        // LuckRing/TK18's `B002`) rejects a WRITE_TYPE_DEFAULT request over GATT —
                        // the ring's own GATT server has no Write property to answer it, so the write
                        // fails or times out instead of ever reaching the ring. Mirrors iOS's
                        // `CBCharacteristicWriteType` fix for the same characteristic.
                        target.writeType = if (target.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        } else {
                            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        }
                        target.value = op.data
                        // Publish the outgoing packet once, on the first issue attempt only — a
                        // retry storm used to log 200 duplicate CommandAcks, flooding the 200-entry
                        // diagnostics ring buffer and evicting the real reply packets.
                        if (op.attempts == 0) {
                            PulseEventBus.publishBlocking(
                                PulseEvent.RawPacket(PacketDirection.OUTGOING, op.data,
                                    RingDecodedEvent.CommandAck(commandId = if (op.data.isNotEmpty()) op.data[0].toUByte() else 0u))
                            )
                        }
                        if (op.attempts == 0) {
                            val prefix = op.data.take(4).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                            recordDiagnostic("write issue $prefix len=${op.data.size}")
                        }
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
                    // Read under opLock: inFlightOp is written on the binder thread under the same
                    // lock, and without it a completion landing right at the timeout could be
                    // invisible here and trigger a spurious reconnect for an op that already retired.
                    val stillInFlight = synchronized(opLock) { inFlightOp === op }
                    if (stillInFlight) {
                        Log.w("RingBLEClient", "GATT op ACK timed out — unblocking queue: ${opLabel(op)}")
                        // Android does not identify a write callback beyond its characteristic.
                        // Two successive protocol writes use the same characteristic, so a late
                        // callback for a timed-out write could otherwise retire its successor.
                        // Reset the link instead of issuing another ambiguous command write.
                        if (op is GattOp.CommandWrite) {
                            recoverWedgedLink()
                            return@launch
                        }
                        // A lost completion callback leaves the framework slot busy: don't just
                        // free our slot and pump the next op into a still-busy stack (that is the
                        // spin-and-drop loop). Escalate to a reconnect once enough pile up.
                        if (noteOpFailureAndMaybeRecover()) return@launch
                        completeOp { it === op }  // never retire a successor issued meanwhile
                    }
                }
                return
            }

            // The stack rejected the op at issue time (transient busy state, or a
            // characteristic that went away). Retry a couple of times after a short
            // pause before giving up — dropping outright could lose a CCCD write that
            // gates CONNECTED, or a queued factory-reset/unbind command.
            var retrying = false
            val stillCurrent = synchronized(opLock) {
                if (inFlightOp !== op || bluetoothGatt !== gatt) {
                    false
                } else {
                    inFlightOp = null
                    if (op.attempts < MAX_OP_ATTEMPTS - 1) {
                        op.attempts++
                        opQueue.addFirst(op)
                        retrying = true
                    }
                    true
                }
            }
            if (!stillCurrent) return
            if (retrying) {
                Log.w("RingBLEClient", "GATT op rejected at issue — retrying (${op.attempts}/$MAX_OP_ATTEMPTS): ${opLabel(op)}")
                scope.launch {
                    delay(OP_RETRY_DELAY_MS)
                    pumpOps()
                }
                return
            }
            Log.w("RingBLEClient", "GATT op dropped after $MAX_OP_ATTEMPTS attempts: ${opLabel(op)}")
            if (op is GattOp.DescriptorWrite &&
                subscriptionGate?.isRequired(op.descriptor.characteristic.uuid.toString()) == true
            ) {
                failConnectAttempt("Could not enable required ring indication")
                return
            }
            // The slot may be wedged — escalate to a reconnect rather than pump the next op into
            // the same busy stack (the tear-down aborts the loop).
            if (noteOpFailureAndMaybeRecover()) return
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
        _state.update { it.update() }
    }

    @Synchronized
    private fun recordDiagnostic(message: String) {
        Log.i("RingBLEClient", message)
        _state.update { current ->
            current.copy(diagnostics = (current.diagnostics + message).takeLast(6))
        }
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
            recordDiagnostic("state status=$status new=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        bluetoothGatt = gatt
                        serviceDiscoveryStarted.set(false)
                        // Do NOT bond *here*. Bonding at connect time raced
                        // requestMtu()/discoverServices() — a known Android instability that
                        // caused frequent disconnects. Instead we bond later, and only when the
                        // ring asks: the Colmi engine reads the device-support bitfield during
                        // startup and, if supportBlePair is set, calls back into
                        // bondActiveDevice() well after discovery. The callback still applies the
                        // hardware-validated model allowlist; supportBlePair alone is insufficient.
                        // YCBT firmware is not QRing firmware. Cheap BE94 controllers have been
                        // observed terminating Android links after aggressive connection-parameter
                        // and MTU requests. CoreBluetooth's working YCBT path makes neither request,
                        // and the frame assembler already handles fragmentation, so use the
                        // conservative default link for this family.
                        if (activeCoordinator?.deviceType == RingDeviceType.YCBT) {
                            recordDiagnostic("YCBT default MTU/priority")
                            startServiceDiscovery(gatt)
                            return
                        }
                        // QRing/Jring path: request the parameters used by their Android clients.
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        // Request a larger MTU (helps history-sync throughput), then discover
                        // services ONLY after the MTU exchange completes — never overlap the two
                        // (see serviceDiscoveryStarted). Discovery is kicked off from onMtuChanged;
                        // if requestMtu() is rejected outright, or its callback never arrives,
                        // fall back to discovering anyway so the connect can't hang.
                        val mtuRequested = try { gatt.requestMtu(512) } catch (_: Exception) { false }
                        if (!mtuRequested) {
                            startServiceDiscovery(gatt)
                        } else {
                            scope.launch {
                                delay(MTU_DISCOVERY_FALLBACK_MS)
                                if (gatt === bluetoothGatt) startServiceDiscovery(gatt)
                            }
                        }
                    } else {
                        updateState { copy(lastError = "GATT connect failed: $status") }
                        handleDisconnect(gatt, status)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    handleDisconnect(gatt, status)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== bluetoothGatt) return  // stale callback from a superseded connection
            recordDiagnostic("services status=$status count=${gatt.services.size}")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failConnectAttempt("Service discovery failed (GATT $status)")
                return
            }

            // Log all discovered service UUIDs for diagnostics
            val serviceUuids = gatt.services.map { it.uuid.toString() }
            android.util.Log.i("RingBLEClient", "Services: ${serviceUuids.joinToString(", ")}")

            recordDiagnostic("services ${serviceUuids.joinToString(",")}")

            // Post-connect re-route (issue #29): a Colmi R11 that advertised the generic name
            // "SMART_RING" with no Colmi service UUID in its advertisement gets classified as
            // JRING by the scanner, so the jring driver is installed — but the jring driver's
            // 000056ff characteristics don't exist on this ring, so the binding loop below would
            // find nothing, no notify CCCD write is enqueued, and the connect hangs (30s watchdog
            // → disconnect → retry loop). The ring's real Colmi service only becomes visible now,
            // post-connect, in the GATT table. If we see it while running the jring driver, swap
            // to the Colmi coordinator so the correct characteristics bind below and the R11 gets
            // its OS bond. Scoped to JRING → Colmi only, so YCBT/TK5/etc. are never affected.
            val hasColmiService = gatt.services.any {
                val u = it.uuid.toString()
                u == ColmiUUIDs.SERVICE_V1 || u == ColmiUUIDs.SERVICE_V2
            }
            if (hasColmiService && activeCoordinator?.deviceType == RingDeviceType.JRING) {
                Log.i("RingBLEClient", "Discovered Colmi services under the jring driver — " +
                    "re-routing to the Colmi driver")
                installDriver(ColmiCoordinator)
                // Re-resolve the model against the Colmi family so bonding is evaluated correctly.
                // If the user picked a Colmi model in the carousel it's honored; otherwise the
                // generic R02 base is used (requiresOsBond = false, so no speculative bond prompt).
                val remodel = com.pulseloop.wearables.WearableModel.resolve(
                    advertisedName = activeAdvertisedName,
                    selectedModelID = _state.value.activeWearableModelID,
                    family = RingDeviceType.COLMI_R02,
                )?.id ?: com.pulseloop.wearables.WearableModel.COLMI_R02.id
                updateState { copy(activeWearableModelID = remodel) }
            }

            // Post-connect re-route (issue #29), CRP-firmware sibling of the Colmi case above: the
            // *other* "SMART_RING" R11 firmware exposes a proprietary `fdda` profile (Moyoung
            // "Da Rings" app), not the Colmi UART — so the Colmi check above misses it and it stays
            // on the wrong driver, whose characteristics don't exist on this ring, and the connect
            // hangs (zaggash's ring). The `fdda` service is only visible post-connect.
            //
            // A CRP ring exposes `fdda` and NOT the Colmi UART, so that pair is an unambiguous CRP
            // signature — re-route to the CRP coordinator whenever we see it under either of the two
            // "SMART_RING"-ambiguous drivers: the JRING fallback (user tapped the device) *or* the
            // Colmi driver (user explicitly picked a Colmi-family R11 in the carousel, which
            // honorSelection would have routed to Colmi). YCBT/TK5/LuckRing are left untouched, and
            // a real Colmi-UART ring is excluded by the `!hasColmiService` guard. Unlike the Colmi
            // R11, the CRP ring connects GATT-only (the vendor app performs no bond in its connect
            // path), so no OS bond is triggered here.
            val hasCrpService = gatt.services.any {
                it.uuid.toString().equals(CRPUUIDs.SERVICE, ignoreCase = true)
            }
            val crpReroutable = activeCoordinator?.deviceType.let {
                it == RingDeviceType.JRING || it == RingDeviceType.COLMI_R02
            }
            if (hasCrpService && !hasColmiService && crpReroutable) {
                Log.i("RingBLEClient", "Discovered CRP (fdda) service under the " +
                    "${activeCoordinator?.deviceType} driver — re-routing to the CRP driver")
                installDriver(CRPCoordinator)
                val remodel = com.pulseloop.wearables.WearableModel.resolve(
                    advertisedName = activeAdvertisedName,
                    selectedModelID = _state.value.activeWearableModelID,
                    family = RingDeviceType.CRP,
                )?.id ?: com.pulseloop.wearables.WearableModel.COLMI_R11_CRP.id
                updateState { copy(activeWearableModelID = remodel) }
            }

            val driver = activeDriver ?: return

            // Bind the ring's own service first and enable its notifications BEFORE any
            // other GATT work. The CONNECTED transition is gated on a notify-CCCD descriptor
            // write completing (see onDescriptorWrite), and every GATT op now runs strictly
            // one-at-a-time via the op queue — so queuing the notify writes first means the
            // ring becomes usable as soon as possible instead of waiting behind firmware/
            // battery reads. (This is the R10 fix: its 0x180A DIS firmware read used to be
            // issued ahead of the CCCD write and silently dropped it, so it never connected.)
            val subscriptionGate = subscriptionGate ?: return
            val ringDescriptorOps = mutableListOf<Pair<BluetoothGattDescriptor, ByteArray>>()
            for (service in gatt.services) {
                val svcUuid = service.uuid.toString()
                val isRingSvc = driver.serviceUUIDs.any { it == svcUuid }
                val isBatterySvc = driver.batteryServiceUUID != null && svcUuid == driver.batteryServiceUUID
                if (!isRingSvc && !isBatterySvc) continue

                for (ch in service.characteristics) {
                    val uuid = ch.uuid.toString()
                    // A characteristic may be both writable and notifiable. YCBT's BE94-0001
                    // command channel is exactly that; the old mutually-exclusive `when` stored
                    // it as writeChar but silently skipped its CCCD, losing every command reply.
                    if (uuid == driver.writeUUID) writeChar = ch
                    if (uuid == driver.commandUUID) commandChar = ch
                    if (driver.notifyUUIDs.any { it == uuid }) {
                        notifyChars[ch.uuid] = ch
                        val localEnabled = gatt.setCharacteristicNotification(ch, true)
                        val descriptor = ch.getDescriptor(CCCD_UUID)
                        subscriptionGate.observeCharacteristic(
                            uuid = uuid,
                            localEnabled = localEnabled,
                            hasCccd = descriptor != null,
                        )
                        if (localEnabled && descriptor != null) {
                            val cccdValue = when (subscriptionGate.modeFor(uuid)) {
                                SubscriptionMode.NOTIFICATION -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                SubscriptionMode.INDICATION -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                            }
                            ringDescriptorOps += descriptor to cccdValue
                        }
                    }
                    if (uuid == driver.batteryCharUUID) batteryChar = ch
                }
            }

            subscriptionGate.topologyFailure()?.let { failure ->
                recordDiagnostic(failure)
                failConnectAttempt(failure)
                return
            }
            for ((descriptor, value) in ringDescriptorOps) {
                enqueueOp(GattOp.DescriptorWrite(descriptor, value))
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
                        enqueueOp(GattOp.DescriptorWrite(desc, cccdEnableValue(ch)))
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
            resetOpFailures()
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
            recordDiagnostic("write ${characteristic.uuid.toString().take(8)} status=$status")
            if (gatt !== bluetoothGatt) return  // late callback from a superseded connection
            lastActivityAt = System.currentTimeMillis()  // GATT ACK — link is alive
            if (status != BluetoothGatt.GATT_SUCCESS) {
                updateState { copy(lastError = "Ring command write failed (GATT $status)") }
                // Do not advance into the next command: the failed operation may be one of the
                // required post-subscription handshake writes, and callbacks share a channel.
                recoverWedgedLink()
                return
            }
            resetOpFailures()  // a real completion — the stack is responsive again
            completeOp { it is GattOp.CommandWrite }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic
        ) {
            if (gatt !== bluetoothGatt) return  // late callback from a superseded connection
            lastActivityAt = System.currentTimeMillis()  // inbound notify — link is alive
            val value = characteristic.value ?: return
            val uuid = characteristic.uuid.toString()
            val callbackForgetGeneration = forgetGeneration

            // Standard BLE health services — read before the ring-service guard
            if (uuid.startsWith("00002a35")) {
                // Blood Pressure Measurement — IEEE 11073 SFLOAT
                if (value.size >= 7) {
                    val systolic = decodeSFLOAT(value[1], value[2])
                    val diastolic = decodeSFLOAT(value[3], value[4])
                    if (acceptsCallback(callbackForgetGeneration)) {
                        PulseEventBus.publishBlocking(
                            PulseEvent.BloodPressureSample(
                                systolic = systolic.toInt(),
                                diastolic = diastolic.toInt(),
                                timestamp = java.time.Instant.now(),
                            )
                        )
                    }
                }
                return
            }
            if (uuid.startsWith("00002a18")) {
                // Glucose Measurement — IEEE 11073 SFLOAT in kg/L → mg/dL
                if (value.size >= 12) {
                    val glucoseKgL = decodeSFLOAT(value[10], value[11])
                    if (acceptsCallback(callbackForgetGeneration)) {
                        PulseEventBus.publishBlocking(PulseEvent.HistoryMeasurement(MeasurementKind.BLOOD_SUGAR, glucoseKgL * 100000.0, java.time.Instant.now()))
                    }
                }
                return
            }

            val driver = activeDriver ?: return
            if (!driver.notifyUUIDs.any { it == characteristic.uuid.toString() }) return

            // Raw seam: reply payloads the decoded-event stream doesn't carry
            // (e.g. Colmi pref-read replies seeding the measurement config).
            if (acceptsCallback(callbackForgetGeneration)) {
                activeSyncEngine?.handleRawNotify(value)
            }

            val decodedEvents = driver.ingest(value, characteristic.uuid.toString())
            if (acceptsCallback(callbackForgetGeneration)) {
                val diagnostic = decodedEvents.firstOrNull() ?: RingDecodedEvent.Unknown(
                    commandId = value.firstOrNull()?.toUByte() ?: 0u,
                    raw = value,
                )
                PulseEventBus.publishBlocking(
                    PulseEvent.RawPacket(PacketDirection.INCOMING, value, diagnostic)
                )
                for (decoded in decodedEvents) {
                    if (!acceptsCallback(callbackForgetGeneration)) break
                    if (decoded is RingDecodedEvent.SupportFunctions) {
                        refineActiveCapabilities(decoded.capabilities)
                    }
                    for (event in RingEventBridge.eventsFor(decoded)) {
                        if (!acceptsCallback(callbackForgetGeneration)) break
                        PulseEventBus.publishBlocking(event)
                    }
                    if (acceptsCallback(callbackForgetGeneration)) activeSyncEngine?.handle(decoded)
                }
                return
            }
            val shouldFinalizeForget = synchronized(forgetLock) {
                forgetPending && callbackForgetGeneration == forgetGeneration &&
                    decodedEvents.any { decoded ->
                        decoded is RingDecodedEvent.BindNotify &&
                            (decoded.action == 6 || decoded.action == 3)
                    }
            }
            if (shouldFinalizeForget) finalizeForget(expectedGeneration = callbackForgetGeneration)

        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
        ) {
            recordDiagnostic("notify ${descriptor.characteristic.uuid.toString().take(8)} status=$status")
            if (gatt !== bluetoothGatt) return  // late callback from a superseded connection
            lastActivityAt = System.currentTimeMillis()  // descriptor ACK — link is alive
            resetOpFailures()
            // Retire without pumping: the final required CCCD may need to prepend an immediate
            // vendor handshake ahead of reads and optional descriptors already in the queue.
            if (!retireOp { it is GattOp.DescriptorWrite && it.descriptor === descriptor }) return

            val driver = activeDriver
            val channelUuid = descriptor.characteristic.uuid.toString()
            val isRingChannel = driver?.notifyUUIDs?.any { it == channelUuid } == true
            val gate = subscriptionGate

            if (status != BluetoothGatt.GATT_SUCCESS) {
                val failure = "Could not enable ring notifications (GATT $status, ${channelUuid.substringBefore('-')})"
                if (isRingChannel && gate?.isRequired(channelUuid) == true) {
                    failConnectAttempt(failure)
                } else {
                    updateState { copy(lastError = failure) }
                    pumpOps()
                }
                return
            }

            if (!isRingChannel || driver == null || gate == null) {
                pumpOps()
                return
            }
            gate.descriptorWritten(channelUuid, successful = true)
            if (!gate.isReady || _state.value.connectionState == RingConnectionState.CONNECTED) {
                pumpOps()
                return
            }

            val immediateCommands = driver.immediatePostSubscriptionCommands()
            if (immediateCommands.isNotEmpty()) {
                recordDiagnostic("vendor handshake queued")
                prependCommandWrites(immediateCommands)
            }

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
                    RingConnectionState.CONNECTED,
                    device.address,
                    name = device.name ?: connectingName,
                    deviceType = activeCoordinator?.deviceType,
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
            if (activeCoordinator?.deviceType != RingDeviceType.YCBT) {
                readBattery()
            }

            pumpOps()
            scope.launch { onConnected?.invoke() }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (gatt !== bluetoothGatt) return  // late callback from a superseded connection
            // The MTU exchange has retired — the single-outstanding-op slot is free, so it is
            // now safe to discover services (status is ignored: even a failed negotiation frees
            // the slot, and discovery must proceed either way).
            startServiceDiscovery(gatt)
        }
    }

    /**
     * Begin service discovery exactly once per connection, off the GATT-op critical path.
     * Called from onMtuChanged (the MTU exchange has completed) or, as a fallback, a delayed
     * job after connect — whichever fires first wins via [serviceDiscoveryStarted]. A small
     * settle delay before discovery, plus one retry if the stack rejects it, mirrors the
     * official QRing app (BleBaseControl: waitFor(500) → discoverServices, waitFor(1000) → retry).
     */
    private fun startServiceDiscovery(gatt: BluetoothGatt) {
        if (!serviceDiscoveryStarted.compareAndSet(false, true)) return
        scope.launch {
            delay(SERVICE_DISCOVERY_SETTLE_MS)
            if (gatt !== bluetoothGatt) return@launch
            if (gatt.discoverServices()) return@launch
            // Rejected at issue (transient busy state) — retry once after a longer pause.
            Log.w("RingBLEClient", "discoverServices() rejected — retrying")
            delay(SERVICE_DISCOVERY_RETRY_MS)
            if (gatt !== bluetoothGatt) return@launch
            if (!gatt.discoverServices()) {
                // Still rejected: leave the CONNECTING watchdog to time the attempt out and
                // retry from a fresh connectGatt rather than sitting half-open forever.
                Log.w("RingBLEClient", "discoverServices() rejected twice — connect will time out")
            }
        }
    }

    private fun handleDisconnect(gatt: BluetoothGatt, status: Int = BluetoothGatt.GATT_SUCCESS) {
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
        activeDriver?.connectionDidEnd()

        // Release the dead client immediately (the link is already down, so no
        // disconnect/delay needed): refresh the GATT cache and close, matching the
        // official QRing app's teardown. Holding the old handle for autoConnect is
        // what left zombie clients in the stack — the watchdog now owns reconnection
        // and always starts from a fresh connectGatt.
        bluetoothGatt = null
        writeChar = null; commandChar = null; notifyChars.clear(); batteryChar = null
        subscriptionGate = null
        closeGattQuietly(gatt)
        releaseConnectionOwnership()

        PulseEventBus.publishBlocking(
            PulseEvent.DeviceStateChanged(RingConnectionState.DISCONNECTED, null)
        )

        val requestedByUser = prefs.getBoolean(USER_DISCONNECTED_KEY, false)
        updateState {
            copy(
                connectionState = RingConnectionState.DISCONNECTED,
                lastError = when {
                    requestedByUser -> lastError
                    status == BluetoothGatt.GATT_SUCCESS -> null
                    else -> "Ring disconnected (GATT $status)"
                },
            )
        }
    }

    /** Tear down this client. Returns true only if it owned PulseLoop's process-wide GATT slot. */
    fun destroy(): Boolean {
        watchdogJob?.cancel()
        ownershipRetryJob?.cancel(); ownershipRetryJob = null
        stopKeepalive()
        scanner?.stopScan(scanCallback)
        activeDriver?.connectionDidEnd()
        // The scope is about to die, so the graceful delayed close in disconnect()
        // would never run — tear the GATT down synchronously instead.
        bluetoothGatt?.let { gatt ->
            try { gatt.disconnect() } catch (_: Exception) {}
            closeGattQuietly(gatt)
        }
        bluetoothGatt = null
        val releasedConnection = releaseConnectionOwnership()
        scope.cancel()
        updateState { copy(connectionState = RingConnectionState.DISCONNECTED) }
        return releasedConnection
    }

    private fun claimConnectionOwnership(): Boolean {
        val owner = processConnectionOwner.get()
        return owner === this || (owner == null && processConnectionOwner.compareAndSet(null, this))
    }

    private fun releaseConnectionOwnership(): Boolean =
        processConnectionOwner.compareAndSet(this, null)

    companion object {
        private const val LAST_PERIPHERAL_KEY = "ring.lastPeripheralIdentifier"
        private const val LAST_DEVICE_TYPE_KEY = "ring.lastDeviceType"
        private const val LAST_WEARABLE_MODEL_KEY = "ring.lastWearableModel"
        /** Set when the user taps Disconnect; suppresses every auto-reconnect path
         *  (foreground, watchdog, background worker) until the user reconnects or re-pairs. */
        private const val USER_DISCONNECTED_KEY = "ring.userDisconnected"
        /** A ring has one command stream; never let this process open competing GATT clients. */
        private val processConnectionOwner =
            java.util.concurrent.atomic.AtomicReference<RingBLEClient?>(null)
        /** Foreground retry delay while a short-lived background client yields ownership. */
        private const val PROCESS_OWNER_RETRY_MS = 1_000L
        /** How often the liveness watchdog runs. */
        private const val WATCHDOG_INTERVAL_MS = 15_000L
        /** No GATT activity for this long while CONNECTED ⇒ zombie link ⇒ reconnect.
         *  Comfortably longer than the 15s keepalive so a single missed ACK won't trip it. */
        private const val LINK_STALE_MS = 50_000L
        /** A CONNECTING attempt that hasn't completed in this long is retried from scratch. */
        private const val CONNECT_TIMEOUT_MS = 30_000L
        /** Settle delay after the MTU exchange before discovering services (official app: waitFor(500)). */
        private const val SERVICE_DISCOVERY_SETTLE_MS = 500L
        /** Pause before re-issuing a stack-rejected discoverServices() (official app: waitFor(1000)). */
        private const val SERVICE_DISCOVERY_RETRY_MS = 1_000L
        /** If the onMtuChanged callback never arrives, discover services anyway after this long.
         *  Comfortably inside CONNECT_TIMEOUT_MS so a stuck MTU exchange still leaves time to sync. */
        private const val MTU_DISCOVERY_FALLBACK_MS = 3_000L
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
        /** Total issue attempts for an op the stack rejects before it is dropped. The official
         *  QRing/JRing SDKs retry a rejected write persistently (JRing: up to ~30×) rather than
         *  giving up after a few — a slow/high-latency link (the R10 negotiated interval=99,
         *  latency=4) can leave the single-op slot briefly busy across several attempts. */
        private const val MAX_OP_ATTEMPTS = 6
        /** Pause before re-issuing a stack-rejected op (lets a transient busy state clear). */
        private const val OP_RETRY_DELAY_MS = 200L
        /** Consecutive dropped/timed-out ops that mean the framework's single-op slot is wedged
         *  (mDeviceBusy stuck true after a lost completion callback). Retrying into it only spins,
         *  so we force a reconnect — the one thing that clears the flag — instead of silently
         *  dropping commands (which is how the sleep/history request went missing on the R10). */
        private const val OP_FAILURE_RECONNECT_THRESHOLD = 3
        /** Default bound for [awaitOpsFlushed]. */
        private const val OPS_FLUSH_TIMEOUT_MS = 10_000L
        /** Max wait for the ring's UNBOND_ACK after a forget before forcing teardown. */
        private const val UNBIND_ACK_TIMEOUT_MS = 1_500L
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val DIS_SERVICE_UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        private val FW_REV_UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")

        /**
         * The correct CCCD value for a characteristic's *declared* property — indication
         * (`ENABLE_INDICATION_VALUE`) if it advertises `PROPERTY_INDICATE`, notification otherwise.
         * Every prior driver's notify characteristics happen to be plain-notify, so a single
         * hardcoded `ENABLE_NOTIFICATION_VALUE` never surfaced a bug — but YCBT's async stream
         * (`be940003`) is indicate-only on the real ring (confirmed against the decompiled vendor
         * SDK's `BleHelper.java`), and the standard Blood Pressure / Glucose Measurement
         * characteristics (`0x2A35`/`0x2A18`) are indicate-only per their BLE SIG profiles too.
         * Writing the wrong CCCD value means the peripheral never delivers anything on that
         * characteristic.
         */
        private fun cccdEnableValue(characteristic: BluetoothGattCharacteristic): ByteArray =
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }

        /** Decode IEEE 11073 SFLOAT: exponent (4-bit signed) + mantissa (12-bit signed). */
        fun decodeSFLOAT(b0: Byte, b1: Byte): Double {
            val raw = ((b1.toInt() and 0xFF) shl 8) or (b0.toInt() and 0xFF)
            val exponent = ((raw shr 12) and 0x0F).let { if (it >= 8) it - 16 else it }
            val mantissa = (raw and 0x0FFF).let { if (it >= 0x0800) it - 0x1000 else it }
            return mantissa * Math.pow(10.0, exponent.toDouble())
        }
    }
}
