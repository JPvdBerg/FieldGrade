package com.fieldgrade.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FieldGradeScreen() }
    }
}

@Composable
fun FieldGradeScreen() {
    var auto by remember { mutableStateOf(false) }
    var nudge by remember { mutableIntStateOf(0) }
    MaterialTheme {
        Row(Modifier.fillMaxSize().padding(16.dp)) {
            Card(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("CUT / FILL MAP\nDesign file not loaded", style = MaterialTheme.typography.headlineMedium)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.width(260.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Cut / Fill", style = MaterialTheme.typography.titleLarge)
                Text("+0 mm", style = MaterialTheme.typography.displayMedium)
                Button(onClick = { auto = !auto }, Modifier.fillMaxWidth()) { Text(if (auto) "AUTO ON" else "AUTO OFF") }
                Button(onClick = { nudge += 1 }, Modifier.fillMaxWidth()) { Text("NUDGE UP") }
                Button(onClick = { nudge -= 1 }, Modifier.fillMaxWidth()) { Text("NUDGE DOWN") }
                OutlinedButton(onClick = { nudge = 0 }, Modifier.fillMaxWidth()) { Text("REBENCH") }
                Text("Nudge: $nudge mm")
                Text("RTK: NO FIX")
                Text("Controller: DISCONNECTED")
            }
        }
    }
}
