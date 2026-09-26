package app.hovanki.e2e.devices

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * A minimal Model Context Protocol client over stdio (JSON-RPC 2.0, one message per line) for `maestro mcp`.
 * The server keeps one driver session per device for its whole life, while every `maestro test` process starts
 * the driver again: about a minute per step on an iOS simulator in CI.
 */
class McpClient(command: List<String>, stderr: File) : AutoCloseable {
    class ToolResult(val isError: Boolean, val text: String)

    private val process = ProcessBuilder(command)
        .redirectError(ProcessBuilder.Redirect.appendTo(stderr.apply { parentFile?.mkdirs() }))
        .start()
    private val writer = process.outputStream.bufferedWriter()
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonObject>>()
    private val ids = AtomicLong()

    init {
        Thread(::readResponses, "mcp-reader").apply { isDaemon = true }.start()
        request(
            "initialize",
            buildJsonObject {
                put("protocolVersion", PROTOCOL_VERSION)
                put("capabilities", JsonObject(emptyMap()))
                put(
                    "clientInfo",
                    buildJsonObject {
                        put("name", "hovanki-e2e")
                        put("version", "1")
                    },
                )
            },
            timeout = 2.minutes,
        )
        send(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "notifications/initialized")
            },
        )
    }

    /** Calls tool [name]; the text of its content blocks is joined. */
    fun callTool(name: String, arguments: JsonObject, timeout: Duration): ToolResult {
        val result = request(
            "tools/call",
            buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            },
            timeout,
        )
        val text = (result["content"] as? JsonArray).orEmpty()
            .mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.content }
            .joinToString("\n")
        val isError = (result["isError"] as? JsonPrimitive)?.content == "true"
        return ToolResult(isError, text)
    }

    private fun request(method: String, params: JsonObject, timeout: Duration): JsonObject {
        check(process.isAlive) { "maestro mcp is not running (exit ${process.exitValue()})" }
        val id = ids.incrementAndGet()
        val response = CompletableFuture<JsonObject>().also { pending[id] = it }
        send(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            },
        )
        val message = try {
            response.get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            pending.remove(id)
            throw IllegalStateException("maestro mcp: no answer to $method within $timeout", e)
        }
        (message["error"] as? JsonObject)?.let { error("maestro mcp: $method failed: $it") }
        return message["result"] as? JsonObject ?: JsonObject(emptyMap())
    }

    @Synchronized
    private fun send(message: JsonObject) {
        writer.write(message.toString())
        writer.newLine()
        writer.flush()
    }

    private fun readResponses() {
        process.inputStream.bufferedReader().forEachLine { line ->
            val message = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEachLine
            val id = (message["id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return@forEachLine
            pending.remove(id)?.complete(message)
        }
        // The server is gone: fail whoever is still waiting instead of letting them time out.
        pending.values.forEach { it.completeExceptionally(IllegalStateException("maestro mcp exited")) }
    }

    override fun close() {
        runCatching { writer.close() }
        if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    private companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
    }
}
