package app.hovanki.e2e.devices

import app.hovanki.shared.protocol.GeoPoint
import java.io.File
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** An emulator or simulator with the debug app installed, driven from the host machine. */
interface Device {
    /** adb serial (`emulator-5554`) or simulator UDID: what Maestro's `--device` takes. */
    val id: String

    /** Player name and label in the report, e.g. `Android-1`. */
    val label: String

    /** Android application id / iOS bundle id. */
    val appId: String

    /** Address of the server on the host machine as this device reaches it. */
    fun serverUrl(port: Int): String

    /** Stops the app if it runs and starts it fresh with [options] (`LaunchOptions` keys without the prefix). */
    fun launchApp(options: Map<String, String>)

    fun stopApp()

    /** Brings the running app back from the background, keeping its state. */
    fun bringAppToFront()

    /** Sends the app to the background, like pressing the home button. */
    fun sendAppToBackground()

    fun setLocation(point: GeoPoint)

    fun screenshot(file: File)

    fun clearLogs()

    fun saveLogs(file: File)
}

/** An Android emulator (or device) reached with adb. */
class AndroidDevice(override val id: String, override val label: String, private val shell: Shell) : Device {
    override val appId: String = "app.hovanki"
    private val activity = "$appId/app.hovanki.android.MainActivity"

    /** 10.0.2.2 is the host machine as seen from the emulator. */
    override fun serverUrl(port: Int): String = "http://10.0.2.2:$port"

    fun grantPermissions() {
        for (permission in PERMISSIONS) adb("shell", "pm", "grant", appId, permission)
        adb("shell", "cmd", "location", "set-location-enabled", "true")
    }

    /** `adb install -r -g`: the runtime permissions (location, notifications) are granted up front. */
    fun install(apk: File) {
        shell.run(listOf("adb", "-s", id, "install", "-r", "-g", apk.absolutePath), timeout = 3.minutes).orThrow()
    }

    /** Sets global [settings] (`settings put global`) and returns their previous values ("null" when unset). */
    fun putGlobalSettings(settings: Map<String, String>): Map<String, String> = settings.mapValues { (key, value) ->
        val previous = adb("shell", "settings", "get", "global", key).stdout.trim().ifEmpty { "null" }
        if (value == "null") {
            adb("shell", "settings", "delete", "global", key)
        } else {
            adb("shell", "settings", "put", "global", key, value).orThrow()
        }
        previous
    }

    override fun launchApp(options: Map<String, String>) {
        stopApp()
        // A leftover system dialog ("... isn't responding" on a busy emulator) would cover the app.
        adb("shell", "am", "broadcast", "-a", "android.intent.action.CLOSE_SYSTEM_DIALOGS")
        val extras = options.flatMap { (key, value) -> listOf("--es", "hovanki.$key", value) }
        adb(listOf("shell", "am", "start", "-W", "-n", activity) + extras).orThrow()
    }

    override fun stopApp() {
        adb("shell", "am", "force-stop", appId)
    }

    override fun bringAppToFront() {
        // The launcher intent resumes the existing task instead of starting a new one.
        adb("shell", "monkey", "-p", appId, "-c", "android.intent.category.LAUNCHER", "1").orThrow()
    }

    override fun sendAppToBackground() {
        adb("shell", "input", "keyevent", "KEYCODE_HOME").orThrow()
        // A system image without a launcher (some CI images) would leave the app in front and turn every background
        // check into a foreground one: fail loudly instead.
        val resumed = adb("shell", "dumpsys", "activity", "activities").orThrow().stdout.lines()
            .filter { "ResumedActivity" in it }
        check(resumed.none { appId in it }) {
            "$label: the app is still in the foreground after HOME (no launcher?): ${resumed.joinToString {
                it.trim()
            }}"
        }
    }

    override fun setLocation(point: GeoPoint) {
        // The emulator console wants longitude first.
        adb("emu", "geo", "fix", point.lon.format(), point.lat.format())
    }

    override fun screenshot(file: File) {
        shell.run(listOf("adb", "-s", id, "exec-out", "screencap", "-p"), outputFile = file)
    }

    override fun clearLogs() {
        adb("logcat", "-c")
    }

    override fun saveLogs(file: File) {
        shell.run(listOf("adb", "-s", id, "logcat", "-d", "-v", "time"), outputFile = file)
    }

    private fun adb(vararg args: String) = adb(args.toList())

    private fun adb(args: List<String>) = shell.run(listOf("adb", "-s", id) + args, timeout = 30.seconds)

    private companion object {
        val PERMISSIONS = listOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            // Android 13+: the "round in progress" notification of the foreground service.
            "android.permission.POST_NOTIFICATIONS",
        )
    }
}

/** An iOS simulator reached with `xcrun simctl`. */
class IosDevice(
    override val id: String,
    override val label: String,
    private val shell: Shell,
    override val appId: String = "app.hovanki.ios",
) : Device {
    /** The simulator shares the host's network. */
    override fun serverUrl(port: Int): String = "http://localhost:$port"

    /** The app's stdout/stderr (HTTP log: method, URL, status; uncaught exceptions), one pair of files per launch. */
    private val console = listOf("stdout", "stderr")
        .map { File(System.getProperty("java.io.tmpdir"), "hovanki-e2e-$id.$it.log") }
    private val earlierConsole = StringBuilder()

    override fun launchApp(options: Map<String, String>) {
        stopApp()
        keepConsole()
        // Launch arguments land in NSUserDefaults, where the debug app reads its LaunchOptions.
        val arguments = options.flatMap { (key, value) -> listOf("-hovanki.$key", value) }
        val output = listOf("--stdout=${console[0].absolutePath}", "--stderr=${console[1].absolutePath}")
        simctl(listOf("launch") + output + listOf(id, appId) + arguments, timeout = LAUNCH_TIMEOUT).orThrow()
    }

    /** A new launch overwrites the console files: keep what the previous process printed. */
    private fun keepConsole() {
        for (file in console.filter { it.isFile }) {
            earlierConsole.append("----- ${file.name}\n").append(file.readText())
            file.delete()
        }
    }

    override fun stopApp() {
        simctl("terminate", id, appId)
    }

    override fun bringAppToFront() {
        simctl(listOf("launch", id, appId), timeout = LAUNCH_TIMEOUT).orThrow()
    }

    override fun sendAppToBackground() {
        // No home button in simctl: opening another app puts ours in the background.
        simctl(listOf("launch", id, "com.apple.Preferences"), timeout = LAUNCH_TIMEOUT).orThrow()
    }

    override fun setLocation(point: GeoPoint) {
        simctl("location", id, "set", "${point.lat.format()},${point.lon.format()}")
    }

    override fun screenshot(file: File) {
        simctl("io", id, "screenshot", file.absolutePath)
    }

    override fun clearLogs() {
        console.forEach { it.delete() }
        earlierConsole.clear()
    }

    override fun saveLogs(file: File) {
        val logShow = listOf("log", "show", "--info", "--style", "compact", "--last", "30m") +
            listOf("--predicate", "process == \"Hovanki\"")
        shell.run(listOf("xcrun", "simctl", "spawn", id) + logShow, timeout = 60.seconds, outputFile = file)
        keepConsole()
        file.appendText("\n===== app console (stdout, stderr)\n$earlierConsole")
    }

    private fun simctl(vararg args: String) = simctl(args.toList())

    private fun simctl(args: List<String>, timeout: Duration = 60.seconds) =
        shell.run(listOf("xcrun", "simctl") + args, timeout = timeout)

    private companion object {
        /** A launch waits for the process to start, which takes long on a busy simulator. */
        val LAUNCH_TIMEOUT = 3.minutes
    }
}

private fun Double.format(): String = String.format(Locale.ROOT, "%.6f", this)
