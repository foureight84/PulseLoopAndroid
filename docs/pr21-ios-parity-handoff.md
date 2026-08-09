# PR #21 fixes — iOS parity handoff

Summary of the Android changes from PR #21 (Colmi sleep + AI Coach), and **what iOS
needs vs. what iOS already has**. Most of the coach work was Android catching up to
iOS, so the action list is short. Background: issue #19 (sleep not tracking, "AI same
response"). File/line references are against the iOS (root) repo at the time of writing.

---

## TL;DR — iOS action list

| Area | Change | iOS status |
|------|--------|-----------|
| Coach | Gate the `reasoning` field by model | **⚠️ Needs fix — same bug on iOS** |
| Coach | Surface `result.error` as a red bubble | ✅ Already done (Android ported from iOS) |
| Coach | Filter error bubbles out of replay history | ✅ Already done |
| Colmi | Guard the sleep→HRV pipeline advance by stage | **🔸 Recommended — latent duplicate-request on iOS** |
| Colmi | Decouple sleep into an on-demand `syncSleepNow()` | ⚪ Android-only feature — optional to port |
| Colmi | GATT write-queue hardening (retry + reconnect) | ⚪ N/A — Android framework-specific |

---

## Part A — AI Coach

### A1. Gate the `reasoning` field by model — ⚠️ iOS NEEDS THIS

**Bug (present on both platforms):** the request builder adds `"reasoning": {"effort": …}`
whenever a reasoning effort is set in Settings, with **no check that the selected model
supports it**. OpenAI's legacy chat models (`gpt-4o`, `gpt-4*`, `gpt-3.5`, `chatgpt-*`)
reject any request carrying `reasoning` with an HTTP 400. Combined with the un-gated
Settings combo (non-reasoning model + a set effort), **every** coach turn failed — which,
because of the error-masking bug (A2, already fixed on iOS), looked like "the same answer
every time" in issue #19.

**iOS location:** `PulseLoop/Coach/OpenAI/ResponsesTypes.swift:127`

```swift
if let reasoningEffort, !reasoningEffort.isEmpty { body["reasoning"] = ["effort": reasoningEffort] }
```

**Fix (mirror Android):** only attach `reasoning` when the model supports it. Default to
**allowing** it so unknown/future models aren't blocked; suppress only the known
non-reasoning families. Strip any OpenRouter `vendor/model` prefix before matching.

```swift
// non-reasoning families that 400 on a `reasoning` field
private func modelSupportsReasoning(_ model: String) -> Bool {
    let slug = model.lowercased().split(separator: "/").last.map(String.init) ?? model.lowercased()
    return !(slug.hasPrefix("gpt-4") || slug.hasPrefix("gpt-3") || slug.hasPrefix("chatgpt"))
}

// in body(...):
if let reasoningEffort, !reasoningEffort.isEmpty, modelSupportsReasoning(model) {
    body["reasoning"] = ["effort": reasoningEffort]
}
```

Apply to **both** the chat path (`CoachOrchestrator`) and the summary path
(`CoachSummaryGenerator`) — both call the same builder, so gating it once in
`ResponsesTypes.body(...)` covers them. Android's version:
`OpenAIRequestBuilder.reasoningParams(effort, model)`.

> Note: this only gates the **OpenAI Responses** builder. If OpenRouter/Gemini/MiniMax
> clients can also emit `reasoning` for a non-reasoning model, give them the same guard.

### A2. Surface real errors as a red bubble — ✅ iOS already has this

Android was catching up here. A failed turn returns a fixed `CoachFallbacks.fallback()`
string in `TurnResult.assistant` with the real cause in `TurnResult.error`; Android was
rendering only the fallback and discarding the error, so every failure showed an identical
canned bubble. iOS already renders `result.error` as a distinct `role: "error"` bubble
carrying code + reason — see `CoachViewModel.swift:134`. **No action.**

### A3. Don't replay error bubbles to the model — ✅ iOS already has this

Error bubbles are app-generated diagnostics, not real assistant turns; replaying them as
history feeds the model garbage like "Coach error · HTTP 404 …". iOS already filters them:
`CoachViewModel.swift:226` — `.filter { $0.role != "error" }`. Android now filters on an
`isError` flag to match. **No action.**

> Android-only footnote: Android also had a *duplicate* display bug (the red bubble **and**
> a separate "Error: <code>" footer showed for the same failure). That was specific to the
> Android Compose screen setting `state.error` in addition to the bubble; iOS uses the
> `role: "error"` message as the single surface, so it isn't affected.

---

## Part B — Colmi sleep

### B1. Guard the sleep→HRV pipeline advance by stage — 🔸 iOS recommended

**Latent bug on iOS:** `handleBigDataComplete(.bigDataSleep)` advances the staged history
pipeline to HRV **unconditionally**, without checking the current stage.

**iOS location:** `PulseLoop/RingProtocol/ColmiSyncEngine.swift:259`

```swift
case ColmiCommandID.bigDataSleep:
    stage = .hrv
    daysAgo = 0
    requestHRV()
    armWatchdog()
```

On iOS the failure mode is the **watchdog-skip** case: if the SLEEP stage stalls, the
watchdog `forceAdvanceStage(.sleep)` already sets `stage = .hrv` and requests HRV
(`ColmiSyncEngine.swift:193`). If the real sleep big-data completion then lands late, this
branch runs again → a **duplicate HRV request** mid-pipeline.

**Fix (mirror Android):** only advance when we're actually on the SLEEP stage.

```swift
case ColmiCommandID.bigDataSleep:
    guard stage == .sleep else { return }   // ignore a late/stray sleep completion
    stage = .hrv
    daysAgo = 0
    requestHRV()
    armWatchdog()
```

On Android this guard also protects the standalone-sleep race (see B2); iOS doesn't have
that path yet, so for iOS this is purely the duplicate-request hardening — but it's the
same one-line guard and worth taking.

### B2. Decouple sleep into on-demand `syncSleepNow()` — ⚪ Android-only, optional to port

Android added a standalone, off-pipeline sleep fetch (QRing parity): opening the Sleep
screen fires a dedicated `bigDataSleep()` request instead of depending on the SLEEP stage
surviving four earlier stages (ACTIVITY→HR→STRESS→SPO2). The standalone completion is
guarded (`sleepOnlyActive`) so it doesn't advance the full-sync pipeline.

iOS has **no** `syncSleepNow()` — sleep is still fetched only mid-pipeline. This is an
enhancement, not a correctness bug, so it's optional. If you port it, the B1 guard becomes
load-bearing (a standalone reply that lands after a full sync starts must not jump the
pipeline), so land B1 first.

### B3. GATT write-queue hardening — ⚪ N/A to iOS

Android hardened its BLE op queue: more persistent write retries (`MAX_OP_ATTEMPTS` 3→6),
and — the key change — forcing a reconnect after a run of dropped/timed-out ops to clear
the Android framework's stuck single-op busy flag (`mDeviceBusy`) instead of spin-dropping
commands. This targets an **Android BluetoothGatt framework** behavior; iOS CoreBluetooth
manages write serialization differently and doesn't have the equivalent wedge. **No iOS
counterpart needed.**

---

## Questions

Ping me (Android) if any of the iOS references have drifted — line numbers are from a
snapshot. The two that matter are **A1 (reasoning gate — real bug)** and **B1 (sleep-stage
guard — hardening)**.
