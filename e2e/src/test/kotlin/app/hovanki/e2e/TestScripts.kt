package app.hovanki.e2e

import java.io.File

/**
 * Writes [text] into the executable script [file] for a test to start. Tests run in parallel: a process another test
 * starts while this JVM has the script open for writing inherits that descriptor, and starting the script then fails
 * with «Text file busy» (ETXTBSY). So the JVM writes only a draft that is never started, and `cp`, a process of its
 * own, writes the script.
 */
fun executableScript(file: File, text: String): File {
    val draft = File(file.parentFile, "${file.name}.draft").apply { writeText(text) }
    val cp = ProcessBuilder("cp", draft.path, file.path).redirectErrorStream(true).start()
    val output = cp.inputStream.bufferedReader().readText()
    check(cp.waitFor() == 0) { "cp ${draft.path} ${file.path}: $output" }
    draft.delete()
    check(file.setExecutable(true)) { "can't make ${file.path} executable" }
    return file
}
