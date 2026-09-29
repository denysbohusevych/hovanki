package app.hovanki.shared.rules

import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.protocol.FeatureMode
import app.hovanki.shared.protocol.GameFeatures
import app.hovanki.shared.protocol.GameRules
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.QuestKind
import app.hovanki.shared.protocol.ZoneSchedule
import app.hovanki.shared.protocol.ZoneShape
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the host chooses on the setup screen (docs/adr/0009-game-setup-glow-streets.md), and the [GameSettings] it
 * makes: a zone of [radiusMeters] around the host that, when it [shrinks], narrows in [SHRINK_STEPS] stages over the
 * first [SHRINK_SHARE] of the search down to [FINAL_RADIUS_SHARE] of its size, then keeps squeezing to the size of a
 * catch until almost the end ([withEndgame]); the glow every [glowEveryMinutes] (0: off) for [glowForSeconds]; the
 * [features] the host turned on and the catalog [quests] picked (docs/adr/0012-nearby-radar.md,
 * docs/adr/0013-quests-sparks-and-sensors.md). The defaults are what the app starts a game with. Serializable: the
 * phone remembers the host's last choices for the next game (no place in them: the zone goes around wherever that game
 * is, and the items of the board are placed there).
 */
@Serializable
data class GameSetup(
    val radiusMeters: Int = 500,
    val hidingMinutes: Int = 5,
    val seekingMinutes: Int = 30,
    val shrinks: Boolean = true,
    val zoneShape: ZoneShape = ZoneShape.CIRCLE,
    val glowEveryMinutes: Int = 5,
    val glowForSeconds: Int = 5,
    val features: GameFeatures = GameFeatures(),
    val quests: List<QuestKind> = emptyList(),
    /** Spectators may watch (docs/adr/0011-spectators-and-recordings.md), [spectatorDelaySeconds] behind. */
    val openGame: Boolean = false,
    val spectatorDelaySeconds: Int = 60,
) {
    /** This setup as a game around [center]; [rules] are the thresholds, not chosen on the screen. */
    fun settings(center: GeoPoint, rules: GameRules = GameRules()): GameSettings {
        val seekingSeconds = seekingMinutes * SECONDS_PER_MINUTE
        val zone = if (shrinks) {
            // Each stage: a hold with the next zone announced, then the shrink (a zone by streets switches at its end).
            val stageSeconds = (seekingSeconds * SHRINK_SHARE / SHRINK_STEPS).roundToInt()
            val shrinkSeconds = minOf(MAX_SHRINK_SECONDS, stageSeconds / 3)
            shrinkingZone(
                center,
                initialRadiusMeters = radiusMeters.toDouble(),
                finalRadiusMeters = maxOf(MIN_FINAL_RADIUS_METERS, radiusMeters * FINAL_RADIUS_SHARE),
                steps = SHRINK_STEPS,
                holdSeconds = stageSeconds - shrinkSeconds,
                shrinkSeconds = shrinkSeconds,
            ).withEndgame(seekingSeconds)
        } else {
            shrinkingZone(center, initialRadiusMeters = radiusMeters.toDouble(), steps = 0)
        }
        return GameSettings(
            zone = zone,
            hidingSeconds = hidingMinutes * SECONDS_PER_MINUTE,
            seekingSeconds = seekingSeconds,
            rules = rules,
            glowEverySeconds = glowEveryMinutes * SECONDS_PER_MINUTE,
            glowForSeconds = if (glowEveryMinutes > 0) glowForSeconds else 0,
            zoneShape = zoneShape,
            features = features,
            quests = if (features.quests) quests else emptyList(),
            openGame = openGame,
            spectatorDelaySeconds = spectatorDelaySeconds,
        )
    }

    /**
     * Every choice within what the screen offers; a glow ends before the next one starts; what depends on the radar
     * goes off with it, and the quests are the pickable ones, each once, without the ones the setup can't judge.
     */
    fun coerced(): GameSetup {
        val every = if (glowEveryMinutes <= 0) 0 else glowEveryMinutes.coerceIn(GLOW_EVERY_MINUTES)
        val length = glowForSeconds.coerceIn(GLOW_FOR_SECONDS)
        val withRadar = features.radar != FeatureMode.OFF
        val coercedFeatures = features.copy(
            hiderSense = features.hiderSense && withRadar,
            proximityCatch = features.proximityCatch && withRadar,
            pocketStealth = features.pocketStealth && withRadar,
        )
        val glowOn = every > 0
        val coercedQuests = quests.distinct().filter { kind ->
            kind in QuestCatalog.pickable &&
                (!QuestCatalog.spec(kind).needsRadar || withRadar) &&
                (!QuestCatalog.spec(kind).needsGlow || glowOn)
        }
        return copy(
            radiusMeters = radiusMeters.coerceIn(RADIUS_METERS),
            hidingMinutes = hidingMinutes.coerceIn(HIDING_MINUTES),
            seekingMinutes = seekingMinutes.coerceIn(SEEKING_MINUTES),
            glowEveryMinutes = every,
            glowForSeconds = if (every > 0) minOf(length, every * SECONDS_PER_MINUTE - 1) else length,
            features = coercedFeatures,
            quests = coercedQuests,
            spectatorDelaySeconds = SPECTATOR_DELAYS.minBy { abs(it - spectatorDelaySeconds) },
        )
    }

    companion object {
        val RADIUS_METERS = 150..1500
        const val RADIUS_STEP_METERS = 50
        val HIDING_MINUTES = 1..15
        val SEEKING_MINUTES = 10..90
        const val SEEKING_STEP_MINUTES = 5
        val GLOW_EVERY_MINUTES = 1..15
        val GLOW_FOR_SECONDS = 2..60

        /** What the host can pick for the spectators' delay: live, half a minute, one, two or five minutes. */
        val SPECTATOR_DELAYS = listOf(0, 30, 60, 120, 300)

        const val SHRINK_STEPS = 3
        const val SHRINK_SHARE = 0.7
        const val FINAL_RADIUS_SHARE = 0.2
        const val MIN_FINAL_RADIUS_METERS = 60.0
        const val MAX_SHRINK_SECONDS = 120
        private const val SECONDS_PER_MINUTE = 60

        /**
         * The choices [settings] was made of, as the setup screen shows them: a game made by [settings] comes back
         * the same; others (older apps, tests) as close as the screen gets.
         */
        fun of(settings: GameSettings): GameSetup = GameSetup(
            radiusMeters = settings.zone.initial.radiusMeters.roundToInt(),
            hidingMinutes = (settings.hidingSeconds + SECONDS_PER_MINUTE / 2) / SECONDS_PER_MINUTE,
            seekingMinutes = (settings.seekingSeconds + SECONDS_PER_MINUTE / 2) / SECONDS_PER_MINUTE,
            shrinks = settings.zone.stages.isNotEmpty(),
            zoneShape = settings.zoneShape,
            glowEveryMinutes = if (Glow.isOn(settings)) {
                ((settings.glowEverySeconds + SECONDS_PER_MINUTE / 2) / SECONDS_PER_MINUTE).coerceAtLeast(1)
            } else {
                0
            },
            glowForSeconds = if (Glow.isOn(settings)) settings.glowForSeconds else GameSetup().glowForSeconds,
            features = settings.features,
            quests = settings.quests,
            openGame = settings.openGame,
            spectatorDelaySeconds = settings.spectatorDelaySeconds,
        )
    }
}

/**
 * What the server accepts as game settings (anything the setup screen makes, and the short timers of the tests), so
 * nobody can ask for a zone the size of a city or a game that never ends.
 */
object SettingsLimits {
    const val MIN_ZONE_RADIUS_METERS = 10.0
    const val MAX_ZONE_RADIUS_METERS = 3_000.0
    const val MAX_STAGES = 10
    const val MAX_STAGE_SECONDS = 3_600
    const val MAX_HIDING_SECONDS = 3_600
    const val MAX_SEEKING_SECONDS = 4 * 3_600
    const val MAX_GLOW_EVERY_SECONDS = 3_600
    const val MAX_GLOW_FOR_SECONDS = 600

    /** The spectators of an open game are at most this far behind it (docs/adr/0011-spectators-and-recordings.md). */
    const val MAX_SPECTATOR_DELAY_SECONDS = 600

    /**
     * The host opens at most this many buildings for hiding, each within [OPEN_BUILDING_MARGIN_METERS] of the zone:
     * the buildings are loaded that far around it (docs/adr/0014-settings-lobby-redesign-open-buildings.md).
     */
    const val MAX_OPEN_BUILDINGS = 10
    const val OPEN_BUILDING_MARGIN_METERS = 50.0

    /**
     * How far the host may move the zone's center in the lobby from where the game was made
     * (docs/adr/0014-settings-lobby-redesign-open-buildings.md): the next park, not another town.
     */
    const val MAX_CENTER_MOVE_METERS = 3_000.0

    /** Whether [point] is near enough to [zone] for a building there to be open. */
    fun isNearZone(point: GeoPoint, zone: ZoneSchedule): Boolean {
        val around = zone.boundingCircle(OPEN_BUILDING_MARGIN_METERS)
        return point.distanceTo(around.center) <= around.radiusMeters
    }

    /** What is wrong with [settings]; null when the server takes them. */
    fun problem(settings: GameSettings): String? {
        val zone = settings.zone
        val circles = listOf(zone.initial) + zone.stages.map { it.target }
        return when {
            circles.any { it.radiusMeters !in MIN_ZONE_RADIUS_METERS..MAX_ZONE_RADIUS_METERS } ->
                "The zone is ${MIN_ZONE_RADIUS_METERS.toInt()}..${MAX_ZONE_RADIUS_METERS.toInt()} m"

            zone.stages.size > MAX_STAGES -> "At most $MAX_STAGES zone stages"

            zone.stages.any { it.holdSeconds !in 0..MAX_STAGE_SECONDS || it.shrinkSeconds !in 0..MAX_STAGE_SECONDS } ->
                "A zone stage lasts up to $MAX_STAGE_SECONDS s"

            zone.boundingCircle().radiusMeters > MAX_ZONE_RADIUS_METERS * 2 -> "The zone moves too far"

            settings.hidingSeconds !in 0..MAX_HIDING_SECONDS -> "Hiding takes 0..$MAX_HIDING_SECONDS s"

            settings.seekingSeconds !in 1..MAX_SEEKING_SECONDS -> "The search takes 1..$MAX_SEEKING_SECONDS s"

            settings.glowEverySeconds !in 0..MAX_GLOW_EVERY_SECONDS ||
                settings.glowForSeconds !in 0..MAX_GLOW_FOR_SECONDS ->
                "The glow is every 0..$MAX_GLOW_EVERY_SECONDS s for 0..$MAX_GLOW_FOR_SECONDS s"

            Glow.isOn(settings) && settings.glowForSeconds >= settings.glowEverySeconds ->
                "A glow ends before the next one"

            settings.features.hiderSense && !settings.features.hasRadar -> "The hider's sense needs the radar"

            settings.features.proximityCatch && !settings.features.hasRadar -> "A claim up close needs the radar"

            settings.features.pocketStealth && !settings.features.hasRadar -> "The pocket stealth needs the radar"

            settings.quests.size != settings.quests.distinct().size -> "A quest is picked twice"

            settings.quests.isNotEmpty() && !settings.features.quests -> "Quests are picked but off"

            settings.spectatorDelaySeconds !in 0..MAX_SPECTATOR_DELAY_SECONDS ->
                "Spectators are 0..$MAX_SPECTATOR_DELAY_SECONDS s behind"

            settings.openBuildings.orEmpty().size > MAX_OPEN_BUILDINGS -> "At most $MAX_OPEN_BUILDINGS open buildings"

            settings.openBuildings.orEmpty().any { !isNearZone(it, zone) } -> "An open building is outside the zone"

            else -> settings.quests.firstNotNullOfOrNull { QuestCatalog.problem(it, settings) }
        }
    }
}
