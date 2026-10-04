package app.hovanki.e2e.scenarios

import app.hovanki.e2e.beacon.BeaconCli
import app.hovanki.e2e.bot.FakeRadio
import app.hovanki.e2e.cli.CliArgs
import app.hovanki.e2e.executableScript
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.RadarBand
import java.io.File
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The MacBook as a second phone (`e2e/mac-beacon/run.sh`, [BeaconCli]): it joins by the code, shows Bluetooth on in
 * the lobby, advertises its token in the round, and what its Bluetooth hears reaches the seeker's radar. A shell
 * script plays the Mac's Bluetooth helper: it hears the seeker once the test tells it the seeker's token.
 */
class BeaconTest {
    @Test
    fun theLaptopPlaysAsASecondPhone() = scenario("The MacBook as a second phone") {
        enableAllFeatures()
        val sam = player("Sam", at = PARK)
        sam.createsGame(GameSetups.radar())
        val code = checkNotNull(sam.snapshot).joinCode

        val dir = Files.createTempDirectory("mac-beacon").toFile()
        val advertised = File(dir, "advertised")
        val hear = File(dir, "hear")
        val helper = executableScript(
            File(dir, "beacon"),
            """
            #!/usr/bin/env bash
            echo "state on"
            while true; do
              read -r -t 0.5 line; rc=${'$'}?
              if [[ ${'$'}rc -eq 0 ]]; then echo "${'$'}line" >> "${advertised.path}"
              elif [[ ${'$'}rc -le 128 ]]; then exit 0; fi
              [[ -f "${hear.path}" ]] && echo "heard ${'$'}(cat "${hear.path}") -45"
            done
            """.trimIndent() + "\n",
        )
        thread(isDaemon = true, name = "beacon") {
            BeaconCli.run(
                CliArgs(listOf("--join", code, "--server", serverUrl, "--helper", helper.path, "--name", "MacBook")),
            )
        }
        val laptop = eventually("the laptop is in the lobby with Bluetooth on") {
            sam.snapshot?.players?.firstOrNull { it.name == "MacBook" }
                ?.takeIf { it.capabilities?.bluetooth == BluetoothState.ON }
        }

        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        val advertises = Regex("advertise [0-9a-f]{8}")
        eventually("the laptop advertises its token") {
            advertised.takeIf { it.exists() }?.readLines()?.lastOrNull()?.takeIf(advertises::matches)
        }
        val samToken = eventually("Sam's phone advertises") { (sam.radio as FakeRadio).token }
        hear.writeText(samToken)
        val contact = eventually("Sam's radar hears the laptop burning") {
            sam.snapshot?.me?.radar?.contacts
                ?.firstOrNull { it.playerId == laptop.id && it.band == RadarBand.BURNING }
        }
        check(contact.atMillis != null, "the radar knows when it last heard the laptop")
    }
}
