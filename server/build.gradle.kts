// Deliberately a newer Kotlin than android/ and desktop/, which are pinned at
// 2.0.10 by the Compose compiler plugin. The server is a standalone build with
// no Compose in it, and Ktor 3.5 ships Kotlin 2.3 metadata, so pinning it back
// would mean an older Ktor for no benefit. The shared licence source compiles
// cleanly under both.
plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.0"
    application
}

repositories {
    mavenCentral()
}

// The licence token format is shared verbatim with the tablet: the server signs
// it and the Android app verifies it offline. Sharing the source rather than
// re-describing the format on both sides is what stops the two drifting — the
// same reason desktop/ pulls the operator UI straight out of android/.
kotlin {
    jvmToolchain(17)
    sourceSets["main"].kotlin.srcDir("../shared/src/main/kotlin")
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:3.5.2")
    implementation("io.ktor:ktor-server-netty-jvm:3.5.2")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:3.5.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:3.5.2")
    implementation("io.ktor:ktor-server-status-pages-jvm:3.5.2")
    implementation("io.ktor:ktor-server-call-logging-jvm:3.5.2")
    implementation("io.ktor:ktor-client-core-jvm:3.5.2")
    implementation("io.ktor:ktor-client-cio-jvm:3.5.2")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("ch.qos.logback:logback-classic:1.6.2")

    // Plain JDBC + Flyway rather than an ORM: the schema is small, the SQL is
    // then readable by anyone, and Exposed has just landed a 1.x major whose API
    // churn buys us nothing here.
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.postgresql:postgresql:42.7.13")
    implementation("org.flywaydb:flyway-core:13.2.0")
    implementation("org.flywaydb:flyway-database-postgresql:13.2.0")

    implementation("de.mkammerer:argon2-jvm:2.12")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.5.2")
    // A real Postgres for tests without needing the Docker daemon running.
    testImplementation("io.zonky.test:embedded-postgres:2.2.2")
    testImplementation(enforcedPlatform("io.zonky.test.postgres:embedded-postgres-binaries-bom:16.2.0"))
}

application {
    mainClass.set("com.fieldgrade.server.MainKt")
}

// Run the whole control plane locally with nothing installed:
//   ./gradlew devServer
// Uses the test runtime because embedded Postgres is a test dependency and has
// no business on the production classpath.
tasks.register<JavaExec>("devServer") {
    group = "application"
    description = "Run the server against a throwaway embedded Postgres on :8080"
    mainClass.set("com.fieldgrade.server.DevServerKt")
    classpath = sourceSets["test"].runtimeClasspath
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
}
