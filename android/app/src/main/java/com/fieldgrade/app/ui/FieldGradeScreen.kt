package com.fieldgrade.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldgrade.app.control.GuidanceDirection
import com.fieldgrade.app.control.GuidanceState
import com.fieldgrade.app.geom.MachinePose
import com.fieldgrade.app.gnss.FixQuality
import com.fieldgrade.app.provenance.SessionProvenance
import com.fieldgrade.app.ui.map.FieldMapView
import com.fieldgrade.app.ui.map.FieldTrack
import com.fieldgrade.app.ui.map.HeadingFilter
import com.fieldgrade.app.ui.map.MapOrientation
import com.fieldgrade.app.ui.map.ProfileSampler
import com.fieldgrade.app.ui.map.ProfileView
import kotlinx.coroutines.delay

// The operator screen. Pure Jetpack Compose — no Android-framework calls — so it is
// rendered unchanged both by the Android app (MainActivity) and by the desktop dev
// harness (desktop/). Keep it that way: anything Android-specific belongs in MainActivity.
@Composable
fun FieldGradeScreen(feed: OperatorFeed = remember { DemoOperatorFeed() }) {
    var auto by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(GuidanceState.NONE) }
    var nudgeMm by remember { mutableIntStateOf(0) }
    var pose by remember { mutableStateOf<MachinePose?>(null) }
    // The track is mutable history; swapping the snapshot list is what triggers
    // recomposition, since mutating the recorder in place would not.
    val recorder = remember(feed) { FieldTrack() }
    var trackMarks by remember { mutableStateOf<List<FieldTrack.Mark>>(emptyList()) }

    // Course over ground is unusable raw; the filter owns that problem entirely.
    val heading = remember(feed) { HeadingFilter() }
    var orientation by remember { mutableStateOf(MapOrientation.NORTH_UP) }
    var headingDeg by remember { mutableDoubleStateOf(Double.NaN) }
    var headingHolding by remember { mutableStateOf(true) }
    var showProfile by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf(ProfileSampler.Profile(emptyList(), Double.NaN, 40.0, 10.0)) }

    LaunchedEffect(feed) {
        while (true) {
            state = feed.tick()
            nudgeMm = feed.nudgeMm
            pose = feed.pose
            pose?.let { heading.update(it.headingDeg, it.speedMps) }
            headingDeg = heading.headingDeg
            headingHolding = heading.isHolding
            pose?.let {
                if (state.hasSolution && recorder.offer(it.eastM, it.northM, state.cutFillMm)) {
                    trackMarks = recorder.snapshot()
                }
            }
            // Only sample the profile when it is on screen — it is 50 ray casts
            // into the TIN per tick, and nothing should pay for a hidden pane.
            if (showProfile) {
                val p = pose
                profile = if (p == null) {
                    ProfileSampler.Profile(emptyList(), Double.NaN, 40.0, 10.0)
                } else {
                    ProfileSampler.alongHeading(
                        feed.designSurface, feed.existingSurface,
                        p.eastM, p.northM, headingDeg
                    )
                }
            }
            if (auto && !state.canAuto) auto = false      // interlock drops AUTO
            delay(feed.periodMs)
        }
    }

    Column(Modifier.fillMaxSize()) {
        ProvenanceBanner(feed.provenance)
        Row(Modifier.weight(1f).fillMaxWidth().padding(12.dp)) {
        Column(Modifier.weight(0.78f).fillMaxHeight()) {
            Card(Modifier.fillMaxWidth().weight(if (showProfile) 0.68f else 1f)) {
                FieldMapView(
                    state = state,
                    outline = feed.fieldOutline,
                    track = trackMarks,
                    pose = pose,
                    modifier = Modifier.fillMaxSize(),
                    cutFill = feed.cutFillField,
                    orientation = orientation,
                    headingDeg = headingDeg,
                    headingHolding = headingHolding
                )
            }
            if (showProfile) {
                Spacer(Modifier.height(10.dp))
                Card(Modifier.fillMaxWidth().weight(0.32f)) {
                    ProfileView(
                        profile = profile,
                        modifier = Modifier.fillMaxSize(),
                        bladeElevationM = if (state.hasSolution) state.toolElevationM else Double.NaN
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(
            Modifier.weight(0.22f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PrimaryValue(state)
            AutoButton(auto, state.canAuto) { auto = !auto }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { feed.nudge(5) }, modifier = Modifier.weight(1f)) { Text("NUDGE +") }
                Button(onClick = { feed.nudge(-5) }, modifier = Modifier.weight(1f)) { Text("NUDGE -") }
            }
            OutlinedButton(
                onClick = { feed.rebench() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("REBENCH") }
            OutlinedButton(
                onClick = { orientation = orientation.toggled() },
                modifier = Modifier.fillMaxWidth()
            ) { Text(orientation.label) }
            OutlinedButton(
                onClick = { showProfile = !showProfile },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (showProfile) "PROFILE ON" else "PROFILE OFF") }
            Text("Nudge: $nudgeMm mm", style = MaterialTheme.typography.bodyMedium)
            StatusPanel(state, auto, feed.label, feed.provenance)
            }
        }
    }
}

/**
 * A standing warning whenever any input is generated.
 *
 * Deliberately at the top, full width, and impossible to mistake for chrome. The
 * dangerous failure here is not a crash — it is somebody watching this track a
 * design to 28 mm and believing it says something about hardware. It does not.
 */
@Composable
private fun ProvenanceBanner(provenance: SessionProvenance) {
    val banner = provenance.banner() ?: return
    Surface(
        color = Color(0xFF7A3E00),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Icon plus text: the warning must survive a monochrome screen.
            Text("⚠", color = Color(0xFFFFD08A), fontWeight = FontWeight.Bold)
            Text(
                banner,
                color = Color(0xFFFFE7C7),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun PrimaryValue(state: GuidanceState) {
    val mm = state.cutFillMm
    val label = when (state.direction) {
        GuidanceDirection.RAISE -> "RAISE"
        GuidanceDirection.LOWER -> "LOWER"
        GuidanceDirection.ON_GRADE -> "ON GRADE"
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("CUT / FILL", style = MaterialTheme.typography.labelLarge)
        Text(
            text = if (state.hasSolution) "${if (mm >= 0) "+" else ""}$mm mm" else "-- mm",
            fontSize = 44.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
        )
        Text(label, style = MaterialTheme.typography.titleMedium, color = directionColor(state.direction))
    }
}

@Composable
private fun AutoButton(auto: Boolean, canAuto: Boolean, onToggle: () -> Unit) {
    Button(
        onClick = onToggle,
        enabled = canAuto || auto,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (auto) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
        )
    ) { Text(if (auto) "AUTO ON" else "AUTO OFF", fontWeight = FontWeight.Bold) }
}

@Composable
private fun StatusPanel(
    state: GuidanceState, auto: Boolean, sourceLabel: String, provenance: SessionProvenance
) {
    Card(shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // colour is never the only indicator: always paired with text + icon.
            val (rtkText, rtkIcon) = when (state.quality) {
                FixQuality.FIXED -> "RTK FIX" to "●"
                FixQuality.FLOAT -> "RTK FLOAT" to "◐"
                FixQuality.DGPS -> "DGPS" to "○"
                FixQuality.AUTONOMOUS -> "GPS" to "○"
                FixQuality.NONE -> "NO FIX" to "✕"
            }
            Text("$rtkIcon  $rtkText", color = qualityColor(state.quality), fontWeight = FontWeight.Bold)
            if (state.hasSolution) {
                Text("Tool:   ${"%.3f".format(state.toolElevationM)} m", style = MaterialTheme.typography.bodySmall)
                Text("Design: ${"%.3f".format(state.designElevationM)} m", style = MaterialTheme.typography.bodySmall)
            }
            val autoLine = if (auto) "AUTO engaged" else (state.autoInhibitReason ?: "AUTO ready")
            Text(
                autoLine, style = MaterialTheme.typography.bodySmall,
                color = if (state.canAuto || auto) MaterialTheme.colorScheme.onSurface else Color(0xFFEF6C00)
            )
            // Never let a demo be mistaken for live data.
            Text(
                sourceLabel, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Per-input verdict, so it is clear which half is invented.
            for (src in provenance.sources) {
                Text(
                    "${src.provenance.label}  ${src.role}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (src.provenance == com.fieldgrade.app.provenance.Provenance.REAL)
                        Color(0xFF7FB77F) else Color(0xFFEF9A3D)
                )
            }
        }
    }
}

private fun directionColor(d: GuidanceDirection) = when (d) {
    GuidanceDirection.RAISE -> Color(0xFF2196F3)
    GuidanceDirection.LOWER -> Color(0xFFE53935)
    GuidanceDirection.ON_GRADE -> Color(0xFF4CAF50)
}

private fun qualityColor(q: FixQuality) = when (q) {
    FixQuality.FIXED -> Color(0xFF4CAF50)
    FixQuality.FLOAT -> Color(0xFFFFB300)
    else -> Color(0xFFE53935)
}
