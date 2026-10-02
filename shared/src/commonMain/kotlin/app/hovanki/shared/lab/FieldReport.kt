package app.hovanki.shared.lab

import kotlinx.serialization.Serializable

// The report of a game's field log (docs/adr/0018-field-test-build.md §6, docs/field-test.md step 6), computed by the
// server from the logs of the game's phones and its own events ([FieldReportBuilder]) and shown to admins. The players
// are P1…Pn ([FieldReportPlayer.alias]) everywhere but [FieldReportPlayer.label] (their id in the game, for the raw
// logs); no nicknames, no coordinates: distances in meters only. Stored in `lab_reports` like a lab run's report.

@Serializable
data class FieldReport(
    val version: Int = VERSION,
    val runId: String,
    val gameId: String? = null,
    val computedAtMillis: Long,
    /** The whole game, computed after its run finished; false: a live report of the game so far. */
    val final: Boolean = false,
    /** The time windows it was computed in ([FieldReportStream]). */
    val windows: Int = 0,
    val summary: FieldReportSummary = FieldReportSummary(),
    /** The game: phases, claims, catches, disputes, reveals, glows, marks, the server's bad moments; in time order. */
    val timeline: List<FieldReportEntry> = emptyList(),
    /** Entries of the noisy kinds beyond [FieldReportBuilder.MAX_TIMELINE_PER_KIND] each, left out. */
    val timelineDropped: Int = 0,
    /** «Something is wrong» and the organizers' marks, each with what happened 30 s around it. */
    val marks: List<FieldReportMark> = emptyList(),
    val players: List<FieldReportPlayer> = emptyList(),
    val server: FieldReportServer = FieldReportServer(),
    val radar: FieldReportRadar = FieldReportRadar(),
    /**
     * The detectors' findings ([FieldAnomalies]), at most [FieldReportBuilder.MAX_ANOMALIES_PER_KIND] of a kind; all of
     * them counted.
     */
    val anomalies: List<FieldReportAnomaly> = emptyList(),
    val anomalyCounts: Map<String, Int> = emptyMap(),
    val problems: List<String> = emptyList(),
) {
    companion object {
        /** In `lab_reports.version` of a game's run; a lab run's report is a [LabReport]. */
        const val VERSION = 1
    }
}

/** A name and how many: models, systems, builds, answers. */
@Serializable
data class FieldReportCount(val name: String, val count: Int)

/** Section 1 of ADR 0018 §6. */
@Serializable
data class FieldReportSummary(
    val players: Int = 0,
    /** Phones that joined, rejoins included. */
    val devices: Int = 0,
    val models: List<FieldReportCount> = emptyList(),
    val os: List<FieldReportCount> = emptyList(),
    val builds: List<FieldReportCount> = emptyList(),
    val firstMillis: Long? = null,
    val lastMillis: Long? = null,
    /** The round: the first HIDING to FINISHED, by the server's events. */
    val roundStartMillis: Long? = null,
    val roundEndMillis: Long? = null,
    val roundSeconds: Long? = null,
    /** Players whose logs went quiet for good before the round ended. */
    val dropouts: List<FieldReportDropout> = emptyList(),
    /** Rejoins: a player's app started again in the game (a crash, or the system killed it). */
    val restarts: Int = 0,
    val errors: Int = 0,
    val sentryEvents: Int = 0,
    val marks: Int = 0,
    val surveys: Int = 0,
    val ratings: List<Int> = emptyList(),
    val ratingAverage: Double? = null,
    val broken: List<FieldReportCount> = emptyList(),
    val carry: List<FieldReportCount> = emptyList(),
)

@Serializable
data class FieldReportDropout(val player: String, val atMillis: Long)

/** A moment of the game (section 2): [player] an alias when it is about one; [text] what happened, aliases only. */
@Serializable
data class FieldReportEntry(val atMillis: Long, val kind: String, val player: String? = null, val text: String)

/** A mark ([by]: `player` / `staff`), its few words (scrubbed) and the timeline and anomalies ±30 s around it. */
@Serializable
data class FieldReportMark(
    val atMillis: Long,
    val player: String? = null,
    val by: String? = null,
    val text: String? = null,
    val around: List<String> = emptyList(),
)

/** Section 3: one player, all their phones (rejoins) together. */
@Serializable
data class FieldReportPlayer(
    val alias: String,
    /** The player's id in the game: the raw logs' file names. Admins only; never in report.md or digest.jsonl. */
    val label: String,
    val devices: Int = 1,
    val model: String? = null,
    val os: String? = null,
    val build: String? = null,
    val platform: String? = null,
    val firstMillis: Long? = null,
    val lastMillis: Long? = null,
    val droppedAtMillis: Long? = null,
    val events: Long = 0,
    val gps: FieldReportGps = FieldReportGps(),
    val sync: FieldReportSync = FieldReportSync(),
    /** The app not on the screen (iOS in the background, Android with the screen off). */
    val backgroundSeconds: Long = 0,
    val battery: FieldReportBattery = FieldReportBattery(),
    val thermal: List<String> = emptyList(),
    /** The permissions as last written (`location` → `always`…). */
    val permissions: Map<String, String> = emptyMap(),
    val errors: Int = 0,
    val marks: Int = 0,
    val survey: FieldReportSurvey? = null,
    /** Whom this phone's radio heard. */
    val heard: List<FieldReportHeard> = emptyList(),
    val reveals: Int = 0,
)

/**
 * GPS: [accP50]/[accP95] the accuracy of the fixes (m), [gaps] longer than [FieldReportBuilder.GPS_GAP_MILLIS],
 * [jumps] by [FieldAnomalies.gpsJump]; [serverAccepted]/[serverRefused]: what the server made of the fixes it got
 * (its `fixes` events, by the reason).
 */
@Serializable
data class FieldReportGps(
    val fixes: Int = 0,
    val accP50: Double? = null,
    val accP95: Double? = null,
    val gaps: Int = 0,
    val longestGapSeconds: Long = 0,
    val jumps: Int = 0,
    val serverAccepted: Int = 0,
    val serverRefused: Map<String, Int> = emptyMap(),
)

/** The syncs: [p50]/[p95] ms of the answered ones, the share over the socket, the answers' size, [stalls] > 20 s. */
@Serializable
data class FieldReportSync(
    val count: Int = 0,
    val ok: Int = 0,
    val errors: Int = 0,
    val p50: Long? = null,
    val p95: Long? = null,
    val socketPercent: Double? = null,
    val bytesP50: Long? = null,
    val bytesP95: Long? = null,
    val stalls: Int = 0,
)

/** The battery's level 0…1 first and last, and how fast it went (% an hour, over 10 minutes at least). */
@Serializable
data class FieldReportBattery(
    val firstLevel: Double? = null,
    val lastLevel: Double? = null,
    val percentPerHour: Double? = null,
    val lowPower: Boolean = false,
)

@Serializable
data class FieldReportSurvey(
    val rating: Int? = null,
    val broken: List<String> = emptyList(),
    val carry: String? = null,
    val text: String? = null,
)

/** [peer] (an alias) heard in [seconds] (one `rx` a second), [readings] in all, by these [channels]. */
@Serializable
data class FieldReportHeard(val peer: String, val seconds: Int, val readings: Long, val channels: List<String>)

/** The server's `srv` numbers over the game: the worst of every window. */
@Serializable
data class FieldReportServer(
    val samples: Int = 0,
    val syncs: Long = 0,
    val syncP50Max: Long? = null,
    val syncP95Max: Long? = null,
    val errors5xx: Long = 0,
    val errors429: Long = 0,
    val heapMaxMb: Long? = null,
    val heapLimitMb: Long? = null,
    val cpuMax: Double? = null,
    val dropped: Long = 0,
    val playersMax: Int? = null,
    val socketsMax: Int? = null,
)

/** Section 4. */
@Serializable
data class FieldReportRadar(
    /** Seconds with an RSSI and the pair's GPS distance, by the platforms (sender → listener). */
    val pairSeconds: List<FieldReportCount> = emptyList(),
    val rssi: List<FieldReportRssi> = emptyList(),
    val zeroPoints: List<FieldReportZero> = emptyList(),
    val coverage: List<FieldReportCoverage> = emptyList(),
    val masks: List<FieldReportMask> = emptyList(),
    val shadowRules: FieldReportShadowRules = FieldReportShadowRules(),
    val techniques: FieldReportTechniques? = null,
    /** Bluetooth against GPS: the RSSI and the band's agreement by phone models, carry and distance. */
    val btVsGps: List<FieldReportBtGps> = emptyList(),
    /** The pair-minutes where the band the game showed was farthest from the GPS distance ([FieldPairs.WORST]). */
    val worstMinutes: List<FieldReportPairMinute> = emptyList(),
)

/**
 * The RSSI ([median], [p10], [p90] dBm, of the seconds' medians) heard at a GPS distance [bucket] (`0-5`, `5-10`…
 * meters) between phones of [models] (`sender → listener`) carried [carry] (`sender/listener`: the carry monitor's).
 */
@Serializable
data class FieldReportRssi(
    val models: String,
    val carry: String,
    val bucket: String,
    val seconds: Int,
    val median: Int,
    val p10: Int,
    val p90: Int,
)

/**
 * A truth of «0 m»: a catch confirmed ([kind] `catch`) or a touch (`touch`) of [a] and [b], the loudest each heard of
 * the other then ([rssiAToB]: what b heard of a) and what GPS said of their distance.
 */
@Serializable
data class FieldReportZero(
    val kind: String,
    val atMillis: Long,
    val a: String,
    val b: String,
    val rssiAToB: Int? = null,
    val rssiBToA: Int? = null,
    val gpsMeters: Double? = null,
)

/**
 * Of the round's seconds a [sender] (platform, with the Android hider's layout) was within
 * [FieldReportBuilder.COVERAGE_METERS] of a [listener] by GPS, both advertising, how many it heard them in.
 */
@Serializable
data class FieldReportCoverage(
    val sender: String,
    val listener: String,
    val nearSeconds: Int,
    val heardSeconds: Int,
    val percent: Double? = null,
)

/** The shadow's channels ([tech], `ble.overflow`…): frames a [listener] platform read, [resolved] to a player's phone. */
@Serializable
data class FieldReportMask(
    val tech: String,
    val listener: String,
    val sender: String? = null,
    val frames: Int,
    val resolved: Int,
)

/**
 * The rules in the shadow (ADR 0018 §3.3): of the [claims], [claimsWithShadow] had the proximity rule's answer and it
 * would have taken [shadowAccepted]; of the confirmed [catches], it would have refused [catchesShadowWouldRefuse].
 * The pocket stealth: of the server's [bandChanges], [bandShifted] had a shadow band other than the game's ([shifts]:
 * `band→shadow`).
 */
@Serializable
data class FieldReportShadowRules(
    val claims: Int = 0,
    val claimsWithShadow: Int = 0,
    val shadowAccepted: Int = 0,
    val catches: Int = 0,
    val catchesShadowWouldRefuse: Int = 0,
    val bandChanges: Int = 0,
    val bandShifted: Int = 0,
    val shifts: List<FieldReportCount> = emptyList(),
)

/**
 * The lab's techniques where they apply to a game (docs/adr/0017-radar-techniques-and-big-run.md §3, §7): the touches
 * the lab's detector finds in the players' knocks and readings ([TouchDetector], the touch card's presses its truth),
 * their spread and drift, the presses it [missedTouches], and «without X» ([WithoutChannel]). A game has no step
 * distances: the cards and the band errors, which need them, are the lab run's. [computed] false: the game had more
 * readings than the server reads for them ([note]).
 */
@Serializable
data class FieldReportTechniques(
    val computed: Boolean = true,
    val note: String? = null,
    val touches: List<LabReportTouch> = emptyList(),
    val touchSpreads: List<LabReportTouchSpread> = emptyList(),
    val missedTouches: Int = 0,
    val without: List<LabReportWithout> = emptyList(),
)

/**
 * Seconds a sender was heard at a GPS distance [bucket] by phones of [models] (`sender → listener`) carried [carry]:
 * the RSSI's [median], [p20] and [p80] dBm, and of the [bandSeconds] where the game showed a band for the pair, how
 * many it [bandAgree]d with the distance ([FieldPairs.disagreement] is zero).
 */
@Serializable
data class FieldReportBtGps(
    val models: String,
    val carry: String,
    val bucket: String,
    val seconds: Int,
    val median: Int,
    val p20: Int,
    val p80: Int,
    val bandSeconds: Int = 0,
    val bandAgree: Int = 0,
)

/**
 * A pair of players ([a], [b]: aliases, a before b) in one minute ([atMillis]: its start): the GPS distance (m: median
 * and least of the seconds both had a fix; its accuracy per player), the RSSI of a's signal at b ([rssiAb]: the median
 * of the seconds, [readingsAb] readings) and of b's at a, the loudest [band] the game showed either way and the pocket
 * stealth's [shadowBand], how each phone was carried, their platforms and models, the channels heard, and
 * [disagreementM]: how far the band is from the distance in meters beyond the GPS's error (null: no band).
 */
@Serializable
data class FieldReportPairMinute(
    val atMillis: Long,
    val a: String,
    val b: String,
    val gpsMedian: Double? = null,
    val gpsMin: Double? = null,
    val accA: Double? = null,
    val accB: Double? = null,
    val rssiAb: Int? = null,
    val rssiBa: Int? = null,
    val readingsAb: Int = 0,
    val readingsBa: Int = 0,
    val band: String? = null,
    val shadowBand: String? = null,
    val carryA: String? = null,
    val carryB: String? = null,
    val platA: String? = null,
    val platB: String? = null,
    val modelA: String? = null,
    val modelB: String? = null,
    val channels: List<String> = emptyList(),
    val disagreementM: Double? = null,
)

/** A detector's finding ([kind]: [FieldAnomalies]'s), from [atMillis] (to [untilMillis]), about [player]. */
@Serializable
data class FieldReportAnomaly(
    val kind: String,
    val atMillis: Long,
    val untilMillis: Long? = null,
    val player: String? = null,
    val detail: String = "",
)
