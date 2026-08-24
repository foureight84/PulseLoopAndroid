# CRP (Colmi R11) driver hardening — implementation plan

**Ledger item:** iOS PR [#93](https://github.com/saksham2001/PulseLoopiOS/pull/93), triaged
2026-08-22 in [`ios-sync.md`](ios-sync.md) (§ "2026-08-22 triage").
**Branch:** `ios-sync-triage-2026-08-22`.
**Scope:** five small, independent fixes to the CRP driver that already exists on Android.
**Effort:** S–M total. No schema change, no UI, no new files.

---

## 0. Read this first — what this task is and isn't

iOS PR #93 is **not** a normal upstream feature to port. It is the *iOS port of this repo's own
Android CRP work*, so the driver, the decoder, the sync engine, the "Colmi R11 (Da Rings app)"
pairing card and the not-worn measurement hint are all **already present here**. Do not port them
again. If you find yourself writing a `CRPDecoder`, you are in the wrong task.

What Android is missing is the hardening iOS added **afterwards**, in commit `4d65b60`
("fix(crp): reset the frame assembler across reconnects, gate connect on fdd3"), which was an
adversarial review of that branch. Five of its eight findings apply to Android. Three do not, and
§7 explains why so nobody re-ports them.

**The iOS commit is your reference implementation.** Read it before you start:

```sh
git -C <ios-repo> show 4d65b60
```

The iOS repo is the parent directory of this one (`../` from `android/`), on branch `main`.
Every item below cites the exact Swift hunk it corresponds to. **Judge behaviour, not syntax** —
a Swift fix ports as a Kotlin rule, and item 4 in particular needs materially different Kotlin,
because Kotlin's UTF-8 decode is not Swift's.

### Ground rules

- Read `AGENTS.md` at this repo root and at the parent repo root first.
- **Do not add a `Co-Authored-By` trailer** to commits in this repo.
- These five items are **independent**. Land them in one commit or five; item 1 is the most
  valuable and stands alone if the rest slip.
- Every item has a test. The suite is `./gradlew testDebugUnitTest` and was **1099 tests, 0
  failures** at the tip of `ios-sync-triage-2026-08-22`. Do not finish below that count.
- **No hardware is available in this environment.** Nothing here needs a ring: all five are unit
  testable. Say so plainly in the commit rather than implying hardware verification.

### Files you will touch

| File | Items |
|---|---|
| `app/src/main/java/com/pulseloop/ring/CRPDriver.kt` | 1 |
| `app/src/main/java/com/pulseloop/ring/CRPSyncEngine.kt` | 2, 3 |
| `app/src/main/java/com/pulseloop/ring/CRPDecoder.kt` | 4, 5 |
| `app/src/test/java/com/pulseloop/ring/CRPSyncEngineTest.kt` | 2, 3 |
| `app/src/test/java/com/pulseloop/ring/CRPDecoderTest.kt` | 1, 4, 5 |

---

## 1. Gate `CONNECTED` on the `fdd3` reply channel

**Severity: highest of the five. Do this one even if you do nothing else.**

### The bug

`CRPDriver` does not override `requiredSubscriptionsBeforeConnected`, so it inherits the default
`emptyList()` from `WearableDriver` (`WearableDriver.kt:32`). With an empty list,
`SubscriptionSetupGate.isReady` falls back to *first-notify* readiness — `completed.isNotEmpty()`
(`SubscriptionSetupGate.kt:31-35`). The connection therefore counts as up on whichever notify
characteristic finishes its CCCD write first.

For CRP that is `fdd1` (the current-steps push), **never** `fdd3`, which carries *every* command
reply. `CONNECTED` is what runs `CRPSyncEngine.runStartup`, so the handshake can write its whole
sequence — set-time, firmware query, six read-backs, five timing enables, six history queries and
the six-day sleep backfill, ~26 frames — into a channel the app is not yet listening to.

A reply lost that way is **indistinguishable from a slow one**. That is the exact signature of the
wrong-opcode bug this driver already fixed once (the group-7 firmware query that produced
23 sends / 0 replies in the 2026-07-25 capture), so a regression here would be diagnosed as a
protocol problem, not a connect-ordering problem.

### The fix

In `CRPDriver.kt`, alongside the other topology declarations:

```kotlin
    /**
     * Hold CONNECTED until `fdd3` is live. Without this the connection counts as up on whichever
     * notify characteristic completes its CCCD write first — for CRP that is `fdd1` (the steps
     * push), never `fdd3`, which carries *every* command reply. CONNECTED is what runs
     * [CRPSyncEngine.runStartup], so a handshake begun too early would write the clock, firmware
     * query, read-backs, timing config and the whole history pull into a channel we aren't
     * listening to yet, and each lost reply is indistinguishable from a slow one.
     *
     * Only `fdd3` is required: `fdd1`/`fdd6`/`2a37` carry no reply the handshake waits on, so
     * gating on them would only delay the connect. NOTIFICATION, not INDICATION — CRP's
     * characteristics are notify (unlike YCBT's indicate pair).
     */
    override val requiredSubscriptionsBeforeConnected = listOf(
        RequiredSubscription(CRPUUIDs.CHAR_CMD_NOTIFY, SubscriptionMode.NOTIFICATION),
    )
```

`RingBLEClient` already threads this through — `installDriver` builds the gate from it
(`RingBLEClient.kt:900-904`). There is no wiring to add.

**Reference:** iOS `CRPDriver.swift`, the `requiredSubscriptionsBeforeConnected` property added in
`4d65b60`. Android's element type is `RequiredSubscription` (uuid + mode), not iOS's bare `CBUUID`.

**Model this on:** `YCBTDriver.kt:43-46`, the only existing Android driver that overrides this.

### The test

Add to `CRPDecoderTest.kt` (where the other driver-topology tests live):

```kotlin
    @Test
    fun `connect is held until the command-reply channel is live`() {
        val driver = CRPDriver(null)
        assertEquals(
            listOf(RequiredSubscription(CRPUUIDs.CHAR_CMD_NOTIFY, SubscriptionMode.NOTIFICATION)),
            driver.requiredSubscriptionsBeforeConnected,
        )
        // A required subscription that isn't a declared notify char could never be satisfied, and
        // the connect would hang until the watchdog killed it.
        for (required in driver.requiredSubscriptionsBeforeConnected) {
            assertTrue(
                "${required.uuid} is not a declared notify characteristic",
                driver.notifyUUIDs.any { it.equals(required.uuid, ignoreCase = true) },
            )
        }
    }
```

That second assertion is not padding — it is the failure mode that would turn this fix into a
connect hang.

---

## 2. Stop re-querying firmware on every poll pass

### The bug

`CRPSyncEngine.runStartup` sends `CRPProtocol.queryFirmwareVersion()` unconditionally
(`CRPSyncEngine.kt:42`), while the six read-backs immediately below it are gated behind
`readBacksSent`. The KDoc on `sendConnectionReadBacks` argues the gate exists because the single
`fdd2` channel is scarce and a spot SpO2 needs ~48 s of it — an argument the line above it
contradicts.

`runStartup` **is** the poll pass: `RingSyncWorker`'s ~30-minute background sync and the foreground
`syncNow()` both re-invoke it. So this is one extra write on the scarce channel every half hour,
forever, for a string that cannot change between syncs.

### The fix

In `CRPSyncEngine.kt`:

1. Delete the `send(CRPProtocol.queryFirmwareVersion())` call and its comment block from
   `runStartup` (currently lines 37-42).
2. Move that send to the **top** of `sendConnectionReadBacks()`, before `querySupportSpO2Type()`.
3. Rename `sendConnectionReadBacks` → `sendConnectionQueries` and `readBacksSent` →
   `connectionQueriesSent`. iOS did this because "read-backs" no longer describes the set once
   firmware joins it. Rename the `CRPSyncEngineTest` helper `readBackQueries` to match.
4. Fold the firmware rationale (the 7/1-vs-3/3 opcode history — keep it, it is hard-won) into the
   `sendConnectionQueries` KDoc.
5. Update that KDoc's "six writes" to "seven writes".

**Ordering constraint — do not disturb it.** `sendConnectionQueries()` must still run **before**
`applyTimingSettings(...)`. The state queries report each monitor's *current* interval, and
`applyTimingSettings` force-enables everything moments later; asking afterwards would only describe
the state we just imposed, which answers nothing. `CRPSyncEngineTest` pins this. If that assertion
fails, fix the call site, not the expectation.

**Reference:** iOS `CRPSyncEngine.swift` — the `sendConnectionReadBacks` → `sendConnectionQueries`
rename hunk in `4d65b60`.

### Also update the now-false comments

Two comments elsewhere assert the old behaviour and become wrong:

- `CRPDecoder.kt:195-197` — "…and [CRPSyncEngine.runStartup] re-queries firmware on every sync
  pass". After this change it does not.
- `CRPDecoderTest.kt:119-122`, the test named *`firmware version reaches the device record as its
  own event, not a connection change`* — its comment says "runStartup re-queries firmware on every
  ~30-minute sync pass, so that path would restate CONNECTED all session long."

The **test itself stays and must keep passing** — firmware must still not bridge to
`DeviceStateChanged`. Only the justification changes: a firmware reply says nothing about the
connection, and bridging it to CONNECTED would restamp the device row as freshly connected. Rewrite
the comment; do not delete the test.

### The tests

Two existing tests in `CRPSyncEngineTest.kt` assert the current behaviour and **must** be updated —
they will fail, and that failure is correct:

- **line 37**, `runStartup sends set-time, firmware query, user info, default monitor enables, then
  the history pull`. Both `assertEquals` calls embed `3 to 3` in the expected opcode list. First
  pass: `3 to 3` moves from position 2 into the read-back group. Second pass: `3 to 3` must
  **disappear** — expected becomes `listOf(1 to 1, 1 to 0) + timingEnables + historyQueries`.
  Rename the test to match its new meaning.
- **line 88**, `read-backs are sent once per connection, not once per poll pass`. Add firmware to
  the set it guards, and rename to `connection queries are sent once per connection…`.

Then add the explicit regression:

```kotlin
    @Test
    fun `firmware is asked once per connection, not on every poll pass`() {
        // runStartup IS the ~30-minute background sync. A firmware string is exactly as immutable
        // as the sensor roster gated beside it, and fdd2 is the scarce channel (a spot SpO2 needs
        // ~48 s of it).
        val w = FakeWriter()
        val engine = CRPSyncEngine(w)
        engine.runStartup()
        assertTrue("firmware asked on the first pass", (3 to 3) in w.opcodes())

        w.sent.clear()
        engine.runStartup()
        assertTrue("firmware must not repeat every pass", (3 to 3) !in w.opcodes())

        // A new connection builds a new engine, which asks again.
        val reconnected = FakeWriter()
        CRPSyncEngine(reconnected).runStartup()
        assertTrue((3 to 3) in reconnected.opcodes())
    }
```

**Reference:** iOS `CRPSyncEngineTests.swift`,
`testConnectionQueriesAreSentOncePerConnectionNotPerPass`.

---

## 3. Key the timing follow-up guard on `day` as well as `cmd`

### The bug

`CRPSyncEngine.kt:104` declares `requestedTimingFrames` as `mutableSetOf<Int>()`, and line 179 keys
it `event.cmd * 100 + nextIndex`. The `day` is not in the key.

Today every timing query is `day = 0`, so nothing is broken *right now*. But this engine **already
issues multi-day requests** — `sendSleepBackfill()` walks `1..SLEEP_BACKFILL_DAYS` (6 days). The
moment the timing vitals get the same backfill treatment, day 1's frame-1 follow-up is silently
swallowed because day 0 already inserted the same key. Silently: no error, just a day that never
completes its multi-frame pull.

This is pre-emptive, and worth doing because the failure is invisible when it lands.

### The fix

In `CRPSyncEngine.kt`, replace the `Int` key with a data class:

```kotlin
    /** One timing-history follow-up we've already asked for. Keyed on `day` as well as `cmd` —
     *  today's queries are all day 0, but this engine already issues multi-day requests for sleep
     *  ([sendSleepBackfill]), and a key without `day` would silently swallow day 1's frame-1
     *  follow-up the moment the timing vitals get the same backfill treatment. */
    private data class TimingFrameRequest(val cmd: Int, val day: Int, val frameIndex: Int)

    /** Frame follow-ups already requested this poll pass, so a ring that re-sends the same frame
     *  can't trigger a request storm. Cleared at the start of every [queryAllHistory] pass so each
     *  sync re-pulls the full timeline. */
    private val requestedTimingFrames = mutableSetOf<TimingFrameRequest>()
```

and at the guard (line 179):

```kotlin
            val request = TimingFrameRequest(event.cmd, event.day, nextIndex)
            if (!requestedTimingFrames.add(request)) return
```

`requestedTimingFrames.clear()` in `queryAllHistory()` is unchanged.

**Reference:** iOS `CRPSyncEngine.swift`, the `TimingFrameRequest` struct in `4d65b60`.

### The test

`a repeated frame does not spam duplicate follow-up requests` (line 181) must still pass unchanged —
that is the property this must not break. Add:

```kotlin
    @Test
    fun `the follow-up guard distinguishes days`() {
        val w = FakeWriter()
        val engine = CRPSyncEngine(w)
        engine.runStartup()
        w.sent.clear()
        engine.handle(RingDecodedEvent.TimingHistoryFrame(cmd = 15, day = 0, frameIndex = 0))
        engine.handle(RingDecodedEvent.TimingHistoryFrame(cmd = 15, day = 1, frameIndex = 0))
        assertEquals("a different day is a different follow-up", 2, w.sent.size)
        assertEquals(0, w.sent[0][6].toInt())   // day 0 in the payload
        assertEquals(1, w.sent[1][6].toInt())   // day 1
    }
```

Check `RingDecodedEvent.TimingHistoryFrame`'s actual constructor signature and the payload byte
offset against `CRPProtocol.queryTimingHeartRateHistory(day, frameIndex)` before trusting the
indices above — index 6 is what iOS asserts and Android's frame layout matches, but verify rather
than assume.

**Reference:** iOS `CRPSyncEngineTests.swift`, `testFollowUpGuardDistinguishesDays`.

---

## 4. Validate the firmware string instead of coercing it

**This is the item whose Kotlin differs most from the Swift. Read carefully.**

### The bug

`CRPDecoder.decodeFirmwareVersion` (`CRPDecoder.kt:199-204`) does:

```kotlin
val version = String(payload, Charsets.UTF_8).trim { it <= ' ' }
```

`String(bytes, UTF_8)` in Kotlin/JVM is **lenient**: invalid byte sequences are silently replaced
with U+FFFD. It cannot fail. So a binary payload becomes a row of replacement characters and is
published as `RingDecodedEvent.FirmwareRevision` — and whatever this returns is **shown verbatim in
the Settings device card**. The user sees replacement characters presented as their ring's firmware
version.

### The fix

Strict-decode, then trim padding, then reject anything still holding a control byte:

```kotlin
    private fun decodeFirmwareVersion(payload: ByteArray): List<RingDecodedEvent>? {
        // Validated, not coerced: whatever this returns is shown verbatim in Settings.
        // `String(bytes, UTF_8)` is LENIENT on the JVM — it substitutes U+FFFD for invalid bytes
        // and cannot fail — so a binary payload would render as a row of replacement characters
        // presented as a firmware version. A REPORTing decoder throws instead.
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val raw = try {
            decoder.decode(ByteBuffer.wrap(payload)).toString()
        } catch (_: CharacterCodingException) {
            return null   // not text at all — the caller acks it
        }
        // Trim only what firmwares actually pad with: whitespace and NUL. Deliberately narrower
        // than the vendor's `trim { it <= ' ' }`, which strips ALL control bytes and would let a
        // binary payload's leading junk come off so whatever printable byte followed passed as a
        // "version" (01 02 03 41 -> "A"). Padding comes off, then anything still holding a control
        // byte is rejected outright rather than salvaged.
        val trimmed = raw.trim { it.isWhitespace() || it == NUL }
        if (trimmed.isEmpty()) return null
        if (trimmed.any { it.isISOControl() }) return null
        return listOf(RingDecodedEvent.FirmwareRevision(trimmed))
    }
```

where `NUL` is the NUL character — declare it as a private constant, `private const val NUL = '\u0000'`, so the predicate stays readable.

Imports needed: `java.nio.ByteBuffer`, `java.nio.charset.CharacterCodingException`,
`java.nio.charset.CodingErrorAction`.

Returning `null` is already the "nothing readable" contract — the caller at `CRPDecoder.kt:135`
falls through to `CommandAck`. Do not change that path.

**Item 5 is this same edit** — the narrower trim is the second half of the same function, kept as a
separate ledger row only because iOS listed it separately. There is nothing extra to do for it.

**Reference:** iOS `CRPDecoder.swift`, `decodeFirmwareVersion` in `4d65b60`. Swift's
`String(bytes:encoding:)` returns `nil` on invalid UTF-8, which is why the Swift version needs no
explicit decoder — **Kotlin has no equivalent one-liner**, hence the `CharsetDecoder`.

### The tests

Three existing tests in `CRPDecoderTest.kt` must keep passing unchanged — they are the regression
net against over-tightening:

- `firmware version decodes as the UTF-8 string the vendor reads` (line 109) — `MOY-R1K3-2.1.6`.
- `firmware version tolerates NUL padding` (line 130) — trailing NULs still trimmed.
- `empty firmware payload is acked, not reported as a blank version` (line 138) — a lone `0x00`
  still acks.

Add:

```kotlin
    @Test
    fun `a non-text firmware payload is acked rather than coerced into a version`() {
        // Whatever decodeFirmwareVersion returns is shown verbatim in Settings, so a payload that
        // isn't a version string must ack. `String(bytes, UTF_8)` would have coerced the first into
        // a U+FFFD run and the second into control junk, and published both as a firmware version.
        val payloads = listOf(
            byteArrayOf(0xC3.toByte(), 0x28, 0xA0.toByte(), 0xFF.toByte()),  // invalid UTF-8
            byteArrayOf(0x01, 0x02, 0x03, 0x41),                             // valid UTF-8, binary
        )
        for (payload in payloads) {
            val frame = CRPProtocol.frame(3, CRPCommands.CMD_QUERY_FIRMWARE_VERSION, payload)
            val events = CRPDecoder.decode(frame, fdd3)
            assertTrue(
                "payload must not publish a version",
                events.none { it is RingDecodedEvent.FirmwareRevision },
            )
            assertTrue(events.single() is RingDecodedEvent.CommandAck)
        }
    }
```

The second payload is the one that proves the *narrow* trim (item 5): under the vendor's
`trim { it <= ' ' }` it would have yielded the version string `"A"`.

**Reference:** iOS `CRPDecoderTests.swift`,
`testNonTextFirmwarePayloadsAreRejectedRatherThanCoerced` — same two payloads.

---

## 5. (Same edit as item 4)

Kept as its own row because the ledger and the iOS commit list it separately. The narrower trim is
implemented by the `trim { it.isWhitespace() || it == NUL }` + `isISOControl()` rejection in §4.
Nothing further to do.

---

## 6. Verification

```sh
./gradlew compileDebugKotlin        # KSP/Room validate on the way through
./gradlew testDebugUnitTest         # expect >= 1099 + your new tests, 0 failures
```

Count the suite the way the ledger does:

```sh
python3 - <<'PY'
import glob, re
t = f = e = 0
for p in glob.glob('app/build/test-results/testDebugUnitTest/*.xml'):
    m = re.search(r'tests="(\d+)".*?failures="(\d+)".*?errors="(\d+)"', open(p).read(4000))
    if m: t += int(m[1]); f += int(m[2]); e += int(m[3])
print(f"tests={t} failures={f} errors={e}")
PY
```

**What cannot be verified here:** every one of these is about BLE timing or wire-format edge cases
that need zaggash's R11 to observe for real. The unit tests pin the *rules*; they do not prove the
ring behaves as assumed. Item 1 in particular changes when `CONNECTED` fires, which is exactly the
kind of change that looks fine in tests and reveals itself on hardware. State this honestly in the
commit message — do not write "verified" for anything that wasn't.

If hardware does become available, the honest test for item 1 is: pair the R11, confirm the
handshake completes rather than partially answering, and confirm the connect doesn't hang (a
required subscription that never completes would stall until the 30 s watchdog).

---

## 7. Do NOT port these three

They are in the iOS commit and they do not apply here. Recorded so nobody re-ports them.

### 7a. Frame-assembler reset across reconnects

This was iOS's headline bug: there, auto-reconnect re-dials with a bare `central.connect` and keeps
the `CRPDriver` instance, so a frame left half-assembled when the old link dropped is completed with
bytes from the new one and decoded as genuine. Because the group-2 history frames are long and
multi-notification, the spliced result is a *fabricated vital sample*, not a parse failure.

**Android is safe by construction:**

- It connects with `autoConnect = false` (`RingBLEClient.kt:804`), matching the official QRing app.
- Every reconnect path funnels through `beginConnect`, which calls `installDriver`
  (`RingBLEClient.kt:787`).
- `installDriver` builds a fresh driver via `coordinator.makeDriver` and immediately calls
  `driver.connectionDidStart()` (`RingBLEClient.kt:897-900`).

So each link gets a brand-new `CRPDriver` with a brand-new `CRPFrameAssembler`. Adding a `reset()`
hook would be dead code.

`CRPDriver`'s KDoc already states this invariant correctly. **Keep it accurate.** The single change
that would reintroduce the iOS bug is a reconnect path that reuses a driver instead of reinstalling
it — if you ever make that change, this item comes back, and `connectionDidStart`/`connectionDidEnd`
already exist on `WearableDriver` (`WearableDriver.kt:50-51`) to hang the reset on. `RWfitDriver`
and `YCBTDriver` show the pattern.

### 7b. Half-open sleep-backfill loop

iOS used `for daysAgo in 1...crpSleepBackfillDays`, which **traps at runtime** if the documented
tuning knob is turned down to today-only (`1...0` is an invalid `ClosedRange`). iOS changed it to
`1..<(n + 1)`.

Kotlin's `1..SLEEP_BACKFILL_DAYS` is an `IntRange`, and `1..0` is simply **empty** — no exception,
the loop body doesn't run. `CRPSyncEngine.kt:151` is already safe at `SLEEP_BACKFILL_DAYS = 0`.
Changing it to `until` would be churn.

### 7c. "Once per connection" comment corrections

iOS's comments claimed connection scope for state that is really per-driver-install, and were
rewritten. Android's already say the right thing: `readBacksSent` and `sleepBackfillSent` are both
documented as per-engine-instance, with a fresh engine built per connect
(`CRPSyncEngine.kt:63-65`, `124-126`).

Note the nuance if you touch them: on Android the "fresh engine per connect" claim is *true*
(§7a), which is why the comments are accurate here and were not on iOS.

---

## 8. When you're done

1. Update the port-queue row for #93 in [`ios-sync.md`](ios-sync.md) — flip `☐` to `☑` and put your
   commit SHA in the **Android commit** column.
2. Remove #93 from the "▶ RESUME HERE" list and drop the count back to two threads.
3. If you land only some items, say which in the row rather than flipping it — a half-done row that
   reads as done is worse than an open one.
