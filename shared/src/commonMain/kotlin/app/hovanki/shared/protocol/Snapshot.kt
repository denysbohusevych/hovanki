package app.hovanki.shared.protocol

import kotlinx.serialization.Serializable

/**
 * Authoritative game state as seen by one player. The server filters it per viewer:
 * a client never receives a position it is not allowed to see, so a modified client can't cheat by reading it.
 */
@Serializable
data class GameSnapshot(
    val gameId: GameId,
    val joinCode: String,
    val hostId: PlayerId,
    val phase: GamePhase,
    val settings: GameSettings,
    /** Server clock when the snapshot was produced; clients derive their clock offset from it. */
    val serverTimeMillis: Long,
    val phaseEndsAtMillis: Long? = null,
    /** Start of the zone schedule (= start of SEEKING). */
    val zoneStartedAtMillis: Long? = null,
    val players: List<PlayerView>,
    val me: MyState,
    /** Catch claims the viewer is involved in or may vote on. */
    val catches: List<CatchView> = emptyList(),
    /** The "no hiding in buildings" rule: null from older servers (no rule), or when the value is unknown. */
    val buildings: BuildingsState? = null,
    /**
     * Chat messages the viewer may see, newer than the request's chat cursor (`SyncRequest.chatAfter`), oldest first.
     * Empty when the request had no cursor. Clients merge them by [ChatMessage.seq].
     */
    val chat: List<ChatMessage> = emptyList(),
    /** When the round ended (FINISHED); null before, and from older servers. */
    val finishedAtMillis: Long? = null,
    /**
     * The zone by streets ([ZoneShape.STREETS]): being built, ready at `ApiRoutes.streetZone`, or unavailable (the game
     * uses the circles). Null: a circle zone, or an older server.
     */
    val streetZone: StreetZoneState? = null,
    /**
     * Goes up whenever the host changes the zone in the lobby: the buildings and the zone by streets are loaded again,
     * the ones of an older revision no longer apply.
     */
    val mapRevision: Int = 0,
    /** When the host last drew the roles at random: every phone rolls the dice once for it. Null: never. */
    val rolesDrawnAtMillis: Long? = null,
    /**
     * The [ServerFeature]s the operator turned on, by name: what the host may turn on in the lobby
     * (docs/adr/0012-nearby-radar.md). Empty from older servers.
     */
    val enabledFeatures: List<String> = emptyList(),
    /**
     * The board (docs/adr/0013-quests-sparks-and-sensors.md): what the host placed on the map, as far as the viewer
     * may see it (their audience; the host sees everything in the lobby).
     */
    val items: List<BoardItem> = emptyList(),
    /** The viewer's quests, with their own progress; the seekers' team quests for every seeker. */
    val quests: List<QuestView> = emptyList(),
    /**
     * About how many players the zone fits, from the ground under it (docs/adr/0010-big-games.md): the lobby warns the
     * host when there are more. Null from older servers.
     */
    val capacity: ZoneCapacity? = null,
    /** The big game this is (docs/adr/0010-big-games.md): hosted by the server, nobody's player is [hostId]. */
    val bigGame: BigGameInfo? = null,
    /**
     * Set when [players] is not everybody (a big game): the viewer, whom they see, who is in their catch claims and
     * their friends. How many there are in all is here.
     */
    val counts: PlayerCounts? = null,
    /** How many people watch this open game right now ([GameSettings.openGame]): the players see it. */
    val spectators: Int = 0,
    /**
     * The round stands still (docs/adr/0019-pause-and-sos.md): the phase's time, the zone, the glows and every other
     * timer wait; null: it goes on, and from older servers. Every time in the snapshot is the round's own: once it goes
     * on, the server moves them by the pause's length.
     */
    val pause: GamePause? = null,
    /** Who called for help (docs/adr/0019-pause-and-sos.md), with where they are now, for every player of the game. */
    val sos: List<SosCall> = emptyList(),
)

/** Since when the round is on pause; [sos]: an SOS stopped it (else the host). */
@Serializable
data class GamePause(val sinceMillis: Long, val sos: Boolean = false)

/**
 * A player who called for help (docs/adr/0019-pause-and-sos.md): everybody in the game sees them, and where they are,
 * until it is over. The one exception to «nobody sees where the others are».
 */
@Serializable
data class SosCall(
    val playerId: PlayerId,
    val name: String,
    val sinceMillis: Long,
    /** Their last fix (live, not the round's view of them); null: their phone sent none. */
    val location: SosLocation? = null,
)

@Serializable
data class SosLocation(val point: GeoPoint, val accuracyMeters: Double, val atMillis: Long)

/** How many players a game has in all, by role and state (the snapshot of a big game lists only some of them). */
@Serializable
data class PlayerCounts(
    val players: Int = 0,
    val seekers: Int = 0,
    /** Hiders still in the round. */
    val hidersActive: Int = 0,
    val hidersCaught: Int = 0,
    val hidersEliminated: Int = 0,
)

@Serializable
data class PlayerView(
    val id: PlayerId,
    val name: String,
    val role: Role,
    val status: PlayerStatus,
    /** Present only when the viewer is allowed to see this player right now. */
    val location: VisibleLocation? = null,
    /** The player's account; null for a guest (no account, or an app version without accounts). */
    val userId: UserId? = null,
    /** When a hider stopped playing: caught ([PlayerStatus.CAUGHT]) or eliminated; null while playing. */
    val outAtMillis: Long? = null,
    /** The seeker whose claim caught this hider; null unless [PlayerStatus.CAUGHT]. */
    val caughtBy: PlayerId? = null,
    /** Server time of the player's last request; the lobby shows who is not connected. Null from older servers. */
    val lastSeenMillis: Long? = null,
    /** The player left the game (the round goes on without them): a hider is out, a seeker seeks no more. */
    val left: Boolean = false,
    /** What the player's phone can do (the radar, UWB); null: the phone never said (an older app). */
    val capabilities: Capabilities? = null,
    /** The player's sparks (docs/adr/0013); null in a game without sparks. */
    val sparks: Int? = null,
)

@Serializable
data class VisibleLocation(
    val point: GeoPoint,
    val accuracyMeters: Double,
    val atMillis: Long,
    /**
     * Why the viewer sees this player, as the first app versions understand it: only the values they know. Has no
     * default, so a new value here would break them: newer reasons go to [cause] and map to the closest old one
     * (a building reveal is [VisibilityReason.OUT_OF_ZONE] here).
     */
    val reason: VisibilityReason,
    /** The exact reason, including ones added later (e.g. [VisibilityReason.INSIDE_BUILDING]); null: see [reason]. */
    val cause: VisibilityReason? = null,
) {
    /** Why the viewer sees this player: [cause] when the server sent one this client understands, else [reason]. */
    val exactReason: VisibilityReason get() = cause ?: reason
}

@Serializable
data class MyState(
    val playerId: PlayerId,
    val role: Role,
    val status: PlayerStatus,
    /** Hex TOTP secret for the catch code. Only sent to the hider itself, once the round has started. */
    val catchCodeSecret: String? = null,
    /** Set while the server is confident the player is outside the zone: return before this time. */
    val outOfZoneDeadlineMillis: Long? = null,
    /**
     * Set while the server is confident the player is inside a building: the seekers see them from this time on
     * (already in the past: they see them now). Cleared once the player is out again.
     */
    val insideBuildingRevealAtMillis: Long? = null,
    /**
     * Hex secret of the radar token (`RadarToken`, docs/adr/0012-nearby-radar.md): only to the player themselves,
     * once the round started in a game with the radar.
     */
    val radarSecret: String? = null,
    /** The viewer's radar: whom they feel near, as bands; null without the radar. */
    val radar: RadarState? = null,
    /**
     * A hider with the sense on, during the search: the radar tokens the seekers advertise right now (this five-minute
     * slot and its neighbours), so the phone feels a seeker coming the moment it hears them, without waiting for the
     * server (`HeartbeatRules`). Never a hider's token, never to a seeker.
     */
    val seekerTokens: List<String> = emptyList(),
    /**
     * A seeker with the radar, during the search: the radar tokens the active hiders advertise right now (this slot
     * and its neighbours), without names, so the seeker's phone warms up the moment it hears one, before the server's
     * band per hider comes (docs/adr/0012-nearby-radar.md, «Изменение 2026-09-29»). Never to a hider.
     */
    val hiderTokens: List<String> = emptyList(),
    /**
     * The radar is required and this hider's phone has Bluetooth off: turn it on before this time, or the seekers see
     * them from then on. Null otherwise.
     */
    val bluetoothDeadlineMillis: Long? = null,
    /** Players the viewer's phone may range with by UWB right now, with their discovery tokens. */
    val uwbPeers: List<UwbPeer> = emptyList(),
    /** The viewer's sparks (docs/adr/0013). */
    val sparks: Int = 0,
    /** A hint a perk bought, while it lasts. */
    val hint: Hint? = null,
    /** The perks the viewer may use, with what they cost and what they have. */
    val perks: List<PerkView> = emptyList(),
)

@Serializable
data class CatchView(
    val id: CatchId,
    val seekerId: PlayerId,
    val hiderId: PlayerId,
    val status: CatchStatus,
    val createdAtMillis: Long,
    /** End of the current step: code timeout (AWAITING_CODE) or voting (DISPUTED). */
    val deadlineMillis: Long? = null,
    val canVote: Boolean = false,
    val myVote: Boolean? = null,
)

/** One chat message of a game. Chat lives in the server's memory and is deleted with the game. */
@Serializable
data class ChatMessage(
    /** Increases by one per message in the game (across channels): the chat cursor. */
    val seq: Long,
    /** The sender; the name comes from [GameSnapshot.players]. */
    val playerId: PlayerId,
    val text: String,
    val sentAtMillis: Long,
    val channel: ChatChannel = ChatChannel.ALL,
)
