#!/usr/bin/env python3
"""Non-destructive first-pass inspector for an unknown vendor .gps design file."""
from pathlib import Path
import argparse, binascii, json, plistlib, zipfile

def inspect(path: Path) -> dict:
    raw = path.read_bytes()
    report = {"path": str(path), "size_bytes": len(raw), "first_64_hex": raw[:64].hex()}
    if raw.startswith(b"bplist00"):
        report["detected"] = "Apple binary property list"
        try:
            obj = plistlib.loads(raw)
            report["plist_top_keys"] = list(obj.keys()) if isinstance(obj, dict) else []
            report["warning"] = "This may be File Provider metadata rather than the design payload."
        except Exception as exc:
            report["parse_error"] = str(exc)
    elif raw.startswith(b"PK\x03\x04"):
        report["detected"] = "ZIP container"
        with zipfile.ZipFile(path) as zf:
            report["members"] = zf.namelist()[:100]
    elif raw[:4] in (b"SQLite format 3\x00",):
        report["detected"] = "SQLite database"
    else:
        printable = sum(32 <= b < 127 or b in (9,10,13) for b in raw[:4096])
        report["detected"] = "mostly text" if printable / max(1,min(len(raw),4096)) > 0.8 else "unknown binary"
    return report

def main():
    ap=argparse.ArgumentParser(); ap.add_argument("file", type=Path); args=ap.parse_args()
    print(json.dumps(inspect(args.file), indent=2))
if __name__ == "__main__": main()
