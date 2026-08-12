plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.fieldgrade.app"
    compileSdk = 35
    // Pinned to what is installed locally; AGP 8.5.2 would otherwise pull
    // build-tools 34.0.0, which needs a licence acceptance + download.
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.fieldgrade.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures { compose = true }
    // Kotlin 2.0: Compose compiler version is governed by the kotlin.plugin.compose
    // plugin, so the old composeOptions{kotlinCompilerExtensionVersion} block is gone.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.all {
            // The simulation harness prints a trace; without this Gradle swallows it.
            it.testLogging { showStandardStreams = true }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.compose.ui:ui:1.7.0")
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    testImplementation("junit:junit:4.13.2")
}
