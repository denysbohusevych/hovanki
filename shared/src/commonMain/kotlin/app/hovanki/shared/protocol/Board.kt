package app.hovanki.shared.protocol

import kotlinx.serialization.Serializable

// The board of a game (docs/adr/0013-quests-sparks-and-sensors.md): what the host places on the map in the lobby
// (quest points, checkpoints, perks lying around), the quests, the perks and the sparks.

/** What the host places on the map. */
@Serializable
enum class ItemKind {
    /** A quest point: whoever of its audience gets there earns its sparks («Courier»). */
    QUEST_POINT,

    /** A checkpoint reached by GPS; the first one there earns the full sparks, the others half. */
    CHECKPOINT_GEO,

    /** A checkpoint reached by scanning the code the host printed and hung there. */
    CHECKPOINT_SCAN,

    /** A perk lying on the map: the first one there takes it. */
    PICKUP,
}

/** Whom an item or a quest is for. */
@Serializable
enum class Audience { ALL, HIDERS, SEEKERS }

/** The perks (docs/adr/0013-quests-sparks-and-sensors.md, section 3): what sparks buy, or what lies on the map. */
@Serializable
enum class PerkKind {
    /** Hider: the grey «was here» spot of the last glow disappears from the seekers' maps. */
    ERASE_TRAIL,

    /** Hider: a spot of their choosing appears on the seekers' maps as a glow spot, indistinguishable from a real one. */
    DECOY,

    /** Hider: the next glow doesn't show them. */
    INVISIBLE,

    /** Hider: for a while, the compass sector and how far the nearest seeker is. */
    SENSE,

    /** Seeker: a chosen hider is seen live for a few seconds. */
    SPOTLIGHT,

    /** Seeker: a chosen hider's spot moves to where they were a minute ago. */
    FRESH_TRAIL,

    /** Seeker: for a while, the compass sector of the nearest hider. */
    DIRECTION,

    /** Seeker: for a while, how far the nearest hider is, as a band. */
    RADIUS,
}

/**
 * An item of the board as the players see it. In the lobby the host sees every item and, for a scan checkpoint,
 * its [code] (to print the QR code); in the round everybody of the [audience] sees the items with who took them.
 */
@Serializable
data class BoardItem(
    val id: ItemId,
    val kind: ItemKind,
    val point: GeoPoint,
    /** What the host called it: «Fountain», «Bridge»; empty for no name. */
    val name: String = "",
    val audience: Audience = Audience.ALL,
    /** The sparks it gives; a pickup gives its [perk] instead. */
    val sparks: Int = 0,
    /** The perk of a [ItemKind.PICKUP]. */
    val perk: PerkKind? = null,
    /** Who took it, in order: a pickup once, a checkpoint and a quest point once per player. */
    val takenBy: List<PlayerId> = emptyList(),
    /** The code of a scan checkpoint: only to the host, only in the lobby. */
    val code: String? = null,
)

/** The host places an item in the lobby ([ApiRoutes.ITEMS]); [sparks] null: the default of the kind. */
@Serializable
data class PlaceItemRequest(
    val kind: ItemKind,
    val point: GeoPoint,
    val name: String = "",
    val audience: Audience = Audience.ALL,
    val sparks: Int? = null,
    val perk: PerkKind? = null,
)

/** The player scanned a checkpoint's QR code ([ApiRoutes.CHECKPOINT_SCAN]): the code of its payload. */
@Serializable
data class ScanCheckpointRequest(val code: String)

/**
 * The player uses a perk ([ApiRoutes.PERKS]): from what they picked up, else bought for sparks. [targetId]: the hider
 * of a [PerkKind.SPOTLIGHT] or [PerkKind.FRESH_TRAIL]; [point]: where a [PerkKind.DECOY] appears.
 */
@Serializable
data class UsePerkRequest(val perk: PerkKind, val targetId: PlayerId? = null, val point: GeoPoint? = null)

/** The host makes up a quest in words ([ApiRoutes.QUESTS]); a player says they did it, the host confirms. */
@Serializable
data class CustomQuestRequest(val text: String, val audience: Audience = Audience.ALL, val sparks: Int = 3)

/** The host answers a player who says they did a quest of the host's ([ApiRoutes.QUEST_REVIEW]). */
@Serializable
data class QuestReviewRequest(val playerId: PlayerId, val approved: Boolean)

/** The quests of the catalog (docs/adr/0013-quests-sparks-and-sensors.md, section 2); the host picks which are on. */
@Serializable
enum class QuestKind {
    /** Hider: within the first minutes of the search, be 200 m away from where the search found you. */
    RELOCATE,

    /** Hider: a minute after a glow, be 100 m away from the spot it left; up to three times. */
    AFTER_GLOW,

    /** Hider: five minutes without moving more than 15 m. */
    FREEZE,

    /** Both: 300 m within 3 minutes. */
    SPRINT,

    /** Hider: get «hot» on a seeker's radar and stay free for two minutes. */
    SPY,

    /** Hider: meet another hider: «burning» on each other's radar for ten seconds. */
    MEETING,

    /** Hider: walk 500 m without ever being «hot» on a seeker's radar. */
    SHADOW,

    /** Seeker: be in four different sectors of the zone for twenty seconds each. */
    SWEEP,

    /** Seeker: the first to get «hot» on a hider. */
    BEATER,

    /** Seeker: reach a hider's glow spot within 90 s of the glow. */
    ON_THE_TRAIL,

    /** Seekers: everybody in a different sector for two minutes. */
    SPLIT_UP,

    /** Seeker: the first catch of the round. */
    FIRST_CATCH,

    /** The host's own, in words: a player says they did it, the host confirms. */
    CUSTOM,
}

@Serializable
enum class QuestStatus {
    ACTIVE,
    DONE,
    FAILED,

    /** A custom quest the viewer said they did; the host has not answered yet. */
    PENDING_REVIEW,
}

/**
 * A quest as the viewer sees it: their own progress on it ([progress] of [target]: metres, seconds, sectors, times,
 * depending on the kind). The host also sees who is waiting for their answer on a custom quest ([pending]).
 */
@Serializable
data class QuestView(
    val id: QuestId,
    val kind: QuestKind,
    val sparks: Int,
    val status: QuestStatus,
    val audience: Audience = Audience.ALL,
    /** A custom quest's words. */
    val text: String? = null,
    val progress: Int = 0,
    val target: Int = 0,
    /** Until when, for quests with a time limit; server time. */
    val deadlineMillis: Long? = null,
    /** Custom quests, the host: players who said they did it and wait for the host's answer. */
    val pending: List<PlayerId> = emptyList(),
    /** Who has done it, for quests everybody of the audience can do. */
    val doneBy: List<PlayerId> = emptyList(),
)

/** A perk as the viewer can use it ([MyState.perks]). */
@Serializable
data class PerkView(
    val perk: PerkKind,
    /** Sparks it costs, when bought. */
    val price: Int,
    /** How many of it the player picked up on the map (free to use). */
    val owned: Int = 0,
    /** Uses left this round (bought and picked up together). */
    val usesLeft: Int = 0,
    /** It can be used right now: enough sparks or one owned, uses left, no cooldown, the round allows it. */
    val canUse: Boolean = false,
    /** When the cooldown after the last perk ends; null: none. */
    val availableAtMillis: Long? = null,
)
