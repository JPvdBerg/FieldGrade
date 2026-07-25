# FieldGrade Machine-Control Prototype

GNSS-guided land-levelling / ditch-grading system: an Android tablet (supervisory) driving a dedicated ESP32 hydraulic controller (real-time, fail-safe neutral).

> **Start here:** read [PROJECT_PLAN.md](PROJECT_PLAN.md) — the full build plan, the exact code-level fixes needed for a green build, the work packages, and the safety fence. This README covers the code package itself.

This package contains the initial design documentation and a compilable-oriented code skeleton for an Android tablet connected to a dedicated ESP32 hydraulic controller.

## Important input-file note

The uploaded item named `sp6b.gps 2` is an Apple File Provider metadata object, not the underlying design file. Its metadata reports an original document size of about 1,034,240 bytes, while the uploaded object is only 3,212 bytes. The `.gps` parser in this package therefore uses an adapter interface and a diagnostic inspector until the actual `sp6b.gps` bytes are re-uploaded.

## Package contents

- `docs/FieldGrade_System_Design.docx` — complete system-design document.
- `docs/CONTROL_PROTOCOL.md` — tablet-to-controller command protocol.
- `android/` — Kotlin Android application skeleton.
- `firmware/` — ESP32 PlatformIO firmware skeleton.
- `tools/gps_inspector.py` — identifies a real design file and produces a safe hex/structure report.
- `tools/controller_simulator.py` — desktop simulator for protocol testing.
- `tests/test_protocol.py` — protocol framing tests.

## Safety position

The tablet does not directly drive hydraulics. The ESP32 controller owns the watchdog, command timeout, output limits, neutral state, and emergency-stop input. Field use still requires proper hydraulic engineering, machine-specific commissioning, and independent safety review.

## Next action

Re-upload the actual `sp6b.gps` file from local storage rather than an iCloud placeholder. Once available, run:

```bash
python tools/gps_inspector.py /path/to/sp6b.gps
```

Then implement the matching parser behind `GpsDesignParser`.
