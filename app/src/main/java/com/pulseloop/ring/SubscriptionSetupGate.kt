package com.pulseloop.ring

/** Pure readiness policy for GATT notification setup. Android objects stay in RingBLEClient. */
internal class SubscriptionSetupGate(
    notifyUUIDs: List<String>,
    requiredSubscriptions: List<RequiredSubscription>,
) {
    private data class Observed(val localEnabled: Boolean, val hasCccd: Boolean)

    private val notifyUUIDs = notifyUUIDs.map(String::lowercase).toSet()
    private val required = requiredSubscriptions.associateBy { it.uuid.lowercase() }
    private val observed = mutableMapOf<String, Observed>()
    private val completed = mutableSetOf<String>()

    fun observeCharacteristic(uuid: String, localEnabled: Boolean, hasCccd: Boolean) {
        val key = uuid.lowercase()
        if (key in notifyUUIDs) observed[key] = Observed(localEnabled, hasCccd)
    }

    fun modeFor(uuid: String): SubscriptionMode =
        required[uuid.lowercase()]?.mode ?: SubscriptionMode.NOTIFICATION

    fun isRequired(uuid: String): Boolean = uuid.lowercase() in required

    fun descriptorWritten(uuid: String, successful: Boolean) {
        val key = uuid.lowercase()
        if (successful && key in notifyUUIDs) completed += key
    }

    val isReady: Boolean
        get() = if (required.isEmpty()) {
            completed.isNotEmpty()
        } else {
            required.keys.all { it in completed }
        }

    /** Validate declared topology after service discovery, before any partial setup can connect. */
    fun topologyFailure(): String? {
        if (required.isEmpty()) return null
        val unavailable = required.keys.filter { uuid ->
            val channel = observed[uuid]
            channel == null || !channel.localEnabled || !channel.hasCccd
        }
        if (unavailable.isEmpty()) return null
        val channels = unavailable.joinToString { it.substringBefore('-').uppercase() }
        return "Required ring indication channel unavailable: $channels"
    }
}
