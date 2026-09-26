package app.hovanki.e2e.devices

import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What `e2e/run-devices.sh` prepares, for `./gradlew :e2e:devices` (docs/e2e.md): emulators the developer already
 * runs (Android Studio's Device Manager), the debug APK and the server jar. Works wherever the JVM runs, without bash.
 */
object LocalSetup {
    /** Serials of the booted emulators (`emulator-5554 device`); USB devices can't reach 10.0.2.2 or take `geo fix`. */
    fun runningEmulators(shell: Shell): List<String> {
        val result = shell.run("adb", "devices")
        check(result.ok) { "adb devices failed: ${result.stderr.ifBlank { result.stdout }}" }
        return parseAdbDevices(result.stdout)
    }

    fun parseAdbDevices(output: String): List<String> = output.lines()
        .map { it.trim().split(Regex("\\s+")) }
        .filter { it.size >= 2 && it[0].startsWith("emulator-") && it[1] == "device" }
        .map { it[0] }

    /**
     * Makes a booted emulator ready like the script does for its own: no animations, no "isn't responding" dialogs,
     * location on, [apk] installed with the runtime permissions granted. Returns what to call afterwards to give the
     * developer's emulator its settings back.
     */
    fun prepare(device: AndroidDevice, apk: File): () -> Unit {
        val previous = device.putGlobalSettings(TEST_SETTINGS)
        device.install(apk)
        return { device.putGlobalSettings(previous) }
    }

    private val TEST_SETTINGS = mapOf(
        "window_animation_scale" to "0",
        "transition_animation_scale" to "0",
        "animator_duration_scale" to "0",
        "hide_error_dialogs" to "1",
    )

    /** Fails early with the install command instead of an IOException from the first flow. */
    fun checkMaestro(binary: String) {
        val found = runCatching {
            val process = ProcessBuilder(binary, "--version").redirectErrorStream(true).start()
            process.inputStream.readAllBytes()
            process.waitFor(2, TimeUnit.MINUTES) && process.exitValue() == 0
        }.getOrDefault(false)
        check(found) {
            "Maestro not found ($binary). Install it: curl -fsSL https://get.maestro.mobile.dev | bash " +
                "(Windows: https://docs.maestro.dev/getting-started/installing-maestro/windows)"
        }
    }
}

/**
 * The production server jar with the `e2e` profile on [port] of this machine, as a child process; its log and the
 * access log (request line, status, time; no headers, so no tokens) go to [logDir]. [close] stops it; so does a
 * shutdown hook when the run is cancelled (the Stop button in the IDE).
 */
class LocalServer private constructor(private val process: Process) : AutoCloseable {
    private val hook = Thread { stop() }.also { Runtime.getRuntime().addShutdownHook(it) }

    override fun close() {
        stop()
        runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
    }

    private fun stop() {
        if (!process.isAlive) return
        process.destroy()
        if (!process.waitFor(20, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    companion object {
        /** [buildings]: `hovanki.buildings.source` of the server, `overpass` for real buildings around the game. */
        fun start(
            jar: File,
            port: Int,
            logDir: File,
            buildings: String = "overpass",
            timeout: Duration = 90.seconds,
        ): LocalServer {
            require(jar.isFile) { "No server jar $jar: build it with ./gradlew :server:bootJar" }
            check(portIsFree(port) && !healthy(port)) {
                "Port $port is busy (a server left from another run, or your own bootRun?). Stop it or pick another " +
                    "port: -Pe2e.port=8081"
            }
            logDir.mkdirs()
            val java = File(System.getProperty("java.home"), "bin/java").path
            val command = listOf(
                java,
                "-Xmx512m",
                "-jar",
                jar.absolutePath,
                "--spring.profiles.active=e2e",
                "--server.port=$port",
                "--hovanki.buildings.source=$buildings",
                "--server.tomcat.accesslog.enabled=true",
                "--server.tomcat.accesslog.directory=${logDir.absolutePath}",
                "--server.tomcat.accesslog.prefix=access",
                "--server.tomcat.accesslog.suffix=.log",
                "--server.tomcat.accesslog.pattern=%t %a \"%r\" %s %{ms}Tms",
            )
            val log = File(logDir, "server.log")
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start()
            val server = LocalServer(process)
            val deadline = System.nanoTime() + timeout.inWholeNanoseconds
            while (System.nanoTime() < deadline) {
                if (healthy(port)) return server
                if (!process.isAlive) {
                    server.close()
                    error("The server exited on start:\n${log.readLines().takeLast(40).joinToString("\n")}")
                }
                Thread.sleep(1_000)
            }
            server.close()
            error("The server did not start within $timeout, see $log")
        }

        private fun portIsFree(port: Int): Boolean = runCatching { ServerSocket(port).close() }.isSuccess

        private fun healthy(port: Int): Boolean = runCatching {
            val connection = URI("http://localhost:$port/actuator/health").toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 1_000
            connection.readTimeout = 2_000
            try {
                connection.responseCode == 200
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }
}
