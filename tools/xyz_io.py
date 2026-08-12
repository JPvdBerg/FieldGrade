"""Read and write the XYZ text interchange.

One job: `Point,Easting,Northing,Elevation,Code` rows <-> [(e, n, z)].

Shared by the data tools so the format lives in exactly one place; it is the
same layout the Kotlin XyzPointReader consumes, which is what makes a
round trip through the app meaningful.
"""

HEADER = "Point,Easting,Northing,Elevation,Code"


def read_xyz(path):
    """Read XYZ rows -> [(east, north, elev)]. Header and junk rows are skipped."""
    points = []
    with open(path, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split(",")
            if len(parts) < 4:
                continue
            try:
                points.append((float(parts[1]), float(parts[2]), float(parts[3])))
            except ValueError:
                continue  # header row
    return points


def write_xyz(path, points, code="DESIGN"):
    """Write [(east, north, elev)] as XYZ rows. Returns the count written."""
    with open(path, "w", encoding="utf-8", newline="") as fh:
        fh.write(HEADER + "\n")
        for i, (east, north, elev) in enumerate(points, start=1):
            fh.write(f"{i},{east:.4f},{north:.4f},{elev:.4f},{code}\n")
    return len(points)


def describe(points):
    """Human-readable extent summary, for tool output."""
    if not points:
        return "empty"
    es = [p[0] for p in points]
    ns = [p[1] for p in points]
    zs = [p[2] for p in points]
    return (f"{len(points)} points, "
            f"{max(es) - min(es):.1f} x {max(ns) - min(ns):.1f} m, "
            f"elev {min(zs):.2f}..{max(zs):.2f} (relief {max(zs) - min(zs):.2f} m)")
