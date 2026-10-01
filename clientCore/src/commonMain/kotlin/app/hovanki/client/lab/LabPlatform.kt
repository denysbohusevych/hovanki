package app.hovanki.client.lab

import app.hovanki.radar.AdvertReport
import app.hovanki.radar.AirSummary
import app.hovanki.radar.HeardFrame
import app.hovanki.radar.RadarCatalog
import app.hovanki.radar.RadioApi
import app.hovanki.radar.RadioTrace
import app.hovanki.radar.SightingVia

/*
 * What the radio lab needs from the app (docs/radio-lab.md §5) besides the phone's parts: sharing the files, and the
 * game's radios writing into the lab's log. The phone's parts are elsewhere: the radio in `:radar`
 * (`app.hovanki.radar.lab.LabAir`), the sensors, the screen and the vibration in `:device`
 * (`app.hovanki.device.lab.LabProbes`, `LabScreen`, `LabHaptics`). [LabFiles] is implemented in `composeApp`'s
 * androidMain and iosMain, the no-op one below everywhere else. Debug builds only use them.
 */

/** Hands files to the system «Share»; they live in a temporary folder only for that. */
interface LabFiles {
    fun share(files: List<LabFile>)
}

data class LabFile(val name: String, val mimeType: String, val text: String)

class NoopLabFiles : LabFiles {
    override fun share(files: List<LabFile>) = Unit
}

/**
 * The game's radios tell the log what they advertise, scan and hear ([RadioTrace]): the debug build's lab, and the
 * field build's journal (docs/adr/0018-field-test-build.md), whenever the log records. While it does, the radios run
 * the shadow's channels too ([RadioTrace.isListening]); their readings come here, never to the game.
 */
class LabRadioTrace(private val log: LabLog) : RadioTrace {
    override val isListening: Boolean get() = log.isWriting

    override fun advertise(action: String, mode: String, token: String?, error: String?, report: AdvertReport?) =
        log.adv(action, mode, token, error = error, report = report)

    override fun scan(action: String, api: RadioApi, filters: String?, error: String?) =
        log.scan(action, api, filters, error)

    override fun frame(frame: HeardFrame, tech: String) = log.frame(frame, tech)

    override fun air(summary: AirSummary) = log.air(summary)

    override fun shadow(tech: String, tokens: List<String>, frame: HeardFrame, via: SightingVia) =
        log.shadow(tech, tokens, frame, via)

    override fun region(event: String, state: String?, error: String?) =
        log.region(RadarCatalog.REGION.id, event, state, error)
}
