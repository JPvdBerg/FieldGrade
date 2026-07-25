package com.fieldgrade.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.fieldgrade.app.ui.FieldGradeScreen

// Android entry point only. The operator screen itself lives in ui/FieldGradeScreen.kt
// so it can be rendered unchanged by the desktop dev harness (desktop/).
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { FieldGradeScreen() } }
    }
}
