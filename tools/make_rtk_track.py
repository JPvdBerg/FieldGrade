#!/usr/bin/env python3
"""Generate a SYNTHETIC RTK NMEA track for a machine working a field.

One job: field geometry -> a stream of GGA / GST / RMC sentences.

This exists because no public RTK-fixed *agricultural* NMEA log could be found.
Everything it emits is clearly synthetic and must be replaced by a real Emlid
recording before any hardware claim is made. Its purpose is to exercise the
tablet's decode -> transform -> geometry -> guidance chain offline.

What it models
--------------
* A serpentine ("boustrophedon") field pass -- up one run, shift over, back down.
  That is how a field is actually worked, and it means the track crosses the
  design surface at many headings, which exercises the heading/lever-arm maths.
* Ground that is NOT the design surface. The ground is a least-squares plane
  through the real design points plus smooth undulation, so there is genuine
  cut/fill work for the guidance to compute.
* The GNSS antenna sits on a mast above the blade, so reported height is
  ground + mast. The tablet subtracts the lever arm to recover blade elevation.
* A realistic geoid separation, so orthometric vs ellipsoidal height is a real
  offset the operator must bench out -- exactly as on a real site.

Usage:
    python tools/make_rtk_track.py design.xyz out.nmea [options]
"""
import argparse
import math
import sys

from field_hull import convex_hull, inside_hull_by
from ground_model import fit_plane, ground_elevation
from xyz_io import read_xyz

# Default site: Vaalharts irrigation scheme, Northern Cape -- real land-levelling country.
DEFAULT_LAT = -27.9500
DEFAULT_LON = 24.8300
# EGM96 geoid separation thereabouts. Ellipsoidal = orthometric + this.
DEFAULT_GEOID_M = 28.0


# ---------------------------------------------------------------- geodesy
def metres_per_degree(origin_lat_deg):
    """Metres per degree of lat/lon at a latitude.

    Byte-for-byte the same WGS84 series used by the Kotlin CoordinateTransform,
    so a track generated here round-trips exactly through the tablet's transform.
    """
    phi = math.radians(origin_lat_deg)
    m_lat = (111_132.92 - 559.82 * math.cos(2 * phi)
             + 1.175 * math.cos(4 * phi) - 0.0023 * math.cos(6 * phi))
    m_lon = (111_412.84 * math.cos(phi) - 93.5 * math.cos(3 * phi)
             + 0.118 * math.cos(5 * phi))
    return m_lat, m_lon


def to_geodetic(east_m, north_m, origin_lat, origin_lon):
    m_lat, m_lon = metres_per_degree(origin_lat)
    return origin_lat + north_m / m_lat, origin_lon + east_m / m_lon


# ---------------------------------------------------------------- path
def _walk(a, b, step_m):
    """Yield points from a to b inclusive, at roughly [step_m] spacing."""
    dx, dy = b[0] - a[0], b[1] - a[1]
    dist = math.hypot(dx, dy)
    if dist < 1e-9:
        yield b
        return
    steps = max(1, int(dist / step_m))
    for i in range(1, steps + 1):
        f = i / steps
        yield a[0] + dx * f, a[1] + dy * f


def hull_runs(hull, inset_m, min_e, max_e, min_n, max_n, swath_m, step_m):
    """For each swath, the north extent that lies inside the field."""
    cols = []
    east = min_e
    while east <= max_e:
        lo = hi = None
        north = min_n
        while north <= max_n:
            if inside_hull_by(east, north, hull, inset_m):
                if lo is None:
                    lo = north
                hi = north
            north += step_m
        # Ignore slivers at the very edge of the field.
        if lo is not None and hi is not None and (hi - lo) > 10 * step_m:
            cols.append((east, lo, hi))
        east += swath_m
    return cols


def serpentine(cols, step_m):
    """Yield a continuous back-and-forth pass over hull-clipped runs.

    Continuity matters: an earlier version simply dropped epochs that fell
    outside the field, which teleported the machine between runs. A jump of tens
    of metres is not a manoeuvre any machine can make, and it produced huge
    apparent cut/fill errors that were an artefact of the generator rather than
    anything the control loop did. The headland move between runs is now driven,
    like it is in the paddock.
    """
    prev_end = None
    upward = True
    for east, lo, hi in cols:
        start, end = (lo, hi) if upward else (hi, lo)
        if prev_end is not None:
            # Drive the headland across to the start of the next run.
            yield from _walk(prev_end, (east, start), step_m)
        else:
            yield east, start
        yield from _walk((east, start), (east, end), step_m)
        prev_end = (east, end)
        upward = not upward


# ---------------------------------------------------------------- NMEA
def checksum(body):
    c = 0
    for ch in body:
        c ^= ord(ch)
    return c & 0xFF


def sentence(body):
    return f"${body}*{checksum(body):02X}"


def ddm(value, is_lat):
    """Decimal degrees -> (ddmm.mmmmm string, hemisphere)."""
    hemi = ("N" if value >= 0 else "S") if is_lat else ("E" if value >= 0 else "W")
    v = abs(value)
    deg = int(v)
    minutes = (v - deg) * 60.0
    width = 2 if is_lat else 3
    return f"{deg:0{width}d}{minutes:08.5f}", hemi


def hms(seconds):
    h = int(seconds // 3600) % 24
    m = int(seconds // 60) % 60
    s = seconds % 60.0
    return f"{h:02d}{m:02d}{s:05.2f}"


def emit(fh, lat, lon, ellipsoid_h, geoid_m, t_s, speed_mps, heading_deg, date="120826"):
    """Write one epoch: GGA (quality 4) + GST + RMC."""
    lat_s, lat_h = ddm(lat, True)
    lon_s, lon_h = ddm(lon, False)
    tm = hms(t_s)
    msl = ellipsoid_h - geoid_m

    fh.write(sentence(
        f"GNGGA,{tm},{lat_s},{lat_h},{lon_s},{lon_h},4,18,0.7,"
        f"{msl:.3f},M,{geoid_m:.3f},M,1.0,0000") + "\r\n")
    # Pseudorange noise statistics -> the tablet's horizontal/vertical accuracy.
    fh.write(sentence(
        f"GNGST,{tm},0.012,0.015,0.011,32.1,0.011,0.013,0.021") + "\r\n")
    fh.write(sentence(
        f"GNRMC,{tm},A,{lat_s},{lat_h},{lon_s},{lon_h},"
        f"{speed_mps / 0.514444:.2f},{heading_deg:.1f},{date},,,R") + "\r\n")


# ---------------------------------------------------------------- main
def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("design_xyz")
    ap.add_argument("outfile")
    ap.add_argument("--lat", type=float, default=DEFAULT_LAT)
    ap.add_argument("--lon", type=float, default=DEFAULT_LON)
    ap.add_argument("--geoid", type=float, default=DEFAULT_GEOID_M,
                    help="geoid separation m (ellipsoidal = orthometric + this)")
    ap.add_argument("--mast", type=float, default=3.10,
                    help="antenna height above the blade, m")
    ap.add_argument("--swath", type=float, default=40.0, help="run spacing, m")
    ap.add_argument("--speed", type=float, default=1.8, help="ground speed, m/s")
    ap.add_argument("--rate", type=float, default=5.0, help="GNSS epochs per second")
    ap.add_argument("--undulation", type=float, default=0.15,
                    help="ground roughness amplitude, m")
    ap.add_argument("--inset", type=float, default=6.0,
                    help="stay at least this far inside the surveyed hull, m")
    ap.add_argument("--max-epochs", type=int, default=6000)
    args = ap.parse_args(argv)

    points = read_xyz(args.design_xyz)
    if len(points) < 3:
        print(f"error: no usable points in {args.design_xyz}", file=sys.stderr)
        return 1

    plane = fit_plane(points)
    min_e = min(p[0] for p in points); max_e = max(p[0] for p in points)
    min_n = min(p[1] for p in points); max_n = max(p[1] for p in points)

    step_m = args.speed / args.rate
    dt = 1.0 / args.rate

    hull = convex_hull(points)
    cols = hull_runs(hull, args.inset, min_e, max_e, min_n, max_n, args.swath, step_m)
    if not cols:
        print("error: no swath lies inside the field at this inset", file=sys.stderr)
        return 1

    written = 0
    skipped = 0
    t = 8 * 3600.0  # 08:00 local, a plausible start of shift
    prev = None
    with open(args.outfile, "w", encoding="ascii", newline="") as fh:
        for east, north in serpentine(cols, step_m):
            if written >= args.max_epochs:
                break
            if not inside_hull_by(east, north, hull, args.inset):
                skipped += 1        # headland move; still driven, not skipped
            heading = 0.0
            if prev is not None:
                de, dn = east - prev[0], north - prev[1]
                if de or dn:
                    heading = math.degrees(math.atan2(de, dn)) % 360.0
            ground = ground_elevation(east, north, plane, args.undulation)
            lat, lon = to_geodetic(east, north, args.lat, args.lon)
            emit(fh, lat, lon, ground + args.mast, args.geoid, t,
                 args.speed, heading)
            prev = (east, north)
            t += dt
            written += 1

    print(f"wrote {written} epochs ({written * 3} sentences) -> {args.outfile}")
    print(f"  hull          {len(hull)} vertices, {len(cols)} swaths inside "
          f"(inset {args.inset:.0f} m)")
    print(f"  headland      {skipped} epochs outside the design "
          f"({100.0 * skipped / max(1, written):.1f}% of the drive)")
    print(f"  site origin   {args.lat:.5f}, {args.lon:.5f}  (local 0,0)")
    print(f"  field extent  {max_e - min_e:.0f} x {max_n - min_n:.0f} m")
    print(f"  plane fit     z = {plane[0]:.3f} + {plane[1]:.6f}*E + {plane[2]:.6f}*N")
    print(f"  mast {args.mast:.2f} m, geoid sep {args.geoid:.2f} m, "
          f"{args.speed:.1f} m/s @ {args.rate:.0f} Hz")
    print(f"  duration      {written / args.rate / 60.0:.1f} min of driving")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
