package com.pulseloop.ring

/**
 * TEMPORARY pairing-scan trace — remove once issue #96 is resolved (tracked there).
 *
 * A Colmi R02 replacement ring never appeared on the pairing screen, and the reporter's export
 * (v2.9.1) held no scan results at all, so nothing said what the new ring advertises or whether
 * it was sighted. While the pairing screen is open this logs, into the diagnostics log, each
 * device the scan sees: its advertised name, what the matcher made of it, and whether it is the
 * previously paired ring. Removal: delete this file, its test, and every `PairingScanTrace` /
 * `pairingTrace` call site in [RingBLEClient] (each is marked `TEMP(#96)`).
 *
 * Privacy: no MAC address is logged (only "is this the last-known ring"), and the per-unit serial
 * suffix is replaced by its shape ("R02_A1B2" → `R02` + `_xxxx`), so lines are safe in a masked
 * export too. Unnamed devices that match no ring family are counted, not logged — a city street
 * is full of them.
 */
internal class PairingScanTrace(private val record: (String) -> Unit) {
    private val loggedNames = HashMap<String, String?>()
    private val unnamedIgnored = HashSet<String>()
    private var active = false
    private val dropped = HashSet<String>()

    @Synchronized
    fun scanStarted(hasLastKnown: Boolean) {
        loggedNames.clear(); unnamedIgnored.clear(); dropped.clear()
        active = true
        record("pairing-trace: scan start lastKnown=$hasLastKnown")
    }

    @Synchronized
    fun sighting(
        address: String,
        rawName: String?,
        rssi: Int,
        matchedType: RingDeviceType?,
        matchedModelID: String?,
        serviceUUIDs: List<String>,
        companyIDs: List<Int>,
        isLastKnown: Boolean,
    ) {
        if (!active) return
        if (rawName.isNullOrEmpty() && matchedType == null) {
            unnamedIgnored.add(address)
            return
        }
        // Once per device per scan, again only if its name changes (scan-response names can
        // arrive after an unnamed primary advertisement).
        if (loggedNames.containsKey(address) && loggedNames[address] == rawName) return
        // The cap only applies to devices no ring family claimed: headphones and TVs must not
        // crowd out the ring this trace exists to find.
        if (matchedType == null && !loggedNames.containsKey(address) &&
            loggedNames.size >= MAX_DEVICES_PER_SCAN) {
            dropped.add(address)
            return
        }
        unnamedIgnored.remove(address)
        loggedNames[address] = rawName
        record(describe(rawName, rssi, matchedType, matchedModelID, serviceUUIDs, companyIDs, isLastKnown))
    }

    @Synchronized
    fun scanEnded(reason: String) {
        if (!active) return
        active = false
        record("pairing-trace: scan end ($reason) logged=${loggedNames.size} dropped=${dropped.size} " +
            "unnamedUnmatched=${unnamedIgnored.size}")
    }

    companion object {
        const val MAX_DEVICES_PER_SCAN = 40

        fun describe(
            rawName: String?,
            rssi: Int,
            matchedType: RingDeviceType?,
            matchedModelID: String?,
            serviceUUIDs: List<String>,
            companyIDs: List<Int>,
            isLastKnown: Boolean,
        ): String {
            val name = rawName?.takeIf { it.isNotEmpty() }
            val base = name?.let { ringNameWithoutSerial(it) }
            val suffix = if (name != null && base != null && base.length < name.length) {
                val s = name.substring(base.length)
                s.first() + "x".repeat(s.length - 1)
            } else "-"
            val uuids = serviceUUIDs.take(4).joinToString(",") { shortUuid(it) }.ifEmpty { "-" }
            val companies = companyIDs.take(4).joinToString(",") { "%04x".format(it) }.ifEmpty { "-" }
            return "pairing-trace: seen name=${base?.let { "\"$it\"" } ?: "<none>"} suffix=$suffix " +
                "rssi=$rssi family=${matchedType?.name ?: "-"} model=${matchedModelID ?: "-"} " +
                "uuids=$uuids mfr=$companies lastKnown=$isLastKnown"
        }

        /** `0000fff0-0000-1000-8000-00805f9b34fb` → `fff0`; anything else verbatim. */
        private fun shortUuid(uuid: String): String {
            val u = uuid.lowercase()
            return if (u.startsWith("0000") && u.endsWith("-0000-1000-8000-00805f9b34fb")) u.substring(4, 8) else u
        }
    }
}
