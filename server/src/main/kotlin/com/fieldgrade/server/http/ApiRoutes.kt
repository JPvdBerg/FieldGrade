package com.fieldgrade.server.http

import com.fieldgrade.server.auth.AccountService
import com.fieldgrade.server.domain.User
import com.fieldgrade.server.licence.LicenceService
import com.fieldgrade.server.machine.MachineService
import com.fieldgrade.shared.AddMachineRequest
import com.fieldgrade.shared.ErrorResponse
import com.fieldgrade.shared.LicenceResponse
import com.fieldgrade.shared.LoginRequest
import com.fieldgrade.shared.MachineListResponse
import com.fieldgrade.shared.MachineResponse
import com.fieldgrade.shared.PairRequest
import com.fieldgrade.shared.PairResponse
import com.fieldgrade.shared.PairingCodeResponse
import com.fieldgrade.shared.RegisterRequest
import com.fieldgrade.shared.SessionResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.Clock

/**
 * The JSON API. Two audiences, two credentials.
 *
 * One job: translate HTTP to service calls and back. No business rules live
 * here; if a decision is being made in this file, it is in the wrong place.
 *
 *  - **People** (the web UI, and later an app screen) authenticate with a
 *    session token from login.
 *  - **Tablets** authenticate with a device key from pairing.
 *
 * They are separate on purpose. A stolen tablet must be revocable without
 * touching the owner's login, and a leaked session must not grant a machine's
 * upload rights.
 */
fun Route.apiRoutes(
    accounts: AccountService,
    machines: MachineService,
    licences: LicenceService,
    clock: Clock
) {
    route("/api/v1") {

        // ------------------------------------------------------------ accounts

        post("/auth/register") {
            val body = call.receive<RegisterRequest>()
            when (val result = accounts.register(body.orgName, body.email, body.password)) {
                is AccountService.RegisterResult.Success -> call.respond(
                    HttpStatusCode.Created,
                    SessionResponse(
                        sessionToken = result.sessionToken,
                        userId = result.user.id,
                        orgId = result.org.id,
                        email = result.user.email
                    )
                )
                AccountService.RegisterResult.EmailTaken -> call.fail(
                    HttpStatusCode.Conflict, ErrorResponse.CONFLICT,
                    "that email address is already registered"
                )
                is AccountService.RegisterResult.Invalid -> call.fail(
                    HttpStatusCode.BadRequest, ErrorResponse.INVALID, result.reason
                )
            }
        }

        post("/auth/login") {
            val body = call.receive<LoginRequest>()
            val token = accounts.login(body.email, body.password)
            if (token == null) {
                // One message for unknown address and wrong password alike.
                call.fail(
                    HttpStatusCode.Unauthorized, ErrorResponse.UNAUTHORISED,
                    "email or password is incorrect"
                )
            } else {
                val user = accounts.authenticate(token)!!
                call.respond(SessionResponse(token, user.id, user.orgId, user.email))
            }
        }

        post("/auth/logout") {
            accounts.logout(call.bearerToken())
            call.respond(HttpStatusCode.NoContent)
        }

        // ------------------------------------------------------------ machines

        get("/machines") {
            val user = call.requireUser(accounts) ?: return@get
            call.respond(
                MachineListResponse(
                    machines.listMachines(user.orgId).map { it.toResponse() }
                )
            )
        }

        post("/machines") {
            val user = call.requireUser(accounts) ?: return@post
            val body = call.receive<AddMachineRequest>()
            when (val result = machines.addMachine(user.orgId, body.serial, body.name)) {
                is MachineService.AddResult.Success -> {
                    // Read back rather than synthesising the response: creating a
                    // machine also starts its trial, and POST must describe the
                    // same resource GET does or clients will disagree about state.
                    val created = machines.listMachines(user.orgId)
                        .first { it.machine.id == result.machine.id }
                    call.respond(HttpStatusCode.Created, created.toResponse())
                }
                MachineService.AddResult.SerialTaken -> call.fail(
                    HttpStatusCode.Conflict, ErrorResponse.CONFLICT,
                    "that serial is already registered"
                )
                is MachineService.AddResult.Invalid -> call.fail(
                    HttpStatusCode.BadRequest, ErrorResponse.INVALID, result.reason
                )
            }
        }

        post("/machines/{id}/pairing-code") {
            val user = call.requireUser(accounts) ?: return@post
            val id = call.parameters["id"].orEmpty()
            // Scoped to the caller's org: an id from another customer must read
            // as "not found", not as "forbidden", which would confirm it exists.
            val machine = machines.findOwned(user.orgId, id) ?: return@post call.fail(
                HttpStatusCode.NotFound, ErrorResponse.NOT_FOUND, "no such machine"
            )
            val code = machines.createPairingCode(machine.id)
            call.respond(
                PairingCodeResponse(
                    code = code,
                    expiresAtIso = clock.instant()
                        .plus(MachineService.DEFAULT_PAIRING_LIFETIME).toString()
                )
            )
        }

        // ------------------------------------------------------------ tablets

        /**
         * The one call a tablet makes while a human is standing next to it.
         * Unauthenticated by design — the code *is* the credential, which is why
         * it is short-lived and single-use.
         */
        post("/pair") {
            val body = call.receive<PairRequest>()
            when (val result = machines.redeemPairingCode(body.code)) {
                is MachineService.PairResult.Success -> call.respond(
                    PairResponse(
                        machineId = result.machine.id,
                        serial = result.machine.serial,
                        name = result.machine.name,
                        deviceKey = result.deviceKey,
                        licenceToken = result.licenceToken
                    )
                )
                MachineService.PairResult.Rejected -> call.fail(
                    HttpStatusCode.BadRequest, ErrorResponse.INVALID,
                    "that pairing code is not valid, has expired, or has already been used"
                )
            }
        }

        /**
         * Refresh a licence after a renewal. The tablet calls this when it
         * happens to have signal; it must never need to.
         */
        get("/licence") {
            val machine = machines.authenticateDevice(call.bearerToken())
                ?: return@get call.fail(
                    HttpStatusCode.Unauthorized, ErrorResponse.UNAUTHORISED, "unknown device key"
                )
            call.respond(LicenceResponse(licences.currentToken(machine.id), machine.id))
        }
    }
}

// ---------------------------------------------------------------- helpers

/** `Authorization: Bearer <token>`, or null. */
fun ApplicationCall.bearerToken(): String? =
    request.headers["Authorization"]
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substring(7)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/** Resolve the caller, or answer 401 and return null so the route can bail out. */
suspend fun ApplicationCall.requireUser(accounts: AccountService): User? {
    val user = accounts.authenticate(bearerToken())
    if (user == null) {
        fail(HttpStatusCode.Unauthorized, ErrorResponse.UNAUTHORISED, "sign in first")
    }
    return user
}

suspend fun ApplicationCall.fail(status: HttpStatusCode, code: String, message: String) {
    respond(status, ErrorResponse(code, message))
}

private fun MachineService.MachineSummary.toResponse() = MachineResponse(
    id = machine.id,
    serial = machine.serial,
    name = machine.name,
    subscriptionStatus = subscription?.status?.wire,
    paidThrough = subscription?.currentPeriodEnd?.toString()
)
