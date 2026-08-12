package com.fieldgrade.server

import com.fieldgrade.server.db.Database
import com.fieldgrade.server.licence.LicenceSigner
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.time.Clock

/**
 * Runs the whole control plane on a laptop with nothing installed.
 *
 * `./gradlew devServer` starts a real Postgres (embedded, no Docker daemon
 * needed), migrates it, generates a throwaway signing key, and serves the web
 * UI and API on http://localhost:8080.
 *
 * It lives in the test source set on purpose: embedded Postgres is a test
 * dependency and has no business on the production classpath. The application
 * itself is the same [module] that `main` wires up — this only replaces where
 * the database comes from.
 *
 * Everything is discarded on exit. It is for seeing the thing work, not for
 * keeping anything.
 */
fun main() {
    println("starting embedded postgres…")
    val pg = EmbeddedPostgres.builder().start()
    val database = Database(pg.postgresDatabase)
    println("migrations applied: ${database.migrate()}")

    val keys = LicenceSigner.generateKeyPair()
    val signer = LicenceSigner.fromEncodedPrivateKey(keys.privateKeyBase64Url)!!

    println(
        """
        |
        |  FieldGrade control plane — DEVELOPMENT
        |  ---------------------------------------------------------------
        |  web    http://localhost:8080/signup
        |  api    http://localhost:8080/api/v1
        |  db     embedded postgres, discarded on exit
        |
        |  Tablet public key for this session:
        |  ${keys.publicKeyBase64Url}
        |
        |  Licences issued now stop verifying when this process restarts.
        |  ---------------------------------------------------------------
        """.trimMargin()
    )

    Runtime.getRuntime().addShutdownHook(Thread { runCatching { pg.close() } })
    embeddedServer(Netty, port = 8080) {
        module(database, signer, Clock.systemUTC())
    }.start(wait = true)
}
