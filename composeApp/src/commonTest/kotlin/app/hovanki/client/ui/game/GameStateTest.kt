package app.hovanki.client.ui.game

import app.hovanki.client.session.CatchCode
import app.hovanki.client.session.ConnectionStatus
import app.hovanki.client.session.ZoneCue
import app.hovanki.client.session.ZoneMoment
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.PerkKind
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.rules.GameSetup
import app.hovanki.shared.rules.stateAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameStateTest {
    private val schedule = GameSetup().settings(GeoPoint(50.4501, 30.5234)).zone
    private val claim = ClaimUi(
        id = CatchId("c1"),
        hiderId = PlayerId("p-hider"),
        seekerName = "Sam",
        hiderName = "Anna",
        status = CatchStatus.AWAITING_CODE,
        millisLeft = 30_000L,
        canVote = false,
        myVote = null,
    )

    private fun round(
        myClaim: ClaimUi? = null,
        myCode: CatchCode? = null,
        canScan: Boolean = false,
        canScanCheckpoint: Boolean = false,
        canSos: Boolean = false,
    ) = GameUiState(
        now = 0L,
        phase = GamePhase.SEEKING,
        myRole = Role.SEEKER,
        myStatus = PlayerStatus.ACTIVE,
        isHost = false,
        names = emptyMap(),
        phaseMillisLeft = null,
        hidingElapsedMillis = null,
        zone = schedule.stateAt(0L),
        zoneMoment = ZoneMoment(ZoneCue.CALM, null, null),
        isZoneRunning = true,
        myLocation = null,
        metersToZoneBorder = null,
        bearingToZone = null,
        zoneTimeline = ZoneTimeline(schedule, 0L, null),
        isStreetZoneOff = false,
        glow = null,
        markers = emptyList(),
        hidersLeft = 1,
        hidersTotal = 1,
        huntableHiders = emptyList(),
        canScan = canScan,
        myClaim = myClaim,
        myConfirmedCatches = emptySet(),
        claimAgainstMe = null,
        catchCode = null,
        catchQr = null,
        myCode = myCode,
        myQr = null,
        codeDigits = 6,
        codePeriodMillis = 30_000L,
        claimTimeoutMillis = 60_000L,
        voteTimeoutMillis = 60_000L,
        votes = emptyList(),
        outOfZoneMillisLeft = null,
        insideBuildingMillisLeft = null,
        buildings = null,
        isBuildingRuleOff = false,
        connectionStatus = ConnectionStatus.ONLINE,
        isSharingLocation = true,
        error = null,
        joinCode = "ABCD",
        hasAccount = false,
        canScanCheckpoint = canScanCheckpoint,
        canSos = canSos,
    )

    @Test
    fun theCameraShowsOnlyWhileItsClaimWaitsForTheCode() {
        val local = GameLocal(scanningClaim = claim.id)

        assertEquals(claim, local.applyTo(round(myClaim = claim)).scannedClaim)
        assertNull(local.applyTo(round(myClaim = claim.copy(status = CatchStatus.DISPUTED))).scannedClaim)
        assertNull(local.applyTo(round(myClaim = claim.copy(id = CatchId("c2")))).scannedClaim)
        assertNull(local.applyTo(round()).scannedClaim)
    }

    @Test
    fun whatTheRoundTookAwayIsForgotten() {
        val open = GameLocal(dialog = GameDialog.SOS, scanningCheckpoint = true, showingMyCode = true)
        val code = CatchCode("123456", 10_000L)

        assertEquals(open, open.reconciled(round(myCode = code, canScanCheckpoint = true, canSos = true)))
        val taken = open.reconciled(round())
        assertNull(taken.dialog)
        assertFalse(taken.scanningCheckpoint)
        assertFalse(taken.showingMyCode)
        // The menu stays open whatever the round does.
        assertEquals(GameDialog.MENU, GameLocal(dialog = GameDialog.MENU).reconciled(round()).dialog)
    }

    @Test
    fun theFreeCameraWaitsWhileTheSeekerCannotScan() {
        val local = GameLocal(scanningFree = true)

        assertFalse(local.applyTo(round()).isFreeScanOpen)
        // Kept as it was: the camera comes back once the seeker may scan again.
        assertEquals(local, local.reconciled(round()))
        assertTrue(local.applyTo(round(canScan = true)).isFreeScanOpen)
    }

    @Test
    fun theTargetIsAskedOnlyInThePerksPanel() {
        val local = GameLocal(perkTargeting = PerkKind.entries.first(), isBusy = true)

        assertNull(local.applyTo(round()).perkTargeting)
        val perks = local.copy(panel = GamePanel.PERKS).applyTo(round())
        assertEquals(PerkKind.entries.first(), perks.perkTargeting)
        assertTrue(perks.isBusy)
    }
}
