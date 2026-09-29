package app.hovanki.server.api

import app.hovanki.server.ratelimit.RateLimit
import app.hovanki.server.ratelimit.RateLimiter
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.ServerTimeResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

/**
 * The server's clock, without a token: a device measures its offset before any game (the debug build's radio lab
 * lines up the logs of several phones by it, docs/radio-lab.md §4.3). Nothing but the time; limited per client IP.
 */
@RestController
class TimeController(private val clock: Clock, private val rateLimiter: RateLimiter) {
    @GetMapping(ApiRoutes.TIME)
    fun time(http: HttpServletRequest): ServerTimeResponse {
        rateLimiter.acquire(RateLimit.TIME_PER_IP, http.remoteAddr)
        return ServerTimeResponse(clock.millis())
    }
}
