package app.hovanki.e2e.scenario

import app.hovanki.shared.debug.DebugGameState
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus

/**
 * What holds at every moment of every game, whatever the players do: checked on the server's states one after the
 * other, as the observer reads them (a chaos game polls it every second). Violations are collected, not thrown, so a
 * game shows all of them.
 *
 * - the phase only moves forward, and a finished game keeps its end time;
 * - a player is in at most one open claim, and a finished game has none;
 * - caught only through a confirmed claim on that hider;
 * - eliminated only after a warning at least [graceMillis] earlier (seen by some earlier check).
 */
class GameInvariants(private val graceMillis: Long, private val toleranceMillis: Long = 1_500) {
    private val found = mutableListOf<String>()
    private var lastPhase: GamePhase? = null
    private var finishedAt: Long? = null

    /** The latest out-of-zone warning seen for each player. */
    private val warnedSince = HashMap<PlayerId, Long>()
    private val eliminated = HashSet<PlayerId>()

    val violations: List<String> get() = found.toList()

    var checks = 0
        private set

    fun check(state: DebugGameState) {
        checks++
        val at = "at ${state.serverTimeMillis}"
        lastPhase?.let { if (state.phase < it) found += "$at: phase went back from $it to ${state.phase}" }
        lastPhase = state.phase
        if (state.phase == GamePhase.FINISHED) {
            val end = state.finishedAtMillis
            if (end == null) found += "$at: finished without an end time"
            if (finishedAt != null && end != finishedAt) found += "$at: the end moved from $finishedAt to $end"
            finishedAt = finishedAt ?: end
        }

        val open = state.catches.filter { it.status == CatchStatus.AWAITING_CODE || it.status == CatchStatus.DISPUTED }
        if (state.phase == GamePhase.FINISHED && open.isNotEmpty()) found += "$at: a finished game has open claims"
        val names = state.players.associate { it.id to it.name }
        val openPerPlayer = open.flatMap { listOf(it.hiderId, it.seekerId) }.groupingBy { it }.eachCount()
        for ((player, count) in openPerPlayer) if (count > 1) found += "$at: ${names[player]} is in $count open claims"

        for (player in state.players) {
            player.outOfZoneSinceMillis?.let { warnedSince[player.id] = it }
            if (player.status == PlayerStatus.CAUGHT &&
                state.catches.none { it.hiderId == player.id && it.status == CatchStatus.CONFIRMED }
            ) {
                found += "$at: ${player.name} is caught without a confirmed claim"
            }
            if (player.status == PlayerStatus.ELIMINATED && eliminated.add(player.id)) {
                val warned = warnedSince[player.id]?.let { state.serverTimeMillis - it }
                if (warned == null) {
                    found += "$at: ${player.name} is eliminated without a warning"
                } else if (warned < graceMillis - toleranceMillis) {
                    found += "$at: ${player.name} is eliminated $warned ms after the warning"
                }
            }
        }
    }
}
