package app.hovanki.server.live.v2

import org.springframework.boot.context.properties.ConfigurationProperties

/** `hovanki.live.*`: the live channel v2 (docs/live-v2/server.md). */
@ConfigurationProperties("hovanki.live")
class LiveProperties(
    /**
     * 32 bytes in base64 (env `HOVANKI_LIVE_RESETSECRET`): the key of every session's RESET tag (docs/live-v2/spec.md
     * §5.5), the same through a restart, so the phones know a RESET of the server before the restart for their own.
     * Empty: a random one at every start; a phone then waits for its Ups to go unanswered instead. Never logged.
     */
    val resetSecret: String = "",
) {
    override fun toString(): String = "LiveProperties(resetSecret=${if (resetSecret.isEmpty()) "" else "***"})"
}
