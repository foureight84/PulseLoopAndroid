package com.pulseloop.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.ring.RingBLEClient
import com.pulseloop.ring.RingConnectionState
import com.pulseloop.service.RingSyncCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** The Room flows behind the Debug screen. Reads everything, demo rows included: it's an inspector. */
class DiagnosticsRepository(private val db: PulseLoopDatabase) {
    val packetCount = db.rawPacketDao().observeCount()
    val packets = db.rawPacketDao().observeRecent()
    val events = db.wearableLogDao().observeRecent()
    val counts = db.debugDao().counts()
    fun rows(table: String) = db.debugDao().recentRows(table)
}

/**
 * State for the Debug screen. Everything is observed from Room, so it survives navigation, and the
 * ring actions go through the app's own [RingBLEClient] and [RingSyncCoordinator].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModel(
    repository: DiagnosticsRepository,
    private val client: RingBLEClient,
    private val coordinator: RingSyncCoordinator,
) : ViewModel() {
    private val sharing = SharingStarted.WhileSubscribed(5_000)
    val packetCount = repository.packetCount.stateIn(viewModelScope, sharing, 0)
    val packets = repository.packets.stateIn(viewModelScope, sharing, emptyList())
    val events = repository.events.stateIn(viewModelScope, sharing, emptyList())
    val counts = repository.counts.stateIn(viewModelScope, sharing, emptyList())
    private val selectedTable = MutableStateFlow("measurements")
    val rows = selectedTable.flatMapLatest { repository.rows(it) }.stateIn(viewModelScope, sharing, emptyList())
    fun selectTable(table: String) { selectedTable.value = table }
    private val _status = MutableStateFlow("")
    val status = _status.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun sync(connect: Boolean) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                if (!client.hasPermissions()) { _status.value = "Bluetooth permission is required."; return@launch }
                if (!coordinator.isConnected) {
                    if (!connect) { _status.value = "Connect the ring before syncing."; return@launch }
                    if (!client.hasLastKnownRing) { _status.value = "Pair a ring first."; return@launch }
                    _status.value = "Connecting…"
                    if (client.state.value.connectionState !in setOf(RingConnectionState.CONNECTING, RingConnectionState.RECONNECTING)) {
                        client.connectLastKnown()
                    }
                    val state = withTimeout(45_000) {
                        client.state.first { it.connectionState in setOf(RingConnectionState.CONNECTED, RingConnectionState.FAILED) }
                    }
                    if (state.connectionState != RingConnectionState.CONNECTED) {
                        _status.value = state.lastError ?: "Connection failed."
                        return@launch
                    }
                    // The coordinator starts sync when this same connection becomes ready.
                    _status.value = "Connected; startup query is running."
                } else {
                    coordinator.syncNow()
                    _status.value = "Sync requested. Watch responses below."
                }
            } catch (_: TimeoutCancellationException) {
                _status.value = "Connection timed out. Check the ring and Bluetooth."
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _status.value = "Could not query the ring. See connection events."
            } finally {
                _busy.value = false
            }
        }
    }
}
