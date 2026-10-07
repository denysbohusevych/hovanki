package app.hovanki.client.ui.game

import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.PerkKind
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.QuestId

/** A panel over the round: the quests or the perks (docs/adr/0013-quests-sparks-and-sensors.md). */
enum class GamePanel { QUESTS, PERKS }

/** A dialog over the round: «More» (leave, pause, SOS), the SOS's own, or the map's look. */
enum class GameDialog { MENU, SOS, MAP }

/** A camera over the round: the claimed hider's code, «Found!» with no claim yet, or a checkpoint's code. */
enum class GameScanner { CLAIM, FREE, CHECKPOINT }

/** The decoy being placed on the map: where the player tapped; null: not yet. */
data class DecoyUi(val pick: GeoPoint? = null)

/** What the player does in the round: everything goes through [GameViewModel.onEvent]. */
sealed interface GameEvent {
    /** The host puts the round on pause ([paused]) or lets it go on. */
    data class SetPaused(val paused: Boolean) : GameEvent

    data object CallSos : GameEvent

    /** Ends an SOS: the own one (null), or, for the host, [playerId]'s. */
    data class EndSos(val playerId: PlayerId?) : GameEvent

    data class ClaimCatch(val hiderId: PlayerId) : GameEvent

    data class ConfirmCatch(val catchId: CatchId, val code: String) : GameEvent

    data class Dispute(val catchId: CatchId) : GameEvent

    data class Vote(val catchId: CatchId, val confirm: Boolean) : GameEvent

    /** «Done!» on the host's quest: the host answers. */
    data class QuestDone(val questId: QuestId) : GameEvent

    /** The host confirms or refuses what [playerId] said about their quest. */
    data class ReviewQuest(val questId: QuestId, val playerId: PlayerId, val approved: Boolean) : GameEvent

    data object Leave : GameEvent

    data object DismissError : GameEvent

    data object LocationPermissionGranted : GameEvent

    data class OpenDialog(val dialog: GameDialog) : GameEvent

    data object CloseDialog : GameEvent

    data class OpenPanel(val panel: GamePanel) : GameEvent

    data object ClosePanel : GameEvent

    /**
     * A perk from the perks panel: one aimed at a hider asks which first ([targetId]), the decoy goes to the map, the
     * others are used at once.
     */
    data class UsePerk(val perk: PerkKind, val targetId: PlayerId? = null) : GameEvent

    data class PickDecoy(val point: GeoPoint) : GameEvent

    data object PutDecoy : GameEvent

    data object CancelDecoy : GameEvent

    /** «My code»: the hider shows the code without a claim. */
    data class ShowMyCode(val shown: Boolean) : GameEvent

    data class OpenScanner(val scanner: GameScanner) : GameEvent

    data class CloseScanner(val scanner: GameScanner) : GameEvent

    /** Text the [scanner]'s camera read; the camera closes once it was a code it takes. */
    data class Scanned(val scanner: GameScanner, val text: String) : GameEvent
}

/**
 * This phone's own part of the round: what is open over the map. Shown only while it still makes sense ([applyTo]); what
 * the round took away is forgotten ([reconciled]) so that it does not come back by itself.
 */
internal data class GameLocal(
    /** One request at a time: double taps must not send two claims. */
    val isBusy: Boolean = false,
    val dialog: GameDialog? = null,
    val panel: GamePanel? = null,
    /** The perk aimed at a hider, waiting for which one. */
    val perkTargeting: PerkKind? = null,
    val decoy: DecoyUi? = null,
    /** The claim whose hider's code the seeker's camera looks for. */
    val scanningClaim: CatchId? = null,
    val scanningFree: Boolean = false,
    val scanningCheckpoint: Boolean = false,
    val showingMyCode: Boolean = false,
) {
    /** What the round no longer allows is closed: the SOS without the SOS, the checkpoints' camera, the own code. */
    fun reconciled(state: GameUiState): GameLocal = copy(
        dialog = dialog.takeUnless { it == GameDialog.SOS && !state.canSos },
        scanningCheckpoint = scanningCheckpoint && state.canScanCheckpoint,
        showingMyCode = showingMyCode && state.myCode != null,
    )

    fun applyTo(state: GameUiState): GameUiState = state.copy(
        isBusy = isBusy,
        dialog = dialog,
        panel = panel,
        perkTargeting = perkTargeting.takeIf { panel == GamePanel.PERKS },
        decoy = decoy,
        scannedClaim = state.myClaim?.takeIf { it.id == scanningClaim && it.status == CatchStatus.AWAITING_CODE },
        isFreeScanOpen = scanningFree && state.canScan,
        isCheckpointScanOpen = scanningCheckpoint && state.canScanCheckpoint,
        isMyCodeOpen = showingMyCode && state.myCode != null,
    )
}
