package app.hovanki.shared.lab

import app.hovanki.shared.crash.SentryScrubber
import kotlin.math.roundToLong

/**
 * The detectors of section 5 of the field report (docs/adr/0018-field-test-build.md §6): each a pure function of what
 * it looks at, returning the finding or null. [FieldReportBuilder] feeds them as it reads the game's logs; players are
 * aliases (P1…Pn), texts are scrubbed ([SentryScrubber]), no coordinates.
 */
object FieldAnomalies {
    /** A phone's log silent for longer than [JOURNAL_GAP_MILLIS] in the round, its app not gone to the background. */
    const val JOURNAL_GAP = "journal_gap"

    /** A sync answered after more than [SYNC_STALL_MILLIS], or failing for that long. */
    const val SYNC_STALL = "sync_stall"

    /** Two fixes of a phone [GPS_JUMP_METERS] apart, faster than a person runs ([GPS_JUMP_SPEED]). */
    const val GPS_JUMP = "gps_jump"

    /** A claim refused while the players' phones said by GPS they were within [REFUSED_NEAR_METERS]. */
    const val REFUSED_NEAR = "refused_near"

    /** An exception a phone caught (`err`). */
    const val ERROR = "err"

    /** The server answered with 5xx in a `srv` window. */
    const val SERVER_ERRORS = "srv_5xx"

    /** The server's sync p95 above [SERVER_SLOW_MILLIS] in a `srv` window. */
    const val SERVER_SLOW = "srv_slow"

    const val JOURNAL_GAP_MILLIS = 60_000L
    const val SYNC_STALL_MILLIS = 20_000L
    const val GPS_JUMP_METERS = 100.0
    const val GPS_JUMP_SPEED = 12.0
    const val REFUSED_NEAR_METERS = 10.0
    const val SERVER_SLOW_MILLIS = 1_000L

    /** The app states, and the life events, of an app not on the screen: a silence then is the OS's, not a bug. */
    val BACKGROUND_STATES: Set<String> = setOf("background", "screen_off")
    val BACKGROUND_LIFE: Set<String> = setOf("background", "did_enter_background", "screen_off")

    /**
     * [player]'s log silent from [fromMillis] to [toMillis] (both in the round): a gap, unless the app was last seen
     * not on the screen ([appBefore], the state of the last event before it) or said it went there ([lifeBefore], its
     * last life event).
     */
    fun journalGap(
        player: String,
        fromMillis: Long,
        toMillis: Long,
        appBefore: String?,
        lifeBefore: String?,
    ): FieldReportAnomaly? {
        if (toMillis - fromMillis <= JOURNAL_GAP_MILLIS) return null
        if (appBefore in BACKGROUND_STATES || lifeBefore in BACKGROUND_LIFE) return null
        return FieldReportAnomaly(
            JOURNAL_GAP,
            fromMillis,
            toMillis,
            player,
            "no events for ${seconds(toMillis - fromMillis)} s, app ${appBefore ?: "-"}",
        )
    }

    /**
     * [player]'s syncs: one answered at [atMillis] after [waitedMillis], or a run of failures from [failingSinceMillis]
     * to [atMillis] (the next answer, or the log's end).
     */
    fun syncStall(
        player: String,
        atMillis: Long,
        waitedMillis: Long? = null,
        failingSinceMillis: Long? = null,
        lastError: String? = null,
    ): FieldReportAnomaly? = when {
        waitedMillis != null && waitedMillis > SYNC_STALL_MILLIS -> FieldReportAnomaly(
            SYNC_STALL,
            atMillis - waitedMillis,
            atMillis,
            player,
            "a sync answered after ${seconds(waitedMillis)} s",
        )

        failingSinceMillis != null && atMillis - failingSinceMillis > SYNC_STALL_MILLIS -> FieldReportAnomaly(
            SYNC_STALL,
            failingSinceMillis,
            atMillis,
            player,
            "syncs failing for ${seconds(atMillis - failingSinceMillis)} s" +
                (lastError?.let { ": ${SentryScrubber.text(it).take(MAX_DETAIL)}" } ?: ""),
        )

        else -> null
    }

    /** Two consecutive fixes of [player], [meters] apart over [millis]. */
    fun gpsJump(player: String, atMillis: Long, meters: Double, millis: Long): FieldReportAnomaly? {
        if (meters <= GPS_JUMP_METERS) return null
        val speed = meters / (millis.coerceAtLeast(1_000L) / 1000.0)
        if (speed <= GPS_JUMP_SPEED) return null
        return FieldReportAnomaly(
            GPS_JUMP,
            atMillis - millis,
            atMillis,
            player,
            "${meters.roundToLong()} m in ${seconds(millis)} s",
        )
    }

    /**
     * A claim of [seeker] on [hider] refused with [outcome] at [atMillis] while their phones' GPS said [gpsMeters]
     * (null: not known) and the server's closest [serverMeters].
     */
    fun refusedNear(
        seeker: String,
        hider: String,
        atMillis: Long,
        outcome: String,
        gpsMeters: Double?,
        serverMeters: Double? = null,
    ): FieldReportAnomaly? {
        if (outcome == CLAIM_OPEN || gpsMeters == null || gpsMeters >= REFUSED_NEAR_METERS) return null
        return FieldReportAnomaly(
            REFUSED_NEAR,
            atMillis,
            null,
            seeker,
            "claim on $hider refused ($outcome) at ${round1(gpsMeters)} m by the phones' GPS" +
                (serverMeters?.let { ", the server's closest ${round1(it)} m" } ?: ""),
        )
    }

    /** An exception [player]'s phone caught: its class, where, and its Sentry event if it went there. */
    fun error(
        player: String,
        atMillis: Long,
        type: String?,
        where: String?,
        message: String?,
        sentryId: String?,
    ): FieldReportAnomaly = FieldReportAnomaly(
        ERROR,
        atMillis,
        null,
        player,
        listOfNotNull(
            type ?: "?",
            where?.let { "in $it" },
            message?.let { SentryScrubber.text(it).take(MAX_DETAIL) },
            sentryId?.let { "sentry $it" },
        ).joinToString(" · "),
    )

    /** The server's `srv` of [atMillis]: 5xx in its window, or its sync p95 too slow; one finding each. */
    fun server(atMillis: Long, errors5xx: Long?, syncP95: Long?): List<FieldReportAnomaly> = listOfNotNull(
        errors5xx?.takeIf {
            it > 0
        }?.let { FieldReportAnomaly(SERVER_ERRORS, atMillis, null, null, "$it answers 5xx") },
        syncP95?.takeIf { it > SERVER_SLOW_MILLIS }?.let {
            FieldReportAnomaly(SERVER_SLOW, atMillis, null, null, "sync p95 $it ms")
        },
    )

    /** A claim's outcome when it was taken (a catch opened), not refused. */
    const val CLAIM_OPEN = "open"

    private const val MAX_DETAIL = 120

    private fun seconds(millis: Long): Long = (millis + 500) / 1000

    private fun round1(value: Double): Double = (value * 10).roundToLong() / 10.0
}
