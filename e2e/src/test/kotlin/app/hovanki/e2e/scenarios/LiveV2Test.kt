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
 * The live channel v2 over its WebSocket (docs/live-v2/client.md, docs/adr/0026-live-v2.md): with the operator's
 * switch on, the apps open a live session and sync over binary frames; what happens is pushed as a state, so it
 * reaches the other phones within a second although they sync only every five; a chat message comes itself, to the
 * readers only; an app from before it polls in the same game. Every snapshot the apps put together from the Downs goes
 * through the same privacy audit as v1's. A broken network and the switch turned off mid-game send the apps to v1, and
 * they come back. On a server of its own: the switch changes how every game on a server syncs.
 */
class LiveV2Test {
    /** Syncs every 5 s: whatever arrives within a second came with a push. */
    private val slowSync = GameSetups.fast(rules = GameSetups.FAST_RULES.copy(syncIntervalSeconds = 5))

    @Test
    @ResourceLock(OWN_SERVER)
    fun whatHappensIsPushedToThePhones() = scenarioOnOwnServer("Live channel v2: pushed", properties = emptyMap()) {
        observer.setFeatures(listOf(ServerFeature.LIVE_V2.name), keepLiveChannel = false)
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 15.0))
        val oleg = player("Oleg", at = PARK.offset(northMeters = 60.0), transport = BotTransport.POLLING)
        sam.createsGame(slowSync)
        join(anna, oleg)
        awaitThat("Sam's and Anna's apps sync over the live channel v2") {
            sam.syncTransport == Transport.LIVE_WS && anna.syncTransport == Transport.LIVE_WS
        }
        awaitThat("Oleg's older app polls") { oleg.syncTransport == Transport.POLLING }

        requireOk(sam.sendChat("ready?"), "Sam writes in the lobby")
        within("Anna reads it", 1.seconds) { anna.chat.any { it.text == "ready?" } }
        check(anna.chatPushed.any { it.text == "ready?" }, "the message itself came over Anna's live session")
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
        check(sam.transportHistory == listOf(Transport.POLLING, Transport.LIVE_WS), "Sam's app stayed on v2")
        check(oleg.transportHistory == listOf(Transport.POLLING), "Oleg's app polled all along, in the same game")
    }

    @Test
    @ResourceLock(OWN_SERVER)
    fun aBrokenNetworkComesBackToV2() = scenarioOnOwnServer("Live channel v2: network", properties = emptyMap()) {
        observer.setFeatures(listOf(ServerFeature.LIVE_V2.name), keepLiveChannel = false)
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 40.0), v1AfterLiveFailure = 5.seconds)
        sam.createsGame(slowSync)
        join(anna)
        awaitThat("Anna's app syncs over the live channel v2") { anna.syncTransport == Transport.LIVE_WS }

        anna.losesNetwork()
        awaitThat("Anna's app reconnects") { anna.state.connectionStatus == ConnectionStatus.RECONNECTING }
        delay(3.seconds)
        anna.regainsNetwork()
        awaitThat("Anna's app is back on v2", within = 25.seconds) {
            anna.syncTransport == Transport.LIVE_WS && anna.state.connectionStatus == ConnectionStatus.ONLINE
        }
        requireOk(sam.sendChat("still there?"), "Sam writes")
        within("Anna reads it", 1.seconds) { anna.chat.any { it.text == "still there?" } }
    }

    @Test
    @ResourceLock(OWN_SERVER)
    fun theSwitchTurnedOffMidGame() = scenarioOnOwnServer("Live channel v2: switched off", properties = emptyMap()) {
        observer.setFeatures(listOf(ServerFeature.LIVE_V2.name), keepLiveChannel = false)
        val sam = player("Sam", at = PARK, v1AfterLiveFailure = 5.seconds)
        val anna = player("Anna", at = PARK.offset(eastMeters = 40.0), v1AfterLiveFailure = 5.seconds)
        sam.createsGame(GameSetups.fast())
        join(anna)
        sam.startsGame(seekers = listOf(sam))
        awaitThat("both apps sync over v2") { listOf(sam, anna).all { it.syncTransport == Transport.LIVE_WS } }

        observer.setFeatures(emptyList(), keepLiveChannel = false)
        note("the operator turns the live channel v2 off")
        awaitThat("both apps poll", within = 15.seconds) {
            listOf(sam, anna).all { it.syncTransport == Transport.POLLING }
        }
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        requireOk(sam.sendChat("anyone?"), "the game goes on")
        eventually("Anna reads it by polling", within = 5.seconds) { anna.chat.firstOrNull { it.text == "anyone?" } }
        holdsFor("the apps stay on polling while it is off", 7.seconds) {
            listOf(sam, anna).all { it.syncTransport == Transport.POLLING }
        }

        observer.setFeatures(listOf(ServerFeature.LIVE_V2.name), keepLiveChannel = false)
        note("and on again")
        awaitThat("both apps are back on v2", within = 15.seconds) {
            listOf(sam, anna).all { it.syncTransport == Transport.LIVE_WS }
        }
    }

    /** [condition] holds within [limit] from now: what a push brings, not the next sync. */
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
