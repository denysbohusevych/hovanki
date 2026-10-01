package app.hovanki.e2e.lab

import app.hovanki.shared.lab.LabMerge
import app.hovanki.shared.lab.LabReport
import app.hovanki.shared.lab.LabReportBuilder
import app.hovanki.shared.lab.LabReportInput
import app.hovanki.shared.lab.LabRunScript
import app.hovanki.shared.lab.LabRunScripts
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess

/**
 * `e2e lab merge <files or folders…> --out <folder>` (docs/radio-lab.md §4.5): the lab logs exported from the devices
 * and the Mac's, merged into `timeline.txt`, `summary.md`, `carry.csv`, `masks.csv` and `haptics.csv`.
 * `e2e lab report <files or folders…> [--script <id>] [--out report.json]`: the same logs through [LabReportBuilder],
 * the report the admin shows, as JSON. A folder stands for its `.jsonl` files. No devices needed:
 * `./gradlew :e2e:lab --args="merge a.jsonl b.jsonl --out build/lab"`.
 */
fun main(args: Array<String>) {
    exitProcess(LabCli.run(args.toList()))
}

object LabCli {
    private val reportJson = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun run(args: List<String>): Int {
        val command = args.firstOrNull()
        if (command != "merge" && command != "report") return usage()
        val files = ArrayList<File>()
        var out: File? = null
        var scriptId: String? = null
        var i = 1
        while (i < args.size) {
            when (args[i]) {
                "--out" -> out = File(args.getOrNull(i + 1) ?: return usage())

                "--script" -> scriptId = args.getOrNull(i + 1) ?: return usage()

                else -> {
                    files += File(args[i])
                    i++
                    continue
                }
            }
            i += 2
        }
        files.filterNot { it.exists() }.let { missing ->
            if (missing.isNotEmpty()) {
                System.err.println("No such files: ${missing.joinToString()}")
                return 2
            }
        }
        val logs = files.flatMap { file ->
            if (file.isDirectory) {
                file.listFiles { f ->
                    f.extension == "jsonl"
                }.orEmpty().sortedBy { it.name }
            } else {
                listOf(file)
            }
        }
        if (logs.isEmpty()) return usage()
        if (command == "report") {
            val script = scriptId?.let { id -> LabRunScripts.byId(id) ?: return unknownScript(id) }
            val report = report(logs, script)
            val target = out ?: File(files.first().let { if (it.isDirectory) it else it.parentFile }, "report.json")
            target.parentFile?.mkdirs()
            target.writeText(reportJson.encodeToString(LabReport.serializer(), report))
            println("Report of ${logs.size} logs (scenario ${report.scenarioId ?: "unknown"}) into ${target.path}")
            report.cards.forEach { println("  ${it.tech}: ${it.verdict}") }
            return 0
        }
        val merge = LabMerge(logs.map { it.name to it.readText() })
        val target = out ?: File("e2e/build/lab/merged")
        write(merge, target)
        println("Merged ${logs.size} logs, ${merge.events.size} events, into ${target.path}")
        merge.problems().forEach { println("  ! $it") }
        return 0
    }

    /**
     * The report of [logs] as the server computes it, one device per label (a device's files, in name order, are one
     * log). [script]: else the local run's version its announcement named (`run: script N`), else none.
     */
    fun report(logs: List<File>, script: LabRunScript? = null): LabReport {
        val byLabel = logs.groupBy { labelOf(it) }
        val inputs = byLabel.map { (label, files) ->
            LabReportInput(label, deviceId = label, radarToken = null) {
                files.asSequence().flatMap { it.readLines().asSequence() }
            }
        }
        val announced = logs.firstNotNullOfOrNull { file ->
            file.useLines { lines -> lines.firstNotNullOfOrNull { SCRIPT_NOTE.find(it)?.groupValues?.get(1)?.toInt() } }
        }
        return LabReportBuilder.build(
            runId = logs.first().nameWithoutExtension,
            script = script ?: announced?.let { LabRunScripts.of(it) },
            logs = inputs,
            nowMillis = System.currentTimeMillis(),
        )
    }

    /** The label the log's first event gives (`dev`), else the export's name `hovanki-lab-<dev>-<time>.jsonl`. */
    private fun labelOf(file: File): String =
        file.useLines { lines -> lines.firstOrNull { it.isNotBlank() }?.let { DEV.find(it)?.groupValues?.get(1) } }
            ?: file.name.removePrefix("hovanki-lab-").substringBefore('-')

    fun write(merge: LabMerge, out: File) {
        out.mkdirs()
        File(out, "timeline.txt").writeText(merge.timeline())
        File(out, "summary.md").writeText(merge.summary())
        File(out, "carry.csv").writeText(merge.carryCsv())
        File(out, "masks.csv").writeText(merge.masksCsv())
        File(out, "haptics.csv").writeText(merge.hapticsCsv())
    }

    private fun unknownScript(id: String): Int {
        System.err.println("No such script: $id (${LabRunScripts.ALL.joinToString { it.id }})")
        return 2
    }

    private fun usage(): Int {
        System.err.println(USAGE)
        return 2
    }

    private val DEV = Regex(""""dev"\s*:\s*"([^"]+)"""")
    private val SCRIPT_NOTE = Regex(""""text"\s*:\s*"run: script (\d+)""")

    private val USAGE = """
        Usage: e2e lab merge <lab log .jsonl or folder> [...] [--out e2e/build/lab/merged]
               e2e lab report <lab log .jsonl or folder> [...] [--script <id>] [--out <folder of the first>/report.json]
               ./gradlew :e2e:lab --args="merge a.jsonl b.jsonl --out build/lab"
    """.trimIndent()
}
