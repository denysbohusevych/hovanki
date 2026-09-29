package app.hovanki.e2e.scenarios

import app.hovanki.e2e.bot.FakeRadio
import app.hovanki.e2e.route.Route
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.protocol.RadarBand
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The radar by Bluetooth (docs/adr/0010-nearby-radar.md): the seeker feels a hider «burning» a metre away and nothing
 * from afar; a hider with the sense feels the seeker coming, nameless, on the phone in the pocket («Пульс»); an
 * iPhone in a pocket is never heard by an Android, but hears the seeker's beacon itself and tells the server. The
 * privacy audit checks every response: no names to hiders, no tokens to seekers.
 */
class RadarTest {
    @Test
    fun theSeekerFeelsTheHidersAndTheHidersFeelTheSeeker() = scenario("The radar") {
        enableAllFeatures()
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 60.0))
        val bob = player("Bob", at = PARK.offset(northMeters = 60.0), platform = Platform.IOS)

        sam.createsGame(GameSetups.radar())
        join(anna, bob)
        eventually("the lobby shows Bob's iPhone with Bluetooth on") {
            sam.snapshot?.players?.firstOrNull { it.id == bob.id }?.capabilities
                ?.takeIf { it.platform == Platform.IOS && it.bluetooth == BluetoothState.ON }
        }
        bob.putsPhoneInPocket()
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        eventually("Anna's phone knows the seekers' tokens") {
            anna.snapshot?.me?.seekerTokens?.takeIf { it.isNotEmpty() }
        }
        holdsFor("nothing on the radar 60 m apart", 3.seconds) {
            sam.radarBandOn(anna) == RadarBand.NONE && anna.pulseBand == RadarBand.NONE
        }

        // Sam comes within a metre of Anna: burning on his radar, and her phone beats in the pocket.
        anna.putsPhoneInPocket()
        sam.walksToAndArrives(anna.gps.truePosition.offset(eastMeters = 0.8), speed = Route.RUNNING)
        awaitBand(sam, anna, RadarBand.BURNING)
        eventually("Anna's phone beats: burning") { anna.pulseBand.takeIf { it == RadarBand.BURNING } }
        val felt = checkNotNull(anna.snapshot?.me?.radar?.contacts?.singleOrNull()) { "Anna feels a seeker" }
        check(felt.playerId == null, "Anna feels a seeker, nameless")
        check(sam.radarBandOn(bob) == RadarBand.NONE, "Bob, far away, is not on the radar")

        // Bob's iPhone in a pocket: Sam's Android can't hear it, but it hears Sam's beacon and tells the server.
        sam.walksToAndArrives(bob.gps.truePosition.offset(eastMeters = 0.8), speed = Route.RUNNING)
        awaitBand(sam, bob, RadarBand.BURNING)
        val samRadio = sam.radio as FakeRadio
        val bobRadio = bob.radio as FakeRadio
        check(bobRadio.token !in samRadio.heardTokens, "Sam's phone never heard Bob's iPhone in the pocket")
        check(samRadio.token in bobRadio.heardTokens, "Bob's iPhone heard Sam's beacon")
        check(state().radar.any { setOf(it.a, it.b) == setOf(sam.id, bob.id) }, "the server knows the pair")

        // Away again: the signal is gone after its life, and the pulse stops.
        sam.walksToAndArrives(PARK, speed = Route.RUNNING)
        eventually("the radar is quiet again", within = 30.seconds) {
            (sam.radarBandOn(bob) == RadarBand.NONE && bob.pulseBand == RadarBand.NONE).takeIf { it }
        }
        check(RadarBand.BURNING in bob.pulse.bands, "Bob's phone beat while Sam was next to him")
    }
}
