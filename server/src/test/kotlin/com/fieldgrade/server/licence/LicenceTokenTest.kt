package com.fieldgrade.server.licence

import com.fieldgrade.shared.Base64Url
import com.fieldgrade.shared.LicenceClaim
import com.fieldgrade.shared.LicenceState
import com.fieldgrade.shared.LicenceVerifier
import com.fieldgrade.shared.SignedLicence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The licence round trip, signer to verifier.
 *
 * The property under test is a safety one as much as a commercial one: a machine
 * in a field must keep working when the network, the card or the billing system
 * does not. Expiry may block acquiring new work; it may never interrupt work in
 * progress, and it never reaches the control loop.
 */
class LicenceTokenTest {

    private val keys = LicenceSigner.generateKeyPair()
    private val signer = LicenceSigner.fromEncodedPrivateKey(keys.privateKeyBase64Url)!!
    private val verifier = LicenceVerifier.fromEncodedPublicKey(keys.publicKeyBase64Url)!!

    private val day = LicenceClaim.MILLIS_PER_DAY
    private val issued = 1_770_000_000_000L          // a fixed instant; no wall clock in tests
    private val expires = issued + 30 * day

    private fun claim(graceDays: Int = 30) = LicenceClaim(
        machineId = "mach_7f3a",
        orgId = "org_kobus",
        plan = "per_machine_monthly",
        issuedAtMs = issued,
        expiresAtMs = expires,
        graceDays = graceDays
    )

    // ---- round trip ----

    @Test
    fun a_signed_licence_verifies_and_round_trips_intact() {
        val token = signer.token(claim())
        val recovered = verifier.verify(token)
        assertNotNull(recovered)
        assertEquals(claim(), recovered)
    }

    @Test
    fun the_token_is_a_single_safe_line() {
        // It has to survive being emailed, typed in, or carried in a QR code to a
        // machine that has never had signal.
        val token = signer.token(claim())
        assertEquals(1, token.lines().size)
        assertEquals(1, token.count { it == '.' })
        assertTrue(token.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' })
    }

    // ---- forgery ----

    @Test
    fun a_tampered_payload_is_rejected() {
        // The obvious attack: extend your own expiry.
        val forged = claim().copy(expiresAtMs = expires + 3650 * day)
        val genuine = SignedLicence.decode(signer.token(claim()))!!
        val tampered = SignedLicence(
            com.fieldgrade.shared.LicenceCodec.payloadBytes(forged),
            genuine.signature
        )
        assertNull(verifier.verify(tampered))
    }

    @Test
    fun a_licence_signed_by_a_different_key_is_rejected() {
        val other = LicenceSigner.fromEncodedPrivateKey(
            LicenceSigner.generateKeyPair().privateKeyBase64Url
        )!!
        assertNull(verifier.verify(other.token(claim())))
    }

    @Test
    fun malformed_tokens_are_rejected_rather_than_crashing() {
        for (bad in listOf("", ".", "abc", "abc.", ".abc", "not a token", "a.b.c", "!!!.???")) {
            assertNull("accepted malformed token '$bad'", verifier.verify(bad))
        }
    }

    private fun assertNull(message: String, value: Any?) = assertTrue(value == null, message)

    @Test
    fun a_tablet_cannot_mint_a_licence_from_what_it_holds() {
        // The tablet ships with the public key only. If that were ever enough to
        // sign, the whole scheme would be decoration.
        val publicOnly = Base64Url.decode(keys.publicKeyBase64Url)!!
        assertNull(LicenceSigner.fromEncodedPrivateKey(Base64Url.encode(publicOnly)))
    }

    // ---- the states, and what each permits ----

    @Test
    fun inside_the_paid_period_everything_works() {
        val e = verifier.evaluate(signer.token(claim()), expires - day)
        assertEquals(LicenceState.ACTIVE, e.state)
        assertTrue(e.allowsNewDesignDownload)
        assertNull(e.message)
    }

    @Test
    fun a_lapsed_payment_keeps_working_through_the_grace_window() {
        // A declined card must not cost a farmer a day's work.
        val token = signer.token(claim(graceDays = 30))
        for (daysPast in listOf(1L, 10L, 29L)) {
            val e = verifier.evaluate(token, expires + daysPast * day)
            assertEquals(LicenceState.GRACE, e.state, "at +$daysPast days")
            assertTrue(e.allowsNewDesignDownload, "grace must not block work at +$daysPast days")
            assertNotNull(e.message)
        }
    }

    @Test
    fun past_grace_only_new_design_downloads_are_blocked() {
        val e = verifier.evaluate(signer.token(claim()), expires + 31 * day)
        assertEquals(LicenceState.EXPIRED, e.state)
        assertFalse(e.allowsNewDesignDownload)
        // Still a genuine, readable licence — the tablet can say exactly what is
        // wrong and which machine it belongs to, rather than just failing.
        assertTrue(e.isUsable)
        assertEquals("mach_7f3a", e.claim!!.machineId)
    }

    @Test
    fun the_grace_boundary_is_inclusive_and_exact() {
        val token = signer.token(claim(graceDays = 30))
        val hard = claim().hardExpiryMs
        assertEquals(LicenceState.GRACE, verifier.evaluate(token, hard).state)
        assertEquals(LicenceState.EXPIRED, verifier.evaluate(token, hard + 1).state)
        assertEquals(LicenceState.ACTIVE, verifier.evaluate(token, expires).state)
        assertEquals(LicenceState.GRACE, verifier.evaluate(token, expires + 1).state)
    }

    @Test
    fun zero_grace_is_honoured_but_still_never_blocks_more_than_downloads() {
        val e = verifier.evaluate(signer.token(claim(graceDays = 0)), expires + 1)
        assertEquals(LicenceState.EXPIRED, e.state)
        assertFalse(e.allowsNewDesignDownload)
    }

    // ---- absence and versioning ----

    @Test
    fun a_missing_licence_is_expired_not_a_crash() {
        for (token in listOf(null, "", "   ")) {
            val e = verifier.evaluate(token, issued)
            assertEquals(LicenceState.EXPIRED, e.state)
            assertFalse(e.isUsable)
        }
    }

    @Test
    fun an_unsupported_schema_version_is_refused_rather_than_guessed() {
        // A future server issuing v2 must not have it half-understood by an old
        // tablet. Refusing is safe; downloads stop, work does not.
        val token = signer.token(claim().copy(v = 99))
        val e = verifier.evaluate(token, issued)
        assertEquals(LicenceState.EXPIRED, e.state)
        assertTrue(e.message!!.contains("99"))
    }

    @Test
    fun verification_needs_no_network_no_clock_and_no_state() {
        // Encoded as a test because it is the whole design: two pure calls, an
        // injected instant, and nothing else. If this ever needs a service, the
        // offline guarantee has been broken.
        val token = signer.token(claim())
        val fresh = LicenceVerifier.fromEncodedPublicKey(keys.publicKeyBase64Url)!!
        repeat(3) {
            assertEquals(LicenceState.ACTIVE, fresh.evaluate(token, expires - day).state)
        }
    }

    // ---- base64url ----

    @Test
    fun base64url_round_trips_arbitrary_bytes() {
        for (size in listOf(0, 1, 2, 3, 4, 5, 31, 32, 64, 255)) {
            val bytes = ByteArray(size) { (it * 37 - 128).toByte() }
            val encoded = Base64Url.encode(bytes)
            assertFalse(encoded.contains('+') || encoded.contains('/') || encoded.contains('='))
            if (size == 0) continue
            assertTrue(bytes.contentEquals(Base64Url.decode(encoded)!!), "size $size")
        }
    }

    @Test
    fun base64url_rejects_foreign_characters() {
        assertNull(Base64Url.decode("abc+def"))
        assertNull(Base64Url.decode("abc/def"))
        assertNull(Base64Url.decode("abc=="))
    }
}
