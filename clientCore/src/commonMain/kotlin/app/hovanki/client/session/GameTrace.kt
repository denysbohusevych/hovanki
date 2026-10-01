package app.hovanki.client.session

import app.hovanki.client.network.Transport
import app.hovanki.radar.RadioSighting
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.PlayerSession

/**
 * What happens in the game on this phone, for whoever writes it down: the field log
 * ([app.hovanki.client.lab.FieldSession], docs/adr/0018-field-test-build.md §3.2). [GameSessionManager] tells it as it
 * goes and never waits for it; [None] is every other build. Main thread, except [onSyncSent] (the connection's thread).
 * What it is told is the phone's own: its fixes with their coordinates, what its radio heard. It decides itself what
 * it may keep.
 */
interface GameTrace {
    /** A snapshot was applied: the game's phase, this player's role and status. */
    fun onSnapshot(session: PlayerSession, snapshot: GameSnapshot) = Unit

    /** The phone is out of its game: the player left, or the server no longer has it. */
    fun onSessionEnded() = Unit

    /** A GPS fix as the location provider gave it (its time on the device's clock), before it is queued for the sync. */
    fun onFix(fix: LocationSample) = Unit

    /** The radar's radio heard a phone of the game (its time on the device's clock). */
    fun onSighting(sighting: RadioSighting) = Unit

    /** A sync goes out now. The connection's thread. */
    fun onSyncSent() = Unit

    /** The sync came back with [snapshot] by [transport] (applied already). */
    fun onSynced(transport: Transport, snapshot: GameSnapshot) = Unit

    /** The sync failed with [error]; the connection tries again by itself. */
    fun onSyncFailed(error: Throwable) = Unit

    /** Something failed underneath that the game goes on without: the radio, the location, a command ([where]). */
    fun onError(where: String, error: Throwable) = Unit

    /** Nobody writes anything down. */
    object None : GameTrace
}
