package com.fieldgrade.server.db

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The schema, applied to a real Postgres.
 *
 * Runs against embedded Postgres rather than a Docker container so the suite
 * works on a laptop with the daemon stopped — which is the state this machine
 * is actually in. It is a genuine Postgres binary, so dialect, constraints and
 * partial indexes all behave as they will in production, unlike an H2 stand-in.
 */
class MigrationTest {

    private val pg = EmbeddedPostgres.builder().start()
    private val database = Database(pg.postgresDatabase)

    @AfterTest
    fun tearDown() {
        pg.close()
    }

    @Test
    fun the_schema_applies_and_is_idempotent() {
        val applied = database.migrate()
        assertTrue(applied > 0, "expected migrations to run")
        // Booting twice must be a no-op, because every deploy will do exactly that.
        assertEquals(0, database.migrate())
    }

    @Test
    fun every_expected_table_exists() {
        database.migrate()
        val tables = database.read { c ->
            c.createStatement().executeQuery(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"
            ).use { rs ->
                buildSet { while (rs.next()) add(rs.getString(1)) }
            }
        }
        for (t in listOf(
            "orgs", "users", "machines", "subscriptions",
            "licences", "sessions", "payment_methods", "webhook_events"
        )) {
            assertTrue(t in tables, "missing table $t (have: ${tables.sorted()})")
        }
    }

    @Test
    fun a_duplicate_webhook_event_cannot_be_recorded_twice() {
        // This constraint is what makes a retried delivery harmless. Gateways
        // retry for days; without it, one replay extends a subscription twice.
        database.migrate()
        database.read { c ->
            c.createStatement().execute(
                "INSERT INTO webhook_events (id, provider, provider_event_id, payload) " +
                    "VALUES ('a', 'peach', 'evt_1', '{}')"
            )
            val duplicated = runCatching {
                c.createStatement().execute(
                    "INSERT INTO webhook_events (id, provider, provider_event_id, payload) " +
                        "VALUES ('b', 'peach', 'evt_1', '{}')"
                )
            }
            assertTrue(duplicated.isFailure, "a duplicate provider event id was accepted")
        }
    }

    @Test
    fun a_machine_serial_cannot_be_claimed_by_two_organisations() {
        database.migrate()
        database.read { c ->
            c.createStatement().execute("INSERT INTO orgs (id, name) VALUES ('o1', 'Kobus')")
            c.createStatement().execute("INSERT INTO orgs (id, name) VALUES ('o2', 'Rival')")
            c.createStatement().execute(
                "INSERT INTO machines (id, org_id, serial, name) VALUES ('m1','o1','SN-001','Scraper')"
            )
            val stolen = runCatching {
                c.createStatement().execute(
                    "INSERT INTO machines (id, org_id, serial, name) VALUES ('m2','o2','SN-001','Scraper')"
                )
            }
            assertTrue(stolen.isFailure, "the same serial was claimed twice")
        }
    }

    @Test
    fun one_machine_carries_at_most_one_subscription() {
        // The billing unit is the machine; two live subscriptions on one machine
        // would double-bill a customer.
        database.migrate()
        database.read { c ->
            c.createStatement().execute("INSERT INTO orgs (id, name) VALUES ('o1', 'Kobus')")
            c.createStatement().execute(
                "INSERT INTO machines (id, org_id, serial, name) VALUES ('m1','o1','SN-002','Scraper')"
            )
            fun addSubscription(id: String) = c.createStatement().execute(
                "INSERT INTO subscriptions (id, org_id, machine_id, plan, status, current_period_end) " +
                    "VALUES ('$id','o1','m1','per_machine_monthly','active', now() + interval '30 days')"
            )
            addSubscription("s1")
            assertTrue(runCatching { addSubscription("s2") }.isFailure)
        }
    }

    @Test
    fun an_invalid_subscription_status_is_refused_by_the_database() {
        // Belt and braces behind the application: a typo in a status string
        // should not silently create a subscription nothing knows how to bill.
        database.migrate()
        database.read { c ->
            c.createStatement().execute("INSERT INTO orgs (id, name) VALUES ('o1', 'Kobus')")
            c.createStatement().execute(
                "INSERT INTO machines (id, org_id, serial, name) VALUES ('m1','o1','SN-003','Scraper')"
            )
            val bad = runCatching {
                c.createStatement().execute(
                    "INSERT INTO subscriptions (id, org_id, machine_id, plan, status, current_period_end) " +
                        "VALUES ('s1','o1','m1','per_machine_monthly','nonsense', now())"
                )
            }
            assertTrue(bad.isFailure)
        }
    }

    @Test
    fun deleting_an_org_takes_its_machines_and_subscriptions_with_it() {
        // A customer asking to be deleted must not leave billing rows behind.
        database.migrate()
        database.read { c ->
            c.createStatement().execute("INSERT INTO orgs (id, name) VALUES ('o1', 'Kobus')")
            c.createStatement().execute(
                "INSERT INTO machines (id, org_id, serial, name) VALUES ('m1','o1','SN-004','Scraper')"
            )
            c.createStatement().execute(
                "INSERT INTO subscriptions (id, org_id, machine_id, plan, status, current_period_end) " +
                    "VALUES ('s1','o1','m1','per_machine_monthly','active', now())"
            )
            c.createStatement().execute("DELETE FROM orgs WHERE id = 'o1'")

            c.createStatement().executeQuery("SELECT count(*) FROM subscriptions").use { rs ->
                rs.next()
                assertEquals(0, rs.getInt(1))
            }
        }
    }

    @Test
    fun a_transaction_rolls_back_as_a_unit() {
        database.migrate()
        runCatching {
            database.transaction { c ->
                c.createStatement().execute("INSERT INTO orgs (id, name) VALUES ('rollback', 'x')")
                error("something went wrong halfway through")
            }
        }
        database.read { c ->
            c.createStatement()
                .executeQuery("SELECT count(*) FROM orgs WHERE id = 'rollback'").use { rs ->
                    rs.next()
                    assertEquals(0, rs.getInt(1), "a failed transaction left data behind")
                }
        }
    }
}
