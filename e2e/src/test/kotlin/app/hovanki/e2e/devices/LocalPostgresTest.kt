package app.hovanki.e2e.devices

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LocalPostgresTest {
    @Test
    fun splitsTheCredentialsOffTheUrl() {
        val url = LocalPostgres.ServerUrl.parse(
            "jdbc:postgresql://localhost:5432/hovanki?user=hovanki&password=p%40ss&sslmode=disable",
        )

        assertEquals("hovanki", url.user)
        assertEquals("p@ss", url.password)
        assertEquals(
            "jdbc:postgresql://localhost:5432/hovanki_e2e_1?sslmode=disable",
            url.withDatabase("hovanki_e2e_1"),
        )
    }

    @Test
    fun withoutParameters() {
        val url = LocalPostgres.ServerUrl.parse("jdbc:postgresql://db:5433/postgres")

        assertEquals("", url.password)
        assertEquals("jdbc:postgresql://db:5433/other", url.withDatabase("other"))
    }

    @Test
    fun onlyPostgres() {
        assertFailsWith<IllegalArgumentException> { LocalPostgres.ServerUrl.parse("jdbc:mysql://localhost/db") }
    }
}
