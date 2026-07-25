plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.10" apply false
    // Kotlin 2.0: Compose compiler and serializer are Kotlin plugins.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.10" apply false
}
