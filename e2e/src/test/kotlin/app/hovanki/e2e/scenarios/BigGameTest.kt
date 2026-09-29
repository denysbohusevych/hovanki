package app.hovanki.e2e.scenarios

import app.hovanki.e2e.admin.StaffConsole
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.protocol.AdminBigGameRequest
import app.hovanki.shared.protocol.BigGameSetup
import app.hovanki.shared.protocol.BigGameStatus
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.StreetZoneState
import app.hovanki.shared.protocol.UserRole
import app.hovanki.shared.protocol.ZonePolygon
import app.hovanki.shared.protocol.ZoneShape
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * A big game (docs/adr/0010-big-games.md): an admin schedules it in the admin, players sign up from the «Play» tab,
 * come into the lobby the server hosts, the round starts on time with seekers the server draws, and the admin calls it
 * off. The test source makes the zone built-up ground: one player per 1 000 m².
 */
class BigGameTest {
    @Test
    fun scheduledSignedUpStartedOnTime() = scenario("A big game") {
        val mila = player("Mila", at = PARK.offset(northMeters = 400.0))
        val milaAccount = mila.signsUp()
        mila.confirmsEmail()
        observer.setRole(checkNotNull(mila.userId), UserRole.ADMIN)
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 30.0))
        val boris = player("Boris", at = PARK.offset(northMeters = 30.0))
        val vera = player("Vera", at = PARK.offset(eastMeters = -30.0))
        for (bot in listOf(sam, anna, boris, vera)) bot.signsUp()
        sam.befriends(anna)
        // 300 × 300 m: 90 000 m², 90 players.
        val square = ZonePolygon(
            listOf(
                PARK.offset(eastMeters = -150.0, northMeters = -150.0),
                PARK.offset(eastMeters = 150.0, northMeters = -150.0),
                PARK.offset(eastMeters = 150.0, northMeters = 150.0),
                PARK.offset(eastMeters = -150.0, northMeters = 150.0),
            ),
        )

        StaffConsole(serverUrl, observer).use { console ->
            console.logIn(milaAccount)
            check(console.estimate(square).capacity == 90, "the zone fits 90")
            val game = console.createBigGame(
                AdminBigGameRequest(
                    title = "Saturday in the park",
                    startsAtMillis = System.currentTimeMillis() + 25.seconds.inWholeMilliseconds,
                    timeZone = "Europe/Kyiv",
                    zone = square,
                    setup = BigGameSetup(hidingMinutes = 1, seekingMinutes = 10, glowEveryMinutes = 0, seekers = 1),
                    reason = "the park festival",
                ),
            )
            check(game.status == BigGameStatus.LOBBY, "25 seconds ahead: the lobby opens at once")
            check(game.playerLimit == 90, "as many as the zone fits")

            for (bot in listOf(sam, anna, boris)) {
                requireOk(bot.refreshBigGames(), "${bot.name} opens «Play»")
                requireOk(bot.signsUpFor(game.id), "${bot.name} signs up")
            }
            requireOk(mila.refreshBigGames(), "Mila opens «Play» too")
            expectRejected(mila.signsUpFor(game.id), ErrorCode.FORBIDDEN, "the admin running the game can't play in it")
            requireOk(sam.refreshBigGames(), "Sam looks again")
            val card = sam.bigGames.single { it.id == game.id }
            check(card.signedUp == 3 && card.canJoin, "3 signed up, the lobby is open for Sam")
            check(card.friends.map { it.nickname } == listOf(anna.user?.nickname), "Sam sees Anna signed up")

            expectRejected(
                vera.joinBigGame(game.id),
                ErrorReason.BIG_GAME_SIGNUP_REQUIRED,
                "Vera did not sign up",
            )
            for (bot in listOf(sam, anna, boris)) requireOk(bot.joinBigGame(game.id), "${bot.name} comes in")
            val lobby = checkNotNull(sam.snapshot)
            useGame(lobby.gameId, lobby.joinCode)
            check(lobby.hostId.value == "server", "the server hosts the lobby")
            check(lobby.bigGame?.title == "Saturday in the park", "the lobby knows its big game")
            check(lobby.settings.zoneShape == ZoneShape.DRAWN, "the drawn zone")
            check(lobby.streetZone == StreetZoneState.READY, "its polygons are there at once")
            expectRejected(vera.join(lobby.joinCode), ErrorCode.NOT_FOUND, "no way in by the code")

            // On time, the server starts with one seeker it drew.
            awaitPhase(GamePhase.HIDING, within = 45.seconds)
            val round = state()
            check(round.players.count { it.role == Role.SEEKER } == 1, "one seeker")
            check(console.bigGames().games.single { it.id == game.id }.status == BigGameStatus.RUNNING, "running")

            console.cancelBigGame(game.id, "the park closes early")
            awaitPhase(GamePhase.FINISHED)
            awaitThat("Sam sees the results") { sam.snapshot?.phase == GamePhase.FINISHED }
        }
    }
}
