# FieldGrade Machine-Control Prototype

GNSS-guided land-levelling / ditch-grading system: an Android tablet (supervisory)
driving a dedicated ESP32 hydraulic controller (real-time, fail-safe neutral).

> **New here? Read [HANDOVER.md](HANDOVER.md) first** — setup, what is real
> versus generated, the safety spine, and the known gaps.

The whole chain runs offline today, on real surveyed design surfaces, with no
hardware attached — see **[Run it](#run-it)**.

## The pipeline

```
1. SURVEY    drive the field with RTK   -> XYZ points        SurveyRecorder
2. DESIGN    OptiSurface / AgForm3D     -> XYZ or LandXML    (third-party, not us)
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

Step 2 is bought in. Everything else is here.

## Design file formats

`.gps` is the **proprietary Trimble AgGPS FieldLevel** format — it is what design
tools mean by "export machine control file as AgGPS Field Level (\*.gps)".
PROJECT_PLAN section 12 rules out reimplementing a vendor's proprietary format, so
`GpsDesignParser` deliberately refuses rather than guesses.

It is not on the critical path. The same designs are exchanged as **XYZ text**
(`Point,Easting,Northing,Elevation,Code`) and **LandXML**, both open, both
implemented. Ask the design house for either export of the same field.

## Package contents

- `docs/` — system design document and the tablet-to-controller protocol.
- `android/` — the Kotlin application. Pure-logic packages (`design`, `geom`,
  `gnss`, `control`, `surface`, `transport`, `sim`, `survey`, `ui`) carry no
  Android dependencies, which is why the desktop harness can render the real
  operator screen unchanged.
- `desktop/` — dev harness: opens the real operator UI in a JVM window.
- `firmware/` — ESP32 PlatformIO firmware.
- `tools/` — data tooling and the reference controller model.
- `tools/sampledata/` — real design surfaces and NMEA logs, with provenance.

## Run it

**Prerequisites:** JDK 17, Python 3.11+, Android SDK (build-tools 36, platform 35).

```bash
# The operator screen on a real surveyed surface + replayed RTK track.
cd desktop && ./gradlew run

# The closed loop, headless, with a printed trace.
cd android && ./gradlew testDebugUnitTest --tests "*GradingSimulationTest"

# Everything.
cd android && ./gradlew testDebugUnitTest     # 240 Kotlin tests
cd android && ./gradlew assembleDebug         # app/build/outputs/apk/debug/
python -m pytest tests/ -v                    # 25 protocol/safety tests
cd firmware && pio run -e esp32dev            # needs PlatformIO
```

## What the simulation shows

A 13.5-minute serpentine pass over a real 4.4 ha surveyed field, closed through a
simulated ESP32 and a modelled blade:

```
                     AUTO off     AUTO on
mean |cut/fill|        136.3 mm     28.9 mm
within +/-25 mm         14.0%       84.1%
faults                     none
```

This proves the **modules agree with each other** about units, signs and timing.
It proves nothing about real hydraulics, real timing jitter, or the real ESP32 —
those remain Tier B, behind the bench-HIL gate in PHASES_PLAN.

## Safety position

The tablet does not directly drive hydraulics. The ESP32 controller owns the
watchdog, command timeout, output limits, neutral state, and emergency-stop input.
That state machine now exists three times — firmware, the Python reference model,
and `SimulatedController` — so a disagreement fails a test instead of moving a
machine.

Field use still requires proper hydraulic engineering, machine-specific
commissioning, and independent safety review.

## Known gaps

- **Attitude is not measured.** With a single antenna on a 3.10 m mast, 1 degree of
  pitch moves the derived blade elevation ~54 mm — twice the working tolerance.
  A pitch/roll sensor is needed before AUTO is trusted on uneven ground.
- **The RTK track is synthetic.** No public RTK-fixed agricultural NMEA log was
  found. Replace it with a real Emlid recording.
- `MAX_DUTY`, slew rate and dead time remain commissioning placeholders.
