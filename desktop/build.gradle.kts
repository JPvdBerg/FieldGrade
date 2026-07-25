import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.10"
    id("org.jetbrains.compose") version "1.6.11"
}

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

// The desktop harness renders the SAME operator screen as the Android app by pulling in
// the pure-Kotlin logic packages + the shared Compose UI directly from the android module.
// It deliberately does NOT include MainActivity.kt (the only Android-coupled file) or the
// transport/logging/gpsfile packages (not needed to drive the demo UI).
val sharedRoot = "../android/app/src/main/java/com/fieldgrade/app"

kotlin {
    jvmToolchain(17)
    sourceSets["main"].kotlin.apply {
        srcDir("$sharedRoot/control")
        srcDir("$sharedRoot/geom")
        srcDir("$sharedRoot/gnss")
        srcDir("$sharedRoot/surface")
        srcDir("$sharedRoot/ui")
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.foundation)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
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
