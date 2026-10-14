package app.hovanki.perf

import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.Carry
import app.hovanki.shared.protocol.DeviceReport
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.NearbySighting
import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.rules.RadarToken
import kotlin.random.Random

/**
 * What a player's phone puts into its sync besides the fix (`SyncRequest.device`, `SyncRequest.nearby`): its
 * [DeviceReport] every time, as the app sends it, and in a game with the radar the tokens it heard over Bluetooth since
 * the last sync. Whom it hears is up to [neighbours]: the radar secrets of the other players of its game (the lab's
 * phones have no air between them), a few of them at a time, as a phone in a street hears the players near it.
 */
class Phone(seed: Int, private val radar: Boolean, private val neighbours: () -> Collection<String>) {
    private val random = Random(seed)
    private val platform = if (random.nextInt(100) < IPHONE_PERCENT) Platform.IOS else Platform.ANDROID

    val report = DeviceReport(
        platform = platform,
        bluetooth = BluetoothState.ON,
        carry = if (random.nextBoolean()) Carry.IN_POCKET else Carry.IN_HAND,
        onScreen = false,
        // The model goes only with the radar (docs/adr/0012-nearby-radar.md, «Калибровка»).
        model = if (radar) (if (platform == Platform.IOS) "iPhone15,2" else "Pixel 8") else null,
    )

    /** The sightings of this sync: during the round of a game with the radar, up to [MAX_HEARD] of the neighbours. */
    fun heard(phase: GamePhase, nowMillis: Long): List<NearbySighting> {
        if (!radar || (phase != GamePhase.HIDING && phase != GamePhase.SEEKING)) return emptyList()
        val secrets = neighbours()
        if (secrets.isEmpty()) return emptyList()
        return secrets.shuffled(random).take(random.nextInt(MAX_HEARD + 1)).map { secret ->
            NearbySighting(RadarToken.at(secret, nowMillis), rssi = random.nextInt(-95, -55), atMillis = nowMillis)
        }
    }

    private companion object {
        const val IPHONE_PERCENT = 40
        const val MAX_HEARD = 4
    }
}
