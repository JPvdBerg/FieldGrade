"""Field extent geometry.

One job: the convex hull of a point set, and "is this point well inside it?".

Used to keep a generated machine track on ground that actually has a design
under it. The hull is the exact answer for a Delaunay TIN, which covers
precisely the convex hull of its points -- so "inside the hull" and "inside the
design" are the same region.

Note this cannot be replaced by an occupancy grid: survey points frequently lie
along contour lines, leaving most cells between contours empty even though the
triangulated surface covers them.
"""
import math


def convex_hull(points):
    """Convex hull of (e, n, ...) points, counter-clockwise. Andrew's monotone chain."""
    pts = sorted(set((p[0], p[1]) for p in points))
    if len(pts) < 3:
        return pts

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    lower = []
    for p in pts:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)

    upper = []
    for p in reversed(pts):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)

    return lower[:-1] + upper[:-1]


def inside_hull_by(east, north, hull, inset_m):
    """True when the point is inside the hull by at least [inset_m].

    For a counter-clockwise convex polygon every edge cross-product is positive
    inside; dividing by the edge length turns it into a perpendicular distance,
    so one pass gives both the containment test and the clearance.
    """
    n = len(hull)
    if n < 3:
        return False
    for i in range(n):
        ax, ay = hull[i]
        bx, by = hull[(i + 1) % n]
        ex, ey = bx - ax, by - ay
        length = math.hypot(ex, ey)
        if length < 1e-9:
            continue
        if (ex * (north - ay) - ey * (east - ax)) / length < inset_m:
            return False
    return True
