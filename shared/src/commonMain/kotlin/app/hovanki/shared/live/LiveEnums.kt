package app.hovanki.shared.live

import app.hovanki.shared.protocol.Activity
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.BuildingsState
import app.hovanki.shared.protocol.Carry
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.ChatChannel
import app.hovanki.shared.protocol.DistanceBand
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.HintKind
import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.StreetZoneState
import app.hovanki.shared.protocol.VisibilityReason
import app.hovanki.shared.live.v2.Activity as WireActivity
import app.hovanki.shared.live.v2.BluetoothState as WireBluetoothState
import app.hovanki.shared.live.v2.BuildingsState as WireBuildingsState
import app.hovanki.shared.live.v2.Carry as WireCarry
import app.hovanki.shared.live.v2.CatchStatus as WireCatchStatus
import app.hovanki.shared.live.v2.ChatChannel as WireChatChannel
import app.hovanki.shared.live.v2.DistanceBand as WireDistanceBand
import app.hovanki.shared.live.v2.ErrorCode as WireErrorCode
import app.hovanki.shared.live.v2.GamePhase as WireGamePhase
import app.hovanki.shared.live.v2.HintKind as WireHintKind
import app.hovanki.shared.live.v2.Platform as WirePlatform
import app.hovanki.shared.live.v2.PlayerStatus as WirePlayerStatus
import app.hovanki.shared.live.v2.RadarBand as WireRadarBand
import app.hovanki.shared.live.v2.Role as WireRole
import app.hovanki.shared.live.v2.StreetZoneState as WireStreetZoneState
import app.hovanki.shared.live.v2.VisibilityReason as WireVisibilityReason

/**
 * The enums of v1 and their twins in live_v2.proto. Every v1 value has its own number there; value 0 is the v1 default,
 * or null where the v1 field is nullable (docs/live-v2/spec.md §10). A v1 value without a twin does not compile here:
 * give it the next number in the schema first.
 */
internal object LiveEnums {
    fun wire(value: GamePhase): WireGamePhase = when (value) {
        GamePhase.LOBBY -> WireGamePhase.GAME_PHASE_LOBBY
        GamePhase.HIDING -> WireGamePhase.GAME_PHASE_HIDING
        GamePhase.SEEKING -> WireGamePhase.GAME_PHASE_SEEKING
        GamePhase.FINISHED -> WireGamePhase.GAME_PHASE_FINISHED
    }

    fun v1(value: WireGamePhase): GamePhase = when (value) {
        WireGamePhase.GAME_PHASE_LOBBY -> GamePhase.LOBBY
        WireGamePhase.GAME_PHASE_HIDING -> GamePhase.HIDING
        WireGamePhase.GAME_PHASE_SEEKING -> GamePhase.SEEKING
        WireGamePhase.GAME_PHASE_FINISHED -> GamePhase.FINISHED
    }

    fun wire(value: Role): WireRole = when (value) {
        Role.HIDER -> WireRole.ROLE_HIDER
        Role.SEEKER -> WireRole.ROLE_SEEKER
    }

    fun v1(value: WireRole): Role = when (value) {
        WireRole.ROLE_HIDER -> Role.HIDER
        WireRole.ROLE_SEEKER -> Role.SEEKER
    }

    fun wire(value: PlayerStatus): WirePlayerStatus = when (value) {
        PlayerStatus.ACTIVE -> WirePlayerStatus.PLAYER_STATUS_ACTIVE
        PlayerStatus.CAUGHT -> WirePlayerStatus.PLAYER_STATUS_CAUGHT
        PlayerStatus.ELIMINATED -> WirePlayerStatus.PLAYER_STATUS_ELIMINATED
    }

    fun v1(value: WirePlayerStatus): PlayerStatus = when (value) {
        WirePlayerStatus.PLAYER_STATUS_ACTIVE -> PlayerStatus.ACTIVE
        WirePlayerStatus.PLAYER_STATUS_CAUGHT -> PlayerStatus.CAUGHT
        WirePlayerStatus.PLAYER_STATUS_ELIMINATED -> PlayerStatus.ELIMINATED
    }

    fun wire(value: CatchStatus): WireCatchStatus = when (value) {
        CatchStatus.AWAITING_CODE -> WireCatchStatus.CATCH_STATUS_AWAITING_CODE
        CatchStatus.DISPUTED -> WireCatchStatus.CATCH_STATUS_DISPUTED
        CatchStatus.CONFIRMED -> WireCatchStatus.CATCH_STATUS_CONFIRMED
        CatchStatus.REJECTED -> WireCatchStatus.CATCH_STATUS_REJECTED
    }

    fun v1(value: WireCatchStatus): CatchStatus = when (value) {
        WireCatchStatus.CATCH_STATUS_AWAITING_CODE -> CatchStatus.AWAITING_CODE
        WireCatchStatus.CATCH_STATUS_DISPUTED -> CatchStatus.DISPUTED
        WireCatchStatus.CATCH_STATUS_CONFIRMED -> CatchStatus.CONFIRMED
        WireCatchStatus.CATCH_STATUS_REJECTED -> CatchStatus.REJECTED
    }

    fun wire(value: VisibilityReason): WireVisibilityReason = when (value) {
        VisibilityReason.TEAMMATE -> WireVisibilityReason.VISIBILITY_REASON_TEAMMATE
        VisibilityReason.STALE_SIGNAL -> WireVisibilityReason.VISIBILITY_REASON_STALE_SIGNAL
        VisibilityReason.OUT_OF_ZONE -> WireVisibilityReason.VISIBILITY_REASON_OUT_OF_ZONE
        VisibilityReason.MOCK_LOCATION -> WireVisibilityReason.VISIBILITY_REASON_MOCK_LOCATION
        VisibilityReason.INSIDE_BUILDING -> WireVisibilityReason.VISIBILITY_REASON_INSIDE_BUILDING
        VisibilityReason.GLOW -> WireVisibilityReason.VISIBILITY_REASON_GLOW
        VisibilityReason.RADAR_OFF -> WireVisibilityReason.VISIBILITY_REASON_RADAR_OFF
        VisibilityReason.SPOTLIGHT -> WireVisibilityReason.VISIBILITY_REASON_SPOTLIGHT
        VisibilityReason.FRESH_TRAIL -> WireVisibilityReason.VISIBILITY_REASON_FRESH_TRAIL
    }

    fun v1(value: WireVisibilityReason): VisibilityReason = when (value) {
        WireVisibilityReason.VISIBILITY_REASON_TEAMMATE -> VisibilityReason.TEAMMATE
        WireVisibilityReason.VISIBILITY_REASON_STALE_SIGNAL -> VisibilityReason.STALE_SIGNAL
        WireVisibilityReason.VISIBILITY_REASON_OUT_OF_ZONE -> VisibilityReason.OUT_OF_ZONE
        WireVisibilityReason.VISIBILITY_REASON_MOCK_LOCATION -> VisibilityReason.MOCK_LOCATION
        WireVisibilityReason.VISIBILITY_REASON_INSIDE_BUILDING -> VisibilityReason.INSIDE_BUILDING
        WireVisibilityReason.VISIBILITY_REASON_GLOW -> VisibilityReason.GLOW
        WireVisibilityReason.VISIBILITY_REASON_RADAR_OFF -> VisibilityReason.RADAR_OFF
        WireVisibilityReason.VISIBILITY_REASON_SPOTLIGHT -> VisibilityReason.SPOTLIGHT
        WireVisibilityReason.VISIBILITY_REASON_FRESH_TRAIL -> VisibilityReason.FRESH_TRAIL
    }

    fun wire(value: BuildingsState?): WireBuildingsState = when (value) {
        null -> WireBuildingsState.BUILDINGS_STATE_NONE
        BuildingsState.LOADING -> WireBuildingsState.BUILDINGS_STATE_LOADING
        BuildingsState.READY -> WireBuildingsState.BUILDINGS_STATE_READY
        BuildingsState.UNAVAILABLE -> WireBuildingsState.BUILDINGS_STATE_UNAVAILABLE
    }

    fun v1(value: WireBuildingsState): BuildingsState? = when (value) {
        WireBuildingsState.BUILDINGS_STATE_NONE -> null
        WireBuildingsState.BUILDINGS_STATE_LOADING -> BuildingsState.LOADING
        WireBuildingsState.BUILDINGS_STATE_READY -> BuildingsState.READY
        WireBuildingsState.BUILDINGS_STATE_UNAVAILABLE -> BuildingsState.UNAVAILABLE
    }

    fun wire(value: StreetZoneState?): WireStreetZoneState = when (value) {
        null -> WireStreetZoneState.STREET_ZONE_STATE_NONE
        StreetZoneState.LOADING -> WireStreetZoneState.STREET_ZONE_STATE_LOADING
        StreetZoneState.READY -> WireStreetZoneState.STREET_ZONE_STATE_READY
        StreetZoneState.UNAVAILABLE -> WireStreetZoneState.STREET_ZONE_STATE_UNAVAILABLE
    }

    fun v1(value: WireStreetZoneState): StreetZoneState? = when (value) {
        WireStreetZoneState.STREET_ZONE_STATE_NONE -> null
        WireStreetZoneState.STREET_ZONE_STATE_LOADING -> StreetZoneState.LOADING
        WireStreetZoneState.STREET_ZONE_STATE_READY -> StreetZoneState.READY
        WireStreetZoneState.STREET_ZONE_STATE_UNAVAILABLE -> StreetZoneState.UNAVAILABLE
    }

    fun wire(value: ChatChannel): WireChatChannel = when (value) {
        ChatChannel.ALL -> WireChatChannel.CHAT_CHANNEL_ALL
        ChatChannel.SEEKERS -> WireChatChannel.CHAT_CHANNEL_SEEKERS
        ChatChannel.HIDERS -> WireChatChannel.CHAT_CHANNEL_HIDERS
    }

    fun v1(value: WireChatChannel): ChatChannel = when (value) {
        WireChatChannel.CHAT_CHANNEL_ALL -> ChatChannel.ALL
        WireChatChannel.CHAT_CHANNEL_SEEKERS -> ChatChannel.SEEKERS
        WireChatChannel.CHAT_CHANNEL_HIDERS -> ChatChannel.HIDERS
    }

    fun wire(value: RadarBand): WireRadarBand = when (value) {
        RadarBand.NONE -> WireRadarBand.RADAR_BAND_NONE
        RadarBand.WARM -> WireRadarBand.RADAR_BAND_WARM
        RadarBand.HOT -> WireRadarBand.RADAR_BAND_HOT
        RadarBand.BURNING -> WireRadarBand.RADAR_BAND_BURNING
    }

    fun v1(value: WireRadarBand): RadarBand = when (value) {
        WireRadarBand.RADAR_BAND_NONE -> RadarBand.NONE
        WireRadarBand.RADAR_BAND_WARM -> RadarBand.WARM
        WireRadarBand.RADAR_BAND_HOT -> RadarBand.HOT
        WireRadarBand.RADAR_BAND_BURNING -> RadarBand.BURNING
    }

    fun wire(value: HintKind): WireHintKind = when (value) {
        HintKind.SENSE -> WireHintKind.HINT_KIND_SENSE
        HintKind.DIRECTION -> WireHintKind.HINT_KIND_DIRECTION
        HintKind.RADIUS -> WireHintKind.HINT_KIND_RADIUS
    }

    fun v1(value: WireHintKind): HintKind = when (value) {
        WireHintKind.HINT_KIND_SENSE -> HintKind.SENSE
        WireHintKind.HINT_KIND_DIRECTION -> HintKind.DIRECTION
        WireHintKind.HINT_KIND_RADIUS -> HintKind.RADIUS
    }

    fun wire(value: DistanceBand): WireDistanceBand = when (value) {
        DistanceBand.NEAR -> WireDistanceBand.DISTANCE_BAND_NEAR
        DistanceBand.CLOSE -> WireDistanceBand.DISTANCE_BAND_CLOSE
        DistanceBand.FAR -> WireDistanceBand.DISTANCE_BAND_FAR
    }

    fun v1(value: WireDistanceBand): DistanceBand = when (value) {
        WireDistanceBand.DISTANCE_BAND_NEAR -> DistanceBand.NEAR
        WireDistanceBand.DISTANCE_BAND_CLOSE -> DistanceBand.CLOSE
        WireDistanceBand.DISTANCE_BAND_FAR -> DistanceBand.FAR
    }

    fun wire(value: Platform): WirePlatform = when (value) {
        Platform.OTHER -> WirePlatform.PLATFORM_OTHER
        Platform.ANDROID -> WirePlatform.PLATFORM_ANDROID
        Platform.IOS -> WirePlatform.PLATFORM_IOS
    }

    fun v1(value: WirePlatform): Platform = when (value) {
        WirePlatform.PLATFORM_OTHER -> Platform.OTHER
        WirePlatform.PLATFORM_ANDROID -> Platform.ANDROID
        WirePlatform.PLATFORM_IOS -> Platform.IOS
    }

    fun wire(value: BluetoothState): WireBluetoothState = when (value) {
        BluetoothState.UNSUPPORTED -> WireBluetoothState.BLUETOOTH_STATE_UNSUPPORTED
        BluetoothState.ON -> WireBluetoothState.BLUETOOTH_STATE_ON
        BluetoothState.OFF -> WireBluetoothState.BLUETOOTH_STATE_OFF
        BluetoothState.DENIED -> WireBluetoothState.BLUETOOTH_STATE_DENIED
        BluetoothState.OFF_BY_PLAYER -> WireBluetoothState.BLUETOOTH_STATE_OFF_BY_PLAYER
    }

    fun v1(value: WireBluetoothState): BluetoothState = when (value) {
        WireBluetoothState.BLUETOOTH_STATE_UNSUPPORTED -> BluetoothState.UNSUPPORTED
        WireBluetoothState.BLUETOOTH_STATE_ON -> BluetoothState.ON
        WireBluetoothState.BLUETOOTH_STATE_OFF -> BluetoothState.OFF
        WireBluetoothState.BLUETOOTH_STATE_DENIED -> BluetoothState.DENIED
        WireBluetoothState.BLUETOOTH_STATE_OFF_BY_PLAYER -> BluetoothState.OFF_BY_PLAYER
    }

    fun wire(value: Activity): WireActivity = when (value) {
        Activity.UNKNOWN -> WireActivity.ACTIVITY_UNKNOWN
        Activity.STILL -> WireActivity.ACTIVITY_STILL
        Activity.WALKING -> WireActivity.ACTIVITY_WALKING
        Activity.RUNNING -> WireActivity.ACTIVITY_RUNNING
        Activity.IN_VEHICLE -> WireActivity.ACTIVITY_IN_VEHICLE
    }

    fun v1(value: WireActivity): Activity = when (value) {
        WireActivity.ACTIVITY_UNKNOWN -> Activity.UNKNOWN
        WireActivity.ACTIVITY_STILL -> Activity.STILL
        WireActivity.ACTIVITY_WALKING -> Activity.WALKING
        WireActivity.ACTIVITY_RUNNING -> Activity.RUNNING
        WireActivity.ACTIVITY_IN_VEHICLE -> Activity.IN_VEHICLE
    }

    fun wire(value: Carry): WireCarry = when (value) {
        Carry.UNKNOWN -> WireCarry.CARRY_UNKNOWN
        Carry.IN_HAND -> WireCarry.CARRY_IN_HAND
        Carry.IN_POCKET -> WireCarry.CARRY_IN_POCKET
    }

    fun v1(value: WireCarry): Carry = when (value) {
        WireCarry.CARRY_UNKNOWN -> Carry.UNKNOWN
        WireCarry.CARRY_IN_HAND -> Carry.IN_HAND
        WireCarry.CARRY_IN_POCKET -> Carry.IN_POCKET
    }

    fun wire(value: ErrorCode): WireErrorCode = when (value) {
        ErrorCode.INTERNAL -> WireErrorCode.ERROR_CODE_INTERNAL
        ErrorCode.BAD_REQUEST -> WireErrorCode.ERROR_CODE_BAD_REQUEST
        ErrorCode.UNAUTHORIZED -> WireErrorCode.ERROR_CODE_UNAUTHORIZED
        ErrorCode.FORBIDDEN -> WireErrorCode.ERROR_CODE_FORBIDDEN
        ErrorCode.NOT_FOUND -> WireErrorCode.ERROR_CODE_NOT_FOUND
        ErrorCode.WRONG_STATE -> WireErrorCode.ERROR_CODE_WRONG_STATE
        ErrorCode.NO_LOCATION -> WireErrorCode.ERROR_CODE_NO_LOCATION
        ErrorCode.TOO_FAR -> WireErrorCode.ERROR_CODE_TOO_FAR
        ErrorCode.INVALID_CODE -> WireErrorCode.ERROR_CODE_INVALID_CODE
    }

    fun v1(value: WireErrorCode): ErrorCode = when (value) {
        WireErrorCode.ERROR_CODE_INTERNAL -> ErrorCode.INTERNAL
        WireErrorCode.ERROR_CODE_BAD_REQUEST -> ErrorCode.BAD_REQUEST
        WireErrorCode.ERROR_CODE_UNAUTHORIZED -> ErrorCode.UNAUTHORIZED
        WireErrorCode.ERROR_CODE_FORBIDDEN -> ErrorCode.FORBIDDEN
        WireErrorCode.ERROR_CODE_NOT_FOUND -> ErrorCode.NOT_FOUND
        WireErrorCode.ERROR_CODE_WRONG_STATE -> ErrorCode.WRONG_STATE
        WireErrorCode.ERROR_CODE_NO_LOCATION -> ErrorCode.NO_LOCATION
        WireErrorCode.ERROR_CODE_TOO_FAR -> ErrorCode.TOO_FAR
        WireErrorCode.ERROR_CODE_INVALID_CODE -> ErrorCode.INVALID_CODE
    }
}
