package app.hovanki.e2e.beacon

import app.hovanki.e2e.executableScript
import app.hovanki.shared.protocol.BluetoothState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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
        val helper = executableScript(
            File(dir, "beacon"),
            """
            #!/usr/bin/env bash
            echo "state on"
            while read -r line; do
              echo "${'$'}line" >> "${commands.path}"
              if [[ "${'$'}line" == advertise* ]]; then
                echo "heard 0123abcd -48 name"
                echo "log advertising"
                echo "heard 89abcdef -71 ibeacon"
              fi
            done
            """.trimIndent() + "\n",
        )
        val logged = mutableListOf<String>()
        MacRadio(helper, onLine = { synchronized(logged) { logged += it } }).use { radio ->
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

    /** `--only listen`: nothing goes out; `--only speak`: what is heard is shown but stays out of the game. */
    @Test
    fun oneDirectionAtATime() = runBlocking {
        val dir = Files.createTempDirectory("mac-beacon").toFile()
        val commands = File(dir, "commands.txt")
        val helper = executableScript(
            File(dir, "beacon"),
            """
            #!/usr/bin/env bash
            echo "state on"
            while read -r line; do
              echo "${'$'}line" >> "${commands.path}"
              echo "heard 0123abcd -48 name"
            done
            """.trimIndent() + "\n",
        )
        MacRadio(helper, onLine = {}, advertise = false).use { radio ->
            withTimeout(10_000) { radio.state.first { it == BluetoothState.ON } }
            val heard = withTimeout(10_000) { radio.run(MutableStateFlow("fedcba98")).first() }
            assertEquals("0123abcd", heard.token)
            assertEquals("stop", commands.readLines().first(), "listening only: never advertises")
        }
        commands.delete()
        val shown = mutableListOf<String>()
        MacRadio(helper, onLine = {
        }, onHeard = { token, _, how -> synchronized(shown) { shown += "$token $how" } }, report = false)
            .use { radio ->
                withTimeout(10_000) { radio.state.first { it == BluetoothState.ON } }
                val job = launch { radio.run(MutableStateFlow("fedcba98")).collect { error("reported $it") } }
                withTimeout(10_000) { while (synchronized(shown) { shown.isEmpty() }) delay(50) }
                job.cancel()
                assertEquals("0123abcd name", synchronized(shown) { shown.first() })
                assertEquals("advertise fedcba98", commands.readLines().first(), "speaking only: advertises")
            }
    }
}
