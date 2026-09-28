package app.hovanki.e2e.scenarios

import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The lobby on the server (docs/adr/0009-game-setup-glow-streets.md): everybody sees the roles and the host's draw,
 * the host sets the game up, leaving is for good, an account plays in one game at a time, and an invite reaches a
 * player who waits in another lobby.
 */
class LobbyTest {
    @Test
    fun everybodySeesTheRoles() = scenario("Roles in the lobby") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 20.0))
        val boris = player("Boris", at = PARK.offset(northMeters = 20.0))
        sam.createsGame(GameSetups.fast())
        join(anna, boris)

        requireOk(sam.picksSeekers(listOf(anna)), "Sam makes Anna seek")
        awaitThat("Boris's phone shows Anna seeking") {
            boris.snapshot?.players?.single { it.id == anna.id }?.role == Role.SEEKER
        }
        awaitThat("Anna's phone says she seeks") { anna.snapshot?.me?.role == Role.SEEKER }
        expectRejected(anna.drawsSeekers(1), ErrorCode.FORBIDDEN, "only the host draws")

        requireOk(sam.drawsSeekers(1), "Sam draws one seeker")
        val drawnAt = eventually("the draw reaches Boris", within = 10.seconds) { boris.snapshot?.rolesDrawnAtMillis }
        awaitThat("Anna's phone rolls the same dice") { anna.snapshot?.rolesDrawnAtMillis == drawnAt }
        val seekers = state().players.filter { it.role == Role.SEEKER }
        check(seekers.size == 1, "one seeker drawn")

        // The app starts with the roles everybody sees.
        val seeker = listOf(sam, anna, boris).single { it.id == seekers.single().id }
        sam.startsGame(seekers = listOf(seeker))
        check(state().players.single { it.id == seeker.id }.role == Role.SEEKER, "the drawn one seeks")
    }

    @Test
    fun leavingTheLobbyIsForGood() = scenario("Leaving the lobby") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 20.0))
        val vera = player("Vera", at = PARK.offset(northMeters = 30.0))
        sam.createsGame(GameSetups.fast())
        join(anna, vera)

        requireOk(vera.leave(), "Vera leaves the lobby")
        awaitThat("the server has Vera no more") { state().players.none { it.id == vera.id } }
        awaitThat("Sam's lobby shows two players") { sam.snapshot?.players?.size == 2 }

        // No ghost: once Anna is caught, the game is over.
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        sam.catches(anna)
        awaitPhase(GamePhase.FINISHED)
    }

    @Test
    fun aLeavingHostHandsOver() = scenario("The host leaves") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 20.0))
        val boris = player("Boris", at = PARK.offset(northMeters = 20.0))
        sam.createsGame(GameSetups.fast())
        join(anna, boris)

        requireOk(sam.leave(), "Sam leaves his own lobby")

        awaitThat("Anna hosts now") { anna.snapshot?.hostId == anna.id }
        anna.startsGame(seekers = listOf(anna))
        awaitPhase(GamePhase.HIDING)
    }

    @Test
    fun leavingTheRound() = scenario("Leaving the round") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 20.0))
        val boris = player("Boris", at = PARK.offset(northMeters = 20.0))
        sam.createsGame(GameSetups.fast())
        join(anna, boris)
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)

        requireOk(anna.leave(), "Anna leaves the round")

        val out = awaitStatus(anna, PlayerStatus.ELIMINATED)
        check(out.left, "Anna left")
        check(state().phase == GamePhase.SEEKING, "Boris still hides")
        requireOk(boris.leave(), "Boris leaves too")
        awaitPhase(GamePhase.FINISHED)
    }

    @Test
    fun anAccountPlaysOneGameAtATime() = scenario("One game at a time") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 20.0))
        sam.signsUp()
        anna.signsUp()
        sam.createsGame(GameSetups.fast())
        val samsGame = gameId
        join(anna)
        val annaAtSams = anna.id

        // Anna starts a game of her own: the server takes her out of Sam's lobby.
        requireOk(anna.createGame(GameSetups.fast()), "Anna creates her own game")
        awaitThat("Sam's lobby shows Anna gone") { sam.snapshot?.players?.none { it.id == annaAtSams } == true }
        check(observer.game(samsGame).players.size == 1, "only Sam in his lobby")

        // A round in progress is left only on purpose.
        val annasCode = checkNotNull(anna.snapshot).joinCode
        val boris = player("Boris", at = PARK.offset(northMeters = 20.0))
        boris.signsUp()
        requireOk(boris.join(annasCode), "Boris joins Anna")
        requireOk(anna.startGame(listOf(anna)), "Anna starts, Boris hides")
        awaitThat("Boris sees the round") { boris.snapshot?.phase == GamePhase.HIDING }
        expectRejected(
            boris.createGame(GameSetups.fast()),
            ErrorReason.IN_ANOTHER_GAME,
            "Boris still plays Anna's round",
        )
    }

    @Test
    fun anInviteReachesAnotherLobby() = scenario("An invite into another lobby") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 20.0))
        sam.signsUp()
        anna.signsUp()
        sam.befriends(anna)
        requireOk(anna.createGame(GameSetups.fast()), "Anna waits in a lobby of her own")
        val annasGame = checkNotNull(anna.snapshot).gameId
        // The app polls the inbox on every screen, the lobby too.
        anna.opensInbox()
        sam.createsGame(GameSetups.fast())

        requireOk(sam.invite(listOf(checkNotNull(anna.userId))), "Sam invites Anna")
        val invite = eventually("Anna's lobby shows the invite", within = 30.seconds) {
            anna.inbox.invites.firstOrNull { it.gameId == gameId }
        }

        requireOk(anna.join(invite.joinCode), "Anna goes to Sam's game")
        awaitThat("Anna is in Sam's lobby") { state().players.any { it.id == anna.id } }
        delay(1.seconds)
        check(observer.games().games.none { it.gameId == annasGame }, "Anna's empty lobby is gone")
    }

    @Test
    fun theHostSetsTheGameUp() = scenario("Setting up in the lobby") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 20.0))
        sam.createsGame(GameSetups.fast())
        join(anna)
        awaitThat("Anna's map has the buildings") { anna.state.buildings != null }

        val longer = GameSetups.fixedZone(400.0).copy(hidingSeconds = 5, glowEverySeconds = 30, glowForSeconds = 5)
        requireOk(sam.changesSettings(longer), "Sam makes the zone 400 m and turns the glow on")
        expectRejected(anna.changesSettings(longer), ErrorCode.FORBIDDEN, "only the host")

        awaitThat("Anna sees the new setup") { anna.snapshot?.settings?.zone?.initial?.radiusMeters == 400.0 }
        awaitThat("Anna's map loaded the new zone's buildings") { anna.state.buildings?.mapRevision == 1 }
        sam.startsGame(seekers = listOf(sam))
        val seeking = awaitPhase(GamePhase.SEEKING, within = 15.seconds)
        check(seeking.settings.glowEverySeconds == 30, "the round has the glow")
        sam.catchesUpWith(anna)
        sam.claimsCatch(anna)
        sam.entersCodeShownBy(anna)
        awaitCatch(anna, CatchStatus.CONFIRMED)
    }
}
