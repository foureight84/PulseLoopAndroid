# Agent instructions — PulseLoop Android

Read this before touching ring/BLE hardware code (`app/src/main/java/com/pulseloop/ring/`,
`app/src/main/java/com/pulseloop/wearables/`). Full detail: `docs/qring-ble-adoption.md`.

## Ring BLE protocol work — match the vendor app, not iOS

When porting or fixing a ring's BLE protocol (connect/pairing sequence, GATT characteristic
writes, command framing, notification handling), reference the **decompiled official vendor
Android app** (`decompiled-qring-official/`, `decompiled-jring-offical/`, etc., at the repo
root) and match its actual behavior. Do not port iOS's CoreBluetooth sequencing — Android's
Bluetooth stack behaves differently (pairing/bonding flow, MTU negotiation, background
restrictions), and a straight iOS port has caused real pairing/data-collection bugs before.

## Colmi/Yawell OS-bonding is a hand-curated allowlist — not "match QRing exactly"

**This is the one rule in this file most likely to get silently reverted by a future "generalize
to match the vendor app" fix. Read it before changing anything with "bond" in the name.**

The real QRing app (`decompiled-qring-official/.../DeviceCmdInit.java`) bonds **unconditionally**
whenever a ring's `0x3C` device-support reply sets `supportBlePair` — no per-model check.
PulseLoop deliberately does **not** copy this. `RingBLEClient.bondActiveDevice()` also requires
`WearableModel.requiresOsBond == true` for the resolved model — currently only `COLMI_R09`,
`COLMI_R11`, `YAWELL_R11`. Every other Colmi/Yawell model (notably the **R10**, which also
reports `supportBlePair`) stays GATT-only on purpose: bonding triggers a real OS pairing dialog
and puts the ring in the phone's paired-devices list, a UX cost not worth paying for a model that
already holds a stable link without it.

**This has already regressed once in production**, in the same release: a 2026-07-19 fix for
issue #29 (R11 stuck on "Connecting") removed the allowlist in favor of QRing's blanket rule,
which fixed the R11 but reopened the R10 pairing-dialog bug that a 2026-07-15 commit had
deliberately fixed by introducing the allowlist. Corrected by restoring the allowlist and adding
R11/Yawell R11 to it by name.

**The rule:** when a new model is confirmed (real hardware, or a specific credible user report)
to need an OS bond, add it to `WearableModel.requiresOsBond`'s allowlist by name. Never widen
the condition to "whenever `supportBlePair` is set" to match the vendor app — that is exactly
the change that caused the regression, and it will cause it again for the R10.

See `docs/qring-ble-adoption.md` §5a for the full history and the decompiled source references.
