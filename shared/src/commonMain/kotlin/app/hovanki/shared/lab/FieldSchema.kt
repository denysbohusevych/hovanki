package app.hovanki.shared.lab

/**
 * The kinds the field log adds to the lab's (docs/adr/0018-field-test-build.md §3.2), and the lab's ones it writes
 * differently. Still [LabSchema.VERSION] 2: every field of theirs is optional, a reader skips what it doesn't know.
 * Coordinates only in a field run's `gps` ([LabSchema.COORDINATES]).
 */
object FieldKinds {
    /** A GPS fix ([GpsFields]): in the field log with the coordinates, at most once a second; in the lab without. */
    const val GPS = "gps"

    /** A sync with the game's server ([SyncFields]). */
    const val SYNC = "sync"

    /** A screen opened or closed, or a tap by what it means ([UiFields]); never a text. */
    const val UI = "ui"

    /** The app's permissions as they are now ([PermFields]). */
    const val PERM = "perm"

    /** An exception caught ([ErrFields]). */
    const val ERR = "err"

    /** «Something is wrong» from the player ([MarkFields]); the lab's marks are this kind too. */
    const val MARK = "mark"

    /** The answers after the game ([SurveyFields]). */
    const val SURVEY = "survey"

    /** The server's own numbers every 10 s, written by the device [SERVER_DEVICE] (ADR 0018 §2; [SrvFields]). */
    const val SRV = "srv"

    /** A reading of the radio ([RxFields]): in the field log once a second per peer, in the lab every reading. */
    const val RX = "rx"

    /** A raw frame of ours (ADR 0017 §4): in the field log once per 10 s. */
    const val FRAME = "frame"

    /** The others' frames counted (ADR 0017 §4): in the field log once per 10 s. */
    const val AIR = "air"

    /** Once a second while the process lives: a gap in the ticks is the app suspended. */
    const val TICK = "tick"

    /** The app's life: `foreground`, `background`… */
    const val LIFE = "life"

    /** The battery: level, state, low power. */
    const val BATTERY = "battery"

    /** How hot the phone says it is: `state`, written on a change (the field log's own kind). */
    const val THERMAL = "thermal"

    /** Who the device is: model, OS, build. */
    const val SESSION = "session"

    /** The kinds the field log writes at most once per window (`FieldJoinResponse.frameEveryMillis`). */
    val THROTTLED: Set<String> = setOf(FRAME, AIR)

    /** The label of the server's own events in a field run (ADR 0018 §3.3). */
    const val SERVER_DEVICE = "server"
}

/** `gps`. */
object GpsFields {
    /** Degrees, WGS 84: only in a field run. */
    const val LAT = "lat"
    const val LON = "lon"

    /** Accuracy, meters. */
    const val ACC = "acc"

    /** How old the fix was when the app got it, ms. */
    const val AGE = "age"

    /** m/s, where the phone says. */
    const val SPEED = "speed"

    /** Degrees from north, where the phone says. */
    const val BEARING = "bearing"

    /** Whether the game took the fix; [REJECTED]: why not. */
    const val ACCEPTED = "accepted"
    const val REJECTED = "rejected"

    /** The OS says the fix was simulated. */
    const val MOCK = "mock"
}

/** `sync`. */
object SyncFields {
    /** [POLL] or [SOCKET]. */
    const val TRANSPORT = "transport"
    const val POLL = "poll"
    const val SOCKET = "socket"
    const val OK = "ok"

    /** From the request to the answer, ms. */
    const val MILLIS = "millis"

    /** The HTTP status of a refusal, or the socket's close code. */
    const val CODE = "code"
    const val ERROR = "error"

    /** The answer's size in bytes, where known. */
    const val BYTES = "bytes"

    /** The game's phase now and before, when it changed with this answer. */
    const val PHASE = "phase"
    const val FROM = "from"
}

/** `ui`. */
object UiFields {
    const val SCREEN = "screen"

    /** [OPEN], [CLOSE] or [TAP]. */
    const val EVENT = "event"
    const val OPEN = "open"
    const val CLOSE = "close"
    const val TAP = "tap"

    /** What a tap means: `catch_claim`, `open_chat`… */
    const val ACTION = "action"
}

/** `perm`: one field per permission, its state as a short word (`always`, `when_in_use`, `denied`, `on`, `off`…). */
object PermFields {
    const val LOCATION = "location"
    const val PRECISE = "precise"
    const val BLUETOOTH = "bluetooth"
    const val NOTIFICATIONS = "notifications"
    const val CAMERA = "camera"
    const val POWER_SAVER = "power_saver"

    /** Android: `on` when the system may put the app to sleep to save the battery (the game wants `off`). */
    const val BATTERY_OPTIMIZATION = "battery_opt"
}

/** `err`. */
object ErrFields {
    /** The exception's class. */
    const val CLASS = "class"
    const val MESSAGE = "message"

    /** Where it was caught. */
    const val WHERE = "where"

    /** The Sentry event's id, if it went there too. */
    const val SENTRY_ID = "sentry_id"
}

/** `mark` (the lab's marks have `label`, `step`, `place`… too). */
object MarkFields {
    /** Who marked: [PLAYER] from the game, [STAFF] from the admin; `tester`, `scenario`, `run` in the lab. */
    const val BY = "by"
    const val PLAYER = "player"
    const val STAFF = "staff"

    /** The player's few words, if any. */
    const val TEXT = "text"
    const val LABEL = "label"
}

/** `survey`: the three questions after the game (ADR 0018 §5). */
object SurveyFields {
    /** 1–5. */
    const val RATING = "rating"

    /** What broke, from the list. */
    const val BROKEN = "broken"
    const val TEXT = "text"

    /** Where the phone was: `hand`, `pocket`, `bag`, `mixed`. */
    const val CARRY = "carry"
}

/** `srv` (ADR 0018 §2): the server's numbers in the window. */
object SrvFields {
    const val SYNC_P50 = "sync_p50"
    const val SYNC_P95 = "sync_p95"
    const val ERRORS_5XX = "e5xx"
    const val ERRORS_429 = "e429"
    const val SOCKETS = "sockets"
    const val GAMES = "games"
    const val PLAYERS = "players"
    const val HEAP_MB = "heap_mb"
    const val CPU = "cpu"
}

/** `rx` in the field log: one event per peer and window. */
object RxFields {
    const val TOKEN = "token"
    const val API = "api"
    const val VIA = "via"
    const val PEER = "peer"

    /** The channel that read it (`RadarCatalog`'s id, `:radar`). */
    const val TECH = "tech"

    /** The lab: the reading; the field: the median of the window. */
    const val RSSI = "rssi"

    /** The field: how many readings, and the loudest. */
    const val COUNT = "n"
    const val MAX = "max"
}
