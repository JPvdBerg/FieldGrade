package com.fieldgrade.server

import com.fieldgrade.server.auth.AccountService
import com.fieldgrade.server.auth.PasswordHasher
import com.fieldgrade.server.db.AccountRepository
import com.fieldgrade.server.db.Database
import com.fieldgrade.server.db.MachineRepository
import com.fieldgrade.server.http.apiRoutes
import com.fieldgrade.server.http.webRoutes
import com.fieldgrade.server.licence.LicenceService
import com.fieldgrade.server.licence.LicenceSigner
import com.fieldgrade.server.machine.MachineService
import com.fieldgrade.shared.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * Configuration, from the environment.
 *
 * Secrets are read from env vars and never defaulted in production: a server
 * that silently boots with a development signing key would issue licences no
 * tablet in the field will accept, and the failure would surface days later on
 * a machine rather than here at startup.
 */
data class Config(
    val port: Int,
    val jdbcUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val licencePrivateKey: String?,
    val developmentMode: Boolean
) {
    companion object {
        fun fromEnvironment(): Config = Config(
            port = System.getenv("PORT")?.toIntOrNull() ?: 8080,
            jdbcUrl = System.getenv("DATABASE_URL")
                ?: "jdbc:postgresql://localhost:5432/fieldgrade",
            dbUser = System.getenv("DATABASE_USER") ?: "fieldgrade",
            dbPassword = System.getenv("DATABASE_PASSWORD") ?: "fieldgrade",
            licencePrivateKey = System.getenv("LICENCE_PRIVATE_KEY"),
            developmentMode = System.getenv("FIELDGRADE_ENV")?.lowercase() != "production"
        )
    }
}

private val log = LoggerFactory.getLogger("fieldgrade")

fun main() {
    val config = Config.fromEnvironment()

    val database = Database.pooled(config.jdbcUrl, config.dbUser, config.dbPassword)
    val applied = database.migrate()
    log.info("schema up to date ({} migrations applied this boot)", applied)

    val signer = resolveSigner(config)

    embeddedServer(Netty, port = config.port) {
        module(database, signer, Clock.systemUTC())
    }.start(wait = true)

    // Note: no shutdown hook closing the pool. Ktor's own graceful shutdown
    // handles in-flight requests, and the JVM exiting releases the sockets.
}

/**
 * Wire everything. Separated from [main] so tests can start the same
 * application against an embedded database without a real port or environment.
 */
fun Application.module(database: Database, signer: LicenceSigner, clock: Clock) {
    val accountRepository = AccountRepository()
    val machineRepository = MachineRepository()

    val accounts = AccountService(database, accountRepository, PasswordHasher(), clock)
    val licences = LicenceService(database, machineRepository, signer, clock)
    val machines = MachineService(database, machineRepository, licences, clock)

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            // Log the detail, return none of it. A stack trace in a response
            // body is a gift to anyone probing the service.
            log.error("unhandled failure on ${call.request.local.uri}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("internal", "something went wrong")
            )
        }
    }

    routing {
        apiRoutes(accounts, machines, licences, clock)
        webRoutes(accounts, machines)
    }
}

/**
 * The licence signing key.
 *
 * In production a missing key is fatal — better to refuse to start than to mint
 * licences with an ephemeral key that every tablet in the field will reject.
 * In development it generates one and prints the public half, so a new
 * contributor can run the whole system without being handed a secret.
 */
private fun resolveSigner(config: Config): LicenceSigner {
    config.licencePrivateKey?.let { encoded ->
        return LicenceSigner.fromEncodedPrivateKey(encoded)
            ?: error("LICENCE_PRIVATE_KEY is set but could not be parsed")
    }
    check(config.developmentMode) {
        "LICENCE_PRIVATE_KEY must be set in production — refusing to start with a throwaway key"
    }
    val generated = LicenceSigner.generateKeyPair()
    log.warn(
        "no LICENCE_PRIVATE_KEY set; generated a development keypair.\n" +
            "  LICENCE_PRIVATE_KEY={}\n" +
            "  public key for the tablet={}\n" +
            "  Licences issued now become invalid when this process restarts.",
        generated.privateKeyBase64Url, generated.publicKeyBase64Url
    )
    return LicenceSigner.fromEncodedPrivateKey(generated.privateKeyBase64Url)!!
}
