package app.hovanki.client.automation

import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.PlayerId

/**
 * Stable ids of the elements UI automation needs: `Modifier.testTag` in :composeApp, the device orchestrator in :e2e
 * and the Maestro flows in `e2e/maestro/` (docs/e2e.md). They become the Android resource-id in debug builds
 * (`testTagsAsResourceId`) and the iOS `accessibilityIdentifier`. Renaming one breaks the flows: change them together.
 */
object TestTags {
    const val LOADING = "loading"
    const val RESUMING = "resuming"
    const val RESUMING_LEAVE = "resuming_leave"

    const val HOME_SCREEN = "home_screen"
    const val HOME_NAME = "home_name"
    const val HOME_CREATE = "home_create"
    const val HOME_JOIN_CODE = "home_join_code"
    const val HOME_JOIN = "home_join"
    const val HOME_SERVER = "home_server"
    const val HOME_BUSY = "home_busy"
    const val HOME_PROBLEM = "home_problem"

    const val LOBBY_SCREEN = "lobby_screen"
    const val LOBBY_JOIN_CODE = "lobby_join_code"
    const val LOBBY_START = "lobby_start"
    const val LOBBY_WAITING = "lobby_waiting"

    const val GAME_SCREEN = "game_screen"
    const val GAME_TIMER = "game_timer"
    const val GAME_OUT_OF_ZONE = "game_out_of_zone"
    const val GAME_CAUGHT = "game_caught"
    const val GAME_ELIMINATED = "game_eliminated"
    const val GAME_MAP = "game_map"
    const val MAP_ATTRIBUTION = "map_attribution"
    const val GAME_IN_BUILDING = "game_in_building"
    const val BUILDING_RULE_OFF = "building_rule_off"
    const val CATCH_CODE = "catch_code"
    const val CATCH_DISPUTE = "catch_dispute"
    const val CODE_INPUT = "code_input"
    const val CODE_CONFIRM = "code_confirm"

    const val RESULTS_SCREEN = "results_screen"
    const val RESULTS_BACK = "results_back"

    const val BANNER_RECONNECTING = "banner_reconnecting"
    const val BANNER_ERROR = "banner_error"
    const val BANNER_NO_LOCATION = "banner_no_location"

    fun lobbyPlayer(id: PlayerId) = "lobby_player_${id.value}"

    fun seekerSwitch(id: PlayerId) = "seeker_switch_${id.value}"

    /** Title of the game screen; changes with the phase, so a flow can wait for the next phase. */
    fun phase(phase: GamePhase) = "phase_${phase.name.lowercase()}"

    fun claimButton(hiderId: PlayerId) = "claim_${hiderId.value}"

    fun voteConfirm(catchId: CatchId) = "vote_confirm_${catchId.value}"

    fun voteReject(catchId: CatchId) = "vote_reject_${catchId.value}"
}
