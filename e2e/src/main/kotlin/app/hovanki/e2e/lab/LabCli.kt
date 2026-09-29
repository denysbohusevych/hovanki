package app.hovanki.e2e.lab

import java.io.File
import kotlin.system.exitProcess

/**
 * `e2e lab merge <files…> --out <folder>` (docs/radio-lab.md §4.5): the lab logs exported from the devices and the
 * Mac's, merged into `timeline.txt`, `summary.md`, `carry.csv`, `masks.csv` and `haptics.csv`. No devices needed:
 * `./gradlew :e2e:lab --args="merge a.jsonl b.jsonl --out build/lab"`.
 */
fun main(args: Array<String>) {
    exitProcess(LabCli.run(args.toList()))
}

object LabCli {
    fun run(args: List<String>): Int {
        if (args.firstOrNull() != "merge") {
            System.err.println(USAGE)
            return 2
        }
        val files = ArrayList<File>()
        var out = File("e2e/build/lab/merged")
        var i = 1
        while (i < args.size) {
            if (args[i] == "--out") {
                out = File(args.getOrNull(i + 1) ?: return usage())
                i += 2
            } else {
                files += File(args[i])
                i++
            }
        }
        if (files.isEmpty()) return usage()
        files.filterNot { it.isFile }.let { missing ->
            if (missing.isNotEmpty()) {
                System.err.println("No such files: ${missing.joinToString()}")
                return 2
            }
        }
        val merge = LabMerge(files.map { it.name to it.readText() })
        write(merge, out)
        println("Merged ${files.size} logs, ${merge.events.size} events, into ${out.path}")
        merge.problems().forEach { println("  ! $it") }
        return 0
    }

    fun write(merge: LabMerge, out: File) {
        out.mkdirs()
        File(out, "timeline.txt").writeText(merge.timeline())
        File(out, "summary.md").writeText(merge.summary())
        File(out, "carry.csv").writeText(merge.carryCsv())
        File(out, "masks.csv").writeText(merge.masksCsv())
        File(out, "haptics.csv").writeText(merge.hapticsCsv())
    }

    private fun usage(): Int {
        System.err.println(USAGE)
        return 2
    }

    private val USAGE = """
        Usage: e2e lab merge <lab log .jsonl> [<lab log .jsonl> ...] [--out e2e/build/lab/merged]
               ./gradlew :e2e:lab --args="merge a.jsonl b.jsonl --out build/lab"
    """.trimIndent()
}
