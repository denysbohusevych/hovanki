package app.hovanki.e2e.devices

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Runs the Maestro flows in `e2e/maestro/` on one device at a time (one Maestro driver session at a time is the
 * robust way on both platforms) and reads the UI hierarchy.
 *
 * Devices in [mcpDevices] go through one long-lived `maestro mcp` server that keeps a driver session per device;
 * the others through `maestro test` / `maestro hierarchy`, which restart the driver for every step: seconds on an
 * emulator, over a minute on an iOS simulator in CI. See [Mode].
 */
class Maestro(
    private val shell: Shell,
    private val flowsDir: File,
    private val binary: String = "maestro",
    private val mcpDevices: Set<String> = emptySet(),
    private val logDir: File? = null,
) : AutoCloseable {
    enum class Mode {
        /**
         * MCP for a single iOS simulator, CLI otherwise: the MCP server drives every simulator through the same
         * XCTest port (mobile-dev-inc/maestro#3611), and the CLI is fast enough on emulators.
         */
        AUTO,
        MCP,
        CLI,
        ;

        fun mcpDevices(devices: List<Device>): Set<String> = when (this) {
            MCP -> devices.map { it.id }.toSet()
            CLI -> emptySet()
            AUTO -> devices.filterIsInstance<IosDevice>().singleOrNull()?.let { setOf(it.id) }.orEmpty()
        }
    }

    private fun usesMcp(device: Device) = device.id in mcpDevices

    private val lock = Mutex()

    /**
     * Devices whose Maestro driver is installed and started. In CLI mode Maestro reinstalls it on every call by
     * default: two package installs per tap sequence slow everything down and have crashed the emulator's package
     * manager under load.
     */
    private val driverReady = mutableSetOf<String>()

    private val mcp: McpClient by lazy {
        val stderr = File(logDir ?: File(System.getProperty("java.io.tmpdir")), "maestro-mcp.log")
        McpClient(listOf(binary, "mcp", "--no-viewer", "--working-dir", flowsDir.absolutePath), stderr)
    }

    /** Runs `<flowsDir>/<flow>.yaml`; [env] becomes the flow's environment, `APP_ID` is always set. */
    suspend fun run(
        device: Device,
        flow: String,
        env: Map<String, String> = emptyMap(),
        timeout: Duration = 3.minutes,
    ): Shell.Result {
        val file = File(flowsDir, "$flow.yaml")
        require(file.isFile) { "No Maestro flow $file" }
        val variables = env + ("APP_ID" to device.appId)
        return lock.withLock {
            withContext(Dispatchers.IO) {
                when (usesMcp(device)) {
                    true -> mcpCall(
                        device,
                        "run",
                        buildJsonObject {
                            put("device_id", device.id)
                            put("files", JsonArray(listOf(JsonPrimitive(file.absolutePath))))
                            put("env", JsonObject(variables.mapValues { JsonPrimitive(it.value) }))
                        },
                        label = "run $flow ${variables.entries.joinToString(" ")}",
                        timeout = timeout,
                    )

                    false -> {
                        val command = listOf(binary, "--device", device.id, "test") + reinstallOption(device) +
                            variables.flatMap { (key, value) -> listOf("-e", "$key=$value") } + file.path
                        shell.run(command, processTimeout(device, timeout))
                    }
                }.also { if (it.ok) driverReady += device.id }
            }
        }
    }

    private fun reinstallOption(device: Device): List<String> =
        if (device.id in driverReady) listOf("--no-reinstall-driver") else emptyList()

    /** The first call on a device also installs and starts the driver: minutes on a small CI machine. */
    private fun processTimeout(device: Device, timeout: Duration): Duration =
        if (device.id in driverReady) timeout else maxOf(timeout, FIRST_CALL_TIMEOUT)

    /** The current screen of [device]. */
    suspend fun hierarchy(device: Device): UiTree {
        val result = lock.withLock {
            withContext(Dispatchers.IO) {
                when (usesMcp(device)) {
                    true -> mcpCall(
                        device,
                        "inspect_screen",
                        buildJsonObject { put("device_id", device.id) },
                        label = "inspect_screen",
                        timeout = 2.minutes,
                    )

                    false -> shell.run(
                        listOf(binary, "--device", device.id, "hierarchy") + reinstallOption(device),
                        processTimeout(device, 2.minutes),
                    )
                }.also { if (it.ok) driverReady += device.id }
            }
        }
        result.orThrow()
        return if (usesMcp(device)) UiTree.parseCompact(result.stdout) else UiTree.parse(result.stdout)
    }

    /** A tool call as a [Shell.Result] (exit 0 or 1, the tool's text as stdout), logged like a command. */
    private fun mcpCall(device: Device, tool: String, arguments: JsonObject, label: String, timeout: Duration) =
        shell.record(listOf("maestro-mcp", "--device", device.id, label)) {
            val result = mcp.callTool(tool, arguments, processTimeout(device, timeout))
            (if (result.isError) 1 else 0) to result.text
        }

    override fun close() {
        if (mcpDevices.isNotEmpty()) runCatching { mcp.close() }
    }

    /**
     * What Maestro did during a failed [run] (its `maestro.log`, from the debug output folder it prints): which
     * element it resolved and where it tapped. Empty when the folder is not in the output.
     */
    fun actionsLog(result: Shell.Result, limit: Int = 30): List<String> {
        val folder = DEBUG_OUTPUT.find(result.stdout + "\n" + result.stderr)?.groupValues?.get(1) ?: return emptyList()
        val log = File(folder, "maestro.log").takeIf { it.isFile } ?: return emptyList()
        return log.readLines()
            .filter { line -> ACTION_WORDS.any { line.contains(it, ignoreCase = true) } }
            .filterNot { "Assert that" in it } // repeated many times while waiting
            .takeLast(limit)
    }

    private companion object {
        val FIRST_CALL_TIMEOUT = 10.minutes
        val DEBUG_OUTPUT = Regex("""==== Debug output \(logs & screenshots\) ====\s+(\S+?)\*{0,2}\s""")
        val ACTION_WORDS = listOf("tap", "element", "bounds", "point", "hierarchy", "assert")
    }
}

/** The UI hierarchy as Maestro prints it: nodes with string attributes (`resource-id`, `text`, ...). */
class UiTree(val root: Node) {
    class Node(val attributes: Map<String, String>, val children: List<Node>) {
        /** The element's id: Android resource-id (test tag in debug builds) or iOS accessibilityIdentifier. */
        val id: String? get() = ID_KEYS.firstNotNullOfOrNull { attributes[it]?.takeIf(String::isNotEmpty) }

        val text: String? get() = TEXT_KEYS.firstNotNullOfOrNull { attributes[it]?.takeIf(String::isNotBlank) }

        /** `[x1,y1][x2,y2]` in screen points/pixels as Maestro reports them, where it taps the element. */
        val bounds: String? get() = attributes["bounds"]?.takeIf(String::isNotBlank)

        /** States that explain an ignored tap: a disabled element, a focused text field. */
        fun flags(): String = buildString {
            if (attributes["enabled"] == "false") append(" (disabled)")
            if (attributes["focused"] == "true") append(" (focused)")
        }

        fun walk(): Sequence<Node> = sequence {
            yield(this@Node)
            children.forEach { yieldAll(it.walk()) }
        }
    }

    fun find(id: String): Node? = root.walk().firstOrNull { it.id == id }

    fun contains(id: String): Boolean = find(id) != null

    /**
     * What is on screen, one element per line (`id: text [bounds]`): enough to see why a flow did not find its
     * element, or where a tap on it lands.
     */
    fun describe(limit: Int = 40): String = root.walk()
        .mapNotNull { node ->
            val id = node.id
            val text = node.text
            when {
                id != null && text != null -> "$id: $text"
                id != null -> id
                text != null -> "\"$text\""
                else -> null
            }?.let { line -> node.bounds?.let { "$line $it" } ?: line }
                ?.let { line -> line + node.flags() }
        }
        .distinct()
        .take(limit)
        .joinToString("\n") { "  $it" }

    /** Text of the element [id], or of its first descendant with a text (Compose may split them). */
    fun textOf(id: String): String? {
        val node = find(id) ?: return null
        return node.text ?: node.walk().firstNotNullOfOrNull { it.text }
    }

    companion object {
        private val ID_KEYS = listOf("resource-id", "identifier", "id")
        private val TEXT_KEYS = listOf("text", "accessibilityText", "value", "label")

        /** Parses `maestro hierarchy` output (log lines before the JSON are skipped). */
        fun parse(output: String): UiTree {
            val start = output.indexOf('{')
            require(start >= 0) { "No hierarchy JSON in: ${output.take(300)}" }
            return UiTree(node(Json.parseToJsonElement(output.substring(start))))
        }

        /**
         * Parses `inspect_screen` of `maestro mcp`: `{"ui_schema": ..., "elements": ...}` with abbreviated keys
         * (`rid` resource-id / accessibilityIdentifier, `txt`, `a11y`, `val`, `b` bounds, `c` children) and boolean
         * flags present only when they differ from the platform defaults.
         */
        fun parseCompact(output: String): UiTree {
            val start = output.indexOf('{')
            require(start >= 0) { "No screen JSON in: ${output.take(300)}" }
            val payload = Json.parseToJsonElement(output.substring(start)) as? JsonObject
                ?: error("No screen JSON in: ${output.take(300)}")
            val children = when (val elements = payload["elements"]) {
                is JsonArray -> elements.map(::compactNode)
                is JsonObject -> listOf(compactNode(elements))
                else -> emptyList()
            }
            return UiTree(Node(emptyMap(), children))
        }

        private val COMPACT_KEYS = mapOf(
            "rid" to "resource-id",
            "txt" to "text",
            "a11y" to "accessibilityText",
            "val" to "value",
            "hint" to "hintText",
            "b" to "bounds",
        )
        private val FLAGS = listOf("enabled", "focused", "selected", "checked")

        private fun compactNode(element: JsonElement): Node {
            val obj = element as? JsonObject ?: return Node(emptyMap(), emptyList())
            val attributes = buildMap {
                for ((short, long) in COMPACT_KEYS) obj[short]?.let { put(long, flatten(it)) }
                for (flag in FLAGS) obj[flag]?.let { put(flag, flatten(it)) }
            }
            return Node(attributes, (obj["c"] as? JsonArray).orEmpty().map(::compactNode))
        }

        /** A primitive as is; bounds given as `[x1, y1, x2, y2]` in the `[x1,y1][x2,y2]` form of the CLI. */
        private fun flatten(value: JsonElement): String = when (value) {
            is JsonPrimitive -> value.content

            is JsonArray -> {
                val numbers = value.mapNotNull { (it as? JsonPrimitive)?.content }
                if (numbers.size ==
                    4
                ) {
                    "[${numbers[0]},${numbers[1]}][${numbers[2]},${numbers[3]}]"
                } else {
                    value.toString()
                }
            }

            else -> value.toString()
        }

        private fun node(element: JsonElement): Node {
            val obj = element as? JsonObject ?: return Node(emptyMap(), emptyList())
            val attributes = (obj["attributes"] as? JsonObject).orEmpty()
                .mapValues { (_, value) -> (value as? JsonPrimitive)?.content.orEmpty() }
            val children = (obj["children"] as? JsonArray).orEmpty().map(::node)
            return Node(attributes, children)
        }
    }
}
