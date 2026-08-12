package com.fieldgrade.server.db

import com.fieldgrade.server.domain.Org
import com.fieldgrade.server.domain.User
import com.fieldgrade.server.domain.UserRole
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant

/**
 * Persistence for the identity aggregate: organisations, users and sessions.
 *
 * One job: move those rows in and out. It hashes nothing, mints nothing and
 * enforces no policy — [com.fieldgrade.server.auth.AccountService] owns that.
 *
 * Every method takes the [Connection] rather than opening its own, so a service
 * can compose several writes into one transaction. Creating an org and its
 * first owner must be atomic; an org with no way to log into it is worse than
 * no org at all.
 *
 * All SQL is parameterised. There is no string concatenation of user input
 * anywhere in this file, and there should never be.
 */
class AccountRepository {

    // ---------------------------------------------------------------- orgs

    fun insertOrg(c: Connection, org: Org) {
        c.prepareStatement(
            "INSERT INTO orgs (id, name, created_at) VALUES (?, ?, ?)"
        ).use { st ->
            st.setString(1, org.id)
            st.setString(2, org.name)
            st.setTimestamp(3, java.sql.Timestamp.from(org.createdAt))
            st.executeUpdate()
        }
    }

    fun findOrg(c: Connection, id: String): Org? =
        c.prepareStatement("SELECT id, name, created_at FROM orgs WHERE id = ?").use { st ->
            st.setString(1, id)
            st.executeQuery().use { rs -> if (rs.next()) readOrg(rs) else null }
        }

    // ---------------------------------------------------------------- users

    fun insertUser(c: Connection, user: User) {
        c.prepareStatement(
            "INSERT INTO users (id, org_id, email, password_hash, role, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)"
        ).use { st ->
            st.setString(1, user.id)
            st.setString(2, user.orgId)
            st.setString(3, user.email)
            st.setString(4, user.passwordHash)
            st.setString(5, user.role.wire)
            st.setTimestamp(6, java.sql.Timestamp.from(user.createdAt))
            st.executeUpdate()
        }
    }

    /** Email is stored already normalised; callers must normalise before asking. */
    fun findUserByEmail(c: Connection, email: String): User? =
        c.prepareStatement("$USER_COLUMNS WHERE email = ?").use { st ->
            st.setString(1, email)
            st.executeQuery().use { rs -> if (rs.next()) readUser(rs) else null }
        }

    fun findUser(c: Connection, id: String): User? =
        c.prepareStatement("$USER_COLUMNS WHERE id = ?").use { st ->
            st.setString(1, id)
            st.executeQuery().use { rs -> if (rs.next()) readUser(rs) else null }
        }

    fun emailExists(c: Connection, email: String): Boolean =
        c.prepareStatement("SELECT 1 FROM users WHERE email = ?").use { st ->
            st.setString(1, email)
            st.executeQuery().use { it.next() }
        }

    // ---------------------------------------------------------------- sessions

    /** @param tokenHash the fingerprint, never the token itself. */
    fun insertSession(c: Connection, tokenHash: String, userId: String, expiresAt: Instant) {
        c.prepareStatement(
            "INSERT INTO sessions (token_hash, user_id, expires_at) VALUES (?, ?, ?)"
        ).use { st ->
            st.setString(1, tokenHash)
            st.setString(2, userId)
            st.setTimestamp(3, java.sql.Timestamp.from(expiresAt))
            st.executeUpdate()
        }
    }

    /**
     * Resolve a session to its user, rejecting expired ones in the same query.
     *
     * Filtering in SQL rather than in Kotlin means an expired session cannot be
     * used even if a caller forgets to check — the row simply does not come back.
     */
    fun findUserBySession(c: Connection, tokenHash: String, now: Instant): User? =
        c.prepareStatement(
            "SELECT u.id, u.org_id, u.email, u.password_hash, u.role, u.created_at " +
                "FROM sessions s JOIN users u ON u.id = s.user_id " +
                "WHERE s.token_hash = ? AND s.expires_at > ?"
        ).use { st ->
            st.setString(1, tokenHash)
            st.setTimestamp(2, java.sql.Timestamp.from(now))
            st.executeQuery().use { rs -> if (rs.next()) readUser(rs) else null }
        }

    fun deleteSession(c: Connection, tokenHash: String): Boolean =
        c.prepareStatement("DELETE FROM sessions WHERE token_hash = ?").use { st ->
            st.setString(1, tokenHash)
            st.executeUpdate() > 0
        }

    /** Housekeeping; safe to run whenever. */
    fun deleteExpiredSessions(c: Connection, now: Instant): Int =
        c.prepareStatement("DELETE FROM sessions WHERE expires_at <= ?").use { st ->
            st.setTimestamp(1, java.sql.Timestamp.from(now))
            st.executeUpdate()
        }

    // ---------------------------------------------------------------- mapping

    private fun readOrg(rs: ResultSet) = Org(
        id = rs.getString("id"),
        name = rs.getString("name"),
        createdAt = rs.getTimestamp("created_at").toInstant()
    )

    private fun readUser(rs: ResultSet) = User(
        id = rs.getString("id"),
        orgId = rs.getString("org_id"),
        email = rs.getString("email"),
        passwordHash = rs.getString("password_hash"),
        // A role the code does not recognise is treated as the least privileged
        // one rather than failing: a future role must not grant OWNER by accident.
        role = UserRole.parse(rs.getString("role")) ?: UserRole.OPERATOR,
        createdAt = rs.getTimestamp("created_at").toInstant()
    )

    private companion object {
        const val USER_COLUMNS =
            "SELECT id, org_id, email, password_hash, role, created_at FROM users"
    }
}
