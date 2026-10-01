package app.hovanki.e2e.scenarios

import app.hovanki.client.network.ApiException
import app.hovanki.e2e.OWN_SERVER
import app.hovanki.e2e.admin.AdminRejected
import app.hovanki.e2e.admin.StaffConsole
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.e2e.scenario.Scenario
import app.hovanki.e2e.scenarioOnOwnServer
import app.hovanki.shared.lab.LabJoinCode
import app.hovanki.shared.lab.LabReport
import app.hovanki.shared.lab.LabRunScripts
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.LabRunAction
import app.hovanki.shared.protocol.LabRunId
import app.hovanki.shared.protocol.LabRunStatus
import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.protocol.ServerFeature
import app.hovanki.shared.protocol.UserRole
import org.junit.jupiter.api.parallel.ResourceLock
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The radio lab's run on the server (docs/adr/0017-radar-techniques-and-big-run.md §5, docs/radar-run.md step 1): an
 * admin creates a run of the e2e plan in the admin, three lab phones join it by its code, the admin starts it and the
 * phones follow its timed steps by the server's clock, uploading their logs as they go; the plan ends by itself, the
 * report says who heard whom in every step, and the raw logs come as a zip. With the operator's switch off the phones
 * find no run at all.
 */
class LabRunTest {
    @Test
    fun threePhonesFollowTheAdminsRun() = scenario("The radio lab's run", timeout = 4.minutes) {
        // The shared server: this lab's switch on, every other feature as the other scenarios have it.
        observer.enableFeatures(listOf(ServerFeature.RADIO_LAB.name))
        StaffConsole(serverUrl, observer).use { console ->
            logsInAsAdmin(console)
            val script = LabRunScripts.E2E
            val run = console.createLabRun("Three bots", script.id, reason = "the e2e lab run")
            check(run.status == LabRunStatus.CREATED && run.scenarioVersion == script.version, "a new run, not started")

            val a = labPhone("A", at = PARK, platform = Platform.IOS)
            val b = labPhone("B", at = PARK.offset(eastMeters = 2.0), platform = Platform.IOS)
            val droid = labPhone("droid", at = PARK.offset(northMeters = 2.0), platform = Platform.ANDROID)
            val phones = listOf(a, b, droid)

            val wrong = generateSequence { LabJoinCode.random(Random) }.first { it != run.code }
            val refused = runCatching { a.joinRun(wrong) }.exceptionOrNull()
            check((refused as? ApiException)?.status == 404, "a wrong code finds no run (got $refused)")

            // The code as the QR gives it, and typed by hand with a dash.
            a.joinRun(LabJoinCode.qrPayload(run.code))
            b.joinRun(run.code.lowercase())
            droid.joinRun("${run.code.take(3)}-${run.code.drop(3)}")
            val tokens = phones.map { checkNotNull(it.follow.value).radarToken }
            check(tokens.distinct().size == 3, "every phone got a radar token of its own")
            check(phones.all { it.follow.value?.plan?.status == LabRunStatus.CREATED }, "the run waits for the admin")

            val started = console.advanceLabRun(run.id, LabRunAction.NEXT, reason = "go")
            check(started.state.status == LabRunStatus.RUNNING && started.state.stepIndex == 0, "the admin starts it")
            check(started.devices.map { it.label }.sorted() == script.labels.sorted(), "the console lists the phones")
            for (phone in phones) {
                eventually("${phone.label} is in the first step", within = 10.seconds) {
                    phone.follow.value?.takeIf { it.step?.id == "all_hiders" }
                }
            }
            eventually("the live view shows a pair heard", within = 15.seconds) {
                console.labRun(run.id).live.pairs.firstOrNull { it.heardInLast10s > 0 && !it.from.startsWith("?") }
            }

            // The plan is 22 s of timed steps: it ends by itself, and the phones send the rest of their logs.
            eventually("the run is over by its plan", within = 45.seconds) {
                console.labRun(run.id).takeIf { it.state.status == LabRunStatus.FINISHED }
            }
            for (phone in phones) {
                eventually("${phone.label} saw the end and uploaded everything", within = 45.seconds) {
                    phone.takeIf {
                        it.follow.value?.finished == true && !it.isRecording && it.uploadPending.value == 0L
                    }
                }
            }
            val view = console.labRun(run.id)
            check(view.devices.size == 3, "three devices in the run")
            check(view.devices.all { it.events > 0 && it.bytes > 0 && it.lastSeq != null }, "every phone's log arrived")

            val finished = console.finishLabRun(run.id, reason = "the report with every upload")
            check(finished.state.status == LabRunStatus.FINISHED, "finishing a run over changes nothing but the report")
            val report = eventually("the report is computed", within = 30.seconds) {
                reportOrNull(console, run.id)?.takeIf { it.computedAtMillis >= finished.state.serverTimeMillis }
            }
            checkReport(report, labels = script.labels)

            val zip = console.downloadLabRaw(run.id, reason = "a look at the raw logs")
            val entries = zipEntries(zip)
            check(entries.size == 3, "the raw logs: one file per phone (${entries.keys})")
            for (label in script.labels) {
                check(entries.keys.any { it.startsWith("hovanki-lab-$label-") }, "the raw logs have $label's")
            }
            check(entries.values.all { it.isNotEmpty() }, "no empty log")
            // The radio's own journal (docs/adr/0017-radar-techniques-and-big-run.md §4): the advertisement with its
            // channels and bytes (the Android hider's fits: `.scan_response`), and frames of ours whole.
            val droidLog = entries.entries.single { it.key.startsWith("hovanki-lab-droid-") }.value.decodeToString()
            check(
                droidLog.lines().any {
                    "\"k\":\"adv\"" in it && "\"layout\":\"scan_response\"" in it &&
                        "\"bytes\":18" in it
                },
                "the Android hider's advertisement says its layout and bytes",
            )
            check(entries.values.all { "\"k\":\"frame\"" in it.decodeToString() }, "every phone wrote frames of ours")

            phones.forEach { it.leave() }
            console.deleteLabRun(run.id, reason = "the test is over")
            val gone = runCatching { console.labRun(run.id) }.exceptionOrNull()
            check((gone as? AdminRejected)?.status == 404, "the deleted run is gone (got $gone)")
        }
    }

    @Test
    @ResourceLock(OWN_SERVER)
    fun thePhonesFindNoRunWhileTheLabIsOff() = scenarioOnOwnServer("The radio lab off", properties = emptyMap()) {
        StaffConsole(serverUrl, observer).use { console ->
            logsInAsAdmin(console)
            // The admin works whether the lab is on or off: the operator prepares a run, then switches it on.
            val run = console.createLabRun("Off", LabRunScripts.E2E.id, reason = "before the switch")
            val phone = labPhone("A", at = PARK, platform = Platform.IOS)
            val refused = runCatching { phone.joinRun(run.code) }.exceptionOrNull() as? ApiException
            check(refused?.status == 404, "the lab off: no run for the phone (got $refused)")
            check(refused?.error?.code == ErrorCode.NOT_FOUND, "the lab off answers as not found")

            observer.enableFeatures(listOf(ServerFeature.RADIO_LAB.name))
            phone.joinRun(run.code)
            check(phone.follow.value?.plan?.status == LabRunStatus.CREATED, "the lab on: the phone is in the run")
            phone.leave()
        }
    }

    /** A person who signs up, confirms the email, is made an admin on the server and logs in to the admin. */
    private suspend fun Scenario.logsInAsAdmin(console: StaffConsole) {
        val sam = player("Sam", at = PARK)
        val account = sam.signsUp()
        sam.confirmsEmail()
        observer.setRole(checkNotNull(sam.userId), UserRole.ADMIN)
        check(console.logIn(account).role == UserRole.ADMIN, "Sam logs in to the admin as an admin")
    }

    private suspend fun reportOrNull(console: StaffConsole, id: LabRunId): LabReport? = try {
        console.labReport(id)
    } catch (e: AdminRejected) {
        if (e.status == 404) null else throw e
    }

    /**
     * Everybody in the hand 2 m apart: in the first step every phone heard every other about once a second (the
     * [app.hovanki.e2e.bot.RadioWorld] ticks every second), and every sender is known by its token.
     */
    private fun Scenario.checkReport(report: LabReport, labels: List<String>) {
        check(report.devices.map { it.label }.sorted() == labels.sorted(), "the report has the three phones")
        val first = report.steps.filter { it.id == "all_hiders" }
        check(first.isNotEmpty(), "the report has the step all_hiders (${report.steps.map { it.id }})")
        for (from in labels) {
            for (to in labels - from) {
                val best = first.flatMap { it.directions }.filter { it.from == from && it.to == to }
                    .maxOfOrNull { it.perSecond } ?: 0.0
                check(best >= 0.5, "$to heard $from in all_hiders: ${"%.2f".format(best)} readings a second")
            }
        }
        val unknown = report.steps.flatMap { it.directions }.filter { it.from.startsWith("?") }
        check(unknown.isEmpty(), "every sender is known by its token (${unknown.map { it.from }.distinct()})")
    }

    private fun zipEntries(zip: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(zip)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                put(entry.name, input.readBytes())
            }
        }
    }
}
