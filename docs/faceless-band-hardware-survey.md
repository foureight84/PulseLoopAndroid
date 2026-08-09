# Faceless (screenless) health-band ODM survey — white-label sourcing

**Question this answers:** beyond the smart *rings* already in PulseLoop's catalog
(Colmi/Yawell R02–R12, H59, jring), which **displayless / screenless wristbands** have
sensors as good as — or better than — the **Colmi R10** and **Rogbid Loop Air**, **from
manufacturers that offer white-label / OEM / ODM** (rebrandable — custom logo, custom
app/SDK, sold B2B with an MOQ)? And which could PulseLoop's app actually talk to?

**Scope:** screenless wrist bands/straps only (Whoop/Helio-Strap form factor); no screened
watches, no rings. **Hard filter: white-label-capable suppliers.** Consumer-only brands
(Whoop, Fitbit, Amazfit, Hume) appear only as sensor benchmarks, not sourcing options.

> **Sourcing caveat.** The `firecrawl` CLI was out of API credits during this research, so
> data came from web search + vendor/ODM pages (Alibaba/Made-in-China/company sites) + the
> openFDA 510(k) and FCC-ID databases + the Gadgetbridge device registry. Cheap-ODM spec
> sheets are thin and MOQ/pricing is usually quote-only; unknowns are marked *undisclosed*.
> Every "medical-grade / ECG / blood-pressure / glucose" claim is flagged where it is
> marketing rather than a cleared or validated capability.

---

## 1. The headline finding: it's one shared reference design

Almost every sub-$70 "screenless ECG band" is the **same Shenzhen reference platform**,
resold under many names:

> **Jieli JL7013A/JL7073A8** BLE SoC (main control) · **Goodix GH3228T** PPG + single-lead
> **ECG** AFE ("500 Hz") · **PD2325** photodiode · **Minghao DA267** accelerometer.

Goodway's **E900**, Shenzhen **Staranb**'s ECG band, the **Valdus Vitro / "G band"**
rebadges, and — on the silicon evidence — the **Rogbid Loop** (GH3228, 500 Hz) are all this
platform. So a white-label buyer isn't choosing a product; they're **choosing which ODM to
brand the reference design through**, and the differentiator is **MOQ, price, and whether
you get an SDK/API or only a rebranded app.**

---

## 2. Grading rubric: the PPG/ECG chipset, not the metric list

Every band advertises "HR · SpO₂ · HRV · sleep." What separates R10-class from genuinely
good is the **analog front-end (AFE)**:

| AFE / sensor | Vendor | Class | What it buys | Seen in |
|---|---|---|---|---|
| **Vcare VC30F / VC31B** | Vcare | Budget single-channel PPG | HR, basic SpO₂, *algorithmic* HRV | **Colmi R10 (baseline)**, Staranb STH59, most cheap bands |
| **Goodix GH3026** | Goodix | Multichannel PPG (2 AFE, ≤16 ch) | better motion rejection, cleaner SpO₂/HRV | iSmarch, XBAND (benchmark) |
| **Goodix GH3220 / GH3228 / GH3228T** | Goodix | **PPG + single-lead ECG** (500 Hz) | real ECG AFE + PWTT (basis of PPG "BP") | Goodway E900, Staranb ECG, Rogbid Loop, J-Style V8/J1790 |
| **TI AFE4900/4950 · ADI/Maxim MAX86176** | TI / ADI | Premium PPG+ECG | multi-LED/PD, synced ECG | premium/clinical, J-Style 2208/2318 (Maxim PPG) |
| **Proprietary multi-PD arrays** | Whoop/Amazfit/Fitbit/Hume | Premium | 4–5 photodiodes, multi-wavelength | consumer benchmarks only |

**Bar-setting:** the Colmi R10's **VC30F is the floor** (single-channel). A band is
*meaningfully better sensors than the R10* if it's on **GH3026** (multichannel PPG) or
**GH322x/GH3228T** (adds real single-lead ECG), or a **≥4-PD array**. Caveats that hold
across the whole category: GH3228-class "ECG" through one side electrode is **rhythm-strip
grade, not diagnostic**; "blood pressure" and "glucose" are **uncalibrated PPG/PWTT
estimates**; **none of these ODMs is FDA-cleared** (openFDA 510(k) returns no matches for
J-Style/Youhong/Yawell; ISO 13485 is a quality-system cert, not a device clearance).

**Baseline — Colmi R10:** Vcare **VC30F** PPG · **STK8321** accel · Realtek **RTL8762E** BLE
SoC → HR, SpO₂, algorithmic HRV/stress, sleep. No real ECG/BP; R10 skin-temp is doubtful
(protocol reports temp only for R05/R09). QRing app, Oudmon/**Jxr35** 16-byte protocol —
the one **PulseLoop already speaks**.

---

## 3. White-label ODM comparison (R10-class or better)

⭐ = standout for its column. "Real ECG?" = physical electrode + a genuine ECG AFE.
"Protocol" = does it fit PulseLoop's existing Jxr35/Yawell stack, or need new work?

| Supplier | Product | Sensors / AFE | Real ECG? | MOQ | ~Price | White-label: logo · app · **SDK** | App / BLE protocol | vs R10 |
|---|---|---|---|---|---|---|---|---|
| **Shenzhen Yawell Intelligent** (Colmi's own ODM) | Y25 / Y91 / S8 OEM bands; H59 | VC30-class PPG; HR/SpO₂/HRV/sleep; RTL8762 | No | OEM | wholesale | logo ✅ · app ✅ · SDK via Yawell | **QRing/QWatch Pro = Jxr35 → PulseLoop already speaks it** ⭐ | ~R10-equal |
| **Shenzhen Staranb** | STH59 no-screen band | RTL8762ESF; VC30-class PPG HR/SpO₂/BP*(flag)*; no ECG | No | **1 pc (sample)** ⭐ | **$9.80–10.85** ⭐ | logo ✅ · app ✅ · **SDK+API ✅** | app "**Qwatch Pro**" (same Jxr35 family → likely already speaks) ⭐ | ~R10-equal |
| **Shenzhen Staranb** | AI-Health ECG band | **Goodix GH3228T** PPG+ECG; HRV, body temp | **Yes** (rhythm) | 1 pc (sample) | ~$15–25 est. | logo ✅ · app ✅ · **SDK+API ✅** ⭐ | own SDK (new integration) | **Above R10** (ECG) |
| **J-Style / Jointcorp** (Youhong; ISO 13485, CE/FCC) | JCVital **V8** / **J1790** ECG band; 2208A | **GH322x** PPG+single-lead ECG (V8/J1790); 2208A basic PPG+temp | **Yes** (V8/J1790) | **1,000** | quote | logo ✅ · pkg ✅ · **full iOS/Android SDK+API ✅** (best-documented) ⭐ | own SDK/app + cloud | **Above R10** (ECG) ⭐ overall |
| **Goodway Technologies** | E900 (= Valdus Vitro / "G band") | JL7013A; **GH3228T** + PD2325 PD; DA267 accel; HR/SpO₂/HRV/ECG/temp | **Yes** (rhythm) | undisclosed | undisclosed | logo ✅ · pkg ✅ · app ✅ · **SDK not advertised — flag** | "G band" app (protocol undisclosed) | **Above R10** (ECG) |
| **iSmarch** (Shenzhen) | screenless / hybrid variants | **GH3026-class** multichannel 500 Hz PPG + **ECG** + **EDA/GSR** + accel | **Yes** (option) | **2,000–3,000** | EXW quote | logo ✅ · **hardware + protocol/SDK docs ✅** (buyer builds own app) | own protocol/SDK | **Above R10** ⭐ best sensors (multichannel PPG + EDA) |
| **Rogbid** (Shenzhen Ruigebaike) | Loop / Loop Air | Loop: **GH3228** PPG+ECG; Loop Air: JL7073A8 + PPG/ECG/**NTC temp** + **GPS** | **Yes** (Loop); Loop Air soft | white-label per you† | retail $60–70 | white-label per you† · **no *public* OEM/SDK program — direct contact only** | Rogbid app (protocol undisclosed) | **Above R10** (Loop ECG; Loop Air GPS+temp) |

† You noted Rogbid offers white-labelling. Publicly they present as a vertically-integrated
D2C brand (Shenzhen Ruigebaike) with **no listed OEM/ODM or SDK program**, so terms would be
direct-contact-only. Note their Loop is the **same GH3228 reference design** you can also
brand through **Goodway or Staranb** — with a published SDK — if Rogbid won't hand over one.

**Consumer benchmarks (NOT white-label — for sensor comparison only):** Whoop 5.0/MG
(multi-wavelength 4-PD PPG + ECG, subscription) · Amazfit Helio Strap (BioTracker 6.0, 5-PD,
no sub, ~$99) · Fitbit Air (Google AFE, AFib, $99) · Hume Band 2.0 (5-LED/4-PD) · **XBAND /
Codex** (Goodix **GH3026** + nRF52840 in a strap — proves the good AFE exists in this form,
but no rebrand program, ~$279).

---

## 4. What this means for PulseLoop

There's a real tradeoff between **protocol fit** and **sensor quality**:

- **Cheapest path, sensors = R10, protocol already done:** **Yawell OEM bands** and
  **Staranb STH59**. Both run the **QRing/QWatch Pro (Jxr35) protocol PulseLoop already
  implements** (Staranb's app is literally "Qwatch Pro"; the H59 already in `WearableModel.kt`
  as "H59 Ring" is really a screenless *band* whose Gadgetbridge coordinator extends
  `AbstractYawellRingCoordinator`). Staranb even white-labels at **1-pc MOQ + SDK for ~$10**.
  → **Highest-confidence, lowest-effort expansion.** Confirm with a BLE sniff, then add the
  band to the catalog; likely little-to-no new protocol code.
- **Better sensors (real ECG / multichannel), needs new integration but you get an SDK:**
  **J-Style/Jointcorp** (GH322x ECG + full iOS/Android SDK+API, MOQ 1,000) is the strongest
  overall when you want cardiac sensors *and* your own app/data pipeline. **Staranb's
  GH3228T band** is the cheapest way to the same ECG AFE with an SDK. **iSmarch** goes
  furthest on sensors (GH3026 multichannel PPG + ECG + **EDA/GSR**) but at MOQ 2–3k.
  → Each hands over an SDK/API, so this is integration work, not blind reverse-engineering.
- **Rogbid** Loop/Loop Air are attractive hardware (real ECG; GPS+NTC temp) and you say
  they'll white-label — but with no public SDK, sourcing the **same GH3228 design via
  Goodway/Staranb** (which publish SDKs) is the safer route for an app-first product.

**Recommended shortlist for a white-label buyer:**
1. **Staranb STH59** — R10-class sensors, ~$10, 1-pc MOQ, SDK, **and the protocol PulseLoop
   already speaks.** Fastest to ship.
2. **J-Style / Jointcorp V8 / J1790** — real single-lead ECG + the best-documented
   iOS/Android SDK+API. Best if ECG and your own app/cloud matter.
3. **Staranb GH3228T ECG band** — cheapest genuine-ECG-AFE with SDK + low MOQ.
4. **iSmarch** — if you want the best sensors (multichannel GH3026 PPG + ECG + EDA) and can
   meet a 2–3k MOQ.
5. **Rogbid Loop / Loop Air** — if their white-label terms + an SDK check out; otherwise
   brand the underlying GH3228 design through Goodway/Staranb.

**Don't ship the marketing.** Across every ODM here, treat cuffless "blood pressure,"
non-invasive "glucose," and sub-$70 "ECG" as unvalidated (rhythm-grade at best). The only
faceless band with a genuine clinical clearance for any of it is **Aktiia/Hilo** (cuffless
BP, FDA 510(k)) — and it's consumer-only, not white-label, and still needs cuff calibration.

---

### Sources
Reference design / ODMs: goodwaytechs.com (E900) · staranb.en.made-in-china.com (STH59 +
ECG band) · jointcorp.com (JCVital V8, J1790, /sdk-api) · valdusvitro.com · ismarch.com ·
Rogbid: store.rogbid.com + tracxn (Shenzhen Ruigebaike) + gizmochina (Loop/Loop Air
chipsets). Protocol fit: gadgetbridge.org/…/yawell · Gadgetbridge PR #5039 (H59) · Play
Store `com.qcwireless.qcwatch` · fccid.io/2AOM3-Y91 · /2AOM3-S8. Chipsets: goodix.com
(GH3026/3220/3228T) · ti.com (AFE4900/4950) · analog.com (MAX86176) · espruino R10 teardown.
Regulatory: openFDA 510(k) (no matches for J-Style/Youhong/Yawell). Benchmarks: whoop.com +
TechInsights teardown · us.amazfit.com · blog.google (Fitbit Air) · humehealth.com ·
gearpatrol (XBAND/Codex) · Aktiia/Hilo FDA via biospace/medtechdive.
