import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.10"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.10"
    id("org.jetbrains.compose") version "1.6.11"
}

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

// The desktop harness renders the SAME operator screen as the Android app by pulling in
// the pure-Kotlin logic packages + the shared Compose UI directly from the android module.
// It deliberately does NOT include MainActivity.kt — the only Android-coupled file.
//
// The transport and sim packages are included so the harness can close the whole loop
// offline: real design surface -> replayed RTK track -> guidance -> control -> a
// simulated ESP32 -> a modelled blade -> back into guidance.
val sharedRoot = "../android/app/src/main/java/com/fieldgrade/app"

kotlin {
    jvmToolchain(17)
    sourceSets["main"].kotlin.apply {
        srcDir("$sharedRoot/control")
        srcDir("$sharedRoot/design")
        srcDir("$sharedRoot/geom")
        srcDir("$sharedRoot/gnss")
        srcDir("$sharedRoot/logging")
        srcDir("$sharedRoot/sim")
        srcDir("$sharedRoot/surface")
        srcDir("$sharedRoot/survey")
        srcDir("$sharedRoot/transport")
        srcDir("$sharedRoot/ui")
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.foundation)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
}

compose.desktop {
    application {
        mainClass = "com.fieldgrade.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = "FieldGradeHarness"
            packageVersion = "1.0.0"
        }
    }
}
