package com.fieldgrade.app.geom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {

    private val tf = CoordinateTransform(-26.20000, 28.04000)

    @Test fun origin_maps_to_zero() {
        val p = tf.toLocal(-26.20000, 28.04000)
        assertEquals(0.0, p.eastM, 1e-9)
        assertEquals(0.0, p.northM, 1e-9)
    }

    @Test fun north_and_east_have_expected_sign_and_scale() {
        val north = tf.toLocal(-26.19910, 28.04000)   // ~0.0009 deg north
        assertTrue(north.northM > 90 && north.northM < 110)
        assertEquals(0.0, north.eastM, 1e-6)
        val east = tf.toLocal(-26.20000, 28.04100)    // 0.001 deg east
        assertTrue(east.eastM > 90 && east.eastM < 110)
    }

    @Test fun geodetic_round_trip() {
        val local = tf.toLocal(-26.19955, 28.04123)
        val (lat, lon) = tf.toGeodetic(local)
        assertEquals(-26.19955, lat, 1e-9)
        assertEquals(28.04123, lon, 1e-9)
    }

    @Test fun level_tool_is_lever_arm_below_antenna() {
        val up = MachineGeometry.toolUpOffsetM(LeverArm(0.0, 0.0, 3.10), Attitude())
        assertEquals(-3.10, up, 1e-9)
    }

    @Test fun pitch_raises_a_forward_tool() {
        val up = MachineGeometry.toolUpOffsetM(LeverArm(forwardM = 2.0, rightM = 0.0, downM = 0.0),
            Attitude(pitchDeg = 10.0))
        assertEquals(0.347296, up, 1e-5)
    }

    @Test fun heading_rotates_horizontal_offset() {
        val east = MachineGeometry.toolHorizontalOffset(LeverArm(forwardM = 2.0, rightM = 0.0, downM = 0.0), 90.0)
        assertEquals(2.0, east.eastM, 1e-6)
        assertEquals(0.0, east.northM, 1e-6)
    }

    @Test fun tool_point_combines_position_and_elevation() {
        val tp = MachineGeometry.toolPoint(
            antenna = LocalXY(10.0, 20.0),
            antennaElevationM = 100.0,
            arm = LeverArm(0.0, 0.0, 3.0),
            att = Attitude()
        )
        assertEquals(10.0, tp.eastM, 1e-9)
        assertEquals(20.0, tp.northM, 1e-9)
        assertEquals(97.0, tp.elevationM, 1e-9)
    }
}
