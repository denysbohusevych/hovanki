package app.hovanki.client.lab

import app.hovanki.radar.RadioApi
import app.hovanki.radar.RadioTrace

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

/** The game's radios tell the lab's log what they advertise and scan ([RadioTrace]). */
class LabRadioTrace(private val log: LabLog) : RadioTrace {
    override fun advertise(action: String, mode: String, token: String?, error: String?) =
        log.adv(action, mode, token, error = error)

    override fun scan(action: String, api: RadioApi, filters: String?, error: String?) =
        log.scan(action, api, filters, error)
}
