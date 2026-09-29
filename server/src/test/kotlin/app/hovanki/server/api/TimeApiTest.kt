package app.hovanki.server.api

import app.hovanki.server.MutableClock
import app.hovanki.server.account.AccountTestConfig
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.ServerTimeResponse
import app.hovanki.shared.protocol.protocolJson
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/** The server's clock without a token (docs/radio-lab.md §4.3). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AccountTestConfig::class)
class TimeApiTest(@Autowired private val mvc: MockMvc, @Autowired private val clock: MutableClock) {
    @Test
    fun anybodyGetsTheServerClock() {
        assertEquals(clock.millis(), time())
        clock.advance(Duration.ofSeconds(7))
        assertEquals(clock.millis(), time())
    }

    private fun time(): Long {
        val response = mvc.get(ApiRoutes.TIME).andReturn().response
        assertEquals(200, response.status, response.contentAsString)
        return protocolJson.decodeFromString<ServerTimeResponse>(response.contentAsString).serverTimeMillis
    }
}
