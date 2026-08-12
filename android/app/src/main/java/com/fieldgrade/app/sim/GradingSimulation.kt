package com.fieldgrade.app.sim

import com.fieldgrade.app.control.ControlEngine
import com.fieldgrade.app.control.GuidanceEngine
import com.fieldgrade.app.control.GuidanceState
import com.fieldgrade.app.geom.Attitude
import com.fieldgrade.app.geom.CoordinateTransform
import com.fieldgrade.app.geom.LeverArm
import com.fieldgrade.app.geom.MachinePose
import com.fieldgrade.app.gnss.GnssSample
import com.fieldgrade.app.gnss.NmeaReplaySource
import com.fieldgrade.app.surface.DesignSurfaceModel
import com.fieldgrade.app.transport.Clock
import com.fieldgrade.app.transport.CommandStreamer
import com.fieldgrade.app.transport.LinkSupervisor

/**
 * Runs the whole chain against simulated inputs, one time step at a time.
 *
 * One job: advance simulated time and keep the modules in step with each other.
 * Every piece of behaviour lives in the module that owns it — this class decides
 * only *when* each one runs, which is exactly what a real scheduler does:
 *
 *   NMEA replay -> decode -> local transform -> lever arm -> guidance
 *       -> control -> command frame -> controller -> hydraulics -> blade
 *       -> (blade moves, changing the lever arm) -> back to guidance
 *
 * That last arrow is what makes this a closed loop rather than a data pipeline:
 * the blade's own movement changes the next cut/fill reading, so the simulation
 * shows whether the loop actually converges or hunts.
 *
 * Deterministic by construction — a virtual clock, no threads, no wall-clock
 * sleeps — so a 20-minute drive replays in well under a second and any
 * regression reproduces exactly.
 */
class GradingSimulation(
    private val surface: DesignSurfaceModel,
    private val gnss: NmeaReplaySource,
    private val transform: CoordinateTransform,
    private val config: Config = Config()
) {
    data class Config(
        /** Simulation tick. Also the controller's control-loop period. */
        val stepMs: Long = 10,
        /** GNSS epoch interval; the sample log is 5 Hz. */
        val gnssPeriodMs: Long = 200,
        /** Tablet command cadence (25 Hz). */
        val commandPeriodMs: Long = 40,
        /** Antenna height above the blade at neutral cylinder position. */
        val armDownM: Double = 3.10,
        val armForwardM: Double = 0.0,
        val armRightM: Double = 0.0,
        /** Engage AUTO as soon as the guidance says it is permitted. */
        val autoWhenPermitted: Boolean = true
    )

    /** One observation of the whole system, for tracing and assertions. */
    data class Step(
        val timeMs: Long,
        val sample: GnssSample?,
        val guidance: GuidanceState,
        val auto: Boolean,
        val commandedMm: Int,
        val controllerOutput: Int,
        val controllerState: String,
        val fault: Int,
        val bladeOffsetMm: Double,
        val linkState: String
    )

    private var nowMs: Long = 0
    private val clock = Clock { nowMs }

    val controller = SimulatedController()
    val blade = BladeModel()
    private val supervisor = LinkSupervisor(clock)
    private val streamer = CommandStreamer(controller, supervisor, clock, null, config.commandPeriodMs)
    private val guidanceEngine = GuidanceEngine()
    private val controlEngine = ControlEngine()

    private var lastGnssMs: Long = Long.MIN_VALUE / 4
    private var latest: GnssSample? = null
    private var auto = false

    /** Datum correction from bench-in; see [benchIn]. */
    var benchmarkOffsetM: Double = 0.0
        private set
    var isBenched: Boolean = false
        private set

    var lastGuidance: GuidanceState = GuidanceState.NONE
        private set

    /** Operator nudge in millimetres, applied on top of the design elevation. */
    var nudgeMm: Int = 0

    /**
     * Antenna position and course in the local grid, for the map. Null until a
     * fix arrives. This is the *machine*, not the tool — the map shows where the
     * vehicle is, while the cut/fill readout refers to the blade.
     */
    val machinePose: MachinePose?
        get() = latest?.let {
            val local = transform.toLocal(it.latitudeDeg, it.longitudeDeg)
            MachinePose(local.eastM, local.northM, it.headingDeg, it.speedMps)
        }

    init {
        supervisor.onOpen()
    }

    /**
     * Bench in against the design: declare that the blade is, right now, exactly
     * on grade. This is the operator parking on a known point and pressing
     * REBENCH, and it is what absorbs the orthometric-vs-ellipsoidal datum
     * difference (tens of metres) that no amount of clever code can guess.
     *
     * Without this the cut/fill reading is offset by the geoid separation and
     * AUTO would drive the blade straight into its stop.
     */
    fun benchIn(): Boolean {
        val s = latest ?: return false
        val raw = guidanceEngine.compute(
            s, transform, currentArm(), Attitude(), surface, 0.0, 0
        )
        if (!raw.hasSolution) return false
        benchmarkOffsetM = raw.designElevationM - raw.toolElevationM
        isBenched = true
        return true
    }

    /**
     * The lever arm shortens as the blade is raised: the cylinder moves the tool
     * relative to the machine, so blade position is part of the geometry rather
     * than a fudge added to the answer.
     */
    private fun currentArm() = LeverArm(
        forwardM = config.armForwardM,
        rightM = config.armRightM,
        downM = config.armDownM - blade.offsetM
    )

    /** Advance one tick. Returns null once the GNSS log is exhausted. */
    fun step(): Step? {
        nowMs += config.stepMs

        // --- GNSS epoch ---
        if (nowMs - lastGnssMs >= config.gnssPeriodMs) {
            val s = gnss.advance(nowMs)
            if (s == null && gnss.isExhausted) return null
            if (s != null) {
                latest = s
                lastGnssMs = nowMs
            }
        }

        val sample = latest

        // --- guidance ---
        val guidance = if (sample == null) {
            GuidanceState.NONE
        } else {
            guidanceEngine.compute(
                sample, transform, currentArm(), Attitude(), surface, benchmarkOffsetM, nudgeMm
            )
        }
        lastGuidance = guidance

        // --- AUTO interlock: engage when allowed, drop the moment it is not ---
        if (config.autoWhenPermitted && guidance.canAuto && isBenched) auto = true
        if (!guidance.canAuto) auto = false

        // --- control: physical millimetres only; all gain lives on the controller ---
        val commandedMm = if (auto) controlEngine.autoTargetMm(guidance.cutFillMm) else 0
        streamer.mode = if (auto) "AUTO" else "HOLD"
        streamer.targetMm = commandedMm
        streamer.enable = auto

        // --- transport tick (sends at its own cadence) ---
        streamer.tick()

        // --- controller loop + hydraulics ---
        controller.tick(nowMs)
        blade.update(controller.applied, config.stepMs)

        return Step(
            timeMs = nowMs,
            sample = sample,
            guidance = guidance,
            auto = auto,
            commandedMm = commandedMm,
            controllerOutput = controller.applied,
            controllerState = controller.state,
            fault = controller.fault,
            bladeOffsetMm = blade.offsetMm,
            linkState = supervisor.state.name
        )
    }

    /** Assert the e-stop line, as the operator or a hardware interlock would. */
    fun setEstop(asserted: Boolean) {
        controller.estop = asserted
    }

    /** Simulate the cable being pulled: the tablet stops being able to talk. */
    fun severLink() {
        controller.close()
    }
}
