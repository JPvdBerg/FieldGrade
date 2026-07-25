# FieldGrade — Phased Delivery Plan

How the project moves from today's skeleton to a **production-ready** system, in seven phases.
When Phase 7 completes, the system is field-usable and production-ready. Each phase has an
explicit **Definition of Done (DoD)** and a **validation gate** that must pass before the next phase starts.

This plan sits on top of [PROJECT_PLAN.md](PROJECT_PLAN.md) (the detailed build plan and code-level fixes)
and maps onto its work packages (WP0–WP7) and the design doc roadmap [DSN §10].

Governing rule (unchanged, all phases): **the tablet is supervisory and never drives hydraulics directly.
The ESP32 owns all real-time output, is neutral by default, and any doubt = neutral.**

---

## Validation tiers (what "validated" honestly means)

Because parts of this system can only be proven on real hardware, every phase's checks are split:

- **Tier A — software-validatable now** (this dev environment: Python 3.11). Automated, repeatable, run in a loop until green.
- **Tier B — toolchain/hardware acceptance** (programmer's machine + bench + rig + machine). Requires Android SDK, PlatformIO, an ESP32, a scope/LEDs, a test rig, and eventually the machine. These are **not** simulatable here and are carried forward as written, hardware-demonstrated results.

A green Tier-A result is progress, never final sign-off for anything behind the safety fence (PROJECT_PLAN §4).

---

## Phase overview

| Phase | Name | Maps to | Gated by | Production-ready? |
|---|---|---|---|---|
| 1 | Foundation & Fail-Safe Core | WP0 + WP1 | — | no |
| 2 | Tablet Transport & Link Supervision | WP2 | Phase 1 | no |
| 3 | Design-File Parser | WP3 | real `sp6b.gps` | no |
| 4 | GNSS, Geometry & Operator UI | WP4 | Phase 2/3 | no |
| 5 | Dry Hydraulic Commissioning | WP5 | Phase 1–2 + safety review | no |
| 6 | Field Manual → Closed-Loop AUTO | WP6 | Phase 4–5 | no |
| 7 | Production Hardening | WP7 | Phase 6 | **yes** |

---

## Phase 1 — Foundation & Fail-Safe Core

**Goal:** the safety spine is correct and proven, and all three sub-projects are source-correct (no known build defects). This is the most important phase — nothing else is allowed to move oil until this is solid.

**Scope:**
- Apply every build/logic defect fix from PROJECT_PLAN §5 (firmware, Android, Gradle).
- Resolve the double-gain ambiguity (§5.3): **all control gain on the controller; the tablet sends physical millimetres only.**
- Upgrade `tools/controller_simulator.py` into a faithful **reference controller** that enforces every mandatory rule: CRC-16/CCITT verification, monotonic sequence (anti-replay), 250 ms command timeout, e-stop hard-zero, mutually-exclusive raise/lower, direction dead-time, duty/slew limits, status frame, full fault-code set.
- Make firmware `main.cpp` implement the same rules (source-correct; compiled on the programmer's bench).
- Comprehensive automated protocol/safety test suite.

**Tier A — DoD (validated in this environment, looped until green):**
1. CRC-16/CCITT parity: Python implementation matches the Kotlin `crc16Ccitt` algorithm on known vectors.
2. A valid, fresh, in-sequence, CRC-correct command produces the expected bounded output.
3. Bad CRC → neutral + fault 3.
4. Replayed / stale sequence → rejected, neutral, fault 3.
5. Malformed frame → neutral + fault 3.
6. Command timeout (>250 ms silence) → neutral + fault 1.
7. E-stop asserted → neutral + fault 2, regardless of command.
8. `enable=false` → neutral.
9. Raise and lower never simultaneously non-zero (mutual exclusion) across a reversing sequence.
10. Direction reversal inserts dead-time (both channels zero) before energising the opposite channel.
11. Output respects `MAX_DUTY` clamp and per-loop slew limit.
12. Every command/status carries a fault code and the tablet-side model never suppresses a fault.
13. The upgraded test suite passes with zero failures.

**Tier B — acceptance carried to the programmer (NOT done in this env):**
- `./gradlew assembleDebug` produces an installable APK from a fresh clone.
- `pio run -e esp32dev` compiles the firmware.
- Bench HIL with LEDs/scope on the outputs demonstrates DoD items 3–10 on the **real ESP32** (this is the M1 milestone and the true safety sign-off).

**Validation loop for Phase 1 (run now):** apply fixes → run the test suite → read failures → fix → re-run, repeating until all Tier-A checks are green. Then push to the repo.

---

## Phase 2 — Tablet Transport & Link Supervision (WP2)

**Goal:** the tablet holds a real, supervised link to the controller and fails safe on link loss.

- USB-serial and/or Bluetooth SPP (transport choice = open question, PROJECT_PLAN §9 item 3).
- Framing, CRC verify on receive, sequence handling, status decode.
- Command cadence 20–50 Hz off a **real clock**, not a UI-thread timer.
- Honest link state in the UI: connected / stale / faulted. Timestamped, exportable log.

**Tier A:** transport/framing/CRC/sequence logic unit-tested against the Phase-1 reference controller over a local pipe/loopback; simulated link-drop drives a modelled "neutral within 250 ms."
**Tier B (gate):** 30-min stable session against the real ESP32 with no sequence gaps; unplug cable / kill app → controller neutral within 250 ms; full log export.

---

## Phase 3 — Design-File Parser (WP3) — *gated on the real `sp6b.gps`*

**Goal:** load the real design surface and query it.

- Run `tools/gps_inspector.py` on the real file; document findings; implement `GpsDesignParser` behind the existing interface; surface interpolation + boundary query. **Never guess the format.**
- Golden-file tests: known points in → expected elevations out.

**Tier A:** parser + interpolation unit tests on real sample files.
**Gate:** known design points interpolate within agreed tolerance; malformed files rejected, not guessed; parsed surface visually matches the vendor's own rendering.

---

## Phase 4 — GNSS, Geometry & Operator UI (WP4)

**Goal:** live cut/fill from real RTK, and the real operator screen.

- Receiver driver + message parser (chosen hardware); fix-quality model; transform into design coordinates.
- Antenna→tool offset + attitude model → tool elevation at the control point (single-antenna ⇒ pitch matters; recommend pitch/roll + cylinder-position sensors before AUTO is trusted).
- Replace the placeholder Compose screen with the real UI [DSN §6]: map at 75–80% of display; large signed value with visible units; AUTO / nudge / rebench / status; colour never the only indicator.

**Gate:** cut/fill sign & unit confirmed against a surveyed reference; AUTO refused on invalid/stale GNSS; map readable in direct sunlight at arm's length.

---

## Phase 5 — Dry Hydraulic Commissioning (WP5) — *gated on Phase 1–2 + safety review*

**Goal:** first real hydraulic output, on a protected rig, not the machine.

- Verify polarity, neutral, limits, e-stop and fault paths at reduced output.
- Establish real values for `MAX_DUTY`, slew rate, and dead time (replacing the placeholders).

**Gate:** every PROJECT_PLAN §4 / [DSN §11] test passes on the rig with **written** results, and an **independent reviewer signs off** before anything touches the machine.

---

## Phase 6 — Field Manual → Closed-Loop AUTO (WP6)

**Goal:** the system grades on a real machine.

- Manual control on the machine with logging + independent spot checks first.
- AUTO only afterwards, tuned progressively from low speed / conservative authority.

**Gate:** logged blade elevation tracks the design surface within agreed tolerance over a full pass; no unexpected faults; operator can take over instantly.

---

## Phase 7 — Production Hardening → **Production Ready** (WP7)

**Goal:** ship it.

- Signed release builds; configuration backup/restore; audit logs.
- Environmental testing; EMC review; machine-specific installation & operator documentation.
- Final independent safety review and machine-specific commissioning records.

**Gate (production-ready when all true):** signed build reproducible; every §4 safety behaviour demonstrated and documented on the target machine; EMC + independent safety sign-off on file; operator + install docs complete. **On completion, the system is production-ready.**

---

## Progress log

- **Phase 1:** ✅ **Tier A complete + Tier B compiles verified (2026-07-25)** — 22 Python tests green; Android APK builds; firmware compiles. Only bench HIL (real ESP32) remains. See Phase 1 execution log below.
- **Phase 2:** ✅ **Tier A complete (2026-07-25)** — tablet transport built and validated: 13 Kotlin unit tests + 3 Python integration tests green. Tier B (30-min live session vs real ESP32) carried to hardware. See Phase 2 execution log below.

---

## Phase 1 execution log

**Date:** 2026-07-25
**Result:** Tier A **PASS** — `python -m pytest tests/ -q` → **22 passed**.

### Work done
1. **Firmware defects fixed** (`firmware/src/main.cpp`, `firmware/include/config.h`, `firmware/platformio.ini`):
   - `'\n'` char-literal compile error corrected.
   - Operator-precedence bug (`doc["v"] | 0 != 1`) that rejected every valid frame — fixed with an explicit version check.
   - Added CRC-16/CCITT verification (canonical-payload rebuild matching the tablet encoder).
   - Added monotonic sequence check (anti-replay / anti-reorder).
   - Added the status frame (tablet is no longer blind).
   - E-stop now hard-zeros immediately (bypasses slew).
   - **Command timeout now hard-zeros immediately too** — see decision below.
   - Added direction dead-time on reversal; raise/lower structurally mutually exclusive.
   - Pinned `platform = espressif32@^6.9.0` so the `ledcSetup/ledcAttachPin` calls are valid.
2. **Android defects fixed** (`android/`):
   - Applied `kotlin.plugin.serialization` and `kotlin.plugin.compose` (Kotlin 2.0) in root + app Gradle.
   - Removed the obsolete `composeOptions{kotlinCompilerExtensionVersion}` block.
   - `ControlEngine` now outputs **physical millimetres** (gain moved entirely to the controller, §5.3).
   - `GpsDesignParser` no longer uses API-33 `readNBytes` (manual read loop; safe at minSdk 26).
3. **Reference controller** (`tools/controller_simulator.py`): rewritten as the deterministic software twin of the firmware, enforcing every safety rule; importable by tests and still runnable as a stdin/stdout simulator.
4. **Test suite** (`tests/test_protocol.py`): 22 tests covering CRC parity, valid-command output, bad CRC, stale/reordered sequence, malformed frame, wrong version, timeout, e-stop override, enable=false, boot-disabled, direction dead-time + mutual exclusion, slew limit, MAX_DUTY clamp, status/fault reporting, and the subprocess simulator.

### Decision recorded (design refinement)
**Command timeout hard-zeros the output, same as e-stop** (previously assumed to ramp down). Rationale: a slew ramp from full duty (~460 ms) cannot satisfy the acceptance test *"neutral within 250 ms of communication loss."* Comms loss is a fault of the same safety class as e-stop; motion must stop, not coast. The slew limiter now applies only to normal in-control transitions. A single bad frame in a healthy stream still ramps (next good frame re-enables within ~20 ms); persistent bad frames hit the 250 ms timeout and hard-zero. PROJECT_PLAN §5.1(d) updated to match.

### Tier B — build items now DONE locally (toolchains installed 2026-07-25)
- [x] `./gradlew assembleDebug` produces an installable APK from a fresh clone — **verified**, `app/build/outputs/apk/debug/app-debug.apk` (8.9 MB). Fixing this surfaced three more skeleton gaps: missing `gradle.properties` (no `android.useAndroidX`), a manifest theme referencing the absent View-based Material library, and an unset JVM target (Java 1.8 vs Kotlin 17). All fixed.
- [x] `pio run -e esp32dev` compiles the firmware — **verified**, `[SUCCESS]`, RAM 6.6%, Flash 21.6%. (Note: PlatformIO is incompatible with the Microsoft Store build of Python; installed python.org Python 3.12 for the firmware toolchain.)
- [ ] **Bench HIL (the true M1 safety sign-off):** on a real ESP32 with LEDs/scope on the outputs, demonstrate e-stop, comms-loss, bad-CRC, replay, and malformed-frame all force neutral, and that raise/lower never overlap. This is the milestone that authorises moving toward hydraulics. *(Still requires physical hardware.)*

### How to reproduce Tier A
```bash
python -m pip install -r tools/requirements.txt
python -m pytest tests/ -v
```

---

## Phase 2 execution log

**Date:** 2026-07-25
**Result:** Tier A **PASS** — 13 Kotlin unit tests + 3 Python integration tests green (25 Python tests total across Phases 1–2).

### Work done — tablet transport & link supervision (`android/.../transport/`, `.../logging/`)
1. **`ControllerProtocol`** extended: added `StatusFrame` and `decodeStatus()` with **CRC verify on receive** — a status frame with a bad/tampered CRC or wrong version is dropped, never read as valid state.
2. **`Link.kt`**: `Clock` (injectable time), `ByteLink` (the swappable USB-serial / Bluetooth-SPP transport behind an interface), `LineFramer` (reassembles newline frames across arbitrary chunk boundaries), `LinkState` enum.
3. **`LinkSupervisor`**: connected / stale / faulted state machine off the clock; surfaces controller faults and **never suppresses them** (safety constraint 8).
4. **`CommandStreamer`**: monotonic sequence numbers, fixed cadence (default 25 Hz) off a **real clock not a UI timer**, CRC-verified status decode, and stops sending on link loss (so the controller times out to neutral).
5. **`SessionLog`**: immutable, timestamped, exportable TX/RX/event log (acceptance test 7).
6. **Protocol refinement**: status frames are now CRC-protected too (symmetric with commands). Updated `docs/CONTROL_PROTOCOL.md`, firmware `emitStatus()`, and the Python simulator.

### Bugs caught by the validation loop (both real, both would have shipped)
- **`CommandStreamer` never sent its first command.** `now - lastSendMs` with `lastSendMs = Long.MIN_VALUE` overflows to a negative number, so the cadence check was never true — the tablet would have sat silent forever. Caught by `streamer_emits_monotonic_sequence_at_cadence` (`expected:<6> but was:<0>`). Fixed with a nullable initial timestamp.
- (Phase 1 carryover confirmed under real compile: the three Gradle/theme/JVM-target gaps above.)

### Cross-language parity proven
Kotlin `ControllerProtocol.encode(...)` is asserted **byte-for-byte** equal to the frame produced by `tools/controller_simulator.py` (command CRC 33262, status CRC 30287, and the CRC-16/CCITT vector `"123456789"` → 0x29B1). Tablet, firmware, and simulator therefore agree on the wire format.

### Tier B — still owed on hardware (NOT validated here)
- [ ] 30-minute stable live session tablet ↔ real ESP32 with no sequence gaps.
- [ ] Unplug cable / kill app → controller neutral within 250 ms (measured on the bench).
- [ ] Real USB-serial and/or Bluetooth-SPP `ByteLink` implementations (need the chosen tablet + receiver; transport choice is PROJECT_PLAN §9 item 3).

### How to reproduce
```bash
# Python (Phases 1–2 integration)
python -m pytest tests/ -v
# Kotlin transport unit tests (needs JDK 17)
cd android && ./gradlew testDebugUnitTest
```
