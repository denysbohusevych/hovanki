package app.hovanki.e2e.devices

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Runs external tools (adb, xcrun, maestro) and logs every command with its outcome to [log]. */
class Shell(private val log: File? = null) {
    class Result(val command: List<String>, val exitCode: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = exitCode == 0

        fun orThrow(): Result {
            check(ok) { "${command.joinToString(" ")} failed ($exitCode): ${stderr.ifBlank { stdout }.take(2_000)}" }
            return this
        }
    }

    fun run(command: List<String>, timeout: Duration = 2.minutes, outputFile: File? = null): Result {
        val builder = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.PIPE)
        if (outputFile != null) builder.redirectOutput(outputFile)
        val process = builder.start()
        // Drain both streams concurrently: a full pipe would block the tool.
        val stdout = if (outputFile == null) readAsync(process.inputStream) else null
        val stderr = readAsync(process.errorStream)
        val finished = process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        val result = Result(
            command = command,
            exitCode = if (finished) process.exitValue() else -1,
            stdout = stdout?.get().orEmpty(),
            stderr = stderr.get() + if (finished) "" else "\n(timed out after $timeout)",
        )
        log?.appendText("$ ${command.joinToString(" ")}\n  -> exit ${result.exitCode}${summary(result)}\n")
        return result
    }

    fun run(vararg command: String, timeout: Duration = 2.minutes): Result = run(command.toList(), timeout)

    /** Logs a call that is not a process (a request to a long-running tool) like a command; errors become exit -1. */
    fun record(command: List<String>, call: () -> Pair<Int, String>): Result {
        val result = runCatching { call() }
            .fold({ (exitCode, output) -> Result(command, exitCode, output, "") }) { Result(command, -1, "", "$it") }
        log?.appendText("$ ${command.joinToString(" ")}\n  -> exit ${result.exitCode}${summary(result)}\n")
        return result
    }

    private fun summary(result: Result): String {
        val text = (result.stderr.ifBlank { result.stdout }).trim()
        return if (text.isEmpty()) "" else ": " + text.lines().take(3).joinToString(" | ").take(300)
    }

    private fun readAsync(stream: java.io.InputStream): java.util.concurrent.Future<String> {
        val task = java.util.concurrent.FutureTask { stream.bufferedReader().readText() }
        Thread(task, "shell-reader").apply { isDaemon = true }.start()
        return task
    }
}
