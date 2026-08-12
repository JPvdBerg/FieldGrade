package com.fieldgrade.server

import com.fieldgrade.server.auth.AccountService
import com.fieldgrade.server.auth.PasswordHasher
import com.fieldgrade.server.db.AccountRepository
import com.fieldgrade.server.db.Database
import com.fieldgrade.server.db.MachineRepository
import com.fieldgrade.server.domain.PairingCodes
import com.fieldgrade.server.licence.LicenceService
import com.fieldgrade.server.licence.LicenceSigner
import com.fieldgrade.server.machine.MachineService
import com.fieldgrade.shared.LicenceState
import com.fieldgrade.shared.LicenceVerifier
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * The whole path a real customer takes: sign up, add a machine, pair a tablet,
 * and end with a licence the tablet can check with no network at all.
 *
 * Runs against a real Postgres via embedded-postgres, so constraints, upserts
 * and the atomic pairing claim are exercised rather than mocked away.
 */
class FlowTest {

    private val pg = EmbeddedPostgres.builder().start()
    private val database = Database(pg.postgresDatabase).also { it.migrate() }

    private var now: Instant = Instant.parse("2026-08-12T18:00:00Z")
    private val clock = object : Clock() {
        override fun instant() = now
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId) = this
    }

    private val keys = LicenceSigner.generateKeyPair()
    private val signer = LicenceSigner.fromEncodedPrivateKey(keys.privateKeyBase64Url)!!
    private val tablet = LicenceVerifier.fromEncodedPublicKey(keys.publicKeyBase64Url)!!

    private val accounts = AccountService(
        database, AccountRepository(), PasswordHasher.forTests(), clock
    )
    private val machineRepo = MachineRepository()
    private val licences = LicenceService(database, machineRepo, signer, clock)
    private val machines = MachineService(database, machineRepo, licences, clock)

    @AfterTest fun tearDown() = pg.close()

    private fun registerOwner(email: String = "kobus@example.co.za") =
        accounts.register("Kobus Boerdery", email, "a-long-enough-password")
            as AccountService.RegisterResult.Success

    private fun addMachine(orgId: String, serial: String = "SN-0001") =
        machines.addMachine(orgId, serial, "Scraper 1")
            as MachineService.AddResult.Success

    // ---------------------------------------------------------------- the happy path

    @Test
    fun signup_to_a_licence_the_tablet_can_verify_offline() {
        val owner = registerOwner()
        val machine = addMachine(owner.org.id).machine

        val code = machines.createPairingCode(machine.id)
        val paired = machines.redeemPairingCode(code) as MachineService.PairResult.Success

        assertEquals(machine.id, paired.machine.id)
        assertNotNull(paired.licenceToken)

        // The whole point: from here the tablet needs nothing but the token and
        // a public key compiled into the app.
        val evaluation = tablet.evaluate(paired.licenceToken, now.toEpochMilli())
        assertEquals(LicenceState.ACTIVE, evaluation.state)
        assertTrue(evaluation.allowsNewDesignDownload)
        assertEquals(machine.id, evaluation.claim!!.machineId)
        assertEquals(owner.org.id, evaluation.claim!!.orgId)
    }

    @Test
    fun a_new_machine_starts_on_a_trial_so_the_first_job_can_happen_today() {
        val owner = registerOwner()
        val machine = addMachine(owner.org.id).machine
        val summary = machines.listMachines(owner.org.id).single()
        assertEquals("trialing", summary.subscription!!.status.wire)
        assertEquals(now.plus(MachineService.DEFAULT_TRIAL), summary.subscription!!.currentPeriodEnd)
    }

    // ---------------------------------------------------------------- pairing rules

    @Test
    fun a_pairing_code_works_exactly_once() {
        // A code read out over the phone must not be replayable by whoever
        // overheard it.
        val owner = registerOwner()
        val machine = addMachine(owner.org.id).machine
        val code = machines.createPairingCode(machine.id)

        assertTrue(machines.redeemPairingCode(code) is MachineService.PairResult.Success)
        assertTrue(machines.redeemPairingCode(code) is MachineService.PairResult.Rejected)
    }

    @Test
    fun an_expired_pairing_code_is_refused() {
        val owner = registerOwner()
        val machine = addMachine(owner.org.id).machine
        val code = machines.createPairingCode(machine.id)

        now = now.plus(MachineService.DEFAULT_PAIRING_LIFETIME).plusSeconds(1)
        assertTrue(machines.redeemPairingCode(code) is MachineService.PairResult.Rejected)
    }

    @Test
    fun codes_are_accepted_however_the_operator_types_them() {
        val owner = registerOwner()
        val machine = addMachine(owner.org.id).machine
        val code = machines.createPairingCode(machine.id)
        val messy = code.replace("-", "").lowercase().let { " $it " }
        assertTrue(machines.redeemPairingCode(messy) is MachineService.PairResult.Success)
    }

    @Test
    fun nonsense_codes_are_rejected_without_touching_the_database() {
        for (bad in listOf("", "abc", "0000-0000", "IIII-LLLL", "K7M29QXP1")) {
            assertTrue(
                machines.redeemPairingCode(bad) is MachineService.PairResult.Rejected,
                "accepted '$bad'"
            )
        }
    }

    @Test
    fun each_pairing_issues_a_fresh_device_key() {
        val owner = registerOwner()
        val machine = addMachine(owner.org.id).machine

        val first = machines.redeemPairingCode(machines.createPairingCode(machine.id))
                as MachineService.PairResult.Success
        val second = machines.redeemPairingCode(machines.createPairingCode(machine.id))
                as MachineService.PairResult.Success

        assertNotEquals(first.deviceKey, second.deviceKey)
        // Re-pairing a replacement tablet must not silently kill the old one;
        // that is what the explicit revoke is for.
        assertNotNull(machines.authenticateDevice(first.deviceKey))
        assertNotNull(machines.authenticateDevice(second.deviceKey))
    }

    // ---------------------------------------------------------------- device keys

    @Test
    fun a_device_key_authenticates_its_own_machine_and_no_other() {
        val owner = registerOwner()
        val a = addMachine(owner.org.id, "SN-A").machine
        val b = addMachine(owner.org.id, "SN-B").machine

        val keyA = (machines.redeemPairingCode(machines.createPairingCode(a.id))
                as MachineService.PairResult.Success).deviceKey

        assertEquals(a.id, machines.authenticateDevice(keyA)!!.id)
        assertNotEquals(b.id, machines.authenticateDevice(keyA)!!.id)
        assertNull(machines.authenticateDevice("not-a-real-key"))
        assertNull(machines.authenticateDevice(null))
    }

    @Test
    fun revoking_a_stolen_tablet_stops_uploads_but_not_grading() {
        // The two credentials are separate precisely so this is possible: kill
        // the upload key, leave the machine able to work.
        val owner = registerOwner()
        val machine = addMachine(owner.org.id).machine
        val paired = machines.redeemPairingCode(machines.createPairingCode(machine.id))
                as MachineService.PairResult.Success

        machines.revokeDeviceKeys(machine.id)

        assertNull(machines.authenticateDevice(paired.deviceKey))
        // The licence it already holds is untouched and still verifies offline.
        assertEquals(
            LicenceState.ACTIVE,
            tablet.evaluate(paired.licenceToken, now.toEpochMilli()).state
        )
    }

    // ---------------------------------------------------------------- money and time

    @Test
    fun a_lapsed_subscription_keeps_the_machine_working_through_grace() {
        val owner = registerOwner()
        val machine = addMachine(owner.org.id).machine
        val paired = machines.redeemPairingCode(machines.createPairingCode(machine.id))
                as MachineService.PairResult.Success

        // Ten days past the trial: payment has failed, nobody has noticed.
        val tenDaysPast = now.plus(MachineService.DEFAULT_TRIAL).plus(Duration.ofDays(10))
        val evaluation = tablet.evaluate(paired.licenceToken, tenDaysPast.toEpochMilli())

        assertEquals(LicenceState.GRACE, evaluation.state)
        assertTrue(evaluation.allowsNewDesignDownload, "grace must not stop work")
        assertNotNull(evaluation.message)
    }

    @Test
    fun past_grace_only_new_downloads_stop() {
        val owner = registerOwner()
        val machine = addMachine(owner.org.id).machine
        val paired = machines.redeemPairingCode(machines.createPairingCode(machine.id))
                as MachineService.PairResult.Success

        val wellPast = now.plus(MachineService.DEFAULT_TRIAL).plus(Duration.ofDays(45))
        val evaluation = tablet.evaluate(paired.licenceToken, wellPast.toEpochMilli())

        assertEquals(LicenceState.EXPIRED, evaluation.state)
        assertFalse(evaluation.allowsNewDesignDownload)
        // Still readable, so the tablet can name the machine and say why.
        assertTrue(evaluation.isUsable)
    }

    @Test
    fun refreshing_a_licence_picks_up_a_renewal() {
        val owner = registerOwner()
        val machine = addMachine(owner.org.id).machine
        machines.redeemPairingCode(machines.createPairingCode(machine.id))

        // A payment lands and extends the period.
        val extended = now.plus(Duration.ofDays(365))
        database.transaction { c ->
            val existing = machineRepo.findSubscriptionByMachine(c, machine.id)!!
            machineRepo.upsertSubscription(
                c,
                existing.copy(
                    status = com.fieldgrade.server.domain.SubscriptionStatus.ACTIVE,
                    currentPeriodEnd = extended,
                    provider = "peach",
                    providerRef = "reg_abc123"
                )
            )
        }

        val refreshed = licences.currentToken(machine.id)!!
        val claim = tablet.verify(refreshed)!!
        assertEquals(extended.toEpochMilli(), claim.expiresAtMs)
    }

    @Test
    fun a_cancelled_subscription_issues_no_new_licence() {
        val owner = registerOwner()
        val machine = addMachine(owner.org.id).machine
        database.transaction { c ->
            val existing = machineRepo.findSubscriptionByMachine(c, machine.id)!!
            machineRepo.upsertSubscription(
                c, existing.copy(status = com.fieldgrade.server.domain.SubscriptionStatus.CANCELLED)
            )
        }
        assertEquals(LicenceService.IssueResult.NotEntitled, licences.issueFor(machine.id))
    }

    @Test
    fun a_machine_with_no_live_subscription_still_pairs_so_it_can_explain_itself() {
        val owner = registerOwner()
        val machine = addMachine(owner.org.id).machine
        database.transaction { c ->
            val existing = machineRepo.findSubscriptionByMachine(c, machine.id)!!
            machineRepo.upsertSubscription(
                c, existing.copy(status = com.fieldgrade.server.domain.SubscriptionStatus.CANCELLED)
            )
        }
        val paired = machines.redeemPairingCode(machines.createPairingCode(machine.id))
        assertTrue(paired is MachineService.PairResult.Success)
        assertNull((paired as MachineService.PairResult.Success).licenceToken)
    }

    // ---------------------------------------------------------------- tenancy

    @Test
    fun one_organisation_cannot_reach_another_ones_machine() {
        val mine = registerOwner("me@example.co.za")
        val theirs = registerOwner("rival@example.co.za")
        val machine = addMachine(theirs.org.id, "SN-THEIRS").machine

        assertNull(machines.findOwned(mine.org.id, machine.id))
        assertNotNull(machines.findOwned(theirs.org.id, machine.id))
    }

    @Test
    fun a_serial_cannot_be_claimed_twice() {
        val a = registerOwner("a@example.co.za")
        val b = registerOwner("b@example.co.za")
        addMachine(a.org.id, "SN-SHARED")
        assertEquals(
            MachineService.AddResult.SerialTaken,
            machines.addMachine(b.org.id, "SN-SHARED", "Sneaky")
        )
    }

    @Test
    fun listing_machines_shows_only_your_own() {
        val mine = registerOwner("me2@example.co.za")
        val theirs = registerOwner("them2@example.co.za")
        addMachine(mine.org.id, "SN-MINE")
        addMachine(theirs.org.id, "SN-YOURS")
        assertEquals(listOf("SN-MINE"), machines.listMachines(mine.org.id).map { it.machine.serial })
    }

    // ---------------------------------------------------------------- accounts

    @Test
    fun an_email_can_only_register_once() {
        registerOwner("dup@example.co.za")
        assertEquals(
            AccountService.RegisterResult.EmailTaken,
            accounts.register("Another", "DUP@example.co.za", "a-long-enough-password")
        )
    }

    @Test
    fun email_is_matched_case_insensitively_on_login() {
        registerOwner("Mixed.Case@Example.co.za")
        assertNotNull(accounts.login("mixed.case@example.co.za", "a-long-enough-password"))
    }

    @Test
    fun a_wrong_password_yields_no_session() {
        registerOwner("pw@example.co.za")
        assertNull(accounts.login("pw@example.co.za", "wrong-password-entirely"))
        assertNull(accounts.login("nobody@example.co.za", "a-long-enough-password"))
    }

    @Test
    fun short_passwords_are_refused_with_a_reason() {
        val result = accounts.register("Small", "small@example.co.za", "short")
        assertTrue(result is AccountService.RegisterResult.Invalid)
        assertTrue((result as AccountService.RegisterResult.Invalid).reason.contains("10"))
    }

    @Test
    fun a_session_authenticates_until_it_expires() {
        val owner = registerOwner("sess@example.co.za")
        assertEquals(owner.user.id, accounts.authenticate(owner.sessionToken)!!.id)

        now = now.plus(AccountService.DEFAULT_SESSION_LIFETIME).plusSeconds(1)
        assertNull(accounts.authenticate(owner.sessionToken))
    }

    @Test
    fun logging_out_invalidates_the_session_immediately() {
        val owner = registerOwner("out@example.co.za")
        assertTrue(accounts.logout(owner.sessionToken))
        assertNull(accounts.authenticate(owner.sessionToken))
    }

    @Test
    fun a_registration_that_fails_leaves_no_organisation_behind() {
        // Org and owner are created in one transaction; a duplicate email must
        // not leave an org nobody can log into.
        registerOwner("atomic@example.co.za")
        accounts.register("Ghost Org", "atomic@example.co.za", "a-long-enough-password")

        database.read { c ->
            c.prepareStatement("SELECT count(*) FROM orgs WHERE name = 'Ghost Org'").use { st ->
                st.executeQuery().use { rs ->
                    rs.next()
                    assertEquals(0, rs.getInt(1), "a failed signup left an orphan organisation")
                }
            }
        }
    }

    // ---------------------------------------------------------------- code shape

    @Test
    fun pairing_codes_avoid_the_characters_people_misread() {
        repeat(200) {
            val code = PairingCodes.generate()
            assertEquals(9, code.length)                    // XXXX-XXXX
            assertTrue(code.none { it in "01OILUV" }, "ambiguous character in $code")
        }
    }
}
