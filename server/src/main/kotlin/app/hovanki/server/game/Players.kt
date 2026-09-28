package app.hovanki.server.game

import app.hovanki.shared.protocol.Activity
import app.hovanki.shared.protocol.Audience
import app.hovanki.shared.protocol.BoardItem
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.ChatMessage
import app.hovanki.shared.protocol.DeviceReport
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.Hint
import app.hovanki.shared.protocol.ItemId
import app.hovanki.shared.protocol.ItemKind
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.PerkKind
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.QuestId
import app.hovanki.shared.protocol.QuestKind
import app.hovanki.shared.protocol.QuestStatus
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.rules.LocationTrack
import app.hovanki.shared.rules.QuestSpec
import app.hovanki.shared.rules.RouteRecorder

/** A player of a [Game] and everything the rules know about them. Only [Game] and its helpers touch it. */
internal class Player(
    val id: PlayerId,
    val name: String,
    val track: LocationTrack,
    val userId: UserId?,
    /** The whole round, for the history; players with an account only. */
    val route: RouteRecorder?,
    /** The numbers of the round (metres walked), for the quests; everybody, without a single point kept. */
    val odometer: RouteRecorder,
) {
    var role: Role = Role.HIDER
    var status: PlayerStatus = PlayerStatus.ACTIVE

    /** When a hider was caught or eliminated, and by whom they were caught. */
    var outAtMillis: Long? = null
    var caughtBy: PlayerId? = null
    var catchClaims = 0
    var catches = 0
    var zoneWarnings = 0
    var buildingWarnings = 0
    var catchCodeSecret: String? = null
    var lastFixReceivedMillis: Long? = null
    var outOfZoneSinceMillis: Long? = null
    var insideBuildingSinceMillis: Long? = null

    /** Left the game for good (the leave button, or joining another game). */
    var left = false

    /** Server time of the player's last request. */
    var lastSeenMillis: Long? = null

    /** Where the last glow left this hider: what the seekers see between glows. */
    var glowMark: LocationSample? = null

    /** The glow [glowMark] comes from (its index), for the quests that follow a glow. */
    var glowMarkIndex = 0

    /**
     * The whole round, thinned, for the replay right after it: every player, only in memory (unlike [route], which
     * may be saved to the history).
     */
    val replay = ReplayTrack()

    /** When the player's app last fetched the READY buildings (for the e2e observer). */
    var buildingsLoadedAtMillis: Long? = null
    val fixResults = HashMap<LocationTrack.Result, Int>()

    /** When the player's recent chat messages were sent, oldest first (the chat's rate limit). */
    val chatSentAtMillis = ArrayDeque<Long>()

    /** The player's last [Game.CHAT_IDS_KEPT] messages by the app's id for them (`SendChatRequest.clientMessageId`). */
    val chatByClientId = LinkedHashMap<String, ChatMessage>()

    // The radar (docs/adr/0010-nearby-radar.md).

    /** Hex secret the phone derives its radar token from; only ever sent to the player themselves. */
    var radarSecret: String? = null

    /** What the phone last said about itself, and when. */
    var device: DeviceReport? = null
    var deviceAtMillis: Long? = null

    /** Since when the phone reports Bluetooth off while the radar is required; null: it is on (or never required). */
    var bluetoothOffSinceMillis: Long? = null

    // The board (docs/adr/0011-quests-sparks-and-sensors.md).

    var sparks = 0
    var questsDone = 0
    val quests = LinkedHashMap<QuestId, PlayerQuest>()
    val perkUses = HashMap<PerkKind, Int>()
    val perksOwned = HashMap<PerkKind, Int>()
    var lastPerkAtMillis: Long? = null

    /** A hint a perk bought, while it lasts. */
    var hint: Hint? = null

    /** The glow (its index) a hider's «Invisible» perk skips. */
    var invisibleGlowIndex: Int? = null

    /** A seeker's «Spotlight» on this hider: seen live until then. */
    var spotlightUntilMillis: Long? = null

    /** A hider's «Decoy»: shown to the seekers instead of the glow mark until the next glow. */
    var decoyMark: LocationSample? = null

    /** A seeker's «Fresh trail» on this hider: shown instead of the glow mark until the next glow. */
    var freshMark: LocationSample? = null

    /** The activity the phone last reported, when the game asked for it. */
    var activity: Activity = Activity.UNKNOWN

    /** Seconds the phone reported running, for the statistics of a running mode later on. */
    var runningMillis = 0L

    /** Where the search found this player (their first usable fix of the search), for «Relocate». */
    var seekingStartFix: LocationSample? = null

    val isPlayingNow: Boolean get() = !left && (role == Role.SEEKER || status == PlayerStatus.ACTIVE)
}

internal class CatchClaim(
    val id: CatchId,
    val seekerId: PlayerId,
    val hiderId: PlayerId,
    val createdAtMillis: Long,
    var deadlineMillis: Long,
    val estimatedDistanceAtClaimMeters: Double?,
) {
    var status: CatchStatus = CatchStatus.AWAITING_CODE
    var wasDisputed = false
    var failedAttempts = 0
    val votes = LinkedHashMap<PlayerId, Boolean>()
    val isOpen get() = status == CatchStatus.AWAITING_CODE || status == CatchStatus.DISPUTED
}

/** A catalog quest of one player (docs/adr/0011-quests-sparks-and-sensors.md), with what its rule remembers. */
internal class PlayerQuest(val id: QuestId, val spec: QuestSpec, val startedAtMillis: Long) {
    val kind: QuestKind get() = spec.kind
    var status = QuestStatus.ACTIVE
    var progress = 0
    var deadlineMillis: Long? = null

    /** A fix the rule measures from: where the quest started («Relocate», «Freeze»). */
    var anchor: LocationSample? = null
    var anchorAtMillis: Long? = null

    /** The odometer reading the rule counts from («Shadow», «Sprint»). */
    var metersBase = 0.0

    /** «Sprint»: the odometer readings of the last minutes, oldest first. */
    val metersLog = ArrayDeque<Pair<Long, Double>>()

    /** Seconds spent in each sector («Sweep»), and the sector the player was last seen in. */
    val sectorMillis = HashMap<Int, Long>()
    var lastSector: Int? = null
    var lastSectorAtMillis: Long? = null
    val sectorsVisited = HashSet<Int>()

    /** The glow index the rule last judged («After the glow», «On the trail»). */
    var lastGlowIndex = 0

    /** Since when the condition holds («Spy»: hot on a seeker, «Meeting»: burning with a hider). */
    var sinceMillis: Long? = null
}

/** A quest of the whole seekers' team («Split up»). */
internal class TeamQuest(val id: QuestId, val spec: QuestSpec) {
    var status = QuestStatus.ACTIVE
    var progress = 0
    var sinceMillis: Long? = null
}

/** A quest the host made up in words: who says they did it, whom the host confirmed. */
internal class CustomQuest(val id: QuestId, val text: String, val audience: Audience, val sparks: Int) {
    val pending = LinkedHashSet<PlayerId>()
    val doneBy = LinkedHashSet<PlayerId>()
}

/** An item of the board as the server keeps it: the host's placement plus who took it. */
internal class Item(
    val id: ItemId,
    val kind: ItemKind,
    val point: GeoPoint,
    val name: String,
    val audience: Audience,
    val sparks: Int,
    val perk: PerkKind?,
    /** The code of a scan checkpoint. */
    val code: String?,
) {
    val takenBy = LinkedHashSet<PlayerId>()

    fun toView(withCode: Boolean) = BoardItem(
        id = id,
        kind = kind,
        point = point,
        name = name,
        audience = audience,
        sparks = sparks,
        perk = perk,
        takenBy = takenBy.toList(),
        code = code.takeIf { withCode },
    )
}
