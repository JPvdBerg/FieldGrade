package com.fieldgrade.server.domain

import java.security.SecureRandom
import java.time.Instant

/**
 * The domain, as plain data.
 *
 * One job: describe what exists. No SQL, no HTTP, no validation of other
 * people's rules — repositories and services own those.
 */

data class Org(val id: String, val name: String, val createdAt: Instant)

enum class UserRole {
    /** Can add machines, subscribe, and pair devices. */
    OWNER,

    /** Can see the fleet, but not spend money. */
    OPERATOR;

    val wire: String get() = name.lowercase()

    companion object {
        fun parse(value: String): UserRole? =
            entries.firstOrNull { it.wire == value.lowercase() }
    }
}

data class User(
    val id: String,
    val orgId: String,
    val email: String,
    val passwordHash: String,
    val role: UserRole,
    val createdAt: Instant
)

data class Machine(
    val id: String,
    val orgId: String,
    /** Stamped on the controller. Globally unique — one physical machine, one row. */
    val serial: String,
    val name: String,
    val createdAt: Instant
)

enum class SubscriptionStatus {
    TRIALING, ACTIVE, PAST_DUE, CANCELLED;

    val wire: String get() = name.lowercase()

    /**
     * Whether this status should keep issuing licences.
     *
     * `PAST_DUE` deliberately still does. A failed card must not stop a machine
     * the same day — the licence's own grace window is what actually decides
     * when work is affected, and it is measured in weeks. Blocking here as well
     * would collapse that window to zero and undo the whole design.
     */
    val issuesLicences: Boolean get() = this != CANCELLED

    companion object {
        fun parse(value: String): SubscriptionStatus? =
            entries.firstOrNull { it.wire == value.lowercase() }
    }
}

data class Subscription(
    val id: String,
    val orgId: String,
    val machineId: String,
    val plan: String,
    val status: SubscriptionStatus,
    /** Paid through. The licence expiry is derived from this and nothing else. */
    val currentPeriodEnd: Instant,
    val provider: String?,
    val providerRef: String?
)

data class PairingCode(
    val code: String,
    val machineId: String,
    val expiresAt: Instant,
    val redeemedAt: Instant?
) {
    fun isUsable(now: Instant): Boolean = redeemedAt == null && now.isBefore(expiresAt)
}

/**
 * Identifiers.
 *
 * Prefixed and random rather than sequential: a prefix makes a stray id in a log
 * self-describing, and randomness stops anyone inferring how many customers
 * exist from the number on their invoice.
 */
object Ids {
    private val random = SecureRandom()
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

    fun org() = generate("org")
    fun user() = generate("usr")
    fun machine() = generate("mach")
    fun subscription() = generate("sub")
    fun licence() = generate("lic")
    fun webhookEvent() = generate("evt")

    fun generate(prefix: String, length: Int = 12): String {
        val sb = StringBuilder(prefix.length + 1 + length)
        sb.append(prefix).append('_')
        repeat(length) { sb.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }
}
