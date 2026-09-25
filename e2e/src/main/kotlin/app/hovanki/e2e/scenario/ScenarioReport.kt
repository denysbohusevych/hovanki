package app.hovanki.e2e.scenario

import app.hovanki.shared.debug.DebugGameState
import java.io.File

/**
 * Writes `<reportDir>/<scenario>.md`: result, final server state, sync metrics and the timeline.
 * The directory comes from the system property `hovanki.e2e.reportDir` (Gradle: `e2e/build/reports/e2e`).
 */
object ScenarioReport {
    fun reportDir(): File = File(System.getProperty("hovanki.e2e.reportDir") ?: "build/reports/e2e")

    fun write(scenario: Scenario, finalState: DebugGameState?, failure: Throwable?) {
        val dir = reportDir().apply { mkdirs() }
        val file = File(dir, slug(scenario.name) + ".md")
        file.writeText(render(scenario, finalState, failure))
    }

    private fun render(scenario: Scenario, state: DebugGameState?, failure: Throwable?): String = buildString {
        appendLine("# ${scenario.name}")
        appendLine()
        appendLine(if (failure == null) "**Result: passed**" else "**Result: FAILED** — ${failure.message}")
        appendLine()
        appendLine("Server: ${scenario.serverUrl}, game: ${scenario.gameIdOrNull?.value ?: "—"}")
        appendLine()
        if (state != null) {
            appendLine("## Final state (observer)")
            appendLine()
            appendLine("Phase ${state.phase}")
            appendLine()
            appendLine("| Player | Role | Status | Fixes accepted / mock / out of order / implausible | Revealed |")
            appendLine("|---|---|---|---|---|")
            for (p in state.players) {
                val f = p.fixes
                val fixes = "${f.accepted} / ${f.mock} / ${f.outOfOrder} / ${f.implausible}"
                appendLine("| ${p.name} | ${p.role} | ${p.status} | $fixes | ${p.revealedToSeekers ?: "—"} |")
            }
            appendLine()
            if (state.catches.isNotEmpty()) {
                val names = state.players.associate { it.id to it.name }
                appendLine("| Claim | Status | Failed codes | Votes | Est. distance |")
                appendLine("|---|---|---|---|---|")
                for (c in state.catches) {
                    val votes = c.votes.joinToString { "${names[it.voterId]}: ${if (it.confirm) "yes" else "no"}" }
                    val distance = c.estimatedDistanceAtClaimMeters?.let { "%.0f m".format(it) } ?: "—"
                    appendLine(
                        "| ${names[c.seekerId]} → ${names[c.hiderId]} | ${c.status} | ${c.failedAttempts} | $votes | $distance |",
                    )
                }
                appendLine()
            }
        }
        appendLine("## Sync")
        appendLine()
        appendLine(scenario.metrics.summary())
        appendLine()
        appendLine("## Timeline")
        appendLine()
        appendLine("```")
        appendLine(scenario.timeline.render())
        appendLine("```")
    }

    /** ASCII only: file names must survive any file system encoding. */
    fun slug(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "scenario" }
}
