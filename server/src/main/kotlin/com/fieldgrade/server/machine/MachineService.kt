package com.fieldgrade.server.machine

import com.fieldgrade.server.auth.SessionTokens
import com.fieldgrade.server.db.Database
import com.fieldgrade.server.db.MachineRepository
import com.fieldgrade.server.domain.Ids
import com.fieldgrade.server.domain.Machine
import com.fieldgrade.server.domain.PairingCode
import com.fieldgrade.server.domain.PairingCodes
import com.fieldgrade.server.domain.Subscription
import com.fieldgrade.server.domain.SubscriptionStatus
import com.fieldgrade.server.licence.LicenceService
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Adding machines, and getting credentials onto a tablet.
 *
 * One job: the rules around a machine's lifecycle. No SQL, no signing, no HTTP.
 *
 * Pairing is the interesting part. A tablet needs **two** things and they are
 * deliberately different in kind:
 *
 *  - a **licence token** — a signed claim, not a secret, verifiable offline
 *    forever, which gates nothing but new design downloads;
 *  - a **device key** — an actual secret, used to authenticate uploads, stored
 *    here only as a hash, revocable the instant a tablet goes missing.
 *
 * One short-lived code exchanges for both, once, and then the tablet never needs
 * this server again.
 */
class MachineService(
    private val database: Database,
    private val machines: MachineRepository,
    private val licences: LicenceService,
    private val clock: Clock = Clock.systemUTC(),
    private val pairingLifetime: Duration = DEFAULT_PAIRING_LIFETIME,
    private val trialLength: Duration = DEFAULT_TRIAL
) {

    sealed interface AddResult {
        data class Success(val machine: Machine) : AddResult
        /** Serials are globally unique: one physical machine, one owner. */
        data object SerialTaken : AddResult
        data class Invalid(val reason: String) : AddResult
    }

    /**
     * Register a machine to an organisation and start its trial.
     *
     * A trial rather than nothing, so a new customer can pair and work the same
     * afternoon without a card. The subscription row exists from the start,
     * which means every later billing event is an update rather than a special
     * case for "first payment".
     */
    fun addMachine(orgId: String, serial: String, name: String): AddResult {
        val cleanSerial = serial.trim().uppercase()
        val cleanName = name.trim()
        when {
            cleanSerial.isBlank() -> return AddResult.Invalid("serial is required")
            cleanSerial.length < 4 -> return AddResult.Invalid("serial looks too short")
            cleanName.isBlank() -> return AddResult.Invalid("give the machine a name")
        }

        val now = clock.instant()
        return try {
            database.transaction { c ->
                if (machines.findMachineBySerial(c, cleanSerial) != null) {
                    return@transaction AddResult.SerialTaken
                }
                val machine = Machine(Ids.machine(), orgId, cleanSerial, cleanName, now)
                machines.insertMachine(c, machine)
                machines.upsertSubscription(
                    c,
                    Subscription(
                        id = Ids.subscription(),
                        orgId = orgId,
                        machineId = machine.id,
                        plan = DEFAULT_PLAN,
                        status = SubscriptionStatus.TRIALING,
                        currentPeriodEnd = now.plus(trialLength),
                        provider = null,
                        providerRef = null
                    )
                )
                AddResult.Success(machine)
            }
        } catch (e: Exception) {
            if (isUniqueViolation(e)) AddResult.SerialTaken else throw e
        }
    }

    fun listMachines(orgId: String): List<MachineSummary> =
        database.read { c ->
            machines.listMachines(c, orgId).map { machine ->
                MachineSummary(machine, machines.findSubscriptionByMachine(c, machine.id))
            }
        }

    data class MachineSummary(val machine: Machine, val subscription: Subscription?)

    /** Look up a machine, refusing to cross organisation boundaries. */
    fun findOwned(orgId: String, machineId: String): Machine? =
        database.read { c ->
            machines.findMachine(c, machineId)?.takeIf { it.orgId == orgId }
        }

    // ---------------------------------------------------------------- pairing

    /**
     * Mint a short code for the owner to read out or type in.
     *
     * @return the code in display form. It is not stored anywhere retrievable
     *         afterwards in a friendlier shape — if it is lost, issue another.
     */
    fun createPairingCode(machineId: String): String =
        database.transaction { c ->
            val code = PairingCodes.generate()
            machines.insertPairingCode(
                c, PairingCode(code, machineId, clock.instant().plus(pairingLifetime), null)
            )
            code
        }

    sealed interface PairResult {
        /**
         * @param licenceToken verifiable offline; may already be expired if the
         *        subscription lapsed, which the tablet reports rather than hides.
         * @param deviceKey shown exactly once — only its hash is kept.
         */
        data class Success(
            val machine: Machine,
            val licenceToken: String?,
            val deviceKey: String
        ) : PairResult

        /** Wrong, expired, or already used. Deliberately one answer for all three. */
        data object Rejected : PairResult
    }

    /**
     * Exchange a pairing code for credentials.
     *
     * Redemption, key creation and licence issue happen in one transaction: a
     * half-paired tablet holding a device key but no licence, or a code burned
     * with nothing handed back, would both need manual repair.
     *
     * A machine with no valid subscription still pairs, and gets a null licence.
     * Refusing to pair would leave the operator staring at a tablet that cannot
     * even tell them why — far better to pair, then say "subscription expired".
     */
    fun redeemPairingCode(input: String): PairResult {
        val code = PairingCodes.normalise(input) ?: return PairResult.Rejected
        val now = clock.instant()

        return database.transaction { c ->
            // Atomic claim: two tablets racing the same code, one winner.
            if (!machines.redeemPairingCode(c, code, now)) return@transaction PairResult.Rejected

            val pairing = machines.findPairingCode(c, code) ?: return@transaction PairResult.Rejected
            val machine = machines.findMachine(c, pairing.machineId)
                ?: return@transaction PairResult.Rejected

            val deviceKey = SessionTokens.generate()
            machines.insertDeviceKey(c, SessionTokens.fingerprint(deviceKey), machine.id)

            val licenceToken = when (val issued = licences.issueFor(c, machine)) {
                is LicenceService.IssueResult.Issued -> issued.token
                LicenceService.IssueResult.NotEntitled -> null
            }
            PairResult.Success(machine, licenceToken, deviceKey)
        }
    }

    /** Resolve an upload credential to its machine, and note that it called in. */
    fun authenticateDevice(deviceKey: String?): Machine? {
        if (deviceKey.isNullOrBlank()) return null
        val hash = SessionTokens.fingerprint(deviceKey)
        return database.transaction { c ->
            machines.findMachineByDeviceKey(c, hash)?.also {
                machines.touchDeviceKey(c, hash, clock.instant())
            }
        }
    }

    /** The "tablet was stolen" button. Licences are unaffected by design. */
    fun revokeDeviceKeys(machineId: String): Int =
        database.transaction { c -> machines.revokeDeviceKeys(c, machineId, clock.instant()) }

    private fun isUniqueViolation(e: Exception): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is java.sql.SQLException && cause.sqlState == "23505") return true
            cause = cause.cause
        }
        return false
    }

    companion object {
        const val DEFAULT_PLAN = "per_machine_monthly"

        /** Long enough to walk to the machine, short enough to matter. */
        val DEFAULT_PAIRING_LIFETIME: Duration = Duration.ofMinutes(15)

        /** Enough to get through a first job before anyone asks for a card. */
        val DEFAULT_TRIAL: Duration = Duration.ofDays(30)
    }
}
