package com.fieldgrade.server.licence

import com.fieldgrade.server.db.Database
import com.fieldgrade.server.db.MachineRepository
import com.fieldgrade.server.domain.Ids
import com.fieldgrade.server.domain.Machine
import com.fieldgrade.shared.LicenceClaim
import java.sql.Connection
import java.time.Clock
import java.time.Instant

/**
 * Decides what licence a machine is entitled to, and records it.
 *
 * One job: subscription state in, signed token out. It does no cryptography
 * ([LicenceSigner] does) and no persistence ([MachineRepository] does).
 *
 * The rule is deliberately small: **a licence expires when the subscription is
 * paid through, and not a moment earlier.** All the tolerance for a failed card
 * or a farmer out of signal lives in the token's grace window, which the tablet
 * evaluates offline. Adding a second helping of leniency here would make the
 * real cut-off impossible to reason about — there would be two, in different
 * places, in different units.
 */
class LicenceService(
    private val database: Database,
    private val machines: MachineRepository,
    private val signer: LicenceSigner,
    private val clock: Clock = Clock.systemUTC(),
    private val graceDays: Int = LicenceClaim.DEFAULT_GRACE_DAYS
) {

    sealed interface IssueResult {
        data class Issued(val token: String, val expiresAt: Instant) : IssueResult
        /** No subscription at all, or a cancelled one. */
        data object NotEntitled : IssueResult
    }

    /** Issue against the machine's current subscription. */
    fun issueFor(machineId: String): IssueResult =
        database.transaction { c ->
            val machine = machines.findMachine(c, machineId) ?: return@transaction IssueResult.NotEntitled
            issueFor(c, machine)
        }

    /**
     * Issue within a caller's transaction — used by pairing, where the licence
     * and the device key must be created together or not at all.
     */
    fun issueFor(c: Connection, machine: Machine): IssueResult {
        val subscription = machines.findSubscriptionByMachine(c, machine.id)
            ?: return IssueResult.NotEntitled
        if (!subscription.status.issuesLicences) return IssueResult.NotEntitled

        val now = clock.instant()
        val claim = LicenceClaim(
            machineId = machine.id,
            orgId = machine.orgId,
            plan = subscription.plan,
            issuedAtMs = now.toEpochMilli(),
            expiresAtMs = subscription.currentPeriodEnd.toEpochMilli(),
            graceDays = graceDays
        )
        val token = signer.token(claim)

        machines.insertLicence(
            c = c,
            id = Ids.licence(),
            machineId = machine.id,
            token = token,
            issuedAt = now,
            expiresAt = subscription.currentPeriodEnd,
            graceDays = graceDays
        )
        return IssueResult.Issued(token, subscription.currentPeriodEnd)
    }

    /**
     * The machine's current token, minting a fresh one if the stored one is
     * older than the subscription it should reflect.
     *
     * Re-issuing rather than returning a stale token matters after a renewal:
     * the tablet asks, gets the extended expiry, and can then go offline again
     * for another month.
     */
    fun currentToken(machineId: String): String? =
        when (val issued = issueFor(machineId)) {
            is IssueResult.Issued -> issued.token
            IssueResult.NotEntitled -> database.read { c -> machines.findLatestLicence(c, machineId) }
        }
}
