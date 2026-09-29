package app.hovanki.client.tracking

import app.hovanki.shared.protocol.RadarBand

/**
 * The pulse (docs/adr/0012-nearby-radar.md, «Пульс»): the phone beats at the band's pace (`HeartbeatRules`) until told
 * [RadarBand.NONE]. A hider's from the pocket when a seeker comes near, with the screen off; a seeker's sonar in the
 * hand. Android vibrates from the foreground service; iOS can't vibrate in the background and posts notifications at
 * the pace instead. Told again only when the band changes.
 */
interface PocketPulse {
    fun set(band: RadarBand)
}

/** A phone that never beats (the JVM bots). */
object NoopPocketPulse : PocketPulse {
    override fun set(band: RadarBand) = Unit
}
