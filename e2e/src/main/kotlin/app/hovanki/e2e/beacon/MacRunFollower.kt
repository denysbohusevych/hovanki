package app.hovanki.e2e.beacon

import app.hovanki.client.lab.LabRunScript
import app.hovanki.client.lab.LabRunScripts
import app.hovanki.client.lab.LabRunToken
import app.hovanki.client.lab.MacSetup
import app.hovanki.client.lab.RunStep

/**
 * The Mac's side of the radio lab's automatic run (docs/radio-lab-tests.md): it hears the phone's announcement (a
 * hider's token `1ab…`, [onHeard]), learns the script and its start, and from then on sets its Bluetooth for every
 * step by the server's clock ([tick]): advertise as a hider, as a seeker's iBeacon, or nothing; sniffing stays on.
 * Between runs it is [idle]: advertising as a hider, which the manual scenarios (the pocket, the distances) need.
 * Not thread-safe: one thread calls it.
 */
class MacRunFollower(
    private val serverNow: () -> Long,
    private val command: (String) -> Unit,
    private val onRunStart: (token: String, script: LabRunScript, startAt: Long) -> Unit = { _, _, _ -> },
    private val onStep: (index: Int, step: RunStep) -> Unit = { _, _ -> },
    private val onRunEnd: (token: String) -> Unit = {},
) {
    private class Run(val token: String, val script: LabRunScript, val startAt: Long, var index: Int = -1)

    private var run: Run? = null
    private var setup: MacSetup? = null

    val inRun: Boolean get() = run != null

    /** Between runs: advertise as a hider. */
    fun idle() = apply(IDLE)

    /** The helper heard [token]: a new run's announcement starts following it. */
    fun onHeard(token: String) {
        if (run?.token == token) return
        val (version, startAt) = LabRunToken.decode(token, serverNow()) ?: return
        val script = LabRunScripts.of(version) ?: return
        if (serverNow() >= startAt + script.totalMillis) return
        run?.let { onRunEnd(it.token) }
        run = Run(token, script, startAt)
        onRunStart(token, script, startAt)
        apply(MacSetup())
        tick()
    }

    /** Call often (a few times a second): switches to the step due now, and back to idle after the last. */
    fun tick() {
        val current = run ?: return
        val now = serverNow()
        val at = current.script.at(now - current.startAt)
        if (at == null) {
            if (now >= current.startAt) {
                run = null
                onRunEnd(current.token)
                idle()
            }
            return
        }
        if (at.index == current.index) return
        current.index = at.index
        onStep(at.index, at.value)
        apply(at.value.mac)
    }

    private fun apply(mac: MacSetup) {
        if (mac == setup) return
        setup = mac
        command(
            when {
                mac.iBeacon -> "ibeacon ${LabRunScripts.MAC_BEACON_TOKEN}"
                mac.advertise -> "advertise ${LabRunScripts.MAC_HIDER_TOKEN}"
                else -> "stop"
            },
        )
    }

    private companion object {
        val IDLE = MacSetup(advertise = true)
    }
}
