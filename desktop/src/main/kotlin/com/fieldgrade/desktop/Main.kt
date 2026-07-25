package com.fieldgrade.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.fieldgrade.app.ui.FieldGradeScreen

// Desktop dev harness: opens the real operator screen in a resizable JVM window.
// No emulator, no VM. Driven by the same DemoGnssSource the Android app uses, so the
// cut/fill map animates on its own. Run with:  ./gradlew run
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "FieldGrade — Operator (desktop harness)",
        state = rememberWindowState(size = DpSize(1280.dp, 800.dp)),
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            FieldGradeScreen()
        }
    }
}
