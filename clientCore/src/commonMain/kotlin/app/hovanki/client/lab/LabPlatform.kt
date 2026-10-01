package app.hovanki.client.lab

import app.hovanki.radar.AirFrame
import app.hovanki.radar.AirSecond
import app.hovanki.radar.Decoded
import app.hovanki.radar.RadarTrace
import app.hovanki.radar.RadioApi
import app.hovanki.radar.RangeTrace

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
 * The radar's host tells the lab's log what it advertises, scans and hears ([RadarTrace]): `adv` (with the channel and
 * the advertisement's layout; `mode` keeps the older names where a channel had one), `scan`, `frame` and `air`.
 */
class LabRadioTrace(private val log: LabLog) : RadarTrace {
    override fun advertise(action: String, tech: String, token: String?, layout: String?, error: String?) =
        log.adv(action, modeOf(tech), token, error = error, tech = tech, layout = layout)

    override fun scan(action: String, api: RadioApi, filters: String?, error: String?) =
        log.scan(action, api, filters, error)

    override fun frame(frame: AirFrame, decoded: List<Pair<String, Decoded>>) = log.frame(frame, decoded)

    override fun air(second: AirSecond) = log.air(second)

    companion object {
        /** The `adv` event's `mode` before the channels (schema 2): the older readers of the log know these. */
        fun modeOf(tech: String): String = when {
            tech == "ble.name" -> "hider_name"
            tech.startsWith("ble.service_data") -> "hider_service_data"
            tech == "ble.ibeacon" -> "ibeacon"
            else -> tech
        }
    }
}

/**
 * The precision radio tells the lab's log every step of its ranging sessions ([RangeTrace]): the `range` events with
 * an action other than `reading` (`session_start`, `config`, `suspended`, `removed`, `invalidated`…). The platform
 * module gives it to the lab's own precision radio (iOS: `IosPrecisionRadio`); the game's never traces.
 */
class LabRangeTrace(private val log: LabLog) : RangeTrace {
    override fun range(action: String, peer: String?, error: String?) = log.range(action, peer, error = error)
}
