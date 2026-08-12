package com.fieldgrade.server.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** [PasswordHasher] and [SessionTokens] in isolation. */
class PasswordAndSessionTest {

    private val hasher = PasswordHasher.forTests()

    @Test
    fun a_correct_password_verifies() {
        val hash = hasher.hash("correct horse battery staple")
        assertTrue(hasher.verify(hash, "correct horse battery staple"))
    }

    @Test
    fun a_wrong_password_does_not() {
        val hash = hasher.hash("correct horse battery staple")
        assertFalse(hasher.verify(hash, "Correct horse battery staple"))
        assertFalse(hasher.verify(hash, ""))
        assertFalse(hasher.verify(hash, "correct horse battery stapl"))
    }

    @Test
    fun the_same_password_hashes_differently_every_time() {
        // Per-hash salt: two customers sharing a password must not be visibly
        // identical in the table.
        assertNotEquals(hasher.hash("hunter2"), hasher.hash("hunter2"))
    }

    @Test
    fun the_hash_never_contains_the_password() {
        assertFalse(hasher.hash("hunter2").contains("hunter2"))
    }

    @Test
    fun a_corrupt_stored_hash_reads_as_wrong_password_not_an_error() {
        // A 500 here would tell an attacker the account exists.
        for (bad in listOf("", "not-a-hash", "garbage-with-no-structure", " ")) {
            assertFalse(hasher.verify(bad, "anything"))
        }
    }

    @Test
    fun production_parameters_meet_the_owasp_floor() {
        // Guards against someone "optimising" login latency by weakening it.
        assertTrue(PasswordHasher.DEFAULT_ITERATIONS >= 2)
        assertTrue(PasswordHasher.DEFAULT_MEMORY_KB >= 19_456)
        assertTrue(PasswordHasher.DEFAULT_PARALLELISM >= 1)
    }

    // ---- sessions ----

    @Test
    fun tokens_are_unique_and_long() {
        val tokens = (1..500).map { SessionTokens.generate() }
        assertEquals(500, tokens.toSet().size)
        assertTrue(tokens.all { it.length >= 42 })
    }

    @Test
    fun tokens_are_url_safe() {
        repeat(50) {
            val t = SessionTokens.generate()
            assertTrue(t.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' }, "bad token $t")
        }
    }

    @Test
    fun the_stored_form_is_not_the_presented_form() {
        // The whole point: a dump of the sessions table must not let anyone in.
        val token = SessionTokens.generate()
        assertNotEquals(token, SessionTokens.fingerprint(token))
        assertFalse(SessionTokens.fingerprint(token).contains(token))
    }

    @Test
    fun fingerprinting_is_deterministic() {
        val token = SessionTokens.generate()
        assertEquals(SessionTokens.fingerprint(token), SessionTokens.fingerprint(token))
        assertNotEquals(
            SessionTokens.fingerprint(token),
            SessionTokens.fingerprint(SessionTokens.generate())
        )
    }
}
