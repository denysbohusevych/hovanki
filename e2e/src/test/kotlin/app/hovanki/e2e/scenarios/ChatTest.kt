package app.hovanki.e2e.scenarios

import app.hovanki.client.social.UserRelation
import app.hovanki.e2e.bot.BotPlayer
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.e2e.scenario.Scenario
import app.hovanki.shared.protocol.ChatChannel
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.rules.ChatRules
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The in-game chat (docs/adr/0004-accounts-friends-chat.md) as the players' apps show it: messages arrive with the
 * polls, team messages only reach the team (SnapshotAudit checks that in every response too), guests chat like
 * everybody, reports reach the moderators, blocking hides messages on the blocker's phone only.
 */
class ChatTest {
    /** A short round: the game ends by the clock, and the chat goes on on the results screen. */
    private val shortRound = GameSetups.fast().copy(seekingSeconds = 20)

    @Test
    fun chatThroughAGame() = scenario("Chat through a game") {
        val sam = player("Sam", at = PARK)
        val tom = player("Tom", at = PARK)
        val anna = player("Anna", at = PARK.offset(northMeters = 40.0))
        val boris = player("Boris", at = PARK.offset(northMeters = -40.0))
        sam.signsUp()
        anna.signsUp()
        val seekers = listOf(sam, tom)
        val hiders = listOf(anna, boris)

        sam.createsGame(shortRound)
        join(tom, anna, boris)
        requireOk(anna.sendChat("hi all"), "Anna writes in the lobby")
        requireOk(boris.sendChat("hi from a guest", team = true), "Boris (a guest) picks 'my team' in the lobby")
        everybodyReads(players, "hi from a guest")
        check(
            players.all { bot -> bot.chat.map { it.channel } == listOf(ChatChannel.ALL, ChatChannel.ALL) },
            "the lobby has only the ALL channel",
        )
        val lobbyLine = sam.chat.single { it.text == "hi from a guest" }
        check(lobbyLine.isGuest && lobbyLine.senderName == "Boris", "Sam sees Boris as a guest")
        check(sam.chat.single { it.text == "hi all" }.senderUserId == anna.userId, "and Anna with her account")
        check(tom.unreadChatCount == 2, "two unread messages on Tom's chat button")
        tom.readChat()
        check(tom.unreadChatCount == 0, "none after Tom opened the chat")

        sam.startsGame(seekers = seekers)
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        requireOk(sam.sendChat("north side is mine", team = true), "Sam tells the seekers")
        requireOk(anna.sendChat("they are coming north", team = true), "Anna tells the hiders")
        requireOk(tom.sendChat("good luck everyone"), "Tom (a guest) writes to everybody")
        // The last message has the highest seq: whoever has it has had every poll that could bring the others.
        everybodyReads(players, "good luck everyone")
        for (seeker in seekers) {
            val line = seeker.chat.single { it.text == "north side is mine" }
            check(line.channel == ChatChannel.SEEKERS, "${seeker.name} reads the seekers' message")
            check(seeker.chat.none { it.text == "they are coming north" }, "${seeker.name} never gets the hiders' one")
        }
        for (hider in hiders) {
            val line = hider.chat.single { it.text == "they are coming north" }
            check(line.channel == ChatChannel.HIDERS, "${hider.name} reads the hiders' message")
            check(hider.chat.none { it.text == "north side is mine" }, "${hider.name} never gets the seekers' one")
        }
        check(
            state().chat.map { it.channel } ==
                listOf(ChatChannel.ALL, ChatChannel.ALL, ChatChannel.SEEKERS, ChatChannel.HIDERS, ChatChannel.ALL),
            "the server keeps every message with its channel",
        )

        awaitPhase(GamePhase.FINISHED, within = (shortRound.seekingSeconds + 10).seconds)
        awaitThat("every phone shows the results") { players.all { it.snapshot?.phase == GamePhase.FINISHED } }
        requireOk(boris.sendChat("gg"), "Boris writes on the results screen")
        everybodyReads(players, "gg")

        requireOk(anna.leave(), "Anna closes the results")
        requireOk(sam.sendChat("see you next time"), "Sam writes again")
        everybodyReads(listOf(sam, tom, boris), "see you next time")
        check(anna.state.session == null && anna.chat.isEmpty(), "Anna's phone is on the start screen, no chat")
    }

    @Test
    fun rateLimitAndReports() = scenario("Chat limit and reports") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK)
        val boris = player("Boris", at = PARK)
        sam.signsUp()
        anna.signsUp()

        sam.createsGame(GameSetups.fast())
        join(anna, boris)
        repeat(ChatRules.RATE_LIMIT_MESSAGES) { requireOk(anna.sendChat("message ${it + 1}"), "Anna writes") }
        expectRejected(anna.sendChat("one more"), ErrorReason.TOO_MANY_REQUESTS, "a 6th message within 10 s")
        check(state().chat.size == ChatRules.RATE_LIMIT_MESSAGES, "the 6th message was not kept")
        requireOk(boris.sendChat("I can still write"), "the limit is Anna's alone")

        everybodyReads(listOf(sam, boris), "message 3")
        val reported = sam.chat.single { it.text == "message 3" }
        requireOk(sam.reportChat(reported.seq), "Sam reports message 3")
        requireOk(sam.reportChat(reported.seq), "Sam reports it again")
        requireOk(boris.reportChat(reported.seq), "Boris (a guest) reports it too")
        val reports = observer.reports().filter { it.gameId == gameId }
        check(reports.size == 2, "one report per reporter (${reports.size})")
        val bySam = reports.single { it.reporterPlayerId == sam.id }
        check(
            bySam.messageSeq == reported.seq && bySam.text == "message 3" &&
                bySam.reporterUserId == sam.userId && bySam.reportedUserId == anna.userId &&
                bySam.reportedName == anna.user?.nickname,
            "the moderators see the text, who wrote it and who reported it",
        )
        val byBoris = reports.single { it.reporterPlayerId == boris.id }
        check(byBoris.reporterUserId == null, "a guest's report has no account")
        val own = anna.chat.first { it.isMine }
        expectRejected(anna.reportChat(own.seq), ErrorCode.FORBIDDEN, "Anna reports her own message")
        expectRejected(sam.reportChat(reported.seq + 100), ErrorCode.NOT_FOUND, "a message that doesn't exist")

        delay(ChatRules.RATE_LIMIT_WINDOW_MILLIS.milliseconds)
        requireOk(anna.sendChat("back again"), "after 10 s Anna can write again")
    }

    @Test
    fun blockedPlayersMessagesAreHidden() = scenario("Blocked player's messages") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK)
        val vera = player("Vera", at = PARK)
        sam.signsUp()
        anna.signsUp()
        vera.signsUp()

        sam.createsGame(GameSetups.fast())
        join(anna, vera)
        requireOk(sam.sendChat("hello"), "Sam writes")
        everybodyReads(players, "hello")
        val samId = checkNotNull(anna.snapshot?.players?.single { it.id == sam.id }?.userId)

        requireOk(anna.block(samId), "Anna blocks Sam from his chat message")
        check(anna.relationTo(samId) == UserRelation.BLOCKED, "Sam is blocked on Anna's phone")
        check(anna.chat.none { it.playerId == sam.id }, "Sam's messages vanish from Anna's chat")
        requireOk(sam.sendChat("after the block"), "Sam writes again")
        requireOk(vera.sendChat("from vera"), "Vera writes")
        everybodyReads(listOf(sam, vera), "after the block")
        awaitThat("Anna's phone has received both", 10.seconds) {
            anna.state.chat.map { it.text }.containsAll(listOf("after the block", "from vera"))
        }
        check(anna.chat.map { it.text } == listOf("from vera"), "Anna sees only Vera's message")
        check(anna.unreadChatCount == 1, "and only it counts as unread")
        check(vera.chat.any { it.text == "after the block" }, "Vera still sees Sam's messages")

        requireOk(anna.unblock(samId), "Anna unblocks Sam")
        check(anna.chat.map { it.text } == listOf("hello", "after the block", "from vera"), "Sam's messages are back")
    }

    /**
     * 250 messages in the lobby from ten players, each keeping to the limit of five in ten seconds. The game keeps the
     * newest 200 and every phone online has exactly those, in order. A phone offline the whole time gets the newest
     * 100 (one response's worth) when it is back, in order and without duplicates; older ones it doesn't fetch.
     */
    @Test
    fun aLongChat() = scenario("A long chat") {
        val talkers = (1..10).map { player("P$it", at = PARK, logChanges = false) }
        val zoe = player("Zoe", at = PARK, logChanges = false)

        talkers.first().createsGame(GameSetups.fast())
        join(*(talkers.drop(1) + zoe).toTypedArray())
        zoe.losesNetwork()
        coroutineScope {
            for (bot in talkers) {
                launch {
                    repeat(MESSAGES_EACH) { n ->
                        requireOk(bot.sendChat("${bot.name} #$n"), "${bot.name} says #$n")
                        delay(2_100.milliseconds)
                    }
                }
            }
        }
        val kept = state().chat.map { it.seq }
        check(kept.size == ChatRules.HISTORY_SIZE, "the game keeps the newest ${ChatRules.HISTORY_SIZE}")
        check(kept.last() == (talkers.size * MESSAGES_EACH).toLong(), "all ${talkers.size * MESSAGES_EACH} were sent")
        awaitThat("every phone online has the newest ${ChatRules.HISTORY_SIZE}, in order") {
            talkers.all { bot -> bot.state.chat.map { it.seq } == kept }
        }

        zoe.regainsNetwork()
        val newest = kept.takeLast(ChatRules.MAX_PER_RESPONSE)
        awaitThat("Zoe's phone has the newest ${ChatRules.MAX_PER_RESPONSE}, in order", 20.seconds) {
            zoe.state.chat.map { it.seq } == newest
        }
        holdsFor("and nothing else arrives", 3.seconds) { zoe.state.chat.map { it.seq } == newest }
    }

    /**
     * What a message can't be (empty, too long), what is cleaned out of it (line breaks, control and direction
     * characters), and a report of a message the reporter can't see (the other team's) is as unknown as a wrong seq.
     */
    @Test
    fun chatEdges() = scenario("Chat edges") {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 30.0))
        val boris = player("Boris", at = PARK.offset(eastMeters = -30.0))

        sam.createsGame(GameSetups.fast())
        join(anna, boris)
        expectRejected(anna.sendChat("x".repeat(ChatRules.MAX_LENGTH + 1)), ErrorReason.INVALID_MESSAGE, "too long")
        expectRejected(anna.sendChat(" \n\t "), ErrorReason.INVALID_MESSAGE, "only blanks")
        requireOk(anna.sendChat("x".repeat(ChatRules.MAX_LENGTH)), "exactly ${ChatRules.MAX_LENGTH} characters")
        requireOk(anna.sendChat("  meet\nat the \u202Efountain\u0007 "), "a message with control characters")
        awaitThat("Sam reads it cleaned") { sam.chat.any { it.text == "meet at the fountain" } }
        check(state().chat.none { "\u202E" in it.text || "\u0007" in it.text }, "nothing of it is kept on the server")

        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        requireOk(sam.sendChat("going north", team = true), "Sam tells the seekers")
        val secret = state().chat.single { it.text == "going north" }
        check(secret.channel == ChatChannel.SEEKERS, "a seekers' message")
        expectRejected(anna.reportChat(secret.seq), ErrorCode.NOT_FOUND, "Anna reports the seekers' message")
        check(anna.chat.none { it.text == "going north" }, "Anna never saw it")
        check(observer.reports().none { it.messageSeq == secret.seq && it.gameId == gameId }, "no report")
    }

    /** Waits until each of [bots] has [text] in its chat panel. */
    private suspend fun Scenario.everybodyReads(bots: List<BotPlayer>, text: String) {
        awaitThat("${bots.joinToString { it.name }} read \"$text\"", 10.seconds) {
            bots.all { bot -> bot.chat.any { it.text == text } }
        }
    }

    private companion object {
        const val MESSAGES_EACH = 25
    }
}
