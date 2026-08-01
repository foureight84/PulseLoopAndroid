package com.pulseloop.ring

/**
 * Pure post-connect driver-reroute policy. Android GATT objects stay in RingBLEClient; this holds
 * the decision so it can be unit-tested (issue #29).
 */
internal object DriverReroute {

    /**
     * True when the connection should be moved back to the jring driver.
     *
     * `RingBLEClient.connectTo`'s `honorSelection` treats a JRING classification of the generic
     * "SMART_RING" advertisement as a fallback guess and lets the user's carousel pick win. For a
     * jring-firmware ring sold under a Colmi badge that guess is wrong — picking "Colmi / Yawell
     * (SmartHealth app)" installs the YCBT driver against a ring that only speaks 000056ff, and the
     * connect hard-fails on the missing be940001/be940003 channels.
     *
     * The advertisement can't distinguish the two, but the discovered GATT table can: the jring
     * service present *and* every service the active driver declared absent means the carousel pick
     * was wrong about this ring. Requiring the active driver's own services to be missing is what
     * keeps this from stealing a ring that genuinely speaks its selected protocol.
     *
     * @param discoveredServices service UUIDs from the connected GATT table
     * @param activeDeclaredServices `serviceUUIDs` of the currently installed driver
     * @param activeDeviceType family of the currently installed coordinator
     */
    fun shouldRerouteToJring(
        discoveredServices: Collection<String>,
        activeDeclaredServices: Collection<String>,
        activeDeviceType: RingDeviceType?,
    ): Boolean {
        if (activeDeviceType == RingDeviceType.JRING) return false
        val present = discoveredServices.map { it.lowercase() }.toSet()
        if (RingUUIDs.SERVICE.lowercase() !in present) return false
        // An empty declaration would make "none present" vacuously true; a driver that declares no
        // service of its own gives us no evidence the pick was wrong, so leave it alone.
        if (activeDeclaredServices.isEmpty()) return false
        return activeDeclaredServices.none { it.lowercase() in present }
    }
}
