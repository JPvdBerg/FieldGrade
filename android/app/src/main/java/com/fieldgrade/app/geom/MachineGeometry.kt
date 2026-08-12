package com.fieldgrade.app.geom

import kotlin.math.cos
import kotlin.math.sin

/**
 * Lever arm from the GNSS antenna phase centre to the tool (cutting-edge) control
 * point, in the machine body frame with the machine level:
 *   forwardM  : + towards the front of the machine
 *   rightM    : + towards the right-hand side
 *   downM     : + downwards (the tool is normally below the antenna, so > 0)
 */
data class LeverArm(val forwardM: Double, val rightM: Double, val downM: Double)

/** Machine attitude in degrees: pitch (+nose up), roll (+right side down), heading (0=N, CW). */
data class Attitude(val pitchDeg: Double = 0.0, val rollDeg: Double = 0.0, val headingDeg: Double = 0.0)

data class ToolPoint(val eastM: Double, val northM: Double, val elevationM: Double)

/**
 * Where the machine is, which way it is going, and how fast, in the local site grid.
 *
 * Heading is degrees clockwise from north; NaN when it is not yet known. Speed
 * travels with it because the two are inseparable in practice: heading here comes
 * from course over ground, whose trustworthiness is entirely a function of speed.
 * Anything deciding whether to believe the heading needs both.
 */
data class MachinePose(
    val eastM: Double,
    val northM: Double,
    val headingDeg: Double = Double.NaN,
    val speedMps: Double = Double.NaN
)

/**
 * Turns an antenna solution + attitude into the tool control point.
 *
 * With a single antenna the tool elevation is derived, not measured, so attitude
 * matters: on uneven ground pitch/roll swing the tool relative to the antenna.
 * This is exactly why PROJECT_PLAN recommends a pitch/roll sensor before AUTO is
 * trusted. With no attitude (pitch=roll=0) the model reduces to a plain vertical
 * offset, suitable for guidance-only on flat ground.
 */
object MachineGeometry {

    /**
     * Vertical (up) displacement of the tool relative to the antenna for a lever
     * arm and attitude. Body frame is X=forward, Y=right, Z=down; world-up is -Z.
     */
    fun toolUpOffsetM(arm: LeverArm, att: Attitude): Double {
        val p = Math.toRadians(att.pitchDeg)
        val r = Math.toRadians(att.rollDeg)
        // world-down component of the (forward,right,down) vector under roll then pitch
        val down = -arm.forwardM * sin(p) + arm.rightM * cos(p) * sin(r) + arm.downM * cos(p) * cos(r)
        return -down   // up displacement
    }

    /** Horizontal offset of the tool from the antenna in local East/North, using heading. */
    fun toolHorizontalOffset(arm: LeverArm, headingDeg: Double): LocalXY {
        val h = Math.toRadians(headingDeg)
        // forward points along heading; right is 90 deg clockwise from heading.
        val eForward = sin(h); val nForward = cos(h)
        val eRight = cos(h); val nRight = -sin(h)
        return LocalXY(
            eastM = arm.forwardM * eForward + arm.rightM * eRight,
            northM = arm.forwardM * nForward + arm.rightM * nRight
        )
    }

    /** Full tool control point from an antenna local position + elevation. */
    fun toolPoint(
        antenna: LocalXY,
        antennaElevationM: Double,
        arm: LeverArm,
        att: Attitude
    ): ToolPoint {
        val h = toolHorizontalOffset(arm, att.headingDeg)
        return ToolPoint(
            eastM = antenna.eastM + h.eastM,
            northM = antenna.northM + h.northM,
            elevationM = antennaElevationM + toolUpOffsetM(arm, att)
        )
    }
}
