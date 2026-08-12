# Vendored ESP32 host tools

Extracted from `ESP32_S3_Training_Kit.zip` (3.41 GiB, 65,126 entries, sourced from
Google Drive). Only the parts this project actually uses were taken — the rest of that
archive is a portable Arduino IDE 2 plus the full Arduino15 toolchain for every ESP32
variant, which FieldGrade does not need because `firmware/` builds with **PlatformIO**,
and PlatformIO fetches and pins its own toolchain.

The binaries here are **gitignored** (see the block at the bottom of `/.gitignore`).
They are third-party redistributables, not our source. This README is tracked so the
provenance survives; the payload does not.

## Contents

| Path | Size | Why we keep it |
|---|---|---|
| `drivers/CH341SER.EXE` | 850 KB | WCH CH340/CH341 USB-serial driver. Without it Windows will not enumerate a CH340-based ESP32 board as a COM port. |
| `drivers/CP210x_Universal_Windows_Driver.zip` | 291 KB | Silicon Labs CP210x USB-serial driver — the other bridge chip commonly fitted to ESP32 dev boards. |
| `esptool/esptool.exe` | 13.3 MB | Espressif flash/identify tool, v5.1.0. Standalone: runs without PlatformIO. |
| `esptool/LICENSE`, `esptool/README.md` | 20 KB | Upstream licence + notes, kept with the binary. |

Deliberately **not** taken: `espefuse.exe`, `espsecure.exe`, `esp_rfc2217_server.exe`
(44 MB combined, unused here — and `espefuse` burns one-time fuses, which is
irreversible and has no place in this workflow).

## Identifying the board

The firmware currently targets `board = esp32dev` (plain ESP32, Xtensa LX6) and pins
Arduino core 2.x. The training kit is named for the **ESP32-S3**, which is a different
chip. Confirm which silicon is actually on the board before changing anything:

```bash
tools/vendor/esp32/esptool/esptool.exe --port COM<N> chip_id
tools/vendor/esp32/esptool/esptool.exe --port COM<N> flash_id
```

If it reports an ESP32-S3, `firmware/platformio.ini` and the LEDC calls in
`firmware/src/main.cpp` both need changing — see the note in that file's header.

## Re-extracting

```bash
python - <<'PY'
import zipfile, shutil, pathlib
z = zipfile.ZipFile(r"path\to\ESP32_S3_Training_Kit.zip")
WANT = {
 "ESP32_S3_Training_Kit/Drivers/CH341SER.EXE": "drivers/CH341SER.EXE",
 "ESP32_S3_Training_Kit/Drivers/CP210x_Universal_Windows_Driver.zip": "drivers/CP210x_Universal_Windows_Driver.zip",
 "ESP32_S3_Training_Kit/Arduino15/packages/esp32/tools/esptool_py/5.1.0/esptool.exe": "esptool/esptool.exe",
 "ESP32_S3_Training_Kit/Arduino15/packages/esp32/tools/esptool_py/5.1.0/LICENSE": "esptool/LICENSE",
 "ESP32_S3_Training_Kit/Arduino15/packages/esp32/tools/esptool_py/5.1.0/README.md": "esptool/README.md",
}
dest = pathlib.Path(__file__).parent if "__file__" in dir() else pathlib.Path(".")
for m, rel in WANT.items():
    out = dest / rel; out.parent.mkdir(parents=True, exist_ok=True)
    with z.open(m) as s, open(out, "wb") as d: shutil.copyfileobj(s, d, 1<<20)
PY
```

Note: Windows Explorer cannot open the source archive — 275-character internal paths
against a 260-char `MAX_PATH` (`LongPathsEnabled=0` on this machine), plus its size.
The archive itself is sound: every CRC verifies. Use 7-Zip or Python, not Explorer.
