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
     * Scoped to `scanDetectedType == JRING`, i.e. only where `honorSelection` actually overrode a
     * generic-"SMART_RING" guess. Two reasons:
     *  - It matches the rationale. A *confident* scan match (a Colmi name pattern, an advertised
     *    service UUID) is evidence about the hardware that this function has none of; `connectTo`
     *    already draws that ambiguous-vs-confident line and only overrides the JRING fallback.
     *  - Without it, any driver whose services are missing from the table is fair game — including
     *    the Colmi driver on an R09/R11, whose UART profile (`6e40fff0`/`de5bf728`) is suspected to
     *    be gated behind the OS bond (root `AGENTS.md`). Re-routing there would be self-sealing:
     *    the model re-resolves to generic JRING, whose `requiresOsBond` is false, so the bond that
     *    would reveal the Colmi profile never fires — and the jring family is persisted to
     *    `LAST_WEARABLE_MODEL_KEY` on CONNECTED, so every later reconnect starts there too.
     *
     * Cost of the guard: a jring-firmware ring that advertises a *Colmi* name pattern (`R09_ABCD`
     * rather than `SMART_RING`) won't be rescued. No such unit has been reported — itspuia's
     * advertises the generic name — and rescuing it would mean overriding a confident match.
     *
     * @param discoveredServices service UUIDs from the connected GATT table
     * @param activeDeclaredServices `serviceUUIDs` of the currently installed driver
     * @param activeDeviceType family of the currently installed coordinator
     * @param scanDetectedType family the *scanner* classified this ring as, or null when the
     *   connection didn't come from a fresh scan match (a direct reconnect to a stored address)
     */
    fun shouldRerouteToJring(
        discoveredServices: Collection<String>,
        activeDeclaredServices: Collection<String>,
        activeDeviceType: RingDeviceType?,
        scanDetectedType: RingDeviceType?,
    ): Boolean {
        if (activeDeviceType == RingDeviceType.JRING) return false
        if (scanDetectedType != RingDeviceType.JRING) return false
        val present = discoveredServices.map { it.lowercase() }.toSet()
        if (RingUUIDs.SERVICE.lowercase() !in present) return false
        // An empty declaration would make "none present" vacuously true; a driver that declares no
        // service of its own gives us no evidence the pick was wrong, so leave it alone.
        if (activeDeclaredServices.isEmpty()) return false
        return activeDeclaredServices.none { it.lowercase() in present }
    }
}
