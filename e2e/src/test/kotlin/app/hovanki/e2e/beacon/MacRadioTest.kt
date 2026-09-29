package app.hovanki.e2e.beacon

import app.hovanki.shared.protocol.BluetoothState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** The bot's side of the Mac's Bluetooth helper: the lines it reads and writes (a shell script plays the helper). */
class MacRadioTest {
    @Test
    fun advertisesTheTokenAndReportsWhatTheHelperHeard() = runBlocking {
        val dir = Files.createTempDirectory("mac-beacon").toFile()
        val commands = File(dir, "commands.txt")
        // Says it is on, hears two phones once it is told what to advertise, and writes down every command.
        val helper = File(dir, "beacon").apply {
            writeText(
                """
                #!/usr/bin/env bash
                echo "state on"
                while read -r line; do
                  echo "${'$'}line" >> "${commands.path}"
                  if [[ "${'$'}line" == advertise* ]]; then
                    echo "heard 0123abcd -48"
                    echo "log advertising"
                    echo "heard 89abcdef -71"
                  fi
                done
                """.trimIndent() + "\n",
            )
            setExecutable(true)
        }
        val logged = mutableListOf<String>()
        MacRadio(helper) { synchronized(logged) { logged += it } }.use { radio ->
            withTimeout(10_000) { radio.state.first { it == BluetoothState.ON } }
            val heard = withTimeout(10_000) {
                radio.run(MutableStateFlow("fedcba98")).take(2).toList()
            }
            assertEquals(listOf("0123abcd" to -48, "89abcdef" to -71), heard.map { it.token to it.rssi })
            withTimeout(10_000) {
                while (commands.takeIf { it.exists() }?.readLines() != listOf("advertise fedcba98", "stop")) {
                    Thread.sleep(50)
                }
            }
        }
        assertEquals(true, synchronized(logged) { "advertising" in logged })
    }
}
