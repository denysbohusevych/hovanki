package app.hovanki.client.session

import app.hovanki.client.network.Transport
import app.hovanki.radar.ChannelMix
import app.hovanki.radar.ProximityRadio
import app.hovanki.radar.RadioSighting
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.RadarBand
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

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

    /** The size in bytes of the answer the next [onSynced] is about, where the connection measured it. */
    fun onSyncBytes(bytes: Int) = Unit

    /** The sync came back with [snapshot] by [transport] (applied already). */
    fun onSynced(transport: Transport, snapshot: GameSnapshot) = Unit

    /** The sync failed with [error]; the connection tries again by itself. */
    fun onSyncFailed(error: Throwable) = Unit

    /** Something failed underneath that the game goes on without: the radio, the location, a command ([where]). */
    fun onError(where: String, error: Throwable) = Unit

    /**
     * The player did [action] (a word of [FieldActions], never a text or an id): a command goes out now, whatever the
     * server will say of it.
     */
    fun onAction(action: String) = Unit

    /**
     * The token the phone advertises outside the round (the lobby, the results) so that two phones touching hear each
     * other (the field log's «Touch phones with a neighbour», docs/adr/0018-field-test-build.md §5); null: no radio
     * outside the round, as in every build but the field one. What the radio hears then comes to [onSighting] only.
     */
    fun touchRadioToken(snapshot: GameSnapshot): String? = null

    /**
     * Whether the touch's radio is still wanted, as it changes between snapshots (the card dismissed, the log stopped):
     * a touch radio [touchRadioToken] started runs only while this says so.
     */
    val touchRadioWanted: Flow<Boolean> get() = flowOf(true)

    /**
     * The channels the radio runs instead of the game's ([ProximityRadio.run] with a [ChannelMix]) for the player of
     * [playerNumber] (the order they joined): the field log's journal (docs/adr/0018-field-test-build.md §4 B); null:
     * the game's, as in every build but the field one.
     */
    fun radarChannels(playerNumber: Int): ChannelMix? = null

    /** The app came to the screen ([onScreen] true) or left it (the Compose lifecycle's resume and pause). */
    fun onScreenChanged(onScreen: Boolean) = Unit

    /** The pulse's band changed ([GameSessionManager.pulse]): what the phone itself beats now. */
    fun onPulse(band: RadarBand) = Unit

    /** Nobody writes anything down. */
    object None : GameTrace
}

/** The key actions of a player the field log writes down (`ui` events of kind `tap`): closed words, no data. */
object FieldActions {
    const val START = "start_game"
    const val SETTINGS_SAVED = "settings_saved"
    const val CATCH_CLAIM = "catch_claim"
    const val CATCH_SCAN = "catch_scan"
    const val CATCH_CONFIRM = "catch_confirm"
    const val CATCH_DISPUTE = "catch_dispute"
    const val VOTE = "vote"
    const val CHAT_SEND = "chat_send"
    const val CHECKPOINT_SCAN = "checkpoint_scan"
    const val PERK_USE = "perk_use"
    const val QUEST_DONE = "quest_done"
    const val LEAVE = "leave"
    const val PAUSE = "pause"
    const val RESUME = "resume"
    const val SOS = "sos"
    const val SOS_END = "sos_end"
}
