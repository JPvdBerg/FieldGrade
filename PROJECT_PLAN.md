# FieldGrade — Project Build Plan

**Our own GNSS-guided land-levelling / ditch-grading system**
Android tablet (supervisory) + ESP32 controller (real-time hydraulic authority)

Version 1.0 of this plan — 25 July 2026
Owner: drunknine323@gmail.com
Status: pre-development. Nothing has run against hardware. Every number below sourced from a reference file is a **placeholder** until commissioned.

---

## 0. How to read this document

This is the master build plan. It is written so that you (the owner) and a hired programmer can both follow it without re-reading the original chat. It merges three source documents into one actionable plan and adds the exact code-level fixes needed to get a green build on day one.

Source references (all under `C:\Users\drunk\Downloads\`):

| Reference | What it gives us | How we use it |
|---|---|---|
| `FieldGrade_System_Design.docx` | Architecture, safety concept, data flow, UI spec, roadmap, acceptance tests | The "why" — the constitution. Sections below cite it as **[DSN §n]**. |
| `FieldGrade_Project_Package_v0.1/` (and `.zip`) | Working code skeleton: Android Kotlin, ESP32 firmware, protocol doc, Python simulator + inspector | The starting codebase. We fix and extend it; we do **not** restart from zero. |
| `DitchAssist_Gen2_v1.4_...arm64-v8a_release.apk` | A prior/related field build (133 MB) | UX and feature-parity **reference only**. We do not decompile or copy it; we look at how it *behaves* on the tablet. See §12. |

The design doc's own protocol spec lives in the package at
`FieldGrade_Project_Package_v0.1/ditchassist_project/docs/CONTROL_PROTOCOL.md`. Cited below as **[PROTO §n]**.

Golden rule that governs everything: **the tablet is supervisory and never drives hydraulics directly. The ESP32 owns all real-time output, is neutral by default, and any doubt = neutral.** [DSN §4, §7]

---

## 1. What we are building (one paragraph)

An Android app that loads a design surface (`.gps`), reads corrected RTK GNSS, computes the blade's cut/fill against the design, and shows the operator a large map + a large signed correction number. When the operator enables AUTO and all interlocks pass, the tablet streams a bounded correction request at 20–50 Hz to an ESP32 controller over USB-serial or Bluetooth SPP. The ESP32 validates every frame (CRC, sequence, timeout), enforces slew/duty/dead-time limits, honours a hardware e-stop, and drives raise/lower PWM to a proportional hydraulic valve — returning to neutral instantly on any fault. [DSN §1, §5]

---

## 2. Project directory layout (target)

We keep the skeleton's structure but promote it out of `ditchassist_project/` into a clean repo named `fieldgrade-control`. Proposed layout once WP0 is done:

```
C:\Users\drunk\FieldGrade\
├── PROJECT_PLAN.md              ← this file
├── fieldgrade-control\          ← the git repo (create in WP0)
│   ├── README.md
│   ├── docs\
│   │   ├── FieldGrade_System_Design.md   (convert from .docx)
│   │   └── CONTROL_PROTOCOL.md
│   ├── android\                 ← Kotlin/Compose app
│   │   ├── settings.gradle.kts
│   │   ├── build.gradle.kts
│   │   └── app\...
│   ├── firmware\                ← ESP32 PlatformIO
│   │   ├── platformio.ini
│   │   ├── include\config.h
│   │   └── src\main.cpp
│   ├── tools\
│   │   ├── gps_inspector.py
│   │   └── controller_simulator.py
│   └── tests\
│       └── test_protocol.py
└── reference\                   ← copies of the source refs, read-only
```

**First physical action (owner or programmer):** copy the package into the repo:
```bash
# from Git Bash
cp -r "/c/Users/drunk/Downloads/FieldGrade_Project_Package_v0.1/ditchassist_project" \
      "/c/Users/drunk/FieldGrade/fieldgrade-control"
cd "/c/Users/drunk/FieldGrade/fieldgrade-control" && git init
```
Commit the untouched skeleton **first** so every later fix is a reviewable diff.

---

## 3. The single blocker that gates half the work

**The real `sp6b.gps` design file has never been supplied.** What exists is a 3,212-byte Apple File Provider metadata stub; its own metadata says the real document is ≈1,034,240 bytes. [DSN §2]

Consequences:
- `GpsDesignParser` cannot be implemented for real.
- Everything downstream of a design surface — cut/fill, the map colouring, AUTO — cannot be *validated* for real.
- **Do not guess the format. Do not write a speculative parser.** The skeleton's `UnsupportedGpsDesignParser` throws on purpose.

Unblock sequence (once the real file is on the PC, copied from local storage — **not** an iCloud placeholder; confirm size ≈1 MB and that it opens offline):
```bash
python tools/gps_inspector.py /path/to/sp6b.gps
```
Then build the parser from what the report actually shows. Ask the owner for **two or three** design files (include one small simple field) so structure can be diffed, plus the vendor's own rendering (screenshot/printout) of at least one to validate against.

Until the file arrives, we work every package **not** gated by it: WP0, WP1, WP2 (§7). That is weeks of real work — the blocker does not stall the project.

---

## 4. Non-negotiable safety constraints (the fence)

From **[DSN §7]** and **[PROTO §"Mandatory controller rules"]**. These are not the programmer's to trade for convenience:

1. **Neutral by default.** No valid, fresh, CRC-correct command ⇒ zero hydraulic output.
2. **250 ms command timeout**, configurable only within a validated range.
3. **Independent physical e-stop** wired to the controller; it must **also cut valve power in hardware**. The firmware path is a second line of defence, not the only one.
4. Boot, brownout, malformed frame, CRC failure, stale sequence, watchdog reset ⇒ all force neutral.
5. **Raise and lower can never be active simultaneously**, with dead time when reversing direction.
6. Controller-side maximum duty, slew rate, ramp-down. `MAX_DUTY = 820` in `config.h` is a **commissioning placeholder, not a field value**.
7. AUTO is inhibited without sufficient GNSS fix quality, or when correction age/accuracy exceed limits.
8. The tablet never suppresses a safety fault reported by the controller.

Hydraulics stay physically disconnected until WP5. Sequence: **bench → protected rig → machine.** [DSN §10]

---

## 5. Defects in the skeleton — with exact fixes (this is WP1/WP0 detail)

These are real, committed, and confirmed by reading the files. Fix them before anything else. Each has the file, the problem, and the concrete change.

### 5.1 Firmware — does not compile, and would reject every command if it did

File: `firmware/src/main.cpp`, `firmware/include/config.h`

**(a) Compile error — raw newline in char literal.** `main.cpp:53`
```cpp
String line = Serial.readStringUntil('
');            // ← literal newline, will not compile
```
Fix:
```cpp
String line = Serial.readStringUntil('\n');
```

**(b) Logic bug — operator precedence rejects all valid frames.** `main.cpp:30`
```cpp
if (doc["v"] | 0 != 1) return false;   // parses as doc["v"] | (0 != 1)
```
`!=` binds tighter than `|`. With ArduinoJson's `|` default-value operator this is truthy for a valid `v:1` frame, so **every well-formed command is rejected**. Fix:
```cpp
int version = doc["v"] | 0;
if (version != 1) return false;
```

**(c) Missing mandatory validation.** The firmware never verifies `crc16`, never checks `seq` for staleness, and **never emits a status frame** — so the tablet is blind to state/output/estop/fault. All three are mandatory. [PROTO §"Mandatory controller rules" 1, §"Status frame"] Implement in WP1:
- CRC-16/CCITT over the canonical payload excluding `crc16`; mismatch ⇒ neutral + fault `3`.
- Monotonic `seq`; a `seq` ≤ last accepted ⇒ reject as stale (guard against replay/reorder) + fault `3`.
- Emit the status frame every loop (or ≥20 Hz): `{"v":1,"seq_ack":...,"state":...,"output":...,"supply_mv":...,"estop":...,"fault":...,"age_ms":...}`.

**(d) E-stop AND command-timeout must be a hard immediate zero, not a ramp.** Today `neutral()` only zeroes the *request*; the output then ramps down through the slew limiter (`MAX_SLEW_PER_LOOP = 18` per 10 ms ≈ 460 ms from full). **Resolved during Phase 1:** a ramp-down cannot meet the acceptance test "neutral within 250 ms of communication loss" from full duty, so **both e-stop and command-timeout hard-zero the output immediately** (bypassing the slew limiter). Comms loss is a fault of the same safety class as e-stop — the supervising link is gone, so motion must stop, not coast. The slew limiter applies only to normal in-control transitions. A single bad frame amid a healthy stream ramps down (the next good frame re-enables within ~20 ms; persistent bad frames hit the 250 ms timeout and hard-zero). [DSN §11]
```cpp
void hardNeutral() {          // e-stop / brownout path
  requestedOutput = 0;
  appliedOutput = 0;
  enabled = false;
  ledcWrite(0, 0);
  ledcWrite(1, 0);
}
```

**(e) No dead time on direction reversal.** Constraint §4.5. When `appliedOutput` crosses zero (raise→lower or lower→raise), force both channels to 0 and hold for a configurable `DIRECTION_DEADTIME_MS` before energising the opposite channel.

**(f) API-version mismatch risk.** `ledcSetup`/`ledcAttachPin` are ESP32 Arduino core **2.x**; on core **3.x** these are replaced by `ledcAttach(pin, freq, resBits)`. `platformio.ini` pins ArduinoJson 7 (`JsonDocument doc;` unsized is correct for v7). **Pin the platform explicitly** in `platformio.ini` and use one consistent LEDC API. Recommended: pin core 2.x to match the existing calls, or migrate both calls to core 3.x — decide once, document it.

### 5.2 Android — does not build

File: `android/app/build.gradle.kts`, `android/build.gradle.kts`, `GpsDesignParser.kt`

**(a) kotlinx-serialization plugin never applied.** `ControllerProtocol.kt` uses `@Serializable` + `kotlinx-serialization-json`, but the `org.jetbrains.kotlin.plugin.serialization` plugin is applied nowhere ⇒ "serializer has not been found." Fix in root `android/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.10" apply false   // see (b)
}
```
and apply both in `android/app/build.gradle.kts` `plugins { }`.

**(b) Compose compiler moved into the Kotlin plugin (Kotlin 2.0).** The app still declares `composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }`. In Kotlin 2.0 you instead apply `org.jetbrains.kotlin.plugin.compose`. Remove the `composeOptions` block and apply the compose plugin.

**(c) `InputStream.readNBytes` is API 33+, but `minSdk = 26`.** `GpsDesignParser.kt:15`. Options: raise `minSdk`, enable core-library desugaring, or read bytes manually. Recommended (no minSdk change):
```kotlin
val header = ByteArray(32)
var read = 0
while (read < 32) {
    val n = input.read(header, read, 32 - read)
    if (n < 0) break
    read += n
}
```

### 5.3 Protocol ambiguity — the gain is applied twice (resolve before any tuning)

- `ControlEngine.kt:15` multiplies cut/fill by `proportionalPerMm = 12.0` → bounded ±1000.
- `firmware/src/main.cpp:35` **and** `tools/controller_simulator.py:10` multiply `target_mm` by `12` **again** → duty.

But **[PROTO §Command frame]** says `target_mm` is a *signed vertical correction in millimetres*. So the tablet must **not** put `ControlEngine`'s scaled output into `target_mm`.

**Decision (adopt this):** **all control gain lives on the controller.** The tablet sends **physical millimetres only** in `target_mm`. Rationale: a tablet-side bug can then never amplify hydraulic authority. Then make the doc, firmware, simulator, and `ControlEngine` agree, and write a test that pins it (WP1). `ControlEngine`'s job becomes: deadband + nudge + clamp, output in **mm**, not in duty units. Any change to this authority split needs explicit owner sign-off (§11).

---

## 6. Software architecture (module by module)

From **[DSN §9]**. Each module is independently testable; the levelling maths must not know which receiver or transport is connected.

```
Design-file module   format detect · parser adapters · surface interpolation · boundary query
GNSS module          receiver transport · NMEA/RTCM parser · fix-quality model · coord transform
Geometry module      antenna→tool offset · machine attitude · tool elevation at control point
Control module       cut/fill · deadband · nudge · enable/interlock logic · bounded mm request
Transport module     connection lifecycle · framing · CRC · sequence · status decode
UI module            map · primary value · AUTO/nudge/rebench · alarms · diagnostics
Logging module       immutable timestamped samples · export
```

Two abstractions kept swappable from day one (so the maths is source-agnostic):

```
GnssSource              MachineController
├── DemoGnssSource      ├── DemoController
├── BluetoothNmeaSource ├── UsbSerialController
├── UsbNmeaSource       └── BluetoothSppController
└── AndroidLocationSource
```

Data-flow contract [DSN §5]: design load → GNSS in (timestamped, transformed) → tool-point model → cut/fill = **design elevation − measured tool elevation** (sign fixed in config, shown to operator) → supervisory bounded request (AUTO only, interlocks pass) → versioned/sequenced/CRC'd command at 20–50 Hz → ESP32 validates + limits + drives → status + log back.

---

## 7. Work packages (the actual plan)

Sequence matters. **WP0 → WP1 → WP2 can all start immediately; none are gated by the design file.** WP3+ wait on §3 and §9 inputs. Each package lists deliverables + acceptance. A green unit test is **never** acceptance for anything in §4 — safety behaviour needs a written hardware result.

### WP0 — Environment & green build *(not gated)*
Deliverables:
- Copy skeleton into `fieldgrade-control/`, `git init`, commit untouched, then apply §5.1(a–b), §5.2(a–c) build fixes.
- Convert `FieldGrade_System_Design.docx` → `docs/FieldGrade_System_Design.md` (keep the .docx too).
- Add `tools/requirements.txt` (pytest). Document exact build commands in `README.md`.

Accept when: a **fresh clone** builds all three with documented commands —
`./gradlew assembleDebug` produces an installable APK; `pio run -e esp32dev` succeeds; `pytest` runs — and the README build steps match reality.

### WP1 — Firmware correctness & safety envelope *(not gated)*
Deliverables: fix every §5.1 defect; implement CRC verify, monotonic sequence check, status frame, immediate-zero e-stop, direction dead time, fault-code reporting per [PROTO]. Extend `controller_simulator.py` to mirror the **same** rules so protocol work proceeds without hardware. Resolve §5.3 (gain on controller; tablet sends mm).

Accept when — measured on the bench, **no hydraulics**, LED/scope on outputs:
- output neutral within **250 ms** of comms loss;
- e-stop forces neutral regardless of tablet command;
- raise & lower never simultaneously active;
- corrupted CRC, replayed `seq`, and malformed frame each ⇒ neutral + correct fault code;
- restarting either device leaves system **disabled** until deliberate operator re-enable.

### WP2 — Tablet transport & link supervision *(not gated)*
Deliverables: implement USB-serial and/or Bluetooth SPP (choice = open question §9) — connection lifecycle, framing, **CRC verify on receive**, sequence handling, status decode. Drive commands at 20–50 Hz off a **real clock**, not a UI-thread timer. Surface link state honestly: connected / stale / faulted. Timestamped exportable log.

Accept when: tablet holds a stable session against the real ESP32 for **30 min** with no sequence gaps; unplugging cable or killing the app drives controller neutral within 250 ms; every command/status/fault is timestamped in an exportable log.

### WP3 — Design-file parser *(GATED on real `sp6b.gps`, §3)*
Deliverables: run `gps_inspector.py`, document findings, implement `GpsDesignParser` behind the existing interface — surface interpolation + boundary query. Golden-file tests: known points in → expected elevations out.

Accept when: known design points interpolate to expected elevation within agreed tolerance; parser **rejects** malformed files rather than guessing; parsed surface visually matches the vendor's own rendering.

### WP4 — GNSS, geometry & operator UI *(partially gated)*
Deliverables: receiver driver + message parser for chosen hardware; fix-quality model; transform into design coordinate system; antenna→tool offset + attitude model → tool elevation at control point. Replace the placeholder Compose screen (`MainActivity.kt`) with the real UI [DSN §6]: cut/fill map at 75–80% of display; large signed value with **visible units**; AUTO, nudge up/down, rebench, status. **Colour is never the only indicator.** Borrow the calibration + map-flow *ideas* (not code) from prior builds.

Accept when: cut/fill sign & unit confirmed against a surveyed reference; AUTO refused on invalid/stale GNSS quality; map readable in direct sunlight at arm's length from the seat.

### WP5 — Dry hydraulic commissioning *(GATED on WP1+WP2 + safety review)*
Deliverables: connect to a **protected test rig**, not the machine. Verify polarity, neutral, limits, e-stop, fault paths at reduced output. Establish real `MAX_DUTY`, slew rate, dead time. [DSN §10 Phase 4]

Accept when: every §4 / [DSN §11] test passes on the rig with **written** results, and an **independent reviewer signs off** before anything touches the machine.

### WP6 — Field manual mode, then closed-loop AUTO
Deliverables: manual control on the machine with logging + independent spot checks first. AUTO only afterwards, tuned progressively from low speed / conservative authority. [DSN §10 Phase 5–6]

Accept when: logged blade elevation tracks design surface within agreed tolerance over a full pass, no unexpected faults, operator can take over instantly.

### WP7 — Production hardening
Signed builds, config backup, audit logs, environmental testing, EMC review, machine-specific install docs. [DSN §10 Phase 7] Not the developer's to self-certify (§11).

---

## 8. Milestones & suggested first paid deliverable

| Milestone | Packages | Demonstrable result |
|---|---|---|
| M1 — "It builds & fails safe" | WP0 + WP1 | Bench ESP32, LED per output, **no hydraulics anywhere near it**: comms loss, e-stop, bad CRC, replay, malformed frame all → neutral. |
| M2 — "Tablet talks & supervises" | WP2 | 30-min stable link; kill-app → neutral in 250 ms; full log export. |
| M3 — "It knows the ground" | WP3 + WP4 | Load a real design file; live cut/fill against surveyed reference; sunlight-readable UI; AUTO refused on bad GNSS. |
| M4 — "It moves oil, safely" | WP5 | Protected rig: all §4 tests pass, signed off. |
| M5 — "It grades" | WP6 | Field pass tracks design within tolerance. |

**Recommended first paid milestone = M1 (WP0 + WP1)**, demonstrated on a bench ESP32 with an LED on each output and no hydraulics. It proves the safety spine before a single drop of oil is involved.

---

## 9. Information still required from the owner (chase these now)

Items 1–4 block soonest.

1. **The real `sp6b.gps`** (see §3) — ideally 2–3 files + a vendor rendering.
2. **GNSS receiver** brand/model/output protocol (NMEA? RTCM? proprietary?) and how RTK corrections arrive (NTRIP over the tablet's data connection? base radio? other?).
3. **Tablet** model + Android version + preferred physical connection (USB serial **or** Bluetooth SPP). Decides WP2 transport and whether an OTG cable + power plan are needed.
4. **Valve/driver electrical spec** — command type (PWM / current-controlled / on-off), current draw, frequency, neutral behaviour. Without this, `MAX_DUTY` stays a guess.
5. Machine geometry + antenna-to-tool offsets; how the antenna mounts relative to the cutting edge.
6. Available feedback sensors: blade position, pressure, inclination, feedback voltage — if any.
7. Preferred units, cut/fill **sign convention** (is positive "cut" or "fill"?), nudge increment.
8. Country of deployment + applicable machine-safety requirements.

Owner decisions from the original chat already folded in: any Android tablet, Emlid RTK over Bluetooth, Danfoss/ESP32-class controller, any proportional PWM raise/lower valve, single GNSS antenna, contour levelling, metres. **Note:** the single-antenna choice means blade elevation must be derived as `antenna height − calibrated offset − pitch correction`; on uneven ground, pitch is significant. Strongly recommend a pitch/roll sensor and a cylinder-position sensor before AUTO is trusted (WP4/WP6). AUTO stays guidance-only until then.

---

## 10. Testing strategy

- **Unit** (fast, CI): CRC vectors (Kotlin `crc16Ccitt` vs firmware vs Python must agree on known inputs); `ControlEngine` deadband/nudge/clamp; coordinate transforms; surface interpolation golden files. Expand `tests/test_protocol.py` beyond its single happy-path manual command to cover CRC, timeout, e-stop, stale-sequence, and every fault path — against the upgraded simulator.
- **Simulator-in-the-loop:** tablet ↔ `controller_simulator.py` exercising all interlocks with no hardware.
- **Bench HIL:** tablet ↔ real ESP32, LEDs/scope on outputs — the M1/M2 acceptance evidence.
- **Rig:** WP5, reduced output, protected.
- **Field:** WP6, manual-first with independent spot checks.

Every safety-relevant result is **written down**, not verbally assured. [§11]

---

## 11. How work is accepted (contract terms with the programmer)

- Each WP accepted against **its** criteria, demonstrated on hardware where the criteria say hardware.
- Safety behaviour → written test result, not a verbal assurance.
- Placeholder values that reach a machine must be **labelled in code** the way `MAX_DUTY` already is.
- Any change to the tablet/controller authority split, or moving control gain across the link (§5.3), needs **explicit owner sign-off** before implementation.

---

## 12. Using the DitchAssist APK reference (and what we will NOT do)

`DitchAssist_Gen2_v1.4_2026-07-17_arm64-v8a_release.apk` is a **behavioural reference**: install it on a test tablet and study how it presents cut/fill, how the map fills the screen, how AUTO/nudge/rebench feel, sunlight readability, and status clarity. Use those observations to inform **our own** UI (WP4).

Out of scope [DSN §"aim… without copying"; handoff §9]:
- No reverse-engineering, decompiling, or reimplementing any vendor's software, screen layouts, code, trade dress, or proprietary file formats.
- Reading a design file the owner **legitimately possesses** is in scope; cloning a competitor's product is **not**.
- No self-certification. This is a prototype; it becomes field-usable only after machine-specific commissioning, EMC review, and independent safety review.

---

## 13. First-call agenda (owner ↔ programmer)

1. Walk §5 together — confirm the build defects are real and estimable.
2. Settle the control-gain question §5.3 (recommendation: **all gain on the controller**, tablet sends mm). This shapes WP1 + WP2.
3. Answer as many of §9 items 2–4 as possible; agree who chases `sp6b.gps` and by when.
4. Agree the order: **WP0 → WP1 → WP2 start immediately**, not gated by the design file.
5. Agree "done" for the first paid milestone — recommended **M1 = WP0 + WP1** on a bench ESP32, LED per output, no hydraulics.

---

## 14. Immediate next actions (this week)

- [ ] Copy skeleton → `fieldgrade-control/`, `git init`, commit untouched (§2).
- [ ] Apply build fixes §5.1(a–b) + §5.2(a–c); get all three projects to green (WP0).
- [ ] Convert design `.docx` → `docs/*.md`; write real README build steps.
- [ ] Send owner the §9 questionnaire; specifically request the real `sp6b.gps` copied from local storage (size ≈1 MB, opens offline).
- [ ] Order/borrow a bench ESP32 dev board + LEDs for M1 evidence.
- [ ] Book the first call using §13 agenda.
```
