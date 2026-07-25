package com.fieldgrade.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldgrade.app.control.GuidanceDirection
import com.fieldgrade.app.control.GuidanceEngine
import com.fieldgrade.app.control.GuidanceState
import com.fieldgrade.app.geom.Attitude
import com.fieldgrade.app.geom.CoordinateTransform
import com.fieldgrade.app.geom.LeverArm
import com.fieldgrade.app.gnss.DemoGnssSource
import com.fieldgrade.app.gnss.FixQuality
import com.fieldgrade.app.surface.PlaneDesignSurface
import kotlinx.coroutines.delay
import kotlin.math.abs

// The operator screen. Pure Jetpack Compose — no Android-framework calls — so it is
// rendered unchanged both by the Android app (MainActivity) and by the desktop dev
// harness (desktop/). Keep it that way: anything Android-specific belongs in MainActivity.
@Composable
fun FieldGradeScreen() {
    // --- demo wiring: a real receiver + parsed .gps surface are injected later ---
    val originLat = -26.20000; val originLon = 28.04000
    val transform = remember { CoordinateTransform(originLat, originLon) }
    val surface = remember { PlaneDesignSurface(a = 1542.400, b = 0.0, c = -0.005) }
    val arm = remember { LeverArm(forwardM = 0.0, rightM = 0.0, downM = 3.10) }
    val engine = remember { GuidanceEngine() }
    val demo = remember { DemoGnssSource(originLat, originLon) }

    var auto by remember { mutableStateOf(false) }
    var nudgeMm by remember { mutableIntStateOf(0) }
    var benchOffsetM by remember { mutableDoubleStateOf(0.0) }
    var state by remember { mutableStateOf(GuidanceState.NONE) }

    LaunchedEffect(Unit) {
        while (true) {
            val sample = demo.tick()
            state = engine.compute(sample, transform, arm, Attitude(), surface, benchOffsetM, nudgeMm)
            if (auto && !state.canAuto) auto = false      // interlock drops AUTO
            delay(200)
        }
    }

    Row(Modifier.fillMaxSize().padding(12.dp)) {
        Card(Modifier.weight(0.78f).fillMaxHeight()) {
            CutFillMap(state, Modifier.fillMaxSize().padding(8.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(
            Modifier.weight(0.22f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PrimaryValue(state)
            AutoButton(auto, state.canAuto) { auto = !auto }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { nudgeMm += 5 }, modifier = Modifier.weight(1f)) { Text("NUDGE +") }
                Button(onClick = { nudgeMm -= 5 }, modifier = Modifier.weight(1f)) { Text("NUDGE -") }
            }
            OutlinedButton(
                onClick = {
                    if (state.hasSolution) benchOffsetM = 0.0
                    nudgeMm = 0
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("REBENCH") }
            Text("Nudge: $nudgeMm mm", style = MaterialTheme.typography.bodyMedium)
            StatusPanel(state, auto)
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
private fun StatusPanel(state: GuidanceState, auto: Boolean) {
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
        }
    }
}

@Composable
private fun CutFillMap(state: GuidanceState, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        val fill = when (state.direction) {
            GuidanceDirection.RAISE -> Color(0x552196F3)    // blue = fill / raise
            GuidanceDirection.LOWER -> Color(0x55E53935)    // red  = cut / lower
            GuidanceDirection.ON_GRADE -> Color(0x554CAF50) // green = on grade
        }
        Canvas(Modifier.fillMaxSize()) {
            drawRect(color = fill)
            val cx = size.width / 2f; val cy = size.height / 2f; val s = size.minDimension * 0.06f
            drawPath(
                path = Path().apply {
                    moveTo(cx, cy - s); lineTo(cx - s, cy + s); lineTo(cx + s, cy + s); close()
                },
                color = Color.White
            )
            drawLine(Color(0x88FFFFFF), Offset(0f, cy), Offset(size.width, cy), strokeWidth = 2f)
        }
        Text(
            when {
                !state.hasSolution -> state.autoInhibitReason ?: "waiting for GNSS"
                state.direction == GuidanceDirection.ON_GRADE -> "ON GRADE"
                else -> "${abs(state.cutFillMm)} mm ${if (state.direction == GuidanceDirection.RAISE) "FILL" else "CUT"}"
            },
            color = Color.White, fontWeight = FontWeight.Bold
        )
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
