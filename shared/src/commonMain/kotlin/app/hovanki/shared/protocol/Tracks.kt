package app.hovanki.shared.protocol

import kotlinx.serialization.Serializable

/**
 * Where everybody went during the round, for the replay after it (docs/adr/0001-stack.md, «Разбор после игры»): served
 * at [ApiRoutes.tracks] only once the game is FINISHED, to its players. The server keeps the tracks in memory and
 * deletes them with the game.
 */
@Serializable
data class TracksResponse(val tracks: List<PlayerTrack> = emptyList())

/**
 * One player's way from the start of hiding until the end of the round (a hider's until they were caught or
 * eliminated): accurate fixes only, at most one every few seconds, oldest first.
 */
@Serializable
data class PlayerTrack(val playerId: PlayerId, val points: List<TrackPoint> = emptyList())

/** A point of a [PlayerTrack], flat to keep long tracks small. */
@Serializable
data class TrackPoint(val lat: Double, val lon: Double, val atMillis: Long) {
    val point: GeoPoint get() = GeoPoint(lat, lon)
}
