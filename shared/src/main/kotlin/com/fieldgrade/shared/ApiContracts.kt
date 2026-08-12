package com.fieldgrade.shared

import kotlinx.serialization.Serializable

/**
 * The wire format between tablet and server.
 *
 * One job: describe the messages. No transport, no validation, no behaviour.
 *
 * This file is compiled into **both** the server and the Android app, so the
 * two cannot drift. A field renamed here fails to compile on the side that has
 * not caught up, which is the entire reason for sharing the source rather than
 * writing the shapes down twice and hoping.
 */

// ---------------------------------------------------------------- accounts

@Serializable
data class RegisterRequest(val orgName: String, val email: String, val password: String)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class SessionResponse(
    val sessionToken: String,
    val userId: String,
    val orgId: String,
    val email: String
)

// ---------------------------------------------------------------- machines

@Serializable
data class AddMachineRequest(val serial: String, val name: String)

@Serializable
data class MachineResponse(
    val id: String,
    val serial: String,
    val name: String,
    val subscriptionStatus: String?,
    /** ISO-8601, or null when there is no subscription at all. */
    val paidThrough: String?
)

@Serializable
data class MachineListResponse(val machines: List<MachineResponse>)

@Serializable
data class PairingCodeResponse(
    /** Display form, e.g. `K7M2-9QXP`. */
    val code: String,
    val expiresAtIso: String
)

// ---------------------------------------------------------------- pairing

@Serializable
data class PairRequest(val code: String)

/**
 * Everything a tablet needs, handed over once.
 *
 * [deviceKey] is shown exactly once and never retrievable again — the server
 * keeps only its hash. If it is lost, pair again.
 *
 * [licenceToken] may be null when the machine has no live subscription. That is
 * not a pairing failure: the tablet pairs anyway so it can *tell the operator
 * why* it cannot download designs, rather than failing mutely.
 */
@Serializable
data class PairResponse(
    val machineId: String,
    val serial: String,
    val name: String,
    val deviceKey: String,
    val licenceToken: String?
)

@Serializable
data class LicenceResponse(val licenceToken: String?, val machineId: String)

// ---------------------------------------------------------------- errors

/**
 * One error shape for every failure.
 *
 * [code] is for the client to branch on; [message] is for a human. Clients must
 * never parse [message] — it is free to change wording, and will.
 */
@Serializable
data class ErrorResponse(val code: String, val message: String) {
    companion object {
        const val INVALID = "invalid"
        const val UNAUTHORISED = "unauthorised"
        const val NOT_FOUND = "not_found"
        const val CONFLICT = "conflict"
        const val RATE_LIMITED = "rate_limited"
    }
}
