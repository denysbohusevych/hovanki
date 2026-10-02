package app.hovanki.client.lab

import app.hovanki.device.LiveActivityHost
import app.hovanki.device.LiveActivityPlatform
import app.hovanki.device.PocketTrace
import app.hovanki.device.RoundLiveActivity
import app.hovanki.device.lab.LabScreen
import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.protocol.Role
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The field build's round in the pocket (docs/adr/0018-field-test-build.md, wave 4): when the round's Live Activity
 * and the screen by the proximity sensor are on, and what the log is told.
 */
class FieldPocketTest {
    private class FakeScreen(override val canTurnOffByProximity: Boolean = true) : LabScreen {
        var on = false
        var platformRefuses = false
        val switches = ArrayList<Boolean>()

        override fun setOffByProximity(on: Boolean) {
            this.on = on && !platformRefuses
            switches += on
        }

        override fun isOffByProximity(): Boolean = on
    }

    private class FakeHost : LiveActivityHost {
        override val isAvailable = true
        var running = false
        val texts = ArrayList<String>()

        override fun start(title: String, text: String): Boolean {
            running = true
            texts += "start $text"
            return true
        }

        override fun update(text: String, band: Int, detail: String, endsAtMillis: Long) {
            texts += "update $text/$detail"
        }

        override fun alert(title: String, text: String, silent: Boolean) = running

        override fun end() {
            running = false
            texts += "end"
        }
    }

    private class Trace : PocketTrace {
        val lines = ArrayList<String>()

        override fun mode(mode: String, event: String, reason: String?) {
            lines += "$mode $event${reason?.let { " ($it)" }.orEmpty()}"
        }
    }

    private val platform = object : LiveActivityPlatform {
        override fun onWillResignActive(block: () -> Unit): () -> Unit = {}

        override fun prepareSilentSound(): Boolean = true
    }

    private val hiding = PocketRound(inRound = true, hasRadar = true, playing = true, role = Role.HIDER)

    @Test
    fun theRulesOfThePocket() {
        assertTrue(PocketRules.wanted(fieldOn = true, round = hiding))
        assertFalse(PocketRules.wanted(fieldOn = false, round = hiding), "only while the field log writes")
        assertFalse(PocketRules.wanted(fieldOn = true, round = null))
        assertFalse(PocketRules.wanted(true, hiding.copy(inRound = false)), "not in the lobby or on the results")
        assertFalse(PocketRules.wanted(true, hiding.copy(hasRadar = false)), "only a game with the radar")
        assertFalse(PocketRules.wanted(true, hiding.copy(playing = false)), "not a caught hider")

        // On the screen: on. Off it: off, unless the sensor itself darkened a screen that was on.
        assertTrue(PocketRules.proximity(true, onScreen = true, near = null, isOn = false, canTurnOff = true))
        assertFalse(PocketRules.proximity(true, onScreen = false, near = false, isOn = true, canTurnOff = true))
        assertTrue(PocketRules.proximity(true, onScreen = false, near = true, isOn = true, canTurnOff = true))
        assertFalse(PocketRules.proximity(true, onScreen = false, near = true, isOn = false, canTurnOff = true))
        assertFalse(PocketRules.proximity(false, onScreen = true, near = true, isOn = true, canTurnOff = true))
        assertFalse(PocketRules.proximity(true, onScreen = true, near = null, isOn = false, canTurnOff = false))
    }

    @Test
    fun theRoundTurnsBothOnAndItsEndBothOff() = runTest {
        val host = FakeHost()
        val screen = FakeScreen()
        val trace = Trace()
        val pocket = FieldPocket(RoundLiveActivity(host, platform), screen, backgroundScope) {
            PocketTexts(hider = "Вы прячетесь", hot = "Горячо!")
        }.also { it.trace = trace }

        // The lobby: nothing.
        pocket.update(fieldOn = true, round = hiding.copy(inRound = false))
        assertFalse(screen.on)
        assertTrue(host.texts.isEmpty())

        pocket.update(fieldOn = true, round = hiding)
        assertTrue(screen.on)
        assertTrue(pocket.isProximityOn)
        assertTrue(host.running)
        assertTrue(pocket.hint.value, "the hint, once the screen goes dark by itself")
        assertTrue("mode.proximity_screen on (round)" in trace.lines, "${trace.lines}")
        // The app's words come, the card is sent again with them; then only changes.
        runCurrent()
        assertEquals("update Вы прячетесь/Radar: nothing", host.texts.last())
        pocket.onPulse(RadarBand.HOT)
        assertEquals("update Вы прячетесь/Горячо!", host.texts.last())
        val sent = host.texts.size
        pocket.update(fieldOn = true, round = hiding)
        pocket.onPulse(RadarBand.HOT)
        assertEquals(sent, host.texts.size, "nothing new, nothing sent")
        pocket.dismissHint()
        assertFalse(pocket.hint.value)

        // In the pocket: the sensor says near, the screen goes dark by it.
        pocket.onProximity(true)
        pocket.onLifecycle("screen_off")
        assertEquals(
            listOf("mode.proximity_screen near", "mode.proximity_screen app (screen_off)"),
            trace.lines.takeLast(3).take(2),
        )
        assertEquals("mode.proximity_screen screen_dark (sensor)", trace.lines.last())
        // The sensor's own dark screen paused the app: the sensor stays on.
        pocket.onScreenChanged(false)
        assertTrue(screen.on)
        // Taken out of the pocket while away: off now (the app is not on the screen).
        pocket.onProximity(false)
        assertFalse(screen.on)
        assertEquals("mode.proximity_screen off (sensor)", trace.lines.last())
        // Locked by the button: written as the phone locked anyway.
        pocket.onScreenChanged(true)
        assertTrue(screen.on)
        pocket.onLifecycle("protected_data_off")
        assertEquals("mode.proximity_screen locked (passcode)", trace.lines.last())
        assertFalse(pocket.hint.value, "once a round")

        // The round is over: both off, the next round shows the hint again.
        pocket.update(fieldOn = true, round = hiding.copy(inRound = false))
        assertFalse(screen.on)
        assertFalse(host.running)
        assertEquals("end", host.texts.last())
        pocket.update(fieldOn = true, round = hiding)
        assertTrue(pocket.hint.value)
        pocket.stop("left")
        assertFalse(screen.on)
        assertFalse(host.running)
    }

    @Test
    fun aCaughtHiderAndAnAppAwayTurnTheSensorOff() {
        val screen = FakeScreen()
        val pocket = FieldPocket(screen = screen)
        pocket.update(fieldOn = true, round = hiding)
        assertTrue(screen.on)
        // Home pressed with the sensor far: the idle timer and the sensor go with the app.
        pocket.onScreenChanged(false)
        assertFalse(screen.on)
        pocket.onScreenChanged(true)
        assertTrue(screen.on)
        pocket.update(fieldOn = true, round = hiding.copy(playing = false))
        assertFalse(screen.on)
    }

    @Test
    fun aPhoneWithoutTheSensorSaysSoOnceAndIsNotAskedAgain() {
        val trace = Trace()
        val without = FakeScreen(canTurnOffByProximity = false)
        val pocket = FieldPocket(screen = without).also { it.trace = trace }
        pocket.update(fieldOn = true, round = hiding)
        pocket.onPulse(RadarBand.WARM)
        assertEquals(listOf(), without.switches)
        assertEquals(
            listOf("mode.proximity_screen unavailable (no proximity screen off on this phone)"),
            trace.lines.filter { it.startsWith("mode.proximity_screen") },
        )
        assertFalse(pocket.hint.value)

        // iOS leaves the sensor off on a device without one: tried once, failed, not again this round.
        val trace2 = Trace()
        val refusing = FakeScreen().also { it.platformRefuses = true }
        val other = FieldPocket(screen = refusing).also { it.trace = trace2 }
        other.update(fieldOn = true, round = hiding)
        other.onScreenChanged(false)
        other.onScreenChanged(true)
        assertEquals(listOf(true, false), refusing.switches)
        assertEquals(
            listOf("mode.proximity_screen failed (the platform left the sensor off)"),
            trace2.lines.filter { it.startsWith("mode.proximity_screen") && " app " !in it },
        )
    }

    @Test
    fun withoutTheFieldLogNothingHappens() {
        val host = FakeHost()
        val screen = FakeScreen()
        val pocket = FieldPocket(RoundLiveActivity(host, platform), screen)
        pocket.update(fieldOn = false, round = hiding)
        pocket.onPulse(RadarBand.BURNING)
        pocket.onScreenChanged(false)
        pocket.onScreenChanged(true)
        assertTrue(screen.switches.isEmpty())
        assertTrue(host.texts.isEmpty())
    }
}
