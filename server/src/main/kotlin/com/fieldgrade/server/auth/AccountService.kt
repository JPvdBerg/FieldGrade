package com.fieldgrade.server.auth

import com.fieldgrade.server.db.AccountRepository
import com.fieldgrade.server.db.Database
import com.fieldgrade.server.domain.Ids
import com.fieldgrade.server.domain.Org
import com.fieldgrade.server.domain.User
import com.fieldgrade.server.domain.UserRole
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Signing up, signing in, and staying signed in.
 *
 * One job: the rules around identity. It writes no SQL and hashes nothing
 * itself — [AccountRepository], [PasswordHasher] and [SessionTokens] do that.
 *
 * The [Clock] is injected rather than read, so session expiry can be tested at
 * an exact boundary instead of with a sleep.
 */
class AccountService(
    private val database: Database,
    private val accounts: AccountRepository,
    private val hasher: PasswordHasher,
    private val clock: Clock = Clock.systemUTC(),
    private val sessionLifetime: Duration = DEFAULT_SESSION_LIFETIME
) {

    sealed interface RegisterResult {
        data class Success(val user: User, val org: Org, val sessionToken: String) : RegisterResult
        data object EmailTaken : RegisterResult
        data class Invalid(val reason: String) : RegisterResult
    }

    /**
     * Create an organisation and its first owner, atomically.
     *
     * Both or neither: an organisation with no way to log into it would need
     * manual repair, and the whole point of a transaction is that nobody has to.
     */
    fun register(orgName: String, email: String, password: String): RegisterResult {
        val normalisedEmail = normaliseEmail(email)
        validate(orgName, normalisedEmail, password)?.let { return RegisterResult.Invalid(it) }

        val now = clock.instant()
        return try {
            database.transaction { c ->
                if (accounts.emailExists(c, normalisedEmail)) return@transaction RegisterResult.EmailTaken

                val org = Org(Ids.org(), orgName.trim(), now)
                accounts.insertOrg(c, org)

                val user = User(
                    id = Ids.user(),
                    orgId = org.id,
                    email = normalisedEmail,
                    passwordHash = hasher.hash(password),
                    // Whoever creates the organisation owns it. Operators are
                    // added afterwards by that owner.
                    role = UserRole.OWNER,
                    createdAt = now
                )
                accounts.insertUser(c, user)

                val token = SessionTokens.generate()
                accounts.insertSession(
                    c, SessionTokens.fingerprint(token), user.id, now.plus(sessionLifetime)
                )
                RegisterResult.Success(user, org, token)
            }
        } catch (e: Exception) {
            // The unique index is the real guard: two simultaneous signups with
            // the same address get here, and one of them must lose.
            if (isUniqueViolation(e)) RegisterResult.EmailTaken else throw e
        }
    }

    /**
     * Verify credentials and open a session.
     *
     * @return the session token, or null. Deliberately one indistinguishable
     *         failure for "no such user" and "wrong password": telling them
     *         apart hands an attacker a list of which addresses are registered.
     */
    fun login(email: String, password: String): String? {
        val normalisedEmail = normaliseEmail(email)
        val now = clock.instant()

        return database.transaction { c ->
            val user = accounts.findUserByEmail(c, normalisedEmail)
            if (user == null) {
                // Hash anyway. Returning early on an unknown address makes the
                // response measurably faster, which is itself the leak.
                hasher.verify(DUMMY_HASH, password)
                return@transaction null
            }
            if (!hasher.verify(user.passwordHash, password)) return@transaction null

            val token = SessionTokens.generate()
            accounts.insertSession(
                c, SessionTokens.fingerprint(token), user.id, now.plus(sessionLifetime)
            )
            token
        }
    }

    /** Resolve a bearer token to its user, or null if absent, unknown or expired. */
    fun authenticate(sessionToken: String?): User? {
        if (sessionToken.isNullOrBlank()) return null
        return database.read { c ->
            accounts.findUserBySession(c, SessionTokens.fingerprint(sessionToken), clock.instant())
        }
    }

    fun logout(sessionToken: String?): Boolean {
        if (sessionToken.isNullOrBlank()) return false
        return database.transaction { c ->
            accounts.deleteSession(c, SessionTokens.fingerprint(sessionToken))
        }
    }

    fun purgeExpiredSessions(): Int =
        database.transaction { c -> accounts.deleteExpiredSessions(c, clock.instant()) }

    // ---------------------------------------------------------------- helpers

    private fun validate(orgName: String, email: String, password: String): String? = when {
        orgName.isBlank() -> "organisation name is required"
        email.isBlank() -> "email is required"
        // Not an RFC 5322 parser on purpose. The only real proof an address
        // works is sending to it; anything stricter here just rejects valid
        // addresses and annoys people.
        !email.contains('@') || email.startsWith('@') || email.endsWith('@') ->
            "that does not look like an email address"
        password.length < MIN_PASSWORD_LENGTH ->
            "password must be at least $MIN_PASSWORD_LENGTH characters"
        else -> null
    }

    private fun normaliseEmail(email: String) = email.trim().lowercase()

    private fun isUniqueViolation(e: Exception): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            // Postgres 23505. Matched on SQLState rather than message text,
            // which is localised and version-dependent.
            if (cause is java.sql.SQLException && cause.sqlState == "23505") return true
            cause = cause.cause
        }
        return false
    }

    companion object {
        /**
         * Long, because the alternative is farmers logging in on a phone in a
         * shed every fortnight. Sessions are revocable server-side, which is
         * the control that actually matters.
         */
        val DEFAULT_SESSION_LIFETIME: Duration = Duration.ofDays(60)

        /**
         * Length only. Composition rules ("must contain a symbol") push people
         * toward `Password1!` and are no longer recommended practice.
         */
        const val MIN_PASSWORD_LENGTH = 10

        /**
         * A real Argon2 hash of a value nobody knows, used to spend the same
         * time on an unknown address as on a known one.
         */
        private const val DUMMY_HASH =
            "\$argon2id\$v=19\$m=1024,t=1,p=1\$c29tZXNhbHR2YWx1ZQ\$Xf3rMPYCCLQVRSFDCK1S9A"
    }
}
