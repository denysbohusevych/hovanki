package app.hovanki.e2e.bot

import app.hovanki.shared.protocol.ChatChannel
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.VisibilityReason
import app.hovanki.shared.rules.BoardRules
import app.hovanki.shared.rules.ChatRules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Privacy rules every snapshot must follow, whatever happens in the game (docs/architecture.md, "Видимость"):
 * the server never sends a position the viewer may not see, nor a chat message of another team. Checked on every
 * response every bot receives.
 */
object SnapshotAudit {
    private val HIDER_REVEALS = setOf(
        VisibilityReason.OUT_OF_ZONE,
        VisibilityReason.MOCK_LOCATION,
        VisibilityReason.STALE_SIGNAL,
        VisibilityReason.INSIDE_BUILDING,
        VisibilityReason.GLOW,
        VisibilityReason.RADAR_OFF,
        VisibilityReason.SPOTLIGHT,
        VisibilityReason.FRESH_TRAIL,
    )

    /** Values the first app versions know in `VisibleLocation.reason`, which has no default. */
    private val FIRST_VERSION_REASONS = setOf(
        VisibilityReason.TEAMMATE,
        VisibilityReason.OUT_OF_ZONE,
        VisibilityReason.MOCK_LOCATION,
        VisibilityReason.STALE_SIGNAL,
    )

    /** Violations found in [snapshot]; [rawJson] is the response body as it came over the wire. */
    fun check(snapshot: GameSnapshot, rawJson: String): List<String> {
        val problems = mutableListOf<String>()
        val me = snapshot.me
        val phase = snapshot.phase
        // On the wire, not in the decoded snapshot, which drops fields this client doesn't know. Keys only: a chat
        // message or a player's name saying "location" is text, not a position.
        if (me.role == Role.HIDER && hasKey(Json.parseToJsonElement(rawJson), "location")) {
            problems += "hider ${me.playerId.value} received a position in $phase"
        }
        if (me.role != Role.HIDER && me.catchCodeSecret != null) {
            problems += "seeker ${me.playerId.value} received a catch code secret"
        }
        problems += radarProblems(snapshot)
        problems += boardProblems(snapshot)
        for (player in snapshot.players) {
            val location = player.location ?: continue
            val viewer = "${me.role} ${me.playerId.value}"
            val where = "$viewer sees ${player.role} ${player.id.value} (${location.exactReason}) in $phase"
            if (location.reason !in FIRST_VERSION_REASONS) {
                problems += "reason ${location.reason} would break the first app versions: $where"
            }
            when {
                player.id == me.playerId -> problems += "own position echoed: $where"

                me.role != Role.SEEKER -> problems += where

                phase != GamePhase.HIDING && phase != GamePhase.SEEKING -> problems += where

                player.role == Role.SEEKER && location.exactReason != VisibilityReason.TEAMMATE -> problems += where

                player.role == Role.HIDER && location.exactReason !in HIDER_REVEALS -> problems += where

                player.role == Role.HIDER && (phase != GamePhase.SEEKING || player.status != PlayerStatus.ACTIVE) ->
                    problems += where

                location.exactReason == VisibilityReason.GLOW -> glowProblem(snapshot, location.atMillis)?.let {
                    problems += "$it: $where"
                }
            }
        }
        for (message in snapshot.chat) {
            // The role the viewer has in this snapshot; in the lobby nobody has one yet, and there are no teams.
            val maySee = if (phase == GamePhase.LOBBY) {
                message.channel == ChatChannel.ALL
            } else {
                ChatRules.canSee(message.channel, me.role)
            }
            if (!maySee) {
                problems += "${me.role} ${me.playerId.value} received chat message ${message.seq} of channel " +
                    "${message.channel} in $phase"
            }
        }
        return problems
    }

    /**
     * The radar (docs/adr/0012-nearby-radar.md, section 2.6): a hider feels only the nearest seeker, nameless; a
     * seeker gets hiders by name; the seekers' tokens go to hiders only; nothing outside the search.
     */
    private fun radarProblems(snapshot: GameSnapshot): List<String> = buildList {
        val me = snapshot.me
        val viewer = "${me.role} ${me.playerId.value}"
        val contacts = me.radar?.contacts.orEmpty()
        if (contacts.isNotEmpty() && snapshot.phase != GamePhase.SEEKING) add("$viewer has a radar outside the search")
        if (me.role == Role.HIDER && contacts.size > 1) add("hider ${me.playerId.value} feels more than one seeker")
        if (me.role != Role.HIDER && me.seekerTokens.isNotEmpty()) add("$viewer received the seekers' tokens")
        for (contact in contacts) {
            val target = contact.playerId?.let { id -> snapshot.players.firstOrNull { it.id == id } }
            when {
                me.role == Role.HIDER && contact.playerId != null ->
                    add("hider ${me.playerId.value} feels a named player")

                me.role == Role.SEEKER && target == null -> add("seeker ${me.playerId.value} got a nameless contact")

                me.role == Role.SEEKER && target?.role != Role.HIDER -> add("$viewer feels a seeker on the radar")

                target?.status != null && target.status != PlayerStatus.ACTIVE ->
                    add("$viewer feels a player who is out")
            }
        }
    }

    /**
     * The board (docs/adr/0013-quests-sparks-and-sensors.md): in the round only the items of the viewer's team, and a
     * scan checkpoint's code to the host in the lobby only.
     */
    private fun boardProblems(snapshot: GameSnapshot): List<String> = buildList {
        val me = snapshot.me
        val inRound = snapshot.phase == GamePhase.HIDING || snapshot.phase == GamePhase.SEEKING
        for (item in snapshot.items) {
            if (inRound && !BoardRules.isFor(item.audience, me.role)) {
                add("${me.role} ${me.playerId.value} sees item ${item.id.value} for ${item.audience}")
            }
            if (item.code != null && (me.playerId != snapshot.hostId || snapshot.phase != GamePhase.LOBBY)) {
                add("${me.playerId.value} received the code of checkpoint ${item.id.value}")
            }
        }
    }

    /**
     * The glow (docs/adr/0009-game-setup-glow-streets.md), restated here rather than taken from the rules: every
     * `glowEverySeconds` of the search a glow of `glowForSeconds`, the first one a full interval in. During a glow the
     * seekers see the hiders live; between glows only a spot from a fix taken before the last glow ended, never a newer
     * one. Null: [fixAtMillis] may be seen now.
     */
    private fun glowProblem(snapshot: GameSnapshot, fixAtMillis: Long): String? {
        val settings = snapshot.settings
        val start = snapshot.zoneStartedAtMillis ?: return "a glow before the search"
        if (settings.glowEverySeconds <= 0 || settings.glowForSeconds <= 0) return "a glow in a game without glows"
        val every = settings.glowEverySeconds * 1000L
        val length = minOf(settings.glowForSeconds * 1000L, every)
        val now = snapshot.serverTimeMillis
        val index = (now - start) / every
        if (now < start || index < 1) return "a glow before the first one"
        val lastEnd = start + index * every + length
        return if (now < lastEnd || fixAtMillis < lastEnd) null else "a glow spot newer than the glow"
    }

    /** Whether any object in [element], however deep, has the key [key]. */
    private fun hasKey(element: JsonElement, key: String): Boolean = when (element) {
        is JsonObject -> element.any { (name, value) -> name == key || hasKey(value, key) }
        is JsonArray -> element.any { hasKey(it, key) }
        else -> false
    }
}
