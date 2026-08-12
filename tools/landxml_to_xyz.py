#!/usr/bin/env python3
"""Convert a LandXML TIN surface to the XYZ text interchange format.

One job: LandXML  ->  `Point,Easting,Northing,Elevation,Code` rows.

That output column order is the format land-forming design houses actually
exchange (OptiSurface -> Topcon AgForm3D). The geometry is passed through
untouched -- this re-encodes, it does not resample or smooth.

Gotcha this tool exists to absorb: LandXML `<P>` payload order is
**north east elevation**, while XYZ is **easting northing elevation**.
Getting that backwards mirrors the field about a diagonal and is the single
easiest way to grade a paddock into the wrong shape.

Usage:
    python tools/landxml_to_xyz.py in.xml out.xyz [--local]

    --local   shift so the surface min corner becomes (0, 0); elevations are
              left absolute. Useful for a site-local working grid.
"""
import argparse
import re
import sys

# <P id="1">north east elev</P>  -- whitespace-separated, id may carry other attrs.
_P = re.compile(r'<P\b[^>]*\bid="(\d+)"[^>]*>\s*([^<]+?)\s*</P>', re.I)
_UNIT = re.compile(r'<(?:Metric|Imperial)\b[^>]*\blinearUnit="([^"]+)"', re.I)

# LandXML linearUnit -> metres. Units are DECLARED in the file, never assumed:
# real samples include both `meter` and `USSurveyFoot`, and reading survey feet
# as metres inflates the field by 3.28x.
UNITS_TO_METRES = {
    "meter": 1.0, "metre": 1.0,
    "millimeter": 0.001, "millimetre": 0.001,
    "centimeter": 0.01, "centimetre": 0.01,
    "kilometer": 1000.0, "kilometre": 1000.0,
    "foot": 0.3048,
    "ussurveyfoot": 1200.0 / 3937.0,
    "inch": 0.0254,
    "yard": 0.9144,
    "mile": 1609.344,
}


def read_linear_unit(text):
    """Declared linear unit, or None when the file does not say."""
    m = _UNIT.search(text)
    return m.group(1) if m else None


def metres_per_unit(unit):
    if unit is None:
        return 1.0
    try:
        return UNITS_TO_METRES[unit.strip().lower()]
    except KeyError:
        raise SystemExit(
            f"error: unrecognised linearUnit {unit!r} — refusing to guess a scale "
            f"factor. Supported: {sorted(UNITS_TO_METRES)}")


def read_landxml_points(text, scale=1.0):
    """Return [(id, east, north, elev)] in metres from a LandXML surface."""
    out = []
    for pid, payload in _P.findall(text):
        parts = payload.split()
        if len(parts) < 3:
            continue
        try:
            north, east, elev = float(parts[0]), float(parts[1]), float(parts[2])
        except ValueError:
            continue
        out.append((int(pid), east * scale, north * scale, elev * scale))
    return out


def write_xyz(points, stream, local=False):
    """Write `Point,Easting,Northing,Elevation,Code` rows."""
    if local and points:
        min_e = min(p[1] for p in points)
        min_n = min(p[2] for p in points)
    else:
        min_e = min_n = 0.0

    stream.write("Point,Easting,Northing,Elevation,Code\n")
    for pid, east, north, elev in points:
        stream.write(f"{pid},{east - min_e:.4f},{north - min_n:.4f},{elev:.4f},DESIGN\n")
    return len(points)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("infile")
    ap.add_argument("outfile")
    ap.add_argument("--local", action="store_true",
                    help="shift min corner to (0,0)")
    args = ap.parse_args(argv)

    with open(args.infile, encoding="utf-8", errors="replace") as fh:
        text = fh.read()

    unit = read_linear_unit(text)
    scale = metres_per_unit(unit)
    points = read_landxml_points(text, scale)
    if not points:
        print(f"error: no <P> surface points found in {args.infile}", file=sys.stderr)
        return 1
    print(f"source linearUnit: {unit or 'unspecified'} "
          f"(x{scale:.9f} -> metres)")

    with open(args.outfile, "w", encoding="utf-8", newline="") as fh:
        n = write_xyz(points, fh, local=args.local)

    es = [p[1] for p in points]
    ns = [p[2] for p in points]
    zs = [p[3] for p in points]
    print(f"wrote {n} points -> {args.outfile}")
    print(f"  easting  {min(es):.2f} .. {max(es):.2f}  ({max(es) - min(es):.1f} m)")
    print(f"  northing {min(ns):.2f} .. {max(ns):.2f}  ({max(ns) - min(ns):.1f} m)")
    print(f"  elev     {min(zs):.2f} .. {max(zs):.2f}  (relief {max(zs) - min(zs):.2f} m)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
