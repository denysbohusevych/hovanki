package app.hovanki.e2e.beacon

import app.hovanki.client.lab.LabRunToken
import app.hovanki.shared.lab.LabRunScript
import app.hovanki.shared.lab.LabRunScripts
import app.hovanki.shared.lab.PhoneSetup
import app.hovanki.shared.lab.RunStep

/**
 * The Mac's side of the radio lab's automatic run (docs/radio-lab-tests.md): it hears the phone's announcement (a
 * hider's token `1ab…`, [onHeard]), learns the script and its start, and from then on sets its Bluetooth for every
 * step by the server's clock ([tick]): the script's setups of [label] (`mac`), [PhoneSetup.hider] to advertise as a
 * hider, [PhoneSetup.seeker] as a seeker's iBeacon, else nothing; sniffing stays on. Between runs it is [idle]:
 * advertising as a hider, which the manual scenarios (the pocket, the distances) need. Not thread-safe: one thread
 * calls it.
 */
class MacRunFollower(
    private val serverNow: () -> Long,
    private val command: (String) -> Unit,
    private val onRunStart: (token: String, script: LabRunScript, startAt: Long) -> Unit = { _, _, _ -> },
    private val onStep: (index: Int, step: RunStep) -> Unit = { _, _ -> },
    private val onRunEnd: (token: String) -> Unit = {},
    private val label: String = MAC_LABEL,
) {
    private class Run(val token: String, val script: LabRunScript, val startAt: Long, var index: Int = -1)

    private var run: Run? = null
    private var lastCommand: String? = null

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
        apply(PhoneSetup())
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
        apply(current.script.setupOf(label, at.index))
    }

    private fun apply(mac: PhoneSetup) {
        val line = when {
            mac.seeker -> "ibeacon ${LabRunScripts.MAC_BEACON_TOKEN}"
            mac.hider -> "advertise ${LabRunScripts.MAC_HIDER_TOKEN}"
            else -> "stop"
        }
        if (line == lastCommand) return
        lastCommand = line
        command(line)
    }

    companion object {
        /** The Mac's label in the scripts ([LabRunScript.labels]). */
        const val MAC_LABEL = "mac"

        private val IDLE = PhoneSetup(hider = true)
    }
}
