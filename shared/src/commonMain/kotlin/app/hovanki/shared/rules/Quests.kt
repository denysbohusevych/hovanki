package app.hovanki.shared.rules

import app.hovanki.shared.geo.bearingTo
import app.hovanki.shared.protocol.Audience
import app.hovanki.shared.protocol.DistanceBand
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.ItemKind
import app.hovanki.shared.protocol.PerkKind
import app.hovanki.shared.protocol.QuestKind
import app.hovanki.shared.protocol.Role
import kotlin.math.roundToInt

/** A quest of the catalog: whom it is for, what it needs, what it gives and what counts as done. */
data class QuestSpec(
    val kind: QuestKind,
    val audience: Audience,
    val sparks: Int,
    /** What [app.hovanki.shared.protocol.QuestView.progress] counts up to: metres, seconds, sectors or times. */
    val target: Int,
    val needsRadar: Boolean = false,
    val needsGlow: Boolean = false,
    /** A quest of the whole team: the seekers do it together and every seeker gets the sparks. */
    val team: Boolean = false,
)

/**
 * The quests (docs/adr/0013-quests-sparks-and-sensors.md, section 2): the numbers of each. The server judges them by
 * its own data; the app shows the progress with the same numbers.
 */
object QuestCatalog {
    const val RELOCATE_METERS = 200
    const val RELOCATE_SECONDS = 240
    const val AFTER_GLOW_METERS = 100
    const val AFTER_GLOW_SECONDS = 60
    const val AFTER_GLOW_TIMES = 3
    const val FREEZE_SECONDS = 300
    const val FREEZE_RADIUS_METERS = 15.0
    const val SPRINT_METERS = 300
    const val SPRINT_SECONDS = 180
    const val SPY_FREE_SECONDS = 120
    const val MEETING_SECONDS = 10
    const val SHADOW_METERS = 500
    const val SWEEP_SECTORS = 4
    const val SWEEP_SECONDS = 20
    const val ON_THE_TRAIL_SECONDS = 90
    const val ON_THE_TRAIL_METERS = 20.0
    const val SPLIT_UP_SECONDS = 120

    /** How many «slices» the zone is cut into for the seekers' sector quests. */
    const val SECTORS = 6

    /** Sparks the host's own quests give unless said otherwise, and at most. */
    const val CUSTOM_DEFAULT_SPARKS = 3
    const val MAX_SPARKS = 20
    const val MAX_CUSTOM_TEXT = 120
    const val MAX_CUSTOM_QUESTS = 20

    private val specs: Map<QuestKind, QuestSpec> = listOf(
        QuestSpec(QuestKind.RELOCATE, Audience.HIDERS, sparks = 2, target = RELOCATE_METERS),
        QuestSpec(QuestKind.AFTER_GLOW, Audience.HIDERS, sparks = 2, target = AFTER_GLOW_TIMES, needsGlow = true),
        QuestSpec(QuestKind.FREEZE, Audience.HIDERS, sparks = 1, target = FREEZE_SECONDS),
        QuestSpec(QuestKind.SPRINT, Audience.ALL, sparks = 2, target = SPRINT_METERS),
        QuestSpec(QuestKind.SPY, Audience.HIDERS, sparks = 5, target = SPY_FREE_SECONDS, needsRadar = true),
        QuestSpec(QuestKind.MEETING, Audience.HIDERS, sparks = 4, target = MEETING_SECONDS, needsRadar = true),
        QuestSpec(QuestKind.SHADOW, Audience.HIDERS, sparks = 4, target = SHADOW_METERS, needsRadar = true),
        QuestSpec(QuestKind.SWEEP, Audience.SEEKERS, sparks = 2, target = SWEEP_SECTORS),
        QuestSpec(QuestKind.BEATER, Audience.SEEKERS, sparks = 3, target = 1, needsRadar = true),
        QuestSpec(QuestKind.ON_THE_TRAIL, Audience.SEEKERS, sparks = 2, target = 1, needsGlow = true),
        QuestSpec(QuestKind.SPLIT_UP, Audience.SEEKERS, sparks = 2, target = SPLIT_UP_SECONDS, team = true),
        QuestSpec(QuestKind.FIRST_CATCH, Audience.SEEKERS, sparks = 3, target = 1),
        QuestSpec(QuestKind.CUSTOM, Audience.ALL, sparks = CUSTOM_DEFAULT_SPARKS, target = 1),
    ).associateBy { it.kind }

    fun spec(kind: QuestKind): QuestSpec = checkNotNull(specs[kind])

    /** The catalog quests a host can pick (the host's own are made in words). */
    val pickable: List<QuestKind> = QuestKind.entries.filter { it != QuestKind.CUSTOM }

    /** Why [kind] can't be on with [settings]; null when it can. */
    fun problem(kind: QuestKind, settings: GameSettings): String? {
        val spec = spec(kind)
        return when {
            kind == QuestKind.CUSTOM -> "Custom quests are made in the lobby, not picked"
            spec.needsRadar && !settings.features.hasRadar -> "Quest $kind needs the radar"
            spec.needsGlow && !Glow.isOn(settings) -> "Quest $kind needs the glow"
            else -> null
        }
    }

    /** Whether a player of [role] gets a quest for [audience]. */
    fun isFor(audience: Audience, role: Role): Boolean = when (audience) {
        Audience.ALL -> true
        Audience.HIDERS -> role == Role.HIDER
        Audience.SEEKERS -> role == Role.SEEKER
    }
}

/** What a perk does and costs (docs/adr/0013-quests-sparks-and-sensors.md, section 3). */
data class PerkSpec(
    val perk: PerkKind,
    val role: Role,
    val price: Int,
    /** Uses per round, bought and picked up together. */
    val maxUses: Int,
    /** How long its effect lasts, for the ones that last. */
    val effectSeconds: Int = 0,
    val needsTarget: Boolean = false,
    val needsPoint: Boolean = false,
    val needsGlow: Boolean = false,
)

object PerkCatalog {
    private val specs: Map<PerkKind, PerkSpec> = listOf(
        PerkSpec(PerkKind.ERASE_TRAIL, Role.HIDER, price = 3, maxUses = 3, needsGlow = true),
        PerkSpec(PerkKind.DECOY, Role.HIDER, price = 4, maxUses = 3, needsPoint = true, needsGlow = true),
        PerkSpec(PerkKind.INVISIBLE, Role.HIDER, price = 6, maxUses = 1, needsGlow = true),
        PerkSpec(PerkKind.SENSE, Role.HIDER, price = 2, maxUses = 5, effectSeconds = 30),
        PerkSpec(PerkKind.SPOTLIGHT, Role.SEEKER, price = 5, maxUses = 3, needsTarget = true),
        PerkSpec(PerkKind.FRESH_TRAIL, Role.SEEKER, price = 3, maxUses = 3, needsTarget = true, needsGlow = true),
        PerkSpec(PerkKind.DIRECTION, Role.SEEKER, price = 6, maxUses = 3, effectSeconds = 10),
        PerkSpec(PerkKind.RADIUS, Role.SEEKER, price = 3, maxUses = 5, effectSeconds = 60),
    ).associateBy { it.perk }

    fun spec(perk: PerkKind): PerkSpec = checkNotNull(specs[perk])

    fun forRole(role: Role): List<PerkSpec> = PerkKind.entries.map(::spec).filter { it.role == role }

    /** A minute ago: the fix the «Fresh trail» perk moves the spot to. */
    const val FRESH_TRAIL_AGE_MILLIS = 60_000L
}

/** The board's items (docs/adr/0013-quests-sparks-and-sensors.md, section 2.4): defaults and limits. */
object BoardRules {
    const val MAX_ITEMS = 40
    const val MAX_NAME_LENGTH = 30
    const val CHECKPOINT_CODE_LENGTH = 8

    /** The sparks an item gives unless the host says otherwise. */
    fun defaultSparks(kind: ItemKind): Int = when (kind) {
        ItemKind.QUEST_POINT -> 3
        ItemKind.CHECKPOINT_GEO -> 3
        ItemKind.CHECKPOINT_SCAN -> 5
        ItemKind.PICKUP -> 0
    }

    /** A checkpoint after the first player: half the sparks, at least one. */
    fun laterSparks(sparks: Int): Int = maxOf(1, sparks / 2)

    /** Whether [role] sees and may take an item for [audience]. */
    fun isFor(audience: Audience, role: Role): Boolean = QuestCatalog.isFor(audience, role)
}

/** Sectors of the zone («slices») and compass sectors, for the quests and the hints. */
object Sectors {
    const val COMPASS_SECTORS = 8

    /** Which of [count] slices around [center] holds [point]: 0 starts at north, clockwise. */
    fun sectorOf(point: GeoPoint, center: GeoPoint, count: Int = QuestCatalog.SECTORS): Int {
        val bearing = center.bearingTo(point)
        return (bearing / (360.0 / count)).toInt().coerceIn(0, count - 1)
    }

    /** The compass sector of [bearingDegrees]: 0 north, 1 north-east, … 7 north-west. */
    fun compassSector(bearingDegrees: Double): Int {
        val width = 360.0 / COMPASS_SECTORS
        return ((bearingDegrees + width / 2).mod(360.0) / width).toInt().coerceIn(0, COMPASS_SECTORS - 1)
    }

    /** The hint's band for a GPS distance. */
    fun bandFor(meters: Double): DistanceBand = when {
        meters < NEAR_METERS -> DistanceBand.NEAR
        meters < CLOSE_METERS -> DistanceBand.CLOSE
        else -> DistanceBand.FAR
    }

    const val NEAR_METERS = 50.0
    const val CLOSE_METERS = 150.0

    /** Metres as a quest's progress shows them. */
    fun meters(value: Double): Int = value.roundToInt()
}
