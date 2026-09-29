package app.hovanki.e2e.scenarios

import app.hovanki.client.tracking.AlertKind
import app.hovanki.e2e.OWN_SERVER
import app.hovanki.e2e.admin.StaffConsole
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.e2e.scenarioOnOwnServer
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.FeatureMode
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.ServerFeature
import app.hovanki.shared.protocol.UserRole
import app.hovanki.shared.protocol.VisibilityReason
import org.junit.jupiter.api.parallel.ResourceLock
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The server features (docs/adr/0012-nearby-radar.md, section 4): everything is off until an admin switches it on
 * with a reason; a game asking for a feature that is off is refused. The required radar: the game doesn't start
 * while a phone has Bluetooth off, and a hider who turns it off during the search is warned, then seen.
 */
class FeatureFlagTest {
    @Test
    @ResourceLock(OWN_SERVER)
    fun theOperatorSwitchesTheFeaturesOn() = scenarioOnOwnServer("Feature flags", properties = emptyMap()) {
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 40.0))
        expectRejected(
            sam.createGame(GameSetups.radar()),
            ErrorReason.FEATURE_DISABLED,
            "a game with the radar while the server has it off",
        )

        val account = sam.signsUp()
        // Staff log in with a confirmed email only (docs/adr/0008-admin.md).
        sam.confirmsEmail()
        observer.setRole(checkNotNull(sam.userId), UserRole.ADMIN)
        StaffConsole(serverUrl, observer).use { console ->
            check(console.logIn(account).role == UserRole.ADMIN, "Sam logs in to the admin as an admin")
            check(console.features().none { it.enabled }, "everything is off to begin with")
            val features = console.setFeature(ServerFeature.RADAR, enabled = true, reason = "the radar spike")
            check(features.single { it.feature == ServerFeature.RADAR }.enabled, "the radar is on")
            console.setFeature(ServerFeature.HIDER_SENSE, enabled = true, reason = "with the sense")
        }

        sam.createsGame(GameSetups.radar(mode = FeatureMode.REQUIRED))
        eventually("the app learns what the server has on") {
            sam.snapshot?.enabledFeatures?.takeIf { ServerFeature.RADAR.name in it }
        }
        join(anna)
        anna.turnsBluetoothOff()
        eventually("the lobby shows Anna without the radar") {
            sam.snapshot?.players?.firstOrNull { it.id == anna.id }?.capabilities
                ?.takeIf { it.bluetooth == BluetoothState.OFF }
        }
        expectRejected(
            sam.startGame(listOf(sam)),
            ErrorReason.FEATURE_MISSING,
            "the radar is required and Anna's phone has it off",
        )
        anna.turnsBluetoothOn()
        eventually("Anna's phone reports Bluetooth on") {
            sam.snapshot?.players?.firstOrNull { it.id == anna.id }?.capabilities
                ?.takeIf { it.bluetooth == BluetoothState.ON }
        }
        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)

        // A hider who turns Bluetooth off during the search is warned, then seen.
        anna.turnsBluetoothOff()
        eventually("Anna is warned to turn Bluetooth on") { anna.snapshot?.me?.bluetoothDeadlineMillis }
        val seen = awaitReveal(anna, VisibilityReason.RADAR_OFF, to = sam, within = 25.seconds)
        check(seen.reason == VisibilityReason.OUT_OF_ZONE, "for the first app versions the reveal reads as out of zone")
        check(
            AlertKind.BLUETOOTH_OFF in anna.backgroundTracker.alerts.map { it.kind },
            "Anna's phone buzzed about Bluetooth",
        )
        anna.turnsBluetoothOn()
        awaitThat("Anna is hidden again") { sam.snapshot?.players?.firstOrNull { it.id == anna.id }?.location == null }
    }
}
