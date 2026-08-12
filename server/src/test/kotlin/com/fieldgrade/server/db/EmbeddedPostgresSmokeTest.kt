package com.fieldgrade.server.db

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlin.test.Test
import kotlin.test.assertEquals

/** Does a real Postgres start here, without the Docker daemon? */
class EmbeddedPostgresSmokeTest {
    @Test
    fun starts_and_answers_a_query() {
        EmbeddedPostgres.builder().start().use { pg ->
            pg.postgresDatabase.connection.use { c ->
                c.createStatement().executeQuery("select 42").use { rs ->
                    rs.next()
                    assertEquals(42, rs.getInt(1))
                }
            }
        }
    }
}
