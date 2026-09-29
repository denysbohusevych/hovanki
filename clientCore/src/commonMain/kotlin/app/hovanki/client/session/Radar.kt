package app.hovanki.client.session

import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.RadarBand

// What the screen makes of the radar (docs/adr/0012-nearby-radar.md): bands, never metres.

/** The strongest band on the viewer's radar right now; null when the game (or the viewer) has no radar. */
fun GameSnapshot.radarBand(): RadarBand? = me.radar?.let { radar ->
    radar.contacts.maxOfOrNull { it.band }
        ?: RadarBand.NONE
}

/** A seeker's band towards [playerId]; [RadarBand.NONE] when the radar doesn't hear them. */
fun GameSnapshot.radarBandOf(playerId: PlayerId): RadarBand =
    me.radar?.contacts?.firstOrNull { it.playerId == playerId }?.band ?: RadarBand.NONE
