package app.hovanki.shared.live

/**
 * The numbers of the live channel v2 (docs/live-v2/spec.md §14), the same for the server and the apps. Changing one
 * changes the protocol: say so in the spec.
 */
object LiveLimits {
    /** The biggest UDP payload either side sends: it gets through almost everywhere. */
    const val MAX_DATAGRAM_BYTES = 1200

    /** A DATA datagram's header: type, session id, packet number. */
    const val HEADER_BYTES = 13

    /** AES-GCM's tag. */
    const val TAG_BYTES = 16

    /** The plaintext of one datagram: its kind byte and one message. */
    const val MAX_PLAINTEXT_BYTES = MAX_DATAGRAM_BYTES - HEADER_BYTES - TAG_BYTES

    /** One whole message in one datagram (after the kind byte). */
    const val MAX_MESSAGE_IN_DATAGRAM = MAX_PLAINTEXT_BYTES - 1

    /** One fragment's bytes (after the kind, the message id, the index and the count). */
    const val MAX_FRAGMENT_DATA = MAX_PLAINTEXT_BYTES - 5

    /** The most fragments of one message: 74 624 bytes. */
    const val MAX_FRAGMENTS = 64

    /** A message whose fragments did not all come in this long is lost. */
    const val FRAGMENT_TIMEOUT_MILLIS = 2_000L

    /** Messages the receiver puts together at the same time. */
    const val MAX_REASSEMBLIES = 2

    /** Packet numbers below the highest seen that may still come once. */
    const val REPLAY_WINDOW = 64

    /** Datagrams of a live session that did not decrypt before the server closes it. */
    const val MAX_AUTH_FAILURES = 1_000

    /** Datagrams one key encrypts at most, in one direction. */
    const val MAX_DATAGRAMS_PER_KEY = 1 shl 20

    /** A live session lives at most this long: then the phone opens a new one. */
    const val SESSION_MAX_AGE_MILLIS = 3_600_000L

    /** The phone opens the next live session this long before the current one expires. */
    const val REOPEN_BEFORE_MILLIS = 300_000L

    /** The server forgets a live session without a good Up for this long. */
    const val SESSION_IDLE_MILLIS = 60_000L

    /** The longest pause between two Ups in any phase: keeps the carrier's NAT open. */
    const val IDLE_UP_MILLIS = 10_000L

    /** Pushes to one live session at most once in this long (as the pokes of v1). */
    const val PUSH_GAP_MILLIS = 500L

    /** Ups the server answers in [UP_WINDOW_MILLIS]; the rest are dropped. */
    const val MAX_UPS_PER_WINDOW = 12
    const val UP_WINDOW_MILLIS = 5_000L

    /** Live sessions of one player; a new one closes the oldest. */
    const val MAX_SESSIONS_PER_PLAYER = 3

    /** Opens of one player a minute. */
    const val MAX_OPENS_PER_MINUTE = 10

    /** Fixes and radar sightings in one Up, as one sync of v1. */
    const val MAX_FIXES_PER_UP = 100
    const val MAX_SIGHTINGS_PER_UP = 200

    /** Chat in one Down; the rest waits for the next Up ([app.hovanki.shared.live.v2.Down.chat_more]). */
    const val MAX_CHAT_BYTES = 8_192

    /** One binary frame of the WebSocket v2. */
    const val MAX_WS_FRAME_BYTES = 131_072

    /** States the server keeps per session (their hashes), and states the phone keeps (their sections). */
    const val SERVER_STATES_KEPT = 4
    const val CLIENT_STATES_KEPT = 8

    /** Ups of a new session over UDP without any Down before the phone takes the WebSocket v2, and the gap between. */
    const val OPEN_UDP_TRIES = 3
    const val OPEN_UDP_GAP_MILLIS = 1_500L

    /** Ups in a row without a Down before the phone gives up UDP in a game. */
    const val UDP_LOST_AFTER_UPS = 3

    /**
     * After falling back to the WebSocket v2, the phone tries UDP again this long later (or when the network changes).
     */
    const val UDP_RETRY_MILLIS = 600_000L

    /** After the server turned v2 off, the phone stays on v1 this long. */
    const val V1_AFTER_DISABLED_MILLIS = 600_000L

    /** RESET datagrams the server sends a second at most. */
    const val MAX_RESETS_PER_SECOND = 100

    /**
     * A player silent for longer than this is not connected: the lobby shows it, and the server sends their
     * `Presence.last_seen_ms` (docs/live-v2/spec.md §9.1). The lobby's own threshold is the same number.
     */
    const val OFFLINE_AFTER_MILLIS = 20_000L
}
