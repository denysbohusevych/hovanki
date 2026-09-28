package app.hovanki.e2e.scenarios

import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.geo.offsetFrom
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Game history, statistics and saved routes (docs/adr/0007-game-history-and-routes.md) through whole games: every
 * player with an account finds the game in their history, only a player who agreed keeps their route, and it is
 * their own path. Nobody ever gets anybody else's route.
 */
class HistoryTest {
    @Test
    fun onlyTheConsentingPlayerKeepsTheirOwnRoute() = scenario("History and saved routes") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 20.0))
        val boris = player("Boris", at = PARK.offset(eastMeters = -20.0))
        val guest = player("Guest", at = PARK.offset(northMeters = 10.0))
        sam.signsUp()
        anna.signsUp()
        boris.signsUp()
        check(anna.accountState.user?.saveRoutes == false, "saving routes is off by default")
        requireOk(anna.setSaveRoutes(true), "Anna turns on «save my routes»")
        check(anna.accountState.user?.saveRoutes == true, "the profile says so")

        sam.createsGame(GameSetups.fast())
        join(anna, boris, guest)
        sam.startsGame(seekers = listOf(sam))
        // Anna runs off east of the park, Boris west: their paths never cross.
        anna.walksTo(PARK.offset(eastMeters = 80.0), speed = 4.0)
        boris.walksTo(PARK.offset(eastMeters = -40.0), speed = 4.0)
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        anna.arrives()
        boris.arrives()
        sam.catches(guest)
        sam.catches(boris)
        sam.catches(anna)
        awaitPhase(GamePhase.FINISHED)

        // The history is saved in the background right after the end.
        val annaGame = eventually("Anna finds the game in her history", within = 10.seconds) {
            anna.refreshHistory()
            anna.history.games.firstOrNull { it.gameId == gameId }
        }
        check(annaGame.role == Role.HIDER && annaGame.status == PlayerStatus.CAUGHT, "Anna hid and was found")
        check(!annaGame.won, "found hiders lose")
        check(annaGame.hasRoute, "Anna's route is saved")
        check(
            annaGame.distanceMeters in 30.0..200.0,
            "Anna ran about 60 m: ${annaGame.distanceMeters.roundToInt()} m",
        )
        check(anna.history.stats?.games == 1, "her statistics count it")
        requireOk(anna.openRoute(gameId), "Anna opens her route")
        val route = checkNotNull(anna.openedRoute)
        check(route.points.size >= 5, "the route has points: ${route.points.size}")
        check(
            route.points.all { it.point.offsetFrom(PARK).eastMeters > -10.0 },
            "it is Anna's own path, east of the park: none of Boris' points",
        )

        val borisGame = eventually("Boris finds the game in his history") {
            boris.refreshHistory()
            boris.history.games.firstOrNull { it.gameId == gameId }
        }
        check(!borisGame.hasRoute, "Boris never agreed: no route of his is kept")
        expectRejected(boris.openRoute(gameId), ErrorCode.NOT_FOUND, "Boris asks for a route of this game")

        val samGame = eventually("Sam finds the game in his history") {
            sam.refreshHistory()
            sam.history.games.firstOrNull { it.gameId == gameId }
        }
        check(samGame.role == Role.SEEKER && samGame.won && samGame.catches == 3, "Sam found all three")
        check(sam.history.stats?.catches == 3, "Sam's statistics: three catches")
        expectRejected(sam.openRoute(gameId), ErrorCode.NOT_FOUND, "Sam asks for a route of this game")

        requireOk(anna.setSaveRoutes(false), "Anna turns saving routes off")
        expectRejected(anna.openRoute(gameId), ErrorCode.NOT_FOUND, "Anna's route after turning it off")
        requireOk(anna.refreshHistory(), "Anna opens her history again")
        check(
            anna.history.games.single {
                it.gameId == gameId
            }.let { !it.hasRoute },
            "the game stays, the route is gone",
        )
    }

    /** «Save my routes» turned on on the results screen: the game just played is kept too. */
    @Test
    fun turnedOnAfterTheGameKeepsIt() = scenario("Saving routes turned on after the game") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 20.0))
        sam.signsUp()
        anna.signsUp()

        sam.createsGame(GameSetups.fast())
        join(anna)
        sam.startsGame(seekers = listOf(sam))
        anna.walksTo(PARK.offset(eastMeters = 50.0), speed = 4.0)
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        anna.arrives()
        sam.catches(anna)
        awaitPhase(GamePhase.FINISHED)
        eventually("Anna finds the game in her history", within = 10.seconds) {
            anna.refreshHistory()
            anna.history.games.firstOrNull { it.gameId == gameId }
        }
        expectRejected(anna.openRoute(gameId), ErrorCode.NOT_FOUND, "no route without consent")

        requireOk(anna.setSaveRoutes(true), "Anna turns on «save my routes» on the results screen")
        awaitThat("this game's route is kept too", within = 10.seconds) {
            anna.openRoute(gameId)
            anna.openedRoute?.gameId == gameId
        }
        requireOk(anna.deleteRoute(gameId), "Anna deletes the route")
        expectRejected(anna.openRoute(gameId), ErrorCode.NOT_FOUND, "the deleted route")
    }
}
