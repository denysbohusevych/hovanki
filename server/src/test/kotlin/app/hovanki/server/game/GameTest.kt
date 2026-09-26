package app.hovanki.server.game

import app.hovanki.shared.debug.DebugBuildings
import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.BuildingsState
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.ChatChannel
import app.hovanki.shared.protocol.ChatMessage
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.VisibilityReason
import app.hovanki.shared.rules.ChatRules
import app.hovanki.shared.rules.shrinkingZone
import app.hovanki.shared.totp.catchCodeTotp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GameTest {
    private val center = GeoPoint(50.4501, 30.5234)
    private val settings =
        GameSettings(zone = shrinkingZone(center, steps = 0), hidingSeconds = 60, seekingSeconds = 600)
    private val host = PlayerId("host")
    private val seeker = PlayerId("seeker")
    private val hider = PlayerId("hider")
    private val secret = "00112233445566778899aabbccddeeff00112233"

    private var now = 1_700_000_000_000L
    private val game = Game(GameId("g"), "ABC234", host, settings, now)

    /** Lobby with three players; the host hides too. Advances into SEEKING. */
    private fun startedGame(): Game {
        game.addPlayer(host, "Host", now)
        game.addPlayer(seeker, "Seeker", now)
        game.addPlayer(hider, "Hider", now)
        game.start(host, setOf(seeker), { secret }, now)
        assertEquals(GamePhase.HIDING, game.phase)
        tick(60)
        assertEquals(GamePhase.SEEKING, game.phase)
        return game
    }

    private fun tick(seconds: Int) {
        now += seconds * 1000L
        game.advance(now)
    }

    private fun report(player: PlayerId, point: GeoPoint, accuracy: Double = 5.0) {
        game.recordLocations(player, listOf(LocationSample(point, accuracy, now)), now)
        game.advance(now)
    }

    private fun claim(): CatchId {
        val id = CatchId("c$now")
        game.claimCatch(seeker, hider, id, now)
        return id
    }

    private fun code() = catchCodeTotp(secret, settings.rules).codeAt(now)

    private fun statusOf(player: PlayerId) = game.snapshotFor(host, now).players.single { it.id == player }.status

    @Test
    fun catchConfirmedWithTheCode() {
        startedGame()
        report(seeker, center)
        report(hider, center.moveBy(20.0, 0.0))

        val id = claim()
        game.confirmCatch(id, seeker, code(), now)

        assertEquals(PlayerStatus.CAUGHT, statusOf(hider))
        assertEquals(CatchStatus.CONFIRMED, game.snapshotFor(seeker, now).catches.single().status)
    }

    @Test
    fun claimRejectedWhenGpsProvesPlayersAreFarApart() {
        startedGame()
        report(seeker, center)
        report(hider, center.moveBy(300.0, 0.0))

        val error = assertFailsWith<GameException> { claim() }
        assertEquals(ErrorCode.TOO_FAR, error.code)
    }

    @Test
    fun silenceCountsAsCaught() {
        startedGame()
        report(seeker, center)
        claim()

        tick(settings.rules.catchCodeTimeoutSeconds)

        assertEquals(PlayerStatus.CAUGHT, statusOf(hider))
        assertEquals(GamePhase.SEEKING, game.phase, "the host is still hiding")
    }

    @Test
    fun wrongCodesRejectTheClaimAfterMaxAttempts() {
        startedGame()
        report(seeker, center)
        val id = claim()
        val acceptedNow = (-1..1).map { catchCodeTotp(secret, settings.rules).codeAt(now + it * 30_000L) }
        val wrong = (0..9999).map { it.toString().padStart(4, '0') }.first { it !in acceptedNow }

        repeat(settings.rules.catchCodeMaxAttempts) {
            assertEquals(
                ErrorCode.INVALID_CODE,
                assertFailsWith<GameException> {
                    game.confirmCatch(id, seeker, wrong, now)
                }.code,
            )
        }
        assertEquals(CatchStatus.REJECTED, game.snapshotFor(seeker, now).catches.single().status)
        assertEquals(PlayerStatus.ACTIVE, statusOf(hider))
    }

    @Test
    fun disputeIsDecidedByTheOtherPlayers() {
        startedGame()
        report(seeker, center)
        val id = claim()
        game.disputeCatch(id, hider, now)

        val voterView = game.snapshotFor(host, now).catches.single()
        assertEquals(CatchStatus.DISPUTED, voterView.status)
        assertEquals(true, voterView.canVote)
        assertFailsWith<GameException> { game.vote(id, seeker, true, now) }

        game.vote(id, host, confirm = false, now)

        assertEquals(CatchStatus.REJECTED, game.snapshotFor(seeker, now).catches.single().status)
        assertEquals(PlayerStatus.ACTIVE, statusOf(hider))
    }

    @Test
    fun disputeWithoutVotesFallsBackToGps() {
        startedGame()
        report(seeker, center)
        report(hider, center.moveBy(10.0, 0.0))
        val id = claim()
        game.disputeCatch(id, hider, now)

        tick(settings.rules.disputeVoteSeconds)

        assertEquals(PlayerStatus.CAUGHT, statusOf(hider))
    }

    @Test
    fun disputeDefaultRuleUsesTheLikelyDistance() {
        startedGame()
        // 60 m apart with 15 m accuracy each: close enough to open a claim, too far for the default rule.
        report(seeker, center, accuracy = 15.0)
        report(hider, center.moveBy(60.0, 0.0), accuracy = 15.0)
        val id = claim()
        game.disputeCatch(id, hider, now)

        tick(settings.rules.disputeVoteSeconds)

        assertEquals(PlayerStatus.ACTIVE, statusOf(hider))
        assertEquals(CatchStatus.REJECTED, game.snapshotFor(seeker, now).catches.single().status)
    }

    @Test
    fun leavingTheZoneWarnsThenEliminates() {
        startedGame()
        val outside = center.moveBy(700.0, 0.0)
        repeat(3) {
            report(hider, outside)
            tick(5)
        }
        assertNotNull(game.snapshotFor(hider, now).me.outOfZoneDeadlineMillis)
        val seekerView = game.snapshotFor(seeker, now).players.single { it.id == hider }
        assertEquals(VisibilityReason.OUT_OF_ZONE, seekerView.location?.reason)

        repeat(12) {
            report(hider, outside)
            tick(5)
        }
        assertEquals(PlayerStatus.ELIMINATED, statusOf(hider))
    }

    @Test
    fun oneFixInsideDoesNotLiftTheWarning() {
        startedGame()
        // Zone radius 500 m, border margin 10 m: 540 m with 5 m accuracy is clearly outside, 500 m is not.
        val outside = center.moveBy(540.0, 0.0)
        repeat(3) {
            report(hider, outside)
            tick(5)
        }
        val deadline = assertNotNull(game.snapshotFor(hider, now).me.outOfZoneDeadlineMillis)

        report(hider, center.moveBy(500.0, 0.0))
        tick(1)
        assertEquals(deadline, game.snapshotFor(hider, now).me.outOfZoneDeadlineMillis, "a GPS jump is not a return")

        repeat(3) {
            report(hider, center.moveBy(480.0, 0.0))
            tick(2)
        }
        assertNull(game.snapshotFor(hider, now).me.outOfZoneDeadlineMillis, "back for several fixes: warning lifted")
        assertEquals(PlayerStatus.ACTIVE, statusOf(hider))
    }

    @Test
    fun hidersAreInvisibleUntilTheirSignalGoesStale() {
        startedGame()
        report(hider, center.moveBy(50.0, 50.0))
        report(seeker, center)

        assertNull(game.snapshotFor(seeker, now).players.single { it.id == hider }.location)
        assertNull(game.snapshotFor(hider, now).players.single { it.id == seeker }.location, "hiders never see others")

        tick(settings.rules.staleLocationRevealSeconds)
        val revealed = game.snapshotFor(seeker, now).players.single { it.id == hider }.location
        assertEquals(VisibilityReason.STALE_SIGNAL, revealed?.reason)
    }

    @Test
    fun gpsOffIsRevealedEvenIfTheAppKeepsSyncing() {
        startedGame()
        report(hider, center.moveBy(50.0, 50.0))
        repeat(9) {
            tick(5)
            game.recordLocations(hider, emptyList(), now)
        }

        val revealed = game.snapshotFor(seeker, now).players.single { it.id == hider }.location
        assertEquals(VisibilityReason.STALE_SIGNAL, revealed?.reason)
    }

    @Test
    fun onlyHiderGetsItsCatchCodeSecret() {
        startedGame()
        assertEquals(secret, game.snapshotFor(hider, now).me.catchCodeSecret)
        assertNull(game.snapshotFor(seeker, now).me.catchCodeSecret)
    }

    @Test
    fun gameEndsWhenAllHidersAreCaught() {
        startedGame()
        report(seeker, center)
        game.claimCatch(seeker, hider, CatchId("c1"), now)
        game.confirmCatch(CatchId("c1"), seeker, code(), now)
        game.claimCatch(seeker, host, CatchId("c2"), now)
        tick(settings.rules.catchCodeTimeoutSeconds)

        assertEquals(GamePhase.FINISHED, game.phase)
    }

    @Test
    fun gameFinishesWhenTheLastHiderIsCaughtBySilence() {
        startedGame()
        report(seeker, center)
        game.claimCatch(seeker, hider, CatchId("c1"), now)
        game.confirmCatch(CatchId("c1"), seeker, code(), now)
        game.claimCatch(seeker, host, CatchId("c2"), now)
        val deadline = now + settings.rules.catchCodeTimeoutSeconds * 1000L

        // The next request comes a bit after the deadline: the game still ended at the deadline.
        tick(settings.rules.catchCodeTimeoutSeconds + 3)

        assertEquals(GamePhase.FINISHED, game.phase)
        assertEquals(deadline, game.debugState(now).finishedAtMillis)
        assertEquals(deadline, game.debugState(now).phaseStartedAtMillis)
    }

    // ---- Buildings (docs/adr/0003-map-and-buildings.md) ----

    private val insideBlock = center.moveBy(DebugBuildings.INSIDE_EAST, DebugBuildings.INSIDE_NORTH)
    private val nextToBlock = center.moveBy(DebugBuildings.INSIDE_EAST, DebugBuildings.SOUTH - 20)
    private val inTheArch = center.moveBy(DebugBuildings.ARCH_EAST, DebugBuildings.INSIDE_NORTH)
    private val revealMillis = settings.rules.insideBuildingRevealSeconds * 1000L

    private fun withTestQuarter(): Game {
        val quarter = DebugBuildings.around(center)
        game.onBuildingsLoaded(quarter.buildings, quarter.passages)
        return startedGame()
    }

    /** [player] keeps reporting [point] every 3 s for [seconds], the seeker stays put. */
    private fun stay(player: PlayerId, point: GeoPoint, seconds: Int) {
        repeat(seconds / 3) {
            now += 3_000
            report(seeker, center)
            report(player, point)
        }
    }

    private fun warning(): Long? = game.snapshotFor(hider, now).me.insideBuildingRevealAtMillis

    private fun hiderAsSeenBySeeker() = game.snapshotFor(seeker, now).players.single { it.id == hider }.location

    @Test
    fun insideABuildingForLongerThanAllowedIsRevealedNeverEliminated() {
        withTestQuarter()
        stay(hider, insideBlock, 9)

        val revealAt = assertNotNull(warning(), "warned as soon as the server is confident")
        assertEquals(now + revealMillis, revealAt)
        assertNull(hiderAsSeenBySeeker(), "not revealed before the time is up")

        stay(hider, insideBlock, settings.rules.insideBuildingRevealSeconds)

        val seen = assertNotNull(hiderAsSeenBySeeker())
        assertEquals(VisibilityReason.INSIDE_BUILDING, seen.cause)
        assertEquals(VisibilityReason.OUT_OF_ZONE, seen.reason, "the closest reason the first app versions know")
        assertEquals(
            VisibilityReason.INSIDE_BUILDING,
            game.debugState(now).players.single {
                it.id == hider
            }.revealedToSeekers,
        )
        assertEquals(PlayerStatus.ACTIVE, statusOf(hider), "GPS near houses is a hint, not a judge")
    }

    @Test
    fun timeInsideDuringTheHidingPhaseDoesNotCount() {
        val quarter = DebugBuildings.around(center)
        game.onBuildingsLoaded(quarter.buildings, quarter.passages)
        game.addPlayer(host, "Host", now)
        game.addPlayer(seeker, "Seeker", now)
        game.addPlayer(hider, "Hider", now)
        game.start(host, setOf(seeker), { secret }, now)

        stay(hider, insideBlock, settings.hidingSeconds)

        assertEquals(GamePhase.SEEKING, game.phase)
        val seekingStarted = game.debugState(now).phaseStartedAtMillis
        assertEquals(seekingStarted + revealMillis, warning(), "the full time to get out, counted from the seeking")
        stay(hider, insideBlock, settings.rules.insideBuildingRevealSeconds - 3)
        assertNull(hiderAsSeenBySeeker())
    }

    @Test
    fun oneFixThatJumpsIntoTheBuildingDecidesNothing() {
        withTestQuarter()
        stay(hider, nextToBlock, 9)
        stay(hider, insideBlock, 3)
        stay(hider, nextToBlock, settings.rules.insideBuildingRevealSeconds + 9)

        assertNull(warning())
        assertNull(hiderAsSeenBySeeker())
    }

    @Test
    fun leavingBeforeTheRevealLiftsTheWarning() {
        withTestQuarter()
        stay(hider, insideBlock, 9)
        assertNotNull(warning())

        stay(hider, nextToBlock, 9)
        assertNull(warning(), "out again, judged on several fixes")

        stay(hider, nextToBlock, settings.rules.insideBuildingRevealSeconds)
        assertNull(hiderAsSeenBySeeker())
    }

    @Test
    fun oneFixOutsideDoesNotResetTheTimer() {
        withTestQuarter()
        stay(hider, insideBlock, 9)
        val revealAt = assertNotNull(warning())

        stay(hider, nextToBlock, 3)
        stay(hider, insideBlock, 6)
        assertEquals(revealAt, warning())
    }

    @Test
    fun anArchThroughTheBuildingIsOutdoors() {
        withTestQuarter()
        stay(hider, inTheArch, settings.rules.insideBuildingRevealSeconds + 9)

        assertNull(warning())
        assertNull(hiderAsSeenBySeeker())
    }

    @Test
    fun withoutBuildingDataTheRuleIsOffAndThePlayersKnow() {
        assertEquals(BuildingsState.LOADING, game.debugState(now).buildings, "until the source answers")
        game.onBuildingsUnavailable()
        startedGame()

        stay(hider, insideBlock, settings.rules.insideBuildingRevealSeconds + 9)

        assertEquals(BuildingsState.UNAVAILABLE, game.snapshotFor(hider, now).buildings)
        assertEquals(BuildingsState.UNAVAILABLE, game.buildingsFor(hider, now).state)
        assertNull(game.debugState(now).players.single { it.id == hider }.buildingsLoadedAtMillis, "nothing to draw")
        assertNull(warning())
        assertNull(hiderAsSeenBySeeker())
    }

    @Test
    fun playersGetTheBuildingsTheRuleJudgesBy() {
        withTestQuarter()

        val buildings = game.buildingsFor(hider, now)
        assertEquals(BuildingsState.READY, buildings.state)
        assertEquals(DebugBuildings.around(center).buildings, buildings.buildings)
        assertEquals(1, buildings.passages.size)
        assertEquals(now, game.debugState(now).players.single { it.id == hider }.buildingsLoadedAtMillis)
        assertNull(game.debugState(now).players.single { it.id == seeker }.buildingsLoadedAtMillis)
        assertEquals(
            ErrorCode.NOT_FOUND,
            assertFailsWith<GameException> { game.buildingsFor(PlayerId("x"), now) }.code,
        )
    }

    // ---- Accounts (docs/adr/0004-accounts-friends-chat.md) ----

    private val alice = UserId("alice")

    @Test
    fun playersWithAndWithoutAccounts() {
        game.addPlayer(host, "alice", now, alice)
        game.addPlayer(hider, "Guest", now)

        assertEquals(host, game.playerOf(alice))
        assertNull(game.playerOf(UserId("bob")))
        assertEquals(alice, game.userIdOf(host))
        assertNull(game.userIdOf(hider))
        assertEquals(ErrorCode.NOT_FOUND, assertFailsWith<GameException> { game.userIdOf(seeker) }.code)

        val players = game.snapshotFor(hider, now).players
        assertEquals(alice, players.single { it.id == host }.userId)
        assertNull(players.single { it.id == hider }.userId)
        assertEquals(alice, game.debugState(now).players.single { it.id == host }.userId)
    }

    @Test
    fun anAccountHasOnePlayerPerGame() {
        game.addPlayer(host, "alice", now, alice)

        val error = assertFailsWith<GameException> { game.addPlayer(seeker, "alice", now, alice) }
        assertEquals(ErrorCode.WRONG_STATE, error.code)
        assertEquals(listOf(host), game.snapshotFor(host, now).players.map { it.id })
        // Guests can't be told apart: any number of them.
        game.addPlayer(seeker, "Guest", now)
        game.addPlayer(hider, "Guest", now)
    }

    // ---- Chat ----

    /** [player] says [text], 2 s after the previous message: never too fast. */
    private fun say(player: PlayerId, text: String = "hi", team: Boolean = false): ChatMessage {
        now += 2_000
        return game.sendChat(player, text, team, now)
    }

    private fun chatOf(viewer: PlayerId, after: Long = 0) = game.snapshotFor(viewer, now, chatAfter = after).chat

    private fun lobby() {
        game.addPlayer(host, "Host", now)
        game.addPlayer(seeker, "Seeker", now)
    }

    @Test
    fun teamMessagesStayInTheTeam() {
        startedGame()
        val all = say(seeker, "hello all")
        val seekers = say(seeker, "for seekers", team = true)
        val hiders = say(hider, "for hiders", team = true)

        assertEquals(
            listOf(ChatChannel.ALL, ChatChannel.SEEKERS, ChatChannel.HIDERS),
            listOf(all, seekers, hiders).map { it.channel },
        )
        assertEquals(listOf(all, seekers), chatOf(seeker))
        assertEquals(listOf(all, hiders), chatOf(hider))
        assertEquals(listOf(all, hiders), chatOf(host))
        assertEquals(listOf(all, seekers, hiders), game.debugState(now).chat)
    }

    @Test
    fun theLobbyHasOnlyTheCommonChannel() {
        lobby()
        val message = say(seeker, "my team?", team = true)
        assertEquals(ChatChannel.ALL, message.channel)

        // Roles handed out: the lobby's messages stay everybody's.
        game.start(host, setOf(seeker), { secret }, now)
        assertEquals(listOf(message), chatOf(seeker))
        assertEquals(listOf(message), chatOf(host))
    }

    @Test
    fun theResultsScreenHasAChatToo() {
        startedGame()
        tick(settings.seekingSeconds)
        assertEquals(GamePhase.FINISHED, game.phase)

        val all = say(seeker, "gg")
        val hiders = say(hider, "we won", team = true)
        assertEquals(listOf(all), chatOf(seeker))
        assertEquals(listOf(all, hiders), chatOf(host))
    }

    @Test
    fun theCursorBringsOnlyNewerMessages() {
        lobby()
        val one = say(host, "one")
        val two = say(seeker, "two")
        val three = say(host, "three")

        assertEquals(listOf(1L, 2L, 3L), listOf(one, two, three).map { it.seq })
        assertEquals(listOf(one, two, three), chatOf(seeker, after = 0))
        assertEquals(listOf(three), chatOf(seeker, after = two.seq))
        assertEquals(emptyList(), chatOf(seeker, after = three.seq))
        // No cursor: an app without chat gets none.
        assertEquals(emptyList(), game.snapshotFor(seeker, now).chat)
    }

    @Test
    fun theNewestMessagesWithinLimits() {
        lobby()
        repeat(250) { say(if (it % 2 == 0) host else seeker, "message $it") }

        // The game keeps the last 200, a response brings the newest 100 of them.
        assertEquals((51L..250L).toList(), game.debugState(now).chat.map { it.seq })
        assertEquals((151L..250L).toList(), chatOf(host).map { it.seq })
        assertEquals((201L..250L).toList(), chatOf(host, after = 200).map { it.seq })
        assertEquals(ErrorCode.NOT_FOUND, assertFailsWith<GameException> { game.reportedMessage(host, 2) }.code)
    }

    @Test
    fun fiveMessagesInTenSeconds() {
        lobby()
        repeat(5) { game.sendChat(host, "spam $it", false, now + it * 1000L) }

        val tooFast = assertFailsWith<GameException> { game.sendChat(host, "more", false, now + 5_000) }
        assertEquals(ErrorReason.TOO_MANY_REQUESTS, tooFast.reason)
        // The first one leaves the window at +10 s.
        assertEquals(5, tooFast.retryAfterSeconds)
        // The others can still talk.
        game.sendChat(seeker, "calm down", false, now + 5_000)
        game.sendChat(host, "again", false, now + 10_000)
        assertFailsWith<GameException> { game.sendChat(host, "and again", false, now + 10_500) }
        assertEquals(7, game.debugState(now).chat.size)
    }

    @Test
    fun messagesAreCleanedAndChecked() {
        lobby()
        assertEquals("hello world", say(host, "  hello\nworld‮\u0007 ").text)
        assertEquals(ChatRules.MAX_LENGTH, say(host, "x".repeat(ChatRules.MAX_LENGTH)).text.length)

        for (text in listOf("", " \n\t ", "‮\u0000", "x".repeat(ChatRules.MAX_LENGTH + 1))) {
            val error = assertFailsWith<GameException> { say(host, text) }
            assertEquals(ErrorCode.BAD_REQUEST to ErrorReason.INVALID_MESSAGE, error.code to error.reason)
        }
        assertEquals(2, game.debugState(now).chat.size)
    }

    @Test
    fun chattingKeepsTheGameAlive() {
        lobby()
        val idleMillis = 60_000L
        now += idleMillis - 1
        game.sendChat(host, "anyone?", false, now)
        now += idleMillis - 1
        assertFalse(game.isExpired(now, finishedRetentionMillis = idleMillis, idleRetentionMillis = idleMillis))
    }

    @Test
    fun whatCanBeReported() {
        game.addPlayer(host, "alice", now, alice)
        game.addPlayer(seeker, "Seeker", now)
        game.addPlayer(hider, "Hider", now)
        game.start(host, setOf(seeker), { secret }, now)

        val rude = say(host, "rude")
        val reported = game.reportedMessage(seeker, rude.seq)
        assertEquals(rude, reported.message)
        assertEquals("alice", reported.senderName)
        assertEquals(alice, reported.senderUserId)
        assertNull(reported.reporterUserId)
        assertEquals(ErrorCode.FORBIDDEN, assertFailsWith<GameException> { game.reportedMessage(host, rude.seq) }.code)

        // The other team's messages don't exist for the seeker, like unknown ones.
        val plan = say(hider, "hide behind the church", team = true)
        for (seq in listOf(plan.seq, 99L)) {
            assertEquals(ErrorCode.NOT_FOUND, assertFailsWith<GameException> { game.reportedMessage(seeker, seq) }.code)
        }
        val byTeammate = game.reportedMessage(host, plan.seq)
        assertEquals(alice, byTeammate.reporterUserId)
        assertNull(byTeammate.senderUserId)
    }
}
