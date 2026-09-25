package app.hovanki.e2e.bot

import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.VisibilityReason

/**
 * Privacy rules every snapshot must follow, whatever happens in the game (docs/architecture.md, "Видимость"):
 * the server never sends a position the viewer may not see. Checked on every response every bot receives.
 */
object SnapshotAudit {
    private val HIDER_REVEALS = setOf(
        VisibilityReason.OUT_OF_ZONE,
        VisibilityReason.MOCK_LOCATION,
        VisibilityReason.STALE_SIGNAL,
    )

    /** Violations found in [snapshot]; [rawJson] is the response body as it came over the wire. */
    fun check(snapshot: GameSnapshot, rawJson: String): List<String> {
        val problems = mutableListOf<String>()
        val me = snapshot.me
        val phase = snapshot.phase
        if (me.role == Role.HIDER && "\"location\"" in rawJson) {
            problems += "hider ${me.playerId.value} received a position in $phase"
        }
        if (me.role != Role.HIDER && me.catchCodeSecret != null) {
            problems += "seeker ${me.playerId.value} received a catch code secret"
        }
        for (player in snapshot.players) {
            val location = player.location ?: continue
            val viewer = "${me.role} ${me.playerId.value}"
            val where = "$viewer sees ${player.role} ${player.id.value} (${location.reason}) in $phase"
            when {
                player.id == me.playerId -> problems += "own position echoed: $where"

                me.role != Role.SEEKER -> problems += where

                phase != GamePhase.HIDING && phase != GamePhase.SEEKING -> problems += where

                player.role == Role.SEEKER && location.reason != VisibilityReason.TEAMMATE -> problems += where

                player.role == Role.HIDER && location.reason !in HIDER_REVEALS -> problems += where

                player.role == Role.HIDER && (phase != GamePhase.SEEKING || player.status != PlayerStatus.ACTIVE) ->
                    problems += where
            }
        }
        return problems
    }
}
