"""The synthetic existing-ground model.

One job: given a design surface, describe what the ground looked like *before*
anyone graded it.

This exists because the pair of surfaces is what the whole job is about:

    cut/fill  =  design elevation  -  existing ground elevation

A real project gets `existing` from a survey pass and `design` back from a
land-forming service. We have a real design surface and no matching survey, so
this generates a plausible one: the design's own least-squares plane plus smooth
undulation. That is deliberately the shape real fields have -- broadly planar
with metre-scale waviness -- and it means there is genuine, non-uniform work for
the guidance to do.

It is SYNTHETIC and must be labelled as such wherever it is used. Swap it for a
real survey export and nothing downstream changes.

Deterministic by construction: no RNG, so regenerating gives byte-identical
output and any regression reproduces exactly.
"""
import math


def fit_plane(points):
    """Least-squares z = a + b*east + c*north, by normal equations. Pure python."""
    n = len(points)
    if n < 3:
        raise ValueError("need >= 3 points to fit a plane")

    se = sn = sz = see = snn = sen = sez = snz = 0.0
    for east, north, z in points:
        se += east; sn += north; sz += z
        see += east * east; snn += north * north; sen += east * north
        sez += east * z; snz += north * z

    m = [[float(n), se, sn], [se, see, sen], [sn, sen, snn]]
    rhs = [sz, sez, snz]

    def det3(x):
        return (x[0][0] * (x[1][1] * x[2][2] - x[1][2] * x[2][1])
                - x[0][1] * (x[1][0] * x[2][2] - x[1][2] * x[2][0])
                + x[0][2] * (x[1][0] * x[2][1] - x[1][1] * x[2][0]))

    d = det3(m)
    if abs(d) < 1e-9:
        raise ValueError("degenerate point set")

    out = []
    for col in range(3):
        mc = [row[:] for row in m]
        for r in range(3):
            mc[r][col] = rhs[r]
        out.append(det3(mc) / d)
    return out  # a, b, c


def undulation(east, north):
    """Normalised ground waviness in roughly [-1, 1].

    Three incommensurate wavelengths (37 m, 51 m, 23 m) so the pattern never
    repeats over a field and no swath sees the same profile twice -- a periodic
    surface would let the controller look artificially good by driving the same
    correction every pass.
    """
    return (math.sin(east / 37.0) * 0.45
            + math.cos(north / 51.0) * 0.35
            + math.sin((east + north) / 23.0) * 0.20)


def ground_elevation(east, north, plane, amplitude_m):
    """Existing ground at a point: the design plane plus undulation."""
    a, b, c = plane
    return a + b * east + c * north + undulation(east, north) * amplitude_m


def ground_surface(design_points, amplitude_m):
    """Existing ground sampled at the design's own points.

    Reusing the design's point positions (not a fresh grid) means the two
    surfaces share a hull and triangulate identically, so cut/fill is defined
    exactly where the design is -- no ragged edge where one surface exists and
    the other does not.
    """
    plane = fit_plane(design_points)
    return plane, [
        (east, north, ground_elevation(east, north, plane, amplitude_m))
        for east, north, _ in design_points
    ]
