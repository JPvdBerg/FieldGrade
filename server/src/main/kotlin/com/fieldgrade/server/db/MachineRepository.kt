package com.fieldgrade.server.db

import com.fieldgrade.server.domain.Machine
import com.fieldgrade.server.domain.PairingCode
import com.fieldgrade.server.domain.Subscription
import com.fieldgrade.server.domain.SubscriptionStatus
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/**
 * Persistence for the fleet: machines, their subscriptions, pairing codes,
 * device keys and issued licences.
 *
 * One job: move those rows. It mints no codes, signs no licences and decides no
 * billing outcomes.
 *
 * Grouped as one repository because these tables are only ever reached through
 * a machine, and splitting them would mean four objects passed everywhere to
 * answer one question.
 */
class MachineRepository {

    // ---------------------------------------------------------------- machines

    fun insertMachine(c: Connection, machine: Machine) {
        c.prepareStatement(
            "INSERT INTO machines (id, org_id, serial, name, created_at) VALUES (?, ?, ?, ?, ?)"
        ).use { st ->
            st.setString(1, machine.id)
            st.setString(2, machine.orgId)
            st.setString(3, machine.serial)
            st.setString(4, machine.name)
            st.setTimestamp(5, Timestamp.from(machine.createdAt))
            st.executeUpdate()
        }
    }

    fun findMachine(c: Connection, id: String): Machine? =
        c.prepareStatement("$MACHINE_COLUMNS WHERE id = ?").use { st ->
            st.setString(1, id)
            st.executeQuery().use { rs -> if (rs.next()) readMachine(rs) else null }
        }

    fun findMachineBySerial(c: Connection, serial: String): Machine? =
        c.prepareStatement("$MACHINE_COLUMNS WHERE serial = ?").use { st ->
            st.setString(1, serial)
            st.executeQuery().use { rs -> if (rs.next()) readMachine(rs) else null }
        }

    fun listMachines(c: Connection, orgId: String): List<Machine> =
        c.prepareStatement("$MACHINE_COLUMNS WHERE org_id = ? ORDER BY created_at").use { st ->
            st.setString(1, orgId)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(readMachine(rs)) } }
        }

    // ---------------------------------------------------------------- subscriptions

    fun upsertSubscription(c: Connection, subscription: Subscription) {
        // One subscription per machine is a database constraint, so the natural
        // write is an upsert keyed on the machine rather than a read-then-write
        // that could race with a webhook arriving at the same moment.
        c.prepareStatement(
            "INSERT INTO subscriptions " +
                "(id, org_id, machine_id, plan, status, current_period_end, provider, provider_ref) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (machine_id) DO UPDATE SET " +
                "plan = EXCLUDED.plan, status = EXCLUDED.status, " +
                "current_period_end = EXCLUDED.current_period_end, " +
                "provider = EXCLUDED.provider, provider_ref = EXCLUDED.provider_ref, " +
                "updated_at = now()"
        ).use { st ->
            st.setString(1, subscription.id)
            st.setString(2, subscription.orgId)
            st.setString(3, subscription.machineId)
            st.setString(4, subscription.plan)
            st.setString(5, subscription.status.wire)
            st.setTimestamp(6, Timestamp.from(subscription.currentPeriodEnd))
            st.setString(7, subscription.provider)
            st.setString(8, subscription.providerRef)
            st.executeUpdate()
        }
    }

    fun findSubscriptionByMachine(c: Connection, machineId: String): Subscription? =
        c.prepareStatement("$SUBSCRIPTION_COLUMNS WHERE machine_id = ?").use { st ->
            st.setString(1, machineId)
            st.executeQuery().use { rs -> if (rs.next()) readSubscription(rs) else null }
        }

    // ---------------------------------------------------------------- pairing

    fun insertPairingCode(c: Connection, code: PairingCode) {
        c.prepareStatement(
            "INSERT INTO pairing_codes (code, machine_id, expires_at) VALUES (?, ?, ?)"
        ).use { st ->
            st.setString(1, code.code)
            st.setString(2, code.machineId)
            st.setTimestamp(3, Timestamp.from(code.expiresAt))
            st.executeUpdate()
        }
    }

    fun findPairingCode(c: Connection, code: String): PairingCode? =
        c.prepareStatement(
            "SELECT code, machine_id, expires_at, redeemed_at FROM pairing_codes WHERE code = ?"
        ).use { st ->
            st.setString(1, code)
            st.executeQuery().use { rs ->
                if (!rs.next()) null else PairingCode(
                    code = rs.getString("code"),
                    machineId = rs.getString("machine_id"),
                    expiresAt = rs.getTimestamp("expires_at").toInstant(),
                    redeemedAt = rs.getTimestamp("redeemed_at")?.toInstant()
                )
            }
        }

    /**
     * Claim a code atomically.
     *
     * The `redeemed_at IS NULL` in the WHERE clause is what makes single-use
     * real: two tablets racing on the same code produce one winner and one
     * false, decided by the database rather than by a check-then-act in Kotlin
     * that could interleave.
     *
     * @return true if this caller claimed it.
     */
    fun redeemPairingCode(c: Connection, code: String, now: Instant): Boolean =
        c.prepareStatement(
            "UPDATE pairing_codes SET redeemed_at = ? " +
                "WHERE code = ? AND redeemed_at IS NULL AND expires_at > ?"
        ).use { st ->
            st.setTimestamp(1, Timestamp.from(now))
            st.setString(2, code)
            st.setTimestamp(3, Timestamp.from(now))
            st.executeUpdate() > 0
        }

    // ---------------------------------------------------------------- device keys

    /** @param keyHash the fingerprint of the device key, never the key. */
    fun insertDeviceKey(c: Connection, keyHash: String, machineId: String) {
        c.prepareStatement(
            "INSERT INTO device_keys (key_hash, machine_id) VALUES (?, ?)"
        ).use { st ->
            st.setString(1, keyHash)
            st.setString(2, machineId)
            st.executeUpdate()
        }
    }

    /** Resolve an upload credential to its machine, ignoring revoked keys. */
    fun findMachineByDeviceKey(c: Connection, keyHash: String): Machine? =
        c.prepareStatement(
            "SELECT m.id, m.org_id, m.serial, m.name, m.created_at " +
                "FROM device_keys d JOIN machines m ON m.id = d.machine_id " +
                "WHERE d.key_hash = ? AND d.revoked_at IS NULL"
        ).use { st ->
            st.setString(1, keyHash)
            st.executeQuery().use { rs -> if (rs.next()) readMachine(rs) else null }
        }

    fun touchDeviceKey(c: Connection, keyHash: String, now: Instant) {
        c.prepareStatement("UPDATE device_keys SET last_seen_at = ? WHERE key_hash = ?").use { st ->
            st.setTimestamp(1, Timestamp.from(now))
            st.setString(2, keyHash)
            st.executeUpdate()
        }
    }

    /** Revoke every key for a machine — the "tablet was stolen" button. */
    fun revokeDeviceKeys(c: Connection, machineId: String, now: Instant): Int =
        c.prepareStatement(
            "UPDATE device_keys SET revoked_at = ? WHERE machine_id = ? AND revoked_at IS NULL"
        ).use { st ->
            st.setTimestamp(1, Timestamp.from(now))
            st.setString(2, machineId)
            st.executeUpdate()
        }

    // ---------------------------------------------------------------- licences

    fun insertLicence(
        c: Connection, id: String, machineId: String, token: String,
        issuedAt: Instant, expiresAt: Instant, graceDays: Int
    ) {
        c.prepareStatement(
            "INSERT INTO licences (id, machine_id, token, issued_at, expires_at, grace_days) " +
                "VALUES (?, ?, ?, ?, ?, ?)"
        ).use { st ->
            st.setString(1, id)
            st.setString(2, machineId)
            st.setString(3, token)
            st.setTimestamp(4, Timestamp.from(issuedAt))
            st.setTimestamp(5, Timestamp.from(expiresAt))
            st.setInt(6, graceDays)
            st.executeUpdate()
        }
    }

    /** The current licence for a machine: most recently issued wins. */
    fun findLatestLicence(c: Connection, machineId: String): String? =
        c.prepareStatement(
            "SELECT token FROM licences WHERE machine_id = ? ORDER BY issued_at DESC LIMIT 1"
        ).use { st ->
            st.setString(1, machineId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString("token") else null }
        }

    // ---------------------------------------------------------------- mapping

    private fun readMachine(rs: ResultSet) = Machine(
        id = rs.getString("id"),
        orgId = rs.getString("org_id"),
        serial = rs.getString("serial"),
        name = rs.getString("name"),
        createdAt = rs.getTimestamp("created_at").toInstant()
    )

    private fun readSubscription(rs: ResultSet) = Subscription(
        id = rs.getString("id"),
        orgId = rs.getString("org_id"),
        machineId = rs.getString("machine_id"),
        plan = rs.getString("plan"),
        // An unknown status must not read as ACTIVE and quietly keep issuing
        // licences; CANCELLED is the safe interpretation.
        status = SubscriptionStatus.parse(rs.getString("status")) ?: SubscriptionStatus.CANCELLED,
        currentPeriodEnd = rs.getTimestamp("current_period_end").toInstant(),
        provider = rs.getString("provider"),
        providerRef = rs.getString("provider_ref")
    )

    private companion object {
        const val MACHINE_COLUMNS =
            "SELECT id, org_id, serial, name, created_at FROM machines"
        const val SUBSCRIPTION_COLUMNS =
            "SELECT id, org_id, machine_id, plan, status, current_period_end, provider, provider_ref " +
                "FROM subscriptions"
    }
}
