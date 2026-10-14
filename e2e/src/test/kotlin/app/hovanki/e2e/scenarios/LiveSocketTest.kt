package app.hovanki.e2e.scenarios

import app.hovanki.client.network.Transport
import app.hovanki.client.session.ConnectionStatus
import app.hovanki.e2e.OWN_SERVER
import app.hovanki.e2e.bot.BotTransport
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.e2e.scenario.Scenario
import app.hovanki.e2e.scenarioOnOwnServer
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.ServerFeature
import kotlinx.coroutines.delay
import org.junit.jupiter.api.parallel.ResourceLock
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The live channel (docs/adr/0015-websockets.md): with the operator's switch on, the apps sync over a socket and the
 * server pokes them when something happens, so a claim reaches the other phones within a second although they sync only
 * every five; a chat message comes itself, to the readers only; an app from before it polls in the same game. A broken network sends an app to polling
 * and back to the socket; the switch turned off mid-game sends every app to polling, and the game goes on. On a server
 * of its own: the switch changes how every game on a server syncs.
 */
class LiveSocketTest {
    /** Syncs every 5 s: whatever arrives within a second came with a poke. */
    private val slowSync = GameSetups.fast(rules = GameSetups.FAST_RULES.copy(syncIntervalSeconds = 5))

    @Test
    @ResourceLock(OWN_SERVER)
    fun whatHappensReachesThePhonesAtOnce() = scenarioOnOwnServer("Live channel: at once", properties = emptyMap()) {
        observer.setFeatures(listOf(ServerFeature.LIVE_SOCKET.name))
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 15.0))
        val oleg = player("Oleg", at = PARK.offset(northMeters = 60.0), transport = BotTransport.POLLING)
        sam.createsGame(slowSync)
        join(anna, oleg)
        awaitThat("Sam's and Anna's apps sync over the socket") {
            sam.syncTransport == Transport.SOCKET && anna.syncTransport == Transport.SOCKET
        }
        awaitThat("Oleg's older app polls") { oleg.syncTransport == Transport.POLLING }

        requireOk(sam.sendChat("ready?"), "Sam writes in the lobby")
        within("Anna reads it", 1.seconds) { anna.chat.any { it.text == "ready?" } }
        check(anna.chatPushed.any { it.text == "ready?" }, "the message itself came over Anna's socket")
        eventually("Oleg reads it with his next poll", within = 7.seconds) {
            oleg.chat.firstOrNull { it.text == "ready?" }
        }

        sam.startsGame(seekers = listOf(sam))
        within("Anna's phone knows the round began", 1.seconds) { anna.snapshot?.phase == GamePhase.HIDING }
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        within("the search began on Anna's phone", 1.seconds) { anna.snapshot?.phase == GamePhase.SEEKING }

        requireOk(oleg.sendChat("by the fountain", team = true), "Oleg writes to the hiders")
        within("Anna reads it", 1.seconds) { anna.chat.any { it.text == "by the fountain" } }
        check(sam.chatPushed.none { it.text == "by the fountain" }, "the seeker never gets the hiders' message")

        sam.claimsCatch(anna)
        within("Anna's phone shows the claim", 1.seconds) {
            anna.snapshot?.catches?.any { it.hiderId == anna.id && it.status == CatchStatus.AWAITING_CODE } == true
        }
        awaitCatch(anna, CatchStatus.CONFIRMED)
        within("Sam's phone shows Anna caught", 1.seconds) {
            sam.snapshot?.catches?.any { it.hiderId == anna.id && it.status == CatchStatus.CONFIRMED } == true
        }
        check(sam.transportHistory == listOf(Transport.POLLING, Transport.SOCKET), "Sam's app stayed on the socket")
        check(oleg.transportHistory == listOf(Transport.POLLING), "Oleg's app polled all along, in the same game")
    }

    @Test
    @ResourceLock(OWN_SERVER)
    fun aBrokenNetworkPollsAndComesBack() = scenarioOnOwnServer("Live channel: network", properties = emptyMap()) {
        observer.setFeatures(listOf(ServerFeature.LIVE_SOCKET.name))
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 40.0), pollAfterSocketFailure = 5.seconds)
        sam.createsGame(slowSync)
        join(anna)
        awaitThat("Anna's app syncs over the socket") { anna.syncTransport == Transport.SOCKET }

        anna.losesNetwork()
        awaitThat("Anna's app reconnects") { anna.state.connectionStatus == ConnectionStatus.RECONNECTING }
        delay(3.seconds)
        anna.regainsNetwork()
        // The socket doesn't open without a network: polling, until the network is back and a while longer.
        awaitThat("Anna's app polled, then went back to the socket", within = 25.seconds) {
            anna.transportHistory == listOf(Transport.POLLING, Transport.SOCKET, Transport.POLLING, Transport.SOCKET)
        }
        requireOk(sam.sendChat("still there?"), "Sam writes")
        within("Anna reads it", 1.seconds) { anna.chat.any { it.text == "still there?" } }
    }

    @Test
    @ResourceLock(OWN_SERVER)
    fun theSwitchTurnedOffMidGame() = scenarioOnOwnServer("Live channel: switched off", properties = emptyMap()) {
        observer.setFeatures(listOf(ServerFeature.LIVE_SOCKET.name))
        val sam = player("Sam", at = PARK, pollAfterSocketFailure = 5.seconds)
        val anna = player("Anna", at = PARK.offset(eastMeters = 40.0), pollAfterSocketFailure = 5.seconds)
        sam.createsGame(GameSetups.fast())
        join(anna)
        sam.startsGame(seekers = listOf(sam))
        awaitThat("both apps sync over the socket") { listOf(sam, anna).all { it.syncTransport == Transport.SOCKET } }

        observer.setFeatures(emptyList(), keepLiveSocket = false)
        note("the operator turns the live channel off")
        awaitThat("both apps poll", within = 10.seconds) {
            listOf(sam, anna).all { it.syncTransport == Transport.POLLING }
        }
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        requireOk(sam.sendChat("anyone?"), "the game goes on")
        eventually("Anna reads it by polling", within = 5.seconds) { anna.chat.firstOrNull { it.text == "anyone?" } }
        holdsFor("the apps stay on polling while it is off", 7.seconds) {
            listOf(sam, anna).all { it.syncTransport == Transport.POLLING }
        }

        observer.setFeatures(listOf(ServerFeature.LIVE_SOCKET.name))
        note("and on again")
        awaitThat("both apps are back on the socket", within = 15.seconds) {
            listOf(sam, anna).all { it.syncTransport == Transport.SOCKET }
        }
    }

    /** [condition] holds within [limit] from now: what a poke brings, not the next sync. */
    private suspend fun Scenario.within(what: String, limit: Duration, condition: () -> Boolean) {
        val start = TimeSource.Monotonic.markNow()
        awaitThat(what, within = limit + LOG_SLACK) { condition() }
        val took = start.elapsedNow()
        check(took <= limit, "$what in ${took.inWholeMilliseconds} ms")
    }

    private companion object {
        /** The scenario's own polling of the phones' state: its steps, not the app's. */
        val LOG_SLACK = 1.seconds
    }
}
