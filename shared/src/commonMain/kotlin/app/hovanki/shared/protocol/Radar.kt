package app.hovanki.shared.protocol

import kotlinx.serialization.Serializable

// The radar (docs/adr/0012-nearby-radar.md): what the phones hear of each other over Bluetooth and what the server
// makes of it. Positions never travel here: only bands.

/**
 * One phone heard another ([SyncRequest.nearby]): the [token] the other one advertises (`RadarToken`, changes every
 * few minutes; the server knows whose it is), the signal strength in dBm and when, in server time.
 */
@Serializable
data class NearbySighting(val token: String, val rssi: Int, val atMillis: Long)

/** How close another player is by the radar: no metres, the signal only tells this much. */
@Serializable
enum class RadarBand {
    /** No signal. */
    NONE,

    /** Within a few dozen metres. */
    WARM,

    /** Within some ten metres. */
    HOT,

    /** A few metres: close enough to see each other. */
    BURNING,
}

/**
 * A player on the viewer's radar. A seeker gets every hider with their [playerId]; a hider with the sense on gets the
 * nearest seeker without a name ([playerId] null).
 */
@Serializable
data class RadarContact(val band: RadarBand, val playerId: PlayerId? = null, val atMillis: Long? = null)

/** The viewer's radar right now ([MyState.radar]); null when the game has none or the viewer has none. */
@Serializable
data class RadarState(val contacts: List<RadarContact> = emptyList())

/** A player the viewer's phone may range with by UWB: their discovery token, exchanged through the server. */
@Serializable
data class UwbPeer(val playerId: PlayerId, val token: String, val platform: Platform = Platform.OTHER)

/** How far by GPS, in the words of a hint: no distance in metres. */
@Serializable
enum class DistanceBand {
    /** Closer than 50 m. */
    NEAR,

    /** Closer than 150 m. */
    CLOSE,

    FAR,
}

/** What a hint is for: the hider's «Sense», the seeker's «Direction» or «Radius» ([PerkKind]). */
@Serializable
enum class HintKind { SENSE, DIRECTION, RADIUS }

/**
 * A hint bought with sparks ([MyState.hint]): where the nearest player of the other team is, as a compass sector
 * ([sector]: 0 north, 1 north-east, … 7 north-west) and a [DistanceBand]. Computed by the server: the phone gets no
 * coordinates.
 */
@Serializable
data class Hint(val kind: HintKind, val untilMillis: Long, val sector: Int? = null, val band: DistanceBand? = null)
