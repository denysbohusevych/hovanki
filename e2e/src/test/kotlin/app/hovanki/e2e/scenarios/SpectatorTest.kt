package app.hovanki.e2e.scenarios

import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.geo.offsetFrom
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.PlayerStatus
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Open games and their spectators, and the recordings of games (docs/adr/0011-spectators-and-recordings.md): a player
 * with an account watches an open game by its code, the host's delay behind, without playing and without sharing
 * anything; players never watch their own game; afterwards the game's players with an account watch everybody's way
 * again, nobody else does.
 */
class SpectatorTest {
    @Test
    fun aFanWatchesTheDelayBehindAndThePlayersGetTheRecording() = scenario("Spectators and the game's recording") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 20.0))
        val guest = player("Guest", at = PARK.offset(northMeters = 10.0))
        // Far from the game: a spectator shares no position.
        val fan = player("Fan", at = PARK.offset(northMeters = 2_000.0))
        val stranger = player("Stranger", at = PARK.offset(northMeters = -2_000.0))
        sam.signsUp()
        anna.signsUp()
        fan.signsUp()
        stranger.signsUp()

        sam.createsGame(GameSetups.fast().copy(openGame = true, spectatorDelaySeconds = DELAY_SECONDS))
        join(anna, guest)
        val code = state().joinCode

        expectRejected(anna.watch(code), ErrorReason.PLAYING_THIS_GAME, "Anna watches the game she plays in")
        expectRejected(guest.watch(code), ErrorReason.ACCOUNT_REQUIRED, "a guest watches")
        requireOk(fan.watch(code), "Fan watches the open game by its code")
        check(fan.watching.snapshot?.phase == GamePhase.LOBBY, "the fan sees the lobby")
        eventually("the players see that somebody watches") { sam.snapshot?.spectators?.takeIf { it == 1 } }

        sam.startsGame(seekers = listOf(sam))
        anna.walksTo(PARK.offset(eastMeters = 80.0), speed = 4.0)
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        anna.arrives()
        val seen = eventually("the fan sees Anna on the map", within = 30.seconds) {
            fan.watching.snapshot?.players?.firstOrNull { it.id == anna.id }?.location
        }
        val view = checkNotNull(fan.watching.snapshot)
        check(view.atMillis == view.serverTimeMillis - DELAY_SECONDS * 1000L, "${DELAY_SECONDS}s behind the game")
        check(seen.atMillis <= view.atMillis, "nothing newer than the moment shown")

        sam.catches(guest)
        sam.catches(anna)
        awaitPhase(GamePhase.FINISHED)
        val end = eventually("the fan sees the end, the delay later", within = 30.seconds) {
            fan.watching.snapshot?.takeIf { it.phase == GamePhase.FINISHED }
        }
        check(end.players.single { it.id == anna.id }.status == PlayerStatus.CAUGHT, "Anna caught, as she was")
        requireOk(fan.stopWatching(), "Fan stops watching")

        // The recording: everybody's way, for the game's players with an account only.
        eventually("Anna finds the recording in her history", within = 10.seconds) {
            anna.refreshHistory()
            anna.history.games.firstOrNull { it.gameId == gameId && it.hasRecording }
        }
        requireOk(anna.openRecording(gameId), "Anna opens the recording")
        val recording = checkNotNull(anna.openedRecording)
        check(recording.players.size == 3, "everybody who played, the guest too: ${recording.players.size}")
        check(recording.players.single { it.isMe }.playerId == anna.id, "Anna is marked as herself")
        check(
            recording.players.single { it.playerId == anna.id }.points.any {
                it.point.offsetFrom(PARK).eastMeters > 50.0
            },
            "Anna's run east is in it",
        )
        requireOk(sam.openRecording(gameId), "Sam opens it too")
        check(sam.openedRecording?.players?.single { it.isMe }?.playerId == sam.id, "Sam is himself in his")
        expectRejected(fan.openRecording(gameId), ErrorCode.NOT_FOUND, "the fan only watched: no recording")
        expectRejected(stranger.openRecording(gameId), ErrorCode.NOT_FOUND, "a stranger asks for the recording")
    }

    @Test
    fun aClosedGameIsNotWatched() = scenario("A closed game is not watched") {
        val sam = player("Sam", at = PARK)
        val fan = player("Fan", at = PARK.offset(northMeters = 2_000.0))
        sam.signsUp()
        fan.signsUp()
        sam.createsGame(GameSetups.fast())

        expectRejected(fan.watch(state().joinCode), ErrorReason.GAME_NOT_OPEN, "Fan watches a closed game")
        check(!fan.watching.isWatching, "the fan is not watching")
    }

    private companion object {
        const val DELAY_SECONDS = 10
    }
}
