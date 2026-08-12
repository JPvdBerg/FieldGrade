package com.fieldgrade.server.auth

import de.mkammerer.argon2.Argon2Factory

/**
 * Hashes and checks passwords. Argon2id.
 *
 * One job: password in, verifiable hash out. No storage, no sessions, no users.
 *
 * Argon2id rather than bcrypt or PBKDF2 because it is memory-hard: an attacker
 * with a rack of GPUs gains far less than they would against an iteration-only
 * scheme. It won the Password Hashing Competition and is the current default
 * recommendation.
 *
 * The parameters below are the interactive-login tier — roughly 64 MB and a
 * fraction of a second per attempt on a server. Raising them raises attacker
 * cost proportionally, but every login pays it too, so they are stated here
 * rather than buried, and there is a test that they have not been quietly
 * lowered.
 *
 * The salt is generated per hash and stored inside the returned string, which is
 * why nothing here takes or returns one separately.
 */
class PasswordHasher(
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val memoryKb: Int = DEFAULT_MEMORY_KB,
    private val parallelism: Int = DEFAULT_PARALLELISM
) {
    private val argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)

    /** @return a self-describing hash string, safe to store as-is. */
    fun hash(password: CharArray): String =
        try {
            argon2.hash(iterations, memoryKb, parallelism, password)
        } finally {
            // Do not leave the plaintext lying in a heap dump.
            argon2.wipeArray(password)
        }

    fun hash(password: String): String = hash(password.toCharArray())

    /**
     * Constant-time-ish verification, delegated to the library.
     *
     * Returns false rather than throwing on a malformed stored hash: a corrupt
     * row must read as "wrong password", not as a 500 that tells an attacker
     * the account exists.
     */
    fun verify(storedHash: String, password: CharArray): Boolean =
        try {
            argon2.verify(storedHash, password)
        } catch (e: Exception) {
            false
        } finally {
            argon2.wipeArray(password)
        }

    fun verify(storedHash: String, password: String): Boolean =
        verify(storedHash, password.toCharArray())

    companion object {
        /**
         * OWASP's Argon2id guidance for interactive logins. Treat as a floor.
         * Lowering any of these is a security decision, not a performance tweak.
         */
        const val DEFAULT_ITERATIONS = 3
        const val DEFAULT_MEMORY_KB = 65_536      // 64 MB
        const val DEFAULT_PARALLELISM = 4

        /**
         * Cheap parameters for tests only. Hashing at production cost turns a
         * fast test suite into a slow one, and a slow suite stops being run.
         */
        fun forTests() = PasswordHasher(iterations = 1, memoryKb = 1_024, parallelism = 1)
    }
}
