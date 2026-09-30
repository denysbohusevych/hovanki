package app.hovanki.radar

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The borders of `:radar` (docs/adr/0017-radar-techniques-and-big-run.md, section 1), read from the sources of every
 * main source set: a technique's package `app.hovanki.radar.<x>` imports no other technique's `app.hovanki.radar.<y>`
 * (the root package `app.hovanki.radar` is common ground: anyone may import it), and nothing here imports the phone
 * itself (`:device`), the game's client (`:clientCore`, `:composeApp`) or Compose. `:device` has the same test.
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
    }

    @Test
    fun techniquesDoNotImportEachOther() {
        val crossings = sources.flatMap { file ->
            val own = techniqueOf(packageOf(file) + ".File")
            importsOf(file)
                .filter { import -> techniqueOf(import)?.let { it != own } == true }
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

    private fun packageOf(file: File): String =
        file.readLines().firstOrNull { it.startsWith("package ") }?.removePrefix("package ")?.trim().orEmpty()

    private fun importsOf(file: File): List<String> = file.readLines()
        .filter { it.startsWith("import ") }
        .map { it.removePrefix("import ").substringBefore(" as ").trim() }

    /**
     * The technique's package of an imported name: `lab` for `app.hovanki.radar.lab.AirFrame` (and anything below
     * `lab`); null for the root package's names (`app.hovanki.radar.RadioApi`, a nested `….RadioApi.Companion`) and
     * for anything outside this module.
     */
    private fun techniqueOf(name: String): String? {
        if (!name.startsWith("$ROOT.")) return null
        val parts = name.removePrefix("$ROOT.").split('.')
        return parts.first().takeIf { parts.size >= 2 && it.first().isLowerCase() }
    }

    private companion object {
        const val MODULE = "radar"
        const val ROOT = "app.hovanki.radar"
        val FORBIDDEN = listOf("app.hovanki.device", "app.hovanki.client", "androidx.compose")
    }
}
