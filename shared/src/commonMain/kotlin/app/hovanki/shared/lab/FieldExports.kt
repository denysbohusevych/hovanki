package app.hovanki.shared.lab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * `digest.jsonl` (docs/adr/0018-field-test-build.md §6): one JSON object per line for an AI to read, written by
 * [FieldReportBuilder] as it reads the game: a header (`k` = `digest`: the run and its players by alias, model and
 * system), then in time order the game's events by the server (`phase`, `claim`, `catch`, `dispute`, `reveal`, `glow`,
 * `srv`), every `mark`, `survey`, `anomaly` (one found after the lines of its time went out has the time it was found
 * as `t` and its start in `since`, so `t` never goes back), and a [MINUTE] row per player and minute: `gps`
 * fixes, `acc` (their median accuracy, m), `gaps`, `sync` with `sync_p50`/`sync_p95` (ms) and `sync_err`, `battery`
 * (0…1), `bg_s` (seconds not on the screen within the minute; a silent minute has no row), `peers` (whom the radio
 * heard), `bands` (the phone's own bands by name); and a [FieldPairs.KIND] row per pair of players and minute within
 * [FieldPairs.METERS] by GPS or heard (GPS distance, RSSI both ways, band, carry, models: Bluetooth against GPS, at most
 * [FieldPairs.ROWS_PER_MINUTE] a minute, the header says so). Players are P1…Pn, never a nickname or an id; no
 * coordinates.
 */
object FieldDigest {
    const val SCHEMA = 1
    const val MINUTE = "minute"
}

/**
 * The raw logs an admin takes out of a game's run (`raw.zip`, docs/field-test.md step 6): all its devices or some
 * ([devices]: device ids or labels, i.e. player ids or `server`; empty: all), all the time or the window
 * [fromMillis]..[toMillis] on the server's clock (an event's `t`; a line without one is kept: the header's).
 */
data class FieldRawSlice(
    val devices: Set<String> = emptySet(),
    val fromMillis: Long? = null,
    val toMillis: Long? = null,
) {
    init {
        require(fromMillis == null || toMillis == null || fromMillis <= toMillis) { "The window ends before it starts" }
    }

    /** Whether the device [deviceId] labelled [label] is in the slice. */
    fun includes(deviceId: String, label: String): Boolean =
        devices.isEmpty() || deviceId in devices || label in devices

    /** Whether the whole log is taken: no line needs reading. */
    val wholeTime: Boolean get() = fromMillis == null && toMillis == null

    /** Whether [line] (a log's) is in the slice's time. */
    fun keeps(line: String): Boolean {
        if (wholeTime) return true
        val t = timeOf(line) ?: return true
        return (fromMillis == null || t >= fromMillis) && (toMillis == null || t <= toMillis)
    }

    private fun timeOf(line: String): Long? {
        // The common fields come first in every line a phone or the server writes: no need to parse it all.
        val match = T_FIELD.find(line) ?: return runCatching {
            val json = Json.parseToJsonElement(line) as? JsonObject
            (json?.get(LabFields.T) as? JsonPrimitive)?.longOrNull
        }.getOrNull()
        return match.groupValues[1].toLongOrNull()
    }

    private companion object {
        val T_FIELD = Regex("""^\{"t":(-?\d+)[,}]""")
    }
}

/**
 * `report.md` (docs/adr/0018-field-test-build.md §6): a [FieldReport] as Markdown for people and for an AI, tens of
 * KB: the summary, the timeline, a table per player, the radar, the anomalies with their times. Players are P1…Pn
 * (never their ids or nicknames), times UTC on the server's clock; no coordinates. Long lists are cut ([maxRows]).
 */
object FieldReportMarkdown {
    fun render(report: FieldReport, maxRows: Int = MAX_ROWS): String = buildString {
        val s = report.summary
        appendLine("# Field game report")
        appendLine()
        appendLine(
            "Run `${report.runId}`, computed ${utc(report.computedAtMillis)} UTC" +
                (if (report.final) ", the whole game" else ", live: the game so far") +
                ". Players are P1…P${report.players.size}; times are UTC on the server's clock.",
        )
        appendLine()
        appendLine("## 1. Summary")
        appendLine()
        appendLine("| | |")
        appendLine("|---|---|")
        appendLine("| players | ${s.players} (phones ${s.devices}, restarts ${s.restarts}) |")
        appendLine("| models | ${counts(s.models)} |")
        appendLine("| systems | ${counts(s.os)} |")
        appendLine("| builds | ${counts(s.builds)} |")
        appendLine(
            "| round | ${s.roundStartMillis?.let(::time) ?: "-"} → ${s.roundEndMillis?.let(::time) ?: "-"}" +
                "${s.roundSeconds?.let { " (${it / 60} min ${it % 60} s)" } ?: ""} |",
        )
        appendLine("| logs | ${s.firstMillis?.let(::time) ?: "-"} → ${s.lastMillis?.let(::time) ?: "-"} |")
        appendLine(
            "| dropped out | ${s.dropouts.joinToString { "${it.player} at ${time(it.atMillis)}" }.ifEmpty { "-" }} |",
        )
        appendLine("| errors (err) | ${s.errors}, of them in Sentry ${s.sentryEvents} |")
        appendLine("| marks «something is wrong» | ${s.marks} |")
        appendLine(
            "| survey | ${s.surveys} answered, ratings ${s.ratings.joinToString(" ").ifEmpty { "-" }}" +
                "${s.ratingAverage?.let { " (average $it)" } ?: ""} |",
        )
        appendLine("| what broke | ${counts(s.broken)} |")
        appendLine("| where the phone was | ${counts(s.carry)} |")
        appendLine(
            "| server | sync p95 max ${report.server.syncP95Max ?: "-"} ms, 5xx ${report.server.errors5xx}, " +
                "429 ${report.server.errors429}, heap max ${report.server.heapMaxMb ?: "-"}/" +
                "${report.server.heapLimitMb ?: "-"} MB, cpu max ${report.server.cpuMax ?: "-"}, " +
                "events dropped ${report.server.dropped} |",
        )
        if (report.problems.isNotEmpty()) {
            appendLine()
            report.problems.forEach { appendLine("- ${it.cell()}") }
        }

        appendLine()
        appendLine("## 2. Timeline")
        appendLine()
        if (report.timeline.isEmpty()) appendLine("Nothing yet.")
        val timeline = report.timeline.take(maxRows)
        if (timeline.isNotEmpty()) {
            appendLine("| time | event | player | what |")
            appendLine("|---|---|---|---|")
            for (entry in timeline) {
                appendLine("| ${time(entry.atMillis)} | ${entry.kind} | ${entry.player ?: ""} | ${entry.text.cell()} |")
            }
        }
        val cut = report.timeline.size - timeline.size + report.timelineDropped
        if (cut > 0) appendLine("\n$cut more left out.")
        if (report.marks.isNotEmpty()) {
            appendLine()
            appendLine("### Marks and 30 s around them")
            for (mark in report.marks.take(maxRows)) {
                appendLine()
                appendLine(
                    "**${time(mark.atMillis)} ${mark.player ?: mark.by ?: "?"}**: " +
                        (mark.text?.let { "«${it.cell()}»" } ?: "no words"),
                )
                mark.around.forEach { appendLine("- ${it.cell()}") }
            }
        }

        appendLine()
        appendLine("## 3. Players")
        appendLine()
        appendLine(
            "| player | phone | os | phones | GPS fixes | acc p50/p95, m | GPS gaps > 30 s | jumps | " +
                "server refused fixes | syncs | sync p50/p95, ms | sync errors | stalls | socket % | " +
                "answer p50/p95, B | background, min | battery %/h | thermal | errors | marks | rating | " +
                "dropped out |",
        )
        appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        for (p in report.players) {
            val g = p.gps
            val y = p.sync
            appendLine(
                "| ${p.alias} | ${(p.model ?: "?").cell()} | ${(p.os ?: "?").cell()} | ${p.devices} | ${g.fixes} | " +
                    "${g.accP50 ?: "-"}/${g.accP95 ?: "-"} | ${g.gaps} (longest ${g.longestGapSeconds} s) | " +
                    "${g.jumps} | ${refused(g)} " +
                    "of ${g.serverAccepted + g.serverRefused.values.sum()} | ${y.count} | ${y.p50 ?: "-"}/" +
                    "${y.p95 ?: "-"} | ${y.errors} | ${y.stalls} | ${y.socketPercent ?: "-"} | " +
                    "${y.bytesP50 ?: "-"}/${y.bytesP95 ?: "-"} | ${p.backgroundSeconds / 60} | " +
                    "${p.battery.percentPerHour ?: "-"}${if (p.battery.lowPower) " (low power)" else ""} | " +
                    "${p.thermal.joinToString(" ").ifEmpty { "-" }} | ${p.errors} | ${p.marks} | " +
                    "${p.survey?.rating ?: "-"} | ${p.droppedAtMillis?.let(::time) ?: "-"} |",
            )
        }
        val permissions = report.players.filter { it.permissions.isNotEmpty() }
        if (permissions.isNotEmpty()) {
            appendLine()
            appendLine("Permissions (last written):")
            appendLine()
            for (p in permissions) {
                appendLine("- ${p.alias}: ${p.permissions.entries.joinToString { "${it.key} ${it.value}".cell() }}")
            }
        }
        val heard = report.players.filter { it.heard.isNotEmpty() }
        if (heard.isNotEmpty()) {
            appendLine()
            appendLine("The radio: whom each phone heard (seconds, readings, channels):")
            appendLine()
            for (p in heard) {
                appendLine(
                    "- ${p.alias} heard " + p.heard.joinToString("; ") {
                        "${it.peer} ${it.seconds} s, ${it.readings} readings, ${it.channels.joinToString(",")}"
                    },
                )
            }
        }

        radar(report.radar, maxRows)

        appendLine()
        appendLine("## 5. Anomalies")
        appendLine()
        if (report.anomalyCounts.isEmpty()) {
            appendLine("None found.")
        } else {
            appendLine(report.anomalyCounts.entries.joinToString { "${it.key} ${it.value}" })
            appendLine()
            appendLine("| from | to | kind | player | what |")
            appendLine("|---|---|---|---|---|")
            for (a in report.anomalies.take(maxRows)) {
                appendLine(
                    "| ${time(a.atMillis)} | ${a.untilMillis?.let(::time) ?: ""} | ${a.kind} | ${a.player ?: ""} | " +
                        "${a.detail.cell()} |",
                )
            }
            val more = report.anomalyCounts.values.sum() - minOf(report.anomalies.size, maxRows)
            if (more > 0) appendLine("\n$more more left out.")
        }
    }

    private fun StringBuilder.radar(radar: FieldReportRadar, maxRows: Int) {
        appendLine()
        appendLine("## 4. Radar")
        appendLine()
        appendLine("Seconds with an RSSI and the pair's GPS distance (sender → listener): ${counts(radar.pairSeconds)}")
        if (radar.rssi.isNotEmpty()) {
            appendLine()
            appendLine("### RSSI against the GPS distance")
            appendLine()
            appendLine("| models (sender → listener) | carried | meters | seconds | median | p10 | p90 |")
            appendLine("|---|---|---|---|---|---|---|")
            for (row in radar.rssi.take(maxRows)) {
                appendLine(
                    "| ${row.models.cell()} | ${row.carry} | ${row.bucket} | ${row.seconds} | ${row.median} | " +
                        "${row.p10} | ${row.p90} |",
                )
            }
        }
        btVsGps(radar, maxRows)
        if (radar.zeroPoints.isNotEmpty()) {
            appendLine()
            appendLine("### «0 m»: catches and touches")
            appendLine()
            appendLine("| time | kind | a | b | a heard by b | b heard by a | GPS, m |")
            appendLine("|---|---|---|---|---|---|---|")
            for (z in radar.zeroPoints.take(maxRows)) {
                appendLine(
                    "| ${time(z.atMillis)} | ${z.kind} | ${z.a} | ${z.b} | ${z.rssiAToB ?: "-"} | " +
                        "${z.rssiBToA ?: "-"} | ${z.gpsMeters ?: "-"} |",
                )
            }
        }
        if (radar.coverage.isNotEmpty()) {
            appendLine()
            appendLine("### Coverage: within ${FieldReportBuilder.COVERAGE_METERS.toInt()} m by GPS, heard")
            appendLine()
            appendLine("| sender (layout) | listener | seconds near | heard | % |")
            appendLine("|---|---|---|---|---|")
            for (c in radar.coverage) {
                appendLine(
                    "| ${c.sender} | ${c.listener} | ${c.nearSeconds} | ${c.heardSeconds} | ${c.percent ?: "-"} |",
                )
            }
        }
        if (radar.masks.isNotEmpty()) {
            appendLine()
            appendLine("### The shadow's channels")
            appendLine()
            appendLine("| channel | listener | sender | frames | a player's |")
            appendLine("|---|---|---|---|---|")
            for (m in radar.masks) {
                appendLine("| ${m.tech} | ${m.listener} | ${m.sender ?: "?"} | ${m.frames} | ${m.resolved} |")
            }
        }
        val rules = radar.shadowRules
        appendLine()
        appendLine("### The rules in the shadow")
        appendLine()
        appendLine(
            "- Proximity catch: of ${rules.catches} confirmed catches it would have refused " +
                "${rules.catchesShadowWouldRefuse}; of ${rules.claims} claims ${rules.claimsWithShadow} had its " +
                "answer, " +
                "${rules.shadowAccepted} taken.",
        )
        appendLine(
            "- Pocket stealth: ${rules.bandShifted} of ${rules.bandChanges} band changes would have had another band" +
                rules.shifts.takeIf { it.isNotEmpty() }?.let { " (${counts(it)})" }.orEmpty() + ".",
        )
        val techniques = radar.techniques ?: return
        appendLine()
        appendLine("### The lab's techniques")
        appendLine()
        if (!techniques.computed) {
            appendLine(techniques.note ?: "Not computed.")
            return
        }
        val confirmed = techniques.touches.count { it.markAtMillis != null }
        appendLine(
            "Touches: the detector found ${techniques.touches.size} ($confirmed of them pressed «We touched»), " +
                "it missed ${techniques.missedTouches} presses.",
        )
        for (touch in techniques.touches) {
            val rssi = touch.rssi.entries.joinToString { "${it.key} ${it.value} dBm" }.ifEmpty { "-" }
            appendLine("- ${touch.pair}: ${rssi.cell()}")
        }
        for (spread in techniques.touchSpreads) {
            appendLine(
                "- ${spread.direction}: ${spread.touches} touches, spread ${spread.spreadDb} dB, " +
                    "drift ${spread.driftDb} dB",
            )
        }
        if (techniques.without.isNotEmpty()) {
            appendLine()
            appendLine("| without | pair-seconds | same band | only it heard, s |")
            appendLine("|---|---|---|---|")
            for (w in techniques.without) {
                appendLine("| ${w.tech} | ${w.seconds} | ${w.same} | ${w.onlyChannel} |")
            }
        }
    }

    /** «Bluetooth против GPS»: the RSSI by models × carry × distance with the band's agreement, and the worst minutes. */
    private fun StringBuilder.btVsGps(radar: FieldReportRadar, maxRows: Int) {
        if (radar.btVsGps.isEmpty() && radar.worstMinutes.isEmpty()) return
        appendLine()
        appendLine("### Bluetooth против GPS (Bluetooth against GPS)")
        appendLine()
        appendLine(
            "GPS is good to 5–10 m at best: a band that disagrees by less than that is no lie. «Band agrees» counts " +
                "the seconds the game showed a band for the pair (the listener's) that fit the GPS distance " +
                "(burning ≤ 10 m, hot 2–25 m, warm 5–60 m, none ≥ 20 m), the two phones' GPS accuracy taken off.",
        )
        if (radar.btVsGps.isNotEmpty()) {
            appendLine()
            appendLine("| models (sender → listener) | carried | meters | seconds | median | p20 | p80 | band agrees |")
            appendLine("|---|---|---|---|---|---|---|---|")
            for (row in radar.btVsGps.take(maxRows)) {
                val agree = if (row.bandSeconds == 0) "-" else "${row.bandAgree}/${row.bandSeconds}"
                appendLine(
                    "| ${row.models.cell()} | ${row.carry} | ${row.bucket} | ${row.seconds} | ${row.median} | " +
                        "${row.p20} | ${row.p80} | $agree |",
                )
            }
        }
        if (radar.worstMinutes.isNotEmpty()) {
            appendLine()
            appendLine(
                "The ${radar.worstMinutes.size} worst minutes: where the band was farthest from the GPS distance",
            )
            appendLine()
            appendLine(
                "| time | a | b | GPS median/min, m | GPS acc a/b, m | RSSI a→b / b→a | band | shadow band | " +
                    "carried a/b | phones a/b | off, m |",
            )
            appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
            for (row in radar.worstMinutes) {
                appendLine(
                    "| ${time(row.atMillis)} | ${row.a} | ${row.b} | ${row.gpsMedian ?: "-"}/${row.gpsMin ?: "-"} | " +
                        "${row.accA ?: "-"}/${row.accB ?: "-"} | ${row.rssiAb ?: "-"} / ${row.rssiBa ?: "-"} | " +
                        "${row.band ?: "-"} | ${row.shadowBand ?: "-"} | ${row.carryA ?: "-"}/${row.carryB ?: "-"} | " +
                        "${(row.modelA ?: "?").cell()} (${row.platA ?: "?"}) / ${(row.modelB ?: "?").cell()} " +
                        "(${row.platB ?: "?"}) | ${row.disagreementM ?: "-"} |",
                )
            }
        }
    }

    private fun refused(gps: FieldReportGps): String =
        gps.serverRefused.entries.joinToString { "${it.key} ${it.value}" }.ifEmpty { "0" }

    /** The longest list a table shows; the rest is counted. */
    const val MAX_ROWS = 400

    private fun counts(list: List<FieldReportCount>): String =
        list.joinToString { "${it.name.cell()} ${it.count}" }.ifEmpty { "-" }

    private fun time(millis: Long): String = LabSchema.formatUtc(millis).substring(11, 19)

    private fun utc(millis: Long): String = LabSchema.formatUtc(millis).substring(0, 19)

    /** A table's cell: no line breaks, no pipes. */
    private fun String.cell(): String = replace('\n', ' ').replace('\r', ' ').replace("|", "/")
}
