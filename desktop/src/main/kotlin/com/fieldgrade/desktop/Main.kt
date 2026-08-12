package com.fieldgrade.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.fieldgrade.app.design.CutFillField
import com.fieldgrade.app.design.DelaunayTriangulator
import com.fieldgrade.app.design.DesignSurface
import com.fieldgrade.app.design.LandXmlSurfaceReader
import com.fieldgrade.app.design.SurfaceBoundary
import com.fieldgrade.app.design.TinDesignSurface
import com.fieldgrade.app.design.XyzPointReader
import com.fieldgrade.app.geom.CoordinateTransform
import com.fieldgrade.app.geom.LocalXY
import com.fieldgrade.app.gnss.NmeaReplaySource
import com.fieldgrade.app.sim.GradingSimulation
import com.fieldgrade.app.sim.SimulationOperatorFeed
import com.fieldgrade.app.ui.DemoOperatorFeed
import com.fieldgrade.app.ui.FieldGradeScreen
import com.fieldgrade.app.ui.OperatorFeed
import java.io.File

// Desktop dev harness: opens the real operator screen in a resizable JVM window.
// No emulator, no VM, no hardware.  Run with:  ./gradlew run
//
// It prefers real data — a surveyed design surface plus a replayed RTK track,
// closed through the simulated controller and blade — and falls back to the
// synthetic demo if the sample data has not been fetched.
//
//   ./gradlew run                                  # real sample data
//   ./gradlew run --args="--demo"                  # force the synthetic demo
//   ./gradlew run --args="path/to/design.xyz path/to/track.nmea"

// Site origin the sample track was generated around (tools/make_rtk_track.py).
private const val SITE_LAT = -27.9500
private const val SITE_LON = 24.8300

fun main(args: Array<String>) = application {
    val feed = remembering { buildFeed(args) }
    Window(
        onCloseRequest = ::exitApplication,
        title = "FieldGrade — Operator (desktop harness)",
        state = rememberWindowState(size = DpSize(1280.dp, 800.dp)),
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            FieldGradeScreen(feed)
        }
    }
}

/** Build once, outside composition — loading a surface is not a recomposition-safe act. */
private fun <T> remembering(build: () -> T): T = build()

private fun buildFeed(args: Array<String>): OperatorFeed {
    if (args.contains("--demo")) return DemoOperatorFeed(SITE_LAT, SITE_LON)

    val paths = args.filterNot { it.startsWith("--") }
    val designFile = paths.getOrNull(0)?.let(::File) ?: findSample("design/nunosurf_design.xyz")
    val trackFile = paths.getOrNull(1)?.let(::File) ?: findSample("nmea/site_track_rtk.nmea")
    val existingFile = paths.getOrNull(2)?.let(::File) ?: findSample("design/nunosurf_existing.xyz")

    if (designFile == null || trackFile == null || !designFile.isFile || !trackFile.isFile) {
        println("sample data not found — falling back to the synthetic demo.")
        println("  generate it with:")
        println("    python tools/landxml_to_xyz.py tools/sampledata/design/nunosurf.xml \\")
        println("           tools/sampledata/design/nunosurf_design.xyz --local")
        println("    python tools/make_rtk_track.py tools/sampledata/design/nunosurf_design.xyz \\")
        println("           tools/sampledata/nmea/site_track_rtk.nmea --swath 20")
        return DemoOperatorFeed(SITE_LAT, SITE_LON)
    }

    return try {
        val t0 = System.currentTimeMillis()
        val surface = loadSurface(designFile)
        val tin = TinDesignSurface.from(surface)

        // The map outline is the mesh boundary, not a bounding box — the operator
        // must see the shape of the field they are actually allowed to grade.
        val triangles = if (surface.hasTriangles) surface.triangles
        else DelaunayTriangulator.triangulate(surface.points)
        val outline = SurfaceBoundary.outline(surface.points, triangles)
            .map { LocalXY(it.eastM, it.northM) }

        // The second surface. Without it the app can show the target but not how
        // much work is left anywhere except under the blade, so the gradient is
        // simply absent rather than guessed.
        val existingTin = existingFile?.takeIf { it.isFile }?.let {
            TinDesignSurface.from(loadSurface(it))
        }
        val cutFill = existingTin?.let { existing ->
            surface.bounds()?.let { CutFillField.sample(tin, existing, it) }
        }

        val loaded = System.currentTimeMillis() - t0

        println("design  : ${designFile.name} -> ${tin.describe()}")
        surface.bounds()?.let {
            println(
                "extent  : %.0f x %.0f m, relief %.2f m".format(it.widthM, it.heightM, it.reliefM)
            )
        }
        println("outline : ${outline.size} boundary points")
        if (cutFill != null) {
            val s = cutFill.summary()
            println(
                "existing: ${existingFile.name} -> ${cutFill.cols}x${cutFill.rows} cells " +
                    "@ ${cutFill.cellSizeM} m"
            )
            println(
                "work    : %d cut, %d fill, %d on grade (%.1f%% done)".format(
                    s.cut, s.fill, s.onGrade, s.onGradeFraction * 100
                )
            )
        } else {
            println("existing: none — no cut/fill gradient (design only)")
        }
        println("track   : ${trackFile.name}")
        println("loaded in $loaded ms")

        val sim = GradingSimulation(
            surface = tin,
            gnss = NmeaReplaySource(trackFile.readLines()),
            transform = CoordinateTransform(SITE_LAT, SITE_LON)
        )
        SimulationOperatorFeed(
            sim,
            label = "REPLAY — ${designFile.name} + ${trackFile.name}",
            fieldOutline = outline,
            cutFillField = cutFill,
            designSurface = tin,
            existingSurface = existingTin
        )
    } catch (e: Exception) {
        println("could not load sample data (${e.message}) — falling back to the demo.")
        DemoOperatorFeed(SITE_LAT, SITE_LON)
    }
}

/** LandXML keeps its designer's faces; loose XYZ gets triangulated. */
private fun loadSurface(file: File): DesignSurface =
    if (file.extension.equals("xml", ignoreCase = true)) {
        file.inputStream().use { LandXmlSurfaceReader.read(it) }
    } else {
        DesignSurface(file.nameWithoutExtension, XyzPointReader.read(file.readText()).points)
    }

/** Walk up from the working directory looking for tools/sampledata. */
private fun findSample(relative: String): File? {
    var dir: File? = File("").absoluteFile
    while (dir != null) {
        val candidate = File(dir, "tools/sampledata/$relative")
        if (candidate.isFile) return candidate
        dir = dir.parentFile
    }
    return null
}
