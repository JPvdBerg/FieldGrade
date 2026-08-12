# FieldGrade — handover

Everything needed to pick this up cold. Read this before `PROJECT_PLAN.md` or
`PHASES_PLAN.md`, both of which predate the work described here and are stale in
places (see [What the old plans got wrong](#what-the-old-plans-got-wrong)).

---

## 1. Get it running in 10 minutes

**You need:** JDK 17 (not 21, not 25 — Gradle/AGP will not accept them), Python
3.11+, and the Android SDK with **build-tools 36.0.0** and **platform 35**.

`android/local.properties` is **not** in the repo (it is machine-specific and
gitignored). Create it:

```properties
sdk.dir=C:/Program Files (x86)/Android/android-sdk
```

Then:

```bash
# The operator UI, on real surveyed data. No emulator, no hardware.
cd desktop && ./gradlew run

# The full closed loop, headless, with a printed trace.
cd android && ./gradlew testDebugUnitTest --tests "*GradingSimulationTest"

# Everything.
cd android && ./gradlew testDebugUnitTest      # 240 tests
cd android && ./gradlew assembleDebug          # app/build/outputs/apk/debug/
python -m pytest tests/ -v                     # 25 tests
```

If the desktop harness cannot find the sample data it falls back to a synthetic
demo and prints the commands to regenerate it. Nothing needs the network.

---

## 2. What is real and what is invented

**This matters more than anything else in this document.** The system has never
been near a machine. The simulation is convincing — it tracks a design surface to
28 mm — and that number says **nothing** about hardware.

| Input | Status | Where it came from |
|---|---|---|
| `nunosurf.xml`, `bratton_farm.xml` | **REAL** | landxml.org published samples |
| `nunosurf_design.xyz` | **REAL** geometry | re-encoded from `nunosurf.xml` |
| `amod_agl3080_consumer.txt` | **REAL** | a 2012 consumer GPS log |
| `nunosurf_existing_SYNTHETIC.xyz` | **GENERATED** | `tools/make_existing_ground.py` |
| `site_track_rtk_SYNTHETIC.nmea` | **GENERATED** | `tools/make_rtk_track.py` |

Three mechanisms keep this honest, because a README nobody reads is not a control:

1. **Filenames.** Generated files carry `_SYNTHETIC` in the name. A test asserts
   this, so regenerating them under a plain name fails the build.
2. **The app says so.** `SessionProvenance` classifies every input and the screen
   carries a standing amber banner naming which ones are invented. It fails
   *toward* synthetic: anything it cannot verify is treated as generated.
3. **`tools/sampledata/README.md`** documents provenance per file.

**The single most valuable thing you can do** is replace
`site_track_rtk_SYNTHETIC.nmea` with a real RTK recording — drive any vehicle
around a field with an Emlid logging GGA + GST + RMC. Everything downstream is
already built to consume it.

---

## 3. How the thing works

```
1. SURVEY    drive the field with RTK   -> XYZ points        SurveyRecorder
2. DESIGN    OptiSurface / AgForm3D     -> XYZ or LandXML    third-party, not us
3. LOAD      import the design          -> DesignSurfaceModel XyzPointReader,
                                                              LandXmlSurfaceReader,
                                                              DelaunayTriangulator,
                                                              TinDesignSurface
4. POSITION  RTK receiver               -> GnssSample         NmeaGnssDecoder
             local grid + lever arm     -> tool point         CoordinateTransform,
                                                              MachineGeometry
5. COMPARE   design - tool              -> cut/fill mm        GuidanceEngine
6. ACTUATE   mm -> frame -> ESP32 -> PWM -> valve -> cylinder ControlEngine,
                                                              CommandStreamer
```

Step 2 is bought in — that is a scope boundary worth defending. Everything else
is here.

Every module does one job and is unit-tested alone. The packages under
`android/app/src/main/java/com/fieldgrade/app/` carry **no Android dependencies**
except `MainActivity.kt`, which is why the desktop harness renders the real
operator screen unchanged.

---

## 4. The safety spine — do not casually change this

The controller state machine exists **three times**: `firmware/src/main.cpp`,
`tools/controller_simulator.py`, and `sim/SimulatedController.kt`. That
duplication is deliberate. If the three ever disagree about CRC, sequence
numbers, the 250 ms timeout, e-stop, raise/lower mutual exclusion, direction
dead time, duty clamp or slew limit, **a test fails instead of a machine moving**.

Change one, change all three, and run both suites.

The tablet never drives hydraulics. It sends physical millimetres; all control
gain lives on the controller, so a tablet-side bug cannot amplify hydraulic
authority.

---

## 5. Known gaps, in the order they will bite you

1. **Attitude is not measured.** Single antenna on a 3.10 m mast, and blade
   elevation is *derived*, not sensed. **1 degree of pitch = 54 mm** of error —
   twice the working tolerance. Everything in the simulation assumes a perfectly
   level machine. A pitch/roll sensor is needed before AUTO is trusted on
   anything but flat ground. This is the biggest single risk in the project.
2. **The RTK track is generated.** See section 2.
3. **Worst-case cut/fill is 597 mm** against a 28.9 mm mean, at the headland
   turns where design elevation changes faster than a 90 mm/s blade can follow.
   Unclear whether that is real physics or a missing look-ahead. Worth a look.
4. **Triangulation is O(n²)** — 2.1 s for 7,048 points on a desktop. A real 1 MB
   design will be larger and a tablet slower. Needs a spatial acceleration
   structure if it becomes a problem.
5. **`MAX_DUTY`, slew rate and dead time are placeholders.** Real values come
   from Phase 5 commissioning on a rig.
6. **`.gps` is not implemented and should not be.** It is the proprietary Trimble
   AgGPS FieldLevel format. Ask the design house for an XYZ or LandXML export of
   the same field instead — both are open and both are implemented.

---

## 6. What the old plans got wrong

`PROJECT_PLAN.md` and `PHASES_PLAN.md` are worth reading for the safety fence
(§4) and the work-package breakdown, but two things in them are now known to be
wrong:

- **"The single blocker that gates half the work" is not a blocker.** §3 says
  everything waits on the real `sp6b.gps`. That file is a proprietary Trimble
  format, and §12 of the same document forbids reimplementing proprietary vendor
  formats — so the gate could never open by the plan's own rules. The industry
  exchanges these designs as XYZ text and LandXML. Both are implemented. Phases
  3, 4 and 6 are unblocked.
- **The phase numbering no longer matches reality.** Phase 3 was skipped as
  "gated"; it is now done by a different route.

The safety constraints in §4 are unchanged and still binding.

---

## 7. Where to start

Roughly in order of value:

1. Record a real RTK log and drop it in. Nothing else changes.
2. Add a pitch/roll sensor to the model and see what it does to the numbers.
3. Chase down the 597 mm worst case at the headlands.
4. Get the firmware onto a bench ESP32 with LEDs on the outputs and demonstrate
   the fail-safe behaviour on real hardware — that is the M1 milestone and the
   true safety sign-off.

Do **not** connect this to hydraulics before item 4 passes and an independent
reviewer has signed off. The controller is neutral-by-default and well tested in
software, and that is not the same thing as being safe on a machine.
