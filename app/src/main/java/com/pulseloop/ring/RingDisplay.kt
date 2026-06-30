package com.pulseloop.ring

/**
 * Friendly model label for a ring.
 *
 * The whole Colmi/Yawell family (R02, R03, R06, R07, R09, R10, R11, R12, H59…) is served by a
 * single driver whose [RingDeviceType] is [RingDeviceType.COLMI_R02], so `displayName` alone
 * would mislabel e.g. an R10 as "Colmi R02". Derive the real model from the advertised BLE name
 * when possible ("COLMI R10_1203" → "Colmi R10", "R02_AB12" → "Colmi R02"), falling back to the
 * family display name when the name carries no recognizable model token.
 */
fun ringModelLabel(name: String?, deviceType: RingDeviceType?): String {
    val type = deviceType ?: return name?.takeIf { it.isNotBlank() } ?: "Ring"
    if (type != RingDeviceType.COLMI_R02) return type.displayName
    val token = (name ?: "").trim()
        .removePrefix("COLMI ").removePrefix("Colmi ")
        .substringBefore('_')
        .trim()
    return if (token.matches(Regex("^[A-Za-z]{1,2}[0-9]{2,3}[A-Za-z]?$"))) {
        "Colmi ${token.uppercase()}"
    } else {
        type.displayName
    }
}
