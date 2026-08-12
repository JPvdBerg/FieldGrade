package com.fieldgrade.app.sim

import com.fieldgrade.app.SampleData
import com.fieldgrade.app.design.DesignSurface
import com.fieldgrade.app.design.TinDesignSurface
import com.fieldgrade.app.design.XyzPointReader
import com.fieldgrade.app.geom.CoordinateTransform
import com.fieldgrade.app.gnss.NmeaReplaySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import kotlin.math.abs

/**
 * End-to-end: real design surface + RTK track -> guidance -> control -> controller
 * -> hydraulics -> blade -> back into guidance.
 *
 * This is the closed loop, run offline. It proves the modules agree with each
 * other about units, signs and timing — the things that pass in isolation and
 * then fail when wired together.
 *
 * It proves nothing about real hydraulics. See [SimulatedController] and
 * [BladeModel] for what is deliberately not modelled.
 */
class GradingSimulationTest {

    companion object {
        // Site origin used when the track was generated (tools/make_rtk_track.py).
        private const val SITE_LAT = -27.9500
        private const val SITE_LON = 24.8300

        private lateinit var surface: TinDesignSurface

        @BeforeClass
        @JvmStatic
        fun buildSurface() {
            // Loose XYZ points with no faces, so this exercises the full
            // read -> triangulate -> index path on 7,048 real survey points.
            val t0 = System.currentTimeMillis()
            val points = XyzPointReader.read(SampleData.nunosurfXyz().readText()).points
            val read = System.currentTimeMillis()
            surface = TinDesignSurface.from(DesignSurface("nunosurf", points))
            val built = System.currentTimeMillis()
            println(
                "surface: ${surface.describe()}\n" +
                    "  read ${read - t0} ms, triangulated+indexed ${built - read} ms"
            )
        }
    }

    private fun newSim(config: GradingSimulation.Config = GradingSimulation.Config()) =
        GradingSimulation(
            surface = surface,
            gnss = NmeaReplaySource(SampleData.rtkTrack().readLines()),
            transform = CoordinateTransform(SITE_LAT, SITE_LON),
            config = config
        )

    /** Run until the log is exhausted or [maxSteps] is hit. */
    private fun runAll(
        sim: GradingSimulation, maxSteps: Int = 200_000,
        onStep: (GradingSimulation.Step) -> Unit = {}
    ): List<GradingSimulation.Step> {
        val out = ArrayList<GradingSimulation.Step>()
        var benched = false
        repeat(maxSteps) {
            val s = sim.step() ?: return out
            if (!benched && s.guidance.hasSolution) benched = sim.benchIn()
            out.add(s)
            onStep(s)
        }
        return out
    }

    // ---------------------------------------------------------------- the run

    @Test fun closing_the_loop_beats_leaving_the_blade_where_it_was() {
        // Bench-in zeroes the error at the bench point, so "first vs last" says
        // nothing. The question that matters is whether running AUTO leaves the
        // field closer to design than not running it, over the same drive.
        val t0 = System.currentTimeMillis()

        val closed = newSim()
        val closedSteps = runAll(closed)

        val open = newSim(GradingSimulation.Config(autoWhenPermitted = false))
        val openSteps = runAll(open)

        val elapsed = System.currentTimeMillis() - t0

        assertTrue("expected a long run, got ${closedSteps.size} steps", closedSteps.size > 10_000)

        val solved = closedSteps.filter { it.guidance.hasSolution }
        val autoSteps = closedSteps.filter { it.auto }
        assertTrue("no guidance solution at all", solved.isNotEmpty())
        assertTrue("AUTO never engaged", autoSteps.isNotEmpty())

        // Ignore the first two seconds: that is bench-in and the initial pull-in.
        val settleFrom = 200
        val closedErr = closedSteps.drop(settleFrom)
            .filter { it.guidance.hasSolution }.map { abs(it.guidance.cutFillMm) }
        val openErr = openSteps.drop(settleFrom)
            .filter { it.guidance.hasSolution }.map { abs(it.guidance.cutFillMm) }

        val closedMean = closedErr.average()
        val openMean = openErr.average()
        val onGradePct = 100.0 * closedErr.count { it <= 25 } / closedErr.size
        val openOnGradePct = 100.0 * openErr.count { it <= 25 } / openErr.size

        println(
            """
            |
            |=== FieldGrade closed-loop simulation ===
            |  sim steps            ${closedSteps.size}  (${closedSteps.last().timeMs / 1000}s simulated)
            |  both runs in         ${elapsed} ms
            |  guidance solutions   ${solved.size}  (${100 * solved.size / closedSteps.size}% of steps inside the design)
            |  AUTO engaged         ${autoSteps.size} steps
            |
            |                       AUTO off     AUTO on
            |  mean |cut/fill|      ${"%7.1f".format(openMean)} mm  ${"%7.1f".format(closedMean)} mm
            |  worst |cut/fill|     ${"%7d".format(openErr.max())} mm  ${"%7d".format(closedErr.max())} mm
            |  within +/-25 mm      ${"%6.1f".format(openOnGradePct)}%    ${"%6.1f".format(onGradePct)}%
            |
            |  blade travel         ${"%.0f".format(closedSteps.minOf { it.bladeOffsetMm })} .. ${"%.0f".format(closedSteps.maxOf { it.bladeOffsetMm })} mm
            |  faults seen          ${closedSteps.map { it.fault }.toSet().sorted()}
            """.trimMargin()
        )

        assertTrue(
            "AUTO did not improve on grade: ${"%.1f".format(closedMean)} mm vs " +
                "${"%.1f".format(openMean)} mm open-loop",
            closedMean < openMean * 0.5
        )
        assertTrue(
            "settled error still large: ${"%.1f".format(closedMean)} mm",
            closedMean < 40.0
        )
        assertTrue(
            "expected to be on grade most of the time, was ${"%.1f".format(onGradePct)}%",
            onGradePct > 70.0
        )
    }

    @Test fun a_trace_of_the_first_few_seconds_is_readable() {
        val sim = newSim()
        val steps = runAll(sim, maxSteps = 900)
        println("\n=== first 9 s, sampled every 500 ms ===")
        println("    t(s)  fix    designZ   toolZ   cut/fill  dir      auto  cmd_mm  duty  blade_mm  state")
        steps.filter { it.timeMs % 500 == 0L }.forEach { s ->
            val g = s.guidance
            println(
                "  %6.1f  %-5s %8.3f %8.3f %8d mm  %-8s %-5s %6d %5d %9.1f  %s".format(
                    s.timeMs / 1000.0,
                    g.quality.name.take(5),
                    g.designElevationM,
                    g.toolElevationM,
                    g.cutFillMm,
                    g.direction.name,
                    if (s.auto) "AUTO" else "-",
                    s.commandedMm,
                    s.controllerOutput,
                    s.bladeOffsetMm,
                    s.controllerState
                )
            )
        }
    }

    // ---------------------------------------------------------------- wiring

    @Test fun the_generated_track_is_already_in_the_design_datum() {
        // Worth stating explicitly, because it is an assumption baked into the
        // harness rather than a property of the world: the generator writes
        // orthometric height and a geoid separation that the parser adds straight
        // back, so ellipsoidal height returns in the same datum as the design.
        // That models a receiver already outputting heights on the site datum.
        // A receiver that does not is the case covered by the next test.
        val sim = newSim()
        var first: GradingSimulation.Step? = null
        repeat(50) { if (first == null) sim.step()?.let { s -> if (s.guidance.hasSolution) first = s } }
        assertNotNull("never got a guidance solution", first)
        assertTrue(
            "expected the track to already sit near design, got ${first!!.guidance.cutFillMm} mm",
            abs(first!!.guidance.cutFillMm) < 500
        )
    }

    @Test fun benching_in_removes_a_vertical_datum_offset() {
        // A design authored on a different vertical datum from the receiver's
        // output: every design elevation sits 28 m above what the tool reads.
        // Un-benched, AUTO would drive the blade straight into its stop.
        val shifted = object : com.fieldgrade.app.surface.DesignSurfaceModel {
            override fun elevationAt(eastM: Double, northM: Double): Double? =
                surface.elevationAt(eastM, northM)?.plus(28.0)

            override fun contains(eastM: Double, northM: Double): Boolean =
                surface.contains(eastM, northM)
        }
        val sim = GradingSimulation(
            surface = shifted,
            gnss = NmeaReplaySource(SampleData.rtkTrack().readLines()),
            transform = CoordinateTransform(SITE_LAT, SITE_LON)
        )

        var first: GradingSimulation.Step? = null
        repeat(50) { if (first == null) sim.step()?.let { s -> if (s.guidance.hasSolution) first = s } }
        assertNotNull("never got a guidance solution", first)
        assertTrue(
            "expected a ~28 m pre-bench offset, got ${first!!.guidance.cutFillMm} mm",
            abs(first!!.guidance.cutFillMm) > 20_000
        )

        assertTrue("bench-in failed", sim.benchIn())
        assertEquals(28.0, sim.benchmarkOffsetM, 0.5)

        val after = sim.step()!!
        assertTrue(
            "bench-in left ${after.guidance.cutFillMm} mm of offset",
            abs(after.guidance.cutFillMm) < 50
        )
    }

    @Test fun auto_is_refused_until_the_machine_is_benched() {
        val sim = newSim()
        repeat(200) {
            val s = sim.step() ?: return
            assertTrue("AUTO engaged before bench-in", !s.auto)
        }
    }

    @Test fun the_track_stays_inside_the_design_boundary() {
        val sim = newSim()
        val steps = runAll(sim, maxSteps = 40_000)
        val inside = steps.count { it.guidance.hasSolution }
        val pct = 100.0 * inside / steps.size
        assertTrue("only $pct% of the track was inside the design surface", pct > 95.0)
    }

    // ---------------------------------------------------------------- safety

    @Test fun raise_and_lower_are_never_simultaneously_live_across_the_whole_run() {
        val sim = newSim()
        runAll(sim, maxSteps = 40_000) {
            val c = sim.controller
            assertTrue(
                "raise=${c.raiseDuty} lower=${c.lowerDuty} at t=${it.timeMs}",
                c.raiseDuty == 0 || c.lowerDuty == 0
            )
        }
    }

    @Test fun estop_mid_run_stops_the_blade_immediately() {
        val sim = newSim()
        var stepped = 0
        var benched = false
        while (stepped < 6000) {
            val s = sim.step() ?: break
            if (!benched && s.guidance.hasSolution) benched = sim.benchIn()
            stepped++
            if (s.auto && abs(s.controllerOutput) > 100) break
        }
        assertTrue("never reached a moving state to e-stop from", abs(sim.controller.applied) > 100)

        sim.setEstop(true)
        val after = sim.step()!!
        assertEquals(0, after.controllerOutput)
        assertEquals(SimulatedController.FAULT_ESTOP, after.fault)

        // And it stays down while asserted.
        repeat(200) {
            val s = sim.step() ?: return
            assertEquals(0, s.controllerOutput)
        }
    }

    @Test fun losing_the_link_drives_the_controller_neutral() {
        val sim = newSim()
        var stepped = 0
        var benched = false
        while (stepped < 6000) {
            val s = sim.step() ?: break
            if (!benched && s.guidance.hasSolution) benched = sim.benchIn()
            stepped++
            if (s.auto && abs(s.controllerOutput) > 100) break
        }
        assertTrue(abs(sim.controller.applied) > 100)

        sim.severLink()      // the cable is pulled
        var neutralAfterMs: Long? = null
        val from = sim.controller.let { _ -> 0L }
        var elapsed = 0L
        repeat(100) {
            val s = sim.step() ?: return@repeat
            elapsed += 10
            if (s.controllerOutput == 0 && neutralAfterMs == null) neutralAfterMs = elapsed
        }
        assertNotNull("controller never went neutral after link loss", neutralAfterMs)
        assertTrue(
            "took ${neutralAfterMs}ms to reach neutral after link loss",
            neutralAfterMs!! <= 260
        )
        assertEquals(SimulatedController.FAULT_TIMEOUT, sim.controller.fault)
        assertEquals(from, 0L)
    }

    @Test fun no_faults_occur_during_normal_operation() {
        val sim = newSim()
        val steps = runAll(sim, maxSteps = 40_000)
        // Fault 1 is expected only in the first few loops, before the first
        // command has been sent; after that the link must stay clean.
        val settled = steps.drop(100)
        val faults = settled.map { it.fault }.toSet()
        assertEquals("unexpected faults during normal operation: $faults", setOf(0), faults)
    }
}
