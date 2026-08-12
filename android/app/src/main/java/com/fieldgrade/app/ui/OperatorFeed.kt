package com.fieldgrade.app.ui

import com.fieldgrade.app.control.GuidanceEngine
import com.fieldgrade.app.design.CutFillField
import com.fieldgrade.app.design.DesignSurface
import com.fieldgrade.app.control.GuidanceState
import com.fieldgrade.app.geom.Attitude
import com.fieldgrade.app.geom.CoordinateTransform
import com.fieldgrade.app.geom.LeverArm
import com.fieldgrade.app.geom.LocalXY
import com.fieldgrade.app.geom.MachinePose
import com.fieldgrade.app.gnss.DemoGnssSource
import com.fieldgrade.app.provenance.SessionProvenance
import com.fieldgrade.app.surface.DesignSurfaceModel
import com.fieldgrade.app.surface.PlaneDesignSurface

/**
 * What the operator screen needs in order to draw itself, and nothing more.
 *
 * One job: hand the UI the next [GuidanceState]. Where that comes from — a
 * demo generator, a replayed log, a live receiver — is not the screen's
 * business, which is what lets the same Compose code run on the tablet, on the
 * desktop harness, and against real hardware without being rewritten.
 */
interface OperatorFeed {
    /** Short description of the data source, shown in the status panel. */
    val label: String

    /**
     * What this feed is running on, and whether any of it is invented. The screen
     * shows a standing warning whenever it is not all real — a demo that looks
     * like a live machine is the failure mode this guards against.
     */
    val provenance: SessionProvenance

    /** How often the UI should call [tick]. */
    val periodMs: Long get() = 200

    /** Current operator nudge in millimetres. */
    val nudgeMm: Int

    /**
     * The design boundary in local metres, as a closed loop. Empty when the
     * source has no bounded surface — the map then falls back to framing the
     * driven track, rather than drawing an outline it has invented.
     */
    val fieldOutline: List<LocalXY> get() = emptyList()

    /** Where the machine is now, or null when there is no position solution. */
    val pose: MachinePose? get() = null

    /**
     * Remaining work across the whole field, or null when the source has only a
     * design and no existing-ground survey to difference it against. Null is the
     * honest answer there: without both surfaces the remaining work is unknown
     * everywhere except directly under the blade.
     */
    val cutFillField: CutFillField? get() = null

    /**
     * The two surfaces themselves, for anything needing elevations rather than
     * their difference — the profile pane samples both along the machine's course.
     */
    val designSurface: DesignSurfaceModel? get() = null
    val existingSurface: DesignSurfaceModel? get() = null

    /** Advance one tick and return the state to display. */
    fun tick(): GuidanceState

    fun nudge(deltaMm: Int)

    /** Declare the tool to be on grade here, absorbing any datum offset. */
    fun rebench()
}

/**
 * The no-hardware, no-files feed: a synthetic machine creeping across a single
 * grade plane. Keeps the screen alive in an IDE preview or a fresh checkout
 * where the sample data has not been fetched.
 */
class DemoOperatorFeed(
    originLat: Double = -27.9500,
    originLon: Double = 24.8300
) : OperatorFeed {

    override val label = "DEMO — synthetic track on a grade plane"

    override val provenance = SessionProvenance.allSynthetic(
        "no sample data loaded; generated track on a generated plane"
    )

    private val transform = CoordinateTransform(originLat, originLon)

    // A bounded plane, so the demo has a real outline to draw rather than an
    // infinite surface the map would have to guess the edges of.
    private val bounds = PlaneDesignSurface.Bounds(-60.0, -20.0, 60.0, 180.0)
    private val surface = PlaneDesignSurface(a = 118.900, b = 0.0, c = -0.005, bounds = bounds)
    private val arm = LeverArm(forwardM = 0.0, rightM = 0.0, downM = 3.10)
    private val engine = GuidanceEngine()
    private val demo = DemoGnssSource(originLat, originLon, baseHeightM = 122.000)

    override val fieldOutline = listOf(
        LocalXY(bounds.minE, bounds.minN),
        LocalXY(bounds.maxE, bounds.minN),
        LocalXY(bounds.maxE, bounds.maxN),
        LocalXY(bounds.minE, bounds.maxN)
    )

    // A stand-in existing surface at a slightly different grade, so the demo shows
    // the same cut/fill gradient the real data does rather than a bare outline.
    private val existing = PlaneDesignSurface(a = 118.830, b = 0.0015, c = -0.0032, bounds = bounds)

    override val designSurface: DesignSurfaceModel get() = surface
    override val existingSurface: DesignSurfaceModel get() = existing

    override val cutFillField: CutFillField = CutFillField.sample(
        design = surface,
        existing = existing,
        bounds = DesignSurface.Bounds(
            bounds.minE, bounds.minN, bounds.maxE, bounds.maxN, 0.0, 0.0
        )
    )

    override var pose: MachinePose? = null
        private set

    override var nudgeMm: Int = 0
        private set

    private var benchOffsetM = 0.0

    override fun tick(): GuidanceState {
        val sample = demo.tick()
        val local = transform.toLocal(sample.latitudeDeg, sample.longitudeDeg)
        pose = MachinePose(local.eastM, local.northM, sample.headingDeg, sample.speedMps)
        val state = engine.compute(sample, transform, arm, Attitude(), surface, benchOffsetM, nudgeMm)
        if (state.hasSolution) {
            cutFillField.markWorked(local.eastM, local.northM, BLADE_HALF_WIDTH_M, state.cutFillMm)
        }
        return state
    }

    private companion object {
        /** Half a nominal 3 m blade. */
        const val BLADE_HALF_WIDTH_M = 1.5
    }

    override fun nudge(deltaMm: Int) {
        nudgeMm += deltaMm
    }

    override fun rebench() {
        val s = engine.compute(demo.latest() ?: return, transform, arm, Attitude(), surface, 0.0, 0)
        if (s.hasSolution) benchOffsetM = s.designElevationM - s.toolElevationM
        nudgeMm = 0
    }
}
