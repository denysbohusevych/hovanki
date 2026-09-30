package app.hovanki.radar

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The borders of `:radar` (docs/adr/0017-radar-techniques-and-big-run.md, section 1), read from the sources of every
 * main source set. The root package `app.hovanki.radar` is common ground: anyone may import it. A channel's package
 * `app.hovanki.radar.channel.<x>` and the GATT link's `app.hovanki.radar.link` (its platform implementations
 * included) import nothing else of this module (`:shared` and libraries are fine); the other packages (`host`, `lab`)
 * and the root may import the channels and the link (the catalog lists them, the hosts and the lab run them) but not
 * each other. Nothing here imports the phone itself (`:device`), the game's client (`:clientCore`,
 * `:composeApp`) or Compose. `:device` has the same test.
 */
class ModuleBoundariesTest {
    private val sources: List<File> by lazy {
        // Gradle runs the tests in the module's directory; from the repository's root the module is `radar/`.
        val src = listOf(File("src"), File("../$MODULE/src"), File("$MODULE/src")).firstOrNull { it.isDirectory }
            ?: fail("No sources of :$MODULE from ${File(".").absoluteFile}")
        src.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.endsWith("Test") }
            .flatMap { set -> set.resolve("kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" } }
    }

    @Test
    fun thereAreSourcesToCheck() {
        assertTrue(sources.any { it.name == "ProximityRadio.kt" }, "$sources")
        assertTrue(sources.any { areaOf(packageOf(it) + ".File")?.startsWith("$CHANNEL.") == true }, "$sources")
        assertTrue(sources.any { areaOf(packageOf(it) + ".File") == LINK }, "$sources")
    }

    @Test
    fun channelsAndTheLinkImportOnlyTheRoot() {
        val crossings = sources.filter { isLeaf(areaOf(packageOf(it) + ".File")) }
            .flatMap { file ->
                val own = areaOf(packageOf(file) + ".File")
                importsOf(file)
                    .filter { import -> areaOf(import)?.let { it != own } == true }
                    .map { "${file.path}: $it" }
            }
        assertTrue(crossings.isEmpty(), crossings.joinToString("\n"))
    }

    @Test
    fun techniquesDoNotImportEachOther() {
        val crossings = sources.filter { !isLeaf(areaOf(packageOf(it) + ".File")) }
            .flatMap { file ->
                val own = areaOf(packageOf(file) + ".File")
                importsOf(file)
                    .filter { import ->
                        areaOf(import)?.let { it != own && !isLeaf(it) } == true
                    }
                    .map { "${file.path}: $it" }
            }
        assertTrue(crossings.isEmpty(), crossings.joinToString("\n"))
    }

    @Test
    fun nothingFromTheOtherSide() {
        val found = sources.flatMap { file ->
            importsOf(file)
                .filter { import -> FORBIDDEN.any { import == it || import.startsWith("$it.") } }
                .map { "${file.path}: $it" }
        }
        assertTrue(found.isEmpty(), found.joinToString("\n"))
    }

    /** A channel's package or the link's: they import only the root, and the others may import them. */
    private fun isLeaf(area: String?): Boolean = area != null && (area.startsWith("$CHANNEL.") || area == LINK)

    private fun packageOf(file: File): String =
        file.readLines().firstOrNull { it.startsWith("package ") }?.removePrefix("package ")?.trim().orEmpty()

    private fun importsOf(file: File): List<String> = file.readLines()
        .filter { it.startsWith("import ") }
        .map { it.removePrefix("import ").substringBefore(" as ").trim() }

    /**
     * The area of an imported name: `lab` for `app.hovanki.radar.lab.LabFrame` (and anything below `lab`),
     * `channel.name` for `app.hovanki.radar.channel.name.NameChannel`; null for the root package's names
     * (`app.hovanki.radar.RadioApi`, a nested `….RadioApi.Companion`) and for anything outside this module.
     */
    private fun areaOf(name: String): String? {
        if (!name.startsWith("$ROOT.")) return null
        val parts = name.removePrefix("$ROOT.").split('.')
        val first = parts.first().takeIf { parts.size >= 2 && it.first().isLowerCase() } ?: return null
        if (first != CHANNEL) return first
        return "$CHANNEL.${parts[1]}".takeIf { parts.size >= 3 && parts[1].first().isLowerCase() } ?: CHANNEL
    }

    private companion object {
        const val MODULE = "radar"
        const val ROOT = "app.hovanki.radar"
        const val CHANNEL = "channel"
        const val LINK = "link"
        val FORBIDDEN = listOf("app.hovanki.device", "app.hovanki.client", "androidx.compose")
    }
}
