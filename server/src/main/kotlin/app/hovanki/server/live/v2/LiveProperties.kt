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
    /** The UDP of the live channel v2 (docs/live-v2/spec.md §5, server.md §4.3), used while `LIVE_UDP` is on. */
    val udp: Udp = Udp(),
) {
    class Udp(
        /**
         * The port the server listens on, on every address of the machine, from the first open that offers UDP while
         * `LIVE_UDP` is on; 0: any free one (the tests). compose publishes 443/udp to it.
         */
        val port: Int = 8443,
        /** The host the open hands the phones; empty: the one the phone asked the open of (its `Host`). */
        val host: String = "",
        /**
         * The port the open hands the phones; 0: [port] as it was bound. Production's is 443 (env
         * `HOVANKI_LIVE_UDP_PUBLICPORT`), the one compose publishes to [port].
         */
        val publicPort: Int = 0,
        /** The threads that take the datagrams in; each takes its Up to the end, the game's sync and the Down. */
        val threads: Int = 2,
    ) {
        override fun toString(): String = "Udp(port=$port, host=$host, publicPort=$publicPort, threads=$threads)"
    }

    override fun toString(): String =
        "LiveProperties(resetSecret=${if (resetSecret.isEmpty()) "" else "***"}, udp=$udp)"
}
