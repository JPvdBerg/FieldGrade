package com.fieldgrade.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.sql.Connection
import javax.sql.DataSource

/**
 * The connection pool and the schema, and nothing else.
 *
 * One job: hand out connections against a migrated database. It knows no domain
 * types and contains no queries.
 *
 * Migrations run on start rather than by hand. With one small team and a server
 * nobody has shelled into yet, "did you remember to run the migration" is a
 * failure waiting to happen; Flyway makes deploying the code and moving the
 * schema the same action.
 */
class Database(private val dataSource: DataSource) : AutoCloseable {

    /** Apply any pending migrations. Idempotent; safe on every boot. */
    fun migrate(): Int {
        val result = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        return result.migrationsExecuted
    }

    fun <T> read(block: (Connection) -> T): T = dataSource.connection.use(block)

    /**
     * Run in a transaction, rolling back on any exception.
     *
     * Repositories take a [Connection] rather than opening their own, so a
     * service can compose several writes into one unit — creating an org, its
     * owner and its first machine either all happen or none do.
     */
    fun <T> transaction(block: (Connection) -> T): T =
        dataSource.connection.use { connection ->
            val previous = connection.autoCommit
            connection.autoCommit = false
            try {
                val result = block(connection)
                connection.commit()
                result
            } catch (e: Throwable) {
                runCatching { connection.rollback() }
                throw e
            } finally {
                runCatching { connection.autoCommit = previous }
            }
        }

    override fun close() {
        (dataSource as? AutoCloseable)?.close()
    }

    companion object {
        fun pooled(jdbcUrl: String, user: String, password: String, poolSize: Int = 10): Database {
            val config = HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                username = user
                this.password = password
                maximumPoolSize = poolSize
                isAutoCommit = true
                // Fail fast rather than hanging a request behind an unreachable DB.
                connectionTimeout = 5_000
                poolName = "fieldgrade"
            }
            return Database(HikariDataSource(config))
        }
    }
}
