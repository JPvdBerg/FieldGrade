package com.fieldgrade.app.sim

import com.fieldgrade.app.control.GuidanceState
import com.fieldgrade.app.design.CutFillField
import com.fieldgrade.app.geom.LocalXY
import com.fieldgrade.app.geom.MachinePose
import com.fieldgrade.app.provenance.SessionProvenance
import com.fieldgrade.app.surface.DesignSurfaceModel
import com.fieldgrade.app.ui.OperatorFeed

/**
 * Drives the operator screen from a [GradingSimulation].
 *
 * One job: adapt the simulation to what the UI needs. This is the seam that
 * lets the real screen render a real design surface and a real (replayed) GNSS
 * track with no hardware attached — and, later, lets a live receiver take the
 * simulation's place without the UI noticing.
 *
 * Each UI tick advances the simulation by [stepsPerTick] control loops, so the
 * blade and controller run at their proper 10 ms cadence while the screen
 * redraws at a sane rate.
 */
class SimulationOperatorFeed(
    private val sim: GradingSimulation,
    override val label: String,
    override val provenance: SessionProvenance,
    override val fieldOutline: List<LocalXY> = emptyList(),
    override val cutFillField: CutFillField? = null,
    override val designSurface: DesignSurfaceModel? = null,
    override val existingSurface: DesignSurfaceModel? = null,
    override val periodMs: Long = 100,
    private val stepsPerTick: Int = 10,
    /** Half the blade width — the swath the machine actually works. */
    private val bladeHalfWidthM: Double = 1.5
) : OperatorFeed {

    private var benched = false

    override val nudgeMm: Int get() = sim.nudgeMm

    override val pose: MachinePose? get() = sim.machinePose

    override fun tick(): GuidanceState {
        repeat(stepsPerTick) {
            sim.step() ?: return sim.lastGuidance
        }
        // Bench in automatically on the first usable fix, as an operator would
        // before starting a job. Without it the datum offset dominates.
        if (!benched && sim.lastGuidance.hasSolution) benched = sim.benchIn()

        // Record what the blade left behind, so the field gradient recolours
        // in the machine's wake instead of showing the pre-job survey forever.
        val guidance = sim.lastGuidance
        if (benched && guidance.hasSolution) {
            sim.machinePose?.let { p ->
                cutFillField?.markWorked(p.eastM, p.northM, bladeHalfWidthM, guidance.cutFillMm)
            }
        }
        return guidance
    }

    override fun nudge(deltaMm: Int) {
        sim.nudgeMm += deltaMm
    }

    override fun rebench() {
        sim.nudgeMm = 0
        benched = sim.benchIn()
    }
}
