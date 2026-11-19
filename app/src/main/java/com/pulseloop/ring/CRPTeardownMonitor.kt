package com.pulseloop.ring

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One monitor per process, using only the application context. A worker can finish while its
 * physical link is still disconnecting; a new foreground client must observe the same gate.
 * The receiver intentionally lives for the process, including while no client is connecting.
 */
@SuppressLint("MissingPermission")
internal class CRPTeardownMonitor private constructor(context: Context) {
    val gate = CRPTeardownGate(SystemClock::elapsedRealtimeNanos)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val bluetoothManager: BluetoothManager? =
        context.applicationContext.getSystemService(BluetoothManager::class.java)

    init {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        // Older Android releases may omit EXTRA_TRANSPORT. CRP rings are LE-only.
                        if (intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT,
                                BluetoothDevice.TRANSPORT_LE) != BluetoothDevice.TRANSPORT_LE) return
                        // IntentCompat gates the typed overload on API 34: it is buggy on 33.
                        val device = IntentCompat.getParcelableExtra(
                            intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java,
                        ) ?: return
                        if (gate.isPending(device.address)) {
                            Log.i("RingBLEClient", "CRP physical disconnect confirmed")
                            gate.aclDisconnected(device.address)
                        }
                    }
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_OFF) {
                            gate.bluetoothOff()
                        }
                    }
                }
            }
        }
        // Bluetooth broadcasts originate in a privileged process outside the system UID.
        // These are protected system actions; NOT_EXPORTED can silently miss their delivery.
        try {
            ContextCompat.registerReceiver(
                context.applicationContext, receiver,
                IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED).apply {
                    addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                },
                ContextCompat.RECEIVER_EXPORTED,
            )
        } catch (e: Exception) {
            Log.w("RingBLEClient", "CRP ACL receiver unavailable; will require fresh advertisement", e)
        }
    }

    /** Register the wait before disconnecting, and close even if the owning client's scope dies. */
    fun disconnect(gatt: BluetoothGatt, close: () -> Unit) {
        // Open the ticket before probing so a broadcast racing the probe is still recorded.
        val ticket = gate.begin(gatt.device.address)
        if (!linkPhysicallyUp(gatt.device)) {
            Log.i("RingBLEClient", "CRP link already down; waiting only for GATT close")
            gate.linkAlreadyDown(ticket)
        }
        try { gatt.disconnect() } catch (_: Exception) {}
        scope.launch {
            delay(500L)
            try { close() } finally { gate.gattClosed(ticket) }
        }
    }

    /** Unknown (no manager, missing permission) counts as up: wait for the broadcast. */
    private fun linkPhysicallyUp(device: BluetoothDevice): Boolean = try {
        bluetoothManager?.getConnectionState(device, BluetoothProfile.GATT)
            ?.let { it == BluetoothProfile.STATE_CONNECTED || it == BluetoothProfile.STATE_DISCONNECTING }
            ?: true
    } catch (_: Exception) {
        true
    }

    companion object {
        @Volatile private var instance: CRPTeardownMonitor? = null
        fun current(): CRPTeardownMonitor? = instance
        fun get(context: Context): CRPTeardownMonitor = instance ?: synchronized(this) {
            instance ?: CRPTeardownMonitor(context.applicationContext).also { instance = it }
        }
    }
}
