package app.hovanki.e2e.beacon

import app.hovanki.client.radio.ProximityRadio
import app.hovanki.client.radio.RadioSighting
import app.hovanki.shared.protocol.BluetoothState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import java.io.File
import kotlin.concurrent.thread

/**
 * The Mac's own Bluetooth as the bot's radio: the helper `e2e/mac-beacon/beacon.swift` (built by run.sh) advertises
 * the token and reports what it hears, line by line. It advertises the iPhone hider's way in either role: macOS has no
 * iBeacon advertising, and the phones hear a seeker by that too (the server takes any player's token).
 */
class MacRadio(helper: File, private val onLine: (String) -> Unit) :
    ProximityRadio,
    AutoCloseable {
    private val process = ProcessBuilder(helper.path).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    private val input = process.outputStream.bufferedWriter()
    private val mutableState = MutableStateFlow(BluetoothState.OFF)
    override val state: StateFlow<BluetoothState> = mutableState.asStateFlow()
    private val sightings = MutableSharedFlow<RadioSighting>(extraBufferCapacity = 256)

    init {
        thread(isDaemon = true, name = "mac-beacon") {
            process.inputStream.bufferedReader().forEachLine(::read)
            mutableState.value = BluetoothState.UNSUPPORTED
            onLine("the Bluetooth helper stopped (exit ${process.waitFor()})")
        }
    }

    private fun read(line: String) {
        val parts = line.split(' ')
        when (parts.first()) {
            "state" -> {
                mutableState.value = when (parts.getOrNull(1)) {
                    "on" -> BluetoothState.ON
                    "denied" -> BluetoothState.DENIED
                    "unsupported" -> BluetoothState.UNSUPPORTED
                    else -> BluetoothState.OFF
                }
                onLine("Bluetooth: ${parts.getOrNull(1)}")
            }

            "heard" -> {
                val rssi = parts.getOrNull(2)?.toIntOrNull() ?: return
                sightings.tryEmit(RadioSighting(parts[1], rssi, System.currentTimeMillis()))
            }

            "log" -> onLine(line.removePrefix("log "))

            else -> onLine(line)
        }
    }

    override fun run(tokens: StateFlow<String?>, asSeeker: Boolean): Flow<RadioSighting> = channelFlow {
        launch { tokens.collect { command(if (it == null) "stop" else "advertise $it") } }
        sightings.collect { send(it) }
    }.onCompletion { command("stop") }

    private fun command(line: String) {
        synchronized(input) {
            runCatching {
                input.write(line)
                input.newLine()
                input.flush()
            }
        }
    }

    override fun close() {
        runCatching { input.close() }
        process.destroy()
    }
}
