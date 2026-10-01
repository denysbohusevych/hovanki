package app.hovanki.e2e.scenarios

import app.hovanki.client.lab.FieldStatus
import app.hovanki.e2e.OWN_SERVER
import app.hovanki.e2e.admin.StaffConsole
import app.hovanki.e2e.bot.BotPlayer
import app.hovanki.e2e.route.Route
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.e2e.scenario.Scenario
import app.hovanki.e2e.scenarioOnOwnServer
import app.hovanki.shared.lab.FieldKinds
import app.hovanki.shared.lab.LabFields
import app.hovanki.shared.lab.LabSchema
import app.hovanki.shared.lab.MarkFields
import app.hovanki.shared.lab.RxFields
import app.hovanki.shared.lab.SyncFields
import app.hovanki.shared.lab.UiFields
import app.hovanki.shared.protocol.AdminLabRun
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.LabRunKind
import app.hovanki.shared.protocol.LabRunStatus
import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.protocol.ServerFeature
import app.hovanki.shared.protocol.UserRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.parallel.ResourceLock
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The field log (docs/adr/0018-field-test-build.md §3, docs/field-test.md step 2): six testers' phones of the field
 * build play a game with the radar on a server with FIELD_LOG; each joins the game's run when the round starts and
 * uploads its log: the GPS with coordinates, the radio thinned, the syncs, and a player's «something is wrong». The
 * admin finds the game's run and its raw logs; coordinates are only in the `gps` events. With the switch off nobody
 * joins anything. The privacy audit of every game response stays green.
 */
class FieldLogTest {
    @Test
    fun sixTestersWriteTheGamesFieldLog() = scenario("The field log", timeout = 4.minutes) {
        enableAllFeatures()
        StaffConsole(serverUrl, observer).use { console ->
            logsInAsAdmin(console)
            val sam = player("Sam", at = PARK, fieldLog = true)
            val hiders = listOf(
                player("Anna", at = PARK.offset(eastMeters = 40.0), fieldLog = true),
                player("Bob", at = PARK.offset(northMeters = 40.0), platform = Platform.IOS, fieldLog = true),
                player("Cora", at = PARK.offset(eastMeters = -40.0), fieldLog = true),
                player("Dan", at = PARK.offset(northMeters = -40.0), fieldLog = true),
                player("Eve", at = PARK.offset(eastMeters = 80.0), platform = Platform.IOS, fieldLog = true),
            )
            val anna = hiders.first()
            val everybody = listOf(sam) + hiders

            sam.createsGame(GameSetups.radar())
            join(*hiders.toTypedArray())
            check(everybody.all { it.fieldState.status == FieldStatus.OFF }, "nobody logs in the lobby")
            check(
                console.labRuns().runs.none { it.gameId == gameId.value },
                "no run for the game before its round starts",
            )
            sam.startsGame(seekers = listOf(sam))
            awaitPhase(GamePhase.SEEKING, within = 20.seconds)
            for (phone in everybody) {
                eventually("${phone.name}'s field log is on", within = 20.seconds) {
                    phone.takeIf { it.fieldState.status == FieldStatus.ON }
                }
            }
            val run = gameRun(console)
            check(everybody.all { it.fieldState.runId == run.id }, "every phone is in the game's one run")

            // Sam comes close to Anna: the radio hears her. Anna marks that something is wrong.
            sam.walksToAndArrives(anna.gps.truePosition.offset(eastMeters = 2.0), speed = Route.RUNNING)
            check(anna.marksSomethingWrong("radar silent"), "Anna's phone logs her mark")
            // Sam's chat goes into his log as an action, never as a text.
            sam.sendChat("a secret text")

            // Every 10 s the phones upload: all six reach the server.
            val view = eventually("every phone's log reached the server", within = 40.seconds) {
                console.labRun(run.id).takeIf { view ->
                    view.devices.size == everybody.size && view.devices.all { it.events > 0 } &&
                        view.devices.any { device -> device.label == anna.id.value && device.events > 20 }
                }
            }
            check(view.run.kind == LabRunKind.GAME && view.run.status == LabRunStatus.RUNNING, "the game's run is on")
            check(view.devices.map { it.label }.toSet() == everybody.map { it.id.value }.toSet(), "labelled by player")
            check(view.devices.all { it.consentAtMillis != null }, "every phone's tester agreed")

            // Anna's mark is in her raw log, as soon as her next upload is.
            val logs = eventually("Anna's mark is in the raw logs", within = 30.seconds) {
                rawLogs(console, run).takeIf { logs ->
                    logs.getValue(anna).any {
                        it.kind == FieldKinds.MARK && it.text(MarkFields.TEXT) == "radar silent"
                    } &&
                        logs.getValue(sam).any { it.text(UiFields.ACTION) == "chat_send" }
                }
            }
            checkLogs(logs, sam, anna)
        }
    }

    @Test
    @ResourceLock(OWN_SERVER)
    fun withTheSwitchOffNobodyLogs() = scenarioOnOwnServer("The field log off", properties = emptyMap()) {
        StaffConsole(serverUrl, observer).use { console ->
            logsInAsAdmin(console)
            val sam = player("Sam", at = PARK, fieldLog = true)
            val anna = player("Anna", at = PARK.offset(eastMeters = 40.0), fieldLog = true)
            sam.createsGame(GameSetups.fast())
            join(anna)
            sam.startsGame(seekers = listOf(sam))
            awaitPhase(GamePhase.HIDING, within = 10.seconds)
            for (phone in listOf(sam, anna)) {
                eventually("${phone.name}'s phone finds no field log", within = 20.seconds) {
                    phone.takeIf { it.fieldState.status == FieldStatus.REFUSED }
                }
            }
            check(console.labRuns().runs.none { it.kind == LabRunKind.GAME }, "no game's run on the server")
            // The game itself goes on as ever.
            awaitPhase(GamePhase.SEEKING, within = 20.seconds)
            check(anna.fieldState.status == FieldStatus.REFUSED, "and the phone doesn't ask again in this game")
        }
    }

    /** A person who signs up, is made an admin on the server and logs in to the admin; never a player. */
    private suspend fun Scenario.logsInAsAdmin(console: StaffConsole) {
        val boss = player("Boss", at = PARK)
        val account = boss.signsUp()
        boss.confirmsEmail()
        observer.setRole(checkNotNull(boss.userId), UserRole.ADMIN)
        check(console.logIn(account).role == UserRole.ADMIN, "Boss logs in to the admin as an admin")
    }

    private suspend fun Scenario.gameRun(console: StaffConsole): AdminLabRun = eventually("the game's run") {
        console.labRuns().runs.firstOrNull { it.gameId == gameId.value }
    }

    /** Every player's raw log, from the admin's zip (one file per device, named by the player's id). */
    private suspend fun Scenario.rawLogs(console: StaffConsole, run: AdminLabRun): Map<BotPlayer, List<JsonObject>> {
        val files = zipEntries(console.downloadLabRaw(run.id, reason = "the e2e field log"))
        return players.filter { it.fieldLog }.associateWith { phone ->
            files.entries.filter { it.key.startsWith("hovanki-lab-${phone.id.value}-") }
                .flatMap { it.value.decodeToString().lines() }
                .filter { it.isNotBlank() }
                .map { Json.parseToJsonElement(it).jsonObject }
        }
    }

    /**
     * Every phone's log has fixes with coordinates (and only `gps` has them), its syncs and its ticks; the seeker
     * heard the hider he ran to (one event a second, with the count); the run's id in every event.
     */
    private fun Scenario.checkLogs(logs: Map<BotPlayer, List<JsonObject>>, seeker: BotPlayer, hider: BotPlayer) {
        val run = logs.values.flatten().map { it.text(LabFields.RUN) }.toSet()
        check(run.size == 1 && run.single() != null, "one run in every event ($run)")
        for ((phone, events) in logs) {
            check(events.isNotEmpty(), "${phone.name} uploaded a log")
            val kinds = events.map { it.kind }.toSet()
            for (kind in listOf(FieldKinds.SESSION, FieldKinds.GPS, FieldKinds.SYNC, FieldKinds.TICK)) {
                check(kind in kinds, "${phone.name}'s log has $kind ($kinds)")
            }
            check(events.filter { it.kind == FieldKinds.GPS }.all(LabSchema::hasCoordinates), "${phone.name}: where")
            check(
                events.any { it.kind == FieldKinds.SYNC && it.text(SyncFields.BYTES) != null },
                "${phone.name}: a sync with the size of its answer",
            )
            val elsewhere = events.filter { it.kind != FieldKinds.GPS && LabSchema.hasCoordinates(it) }
            check(elsewhere.isEmpty(), "${phone.name}: coordinates only in gps (${elsewhere.map { it.kind }})")
        }
        val taps = logs.getValue(seeker).filter { it.kind == FieldKinds.UI && it.text(UiFields.EVENT) == UiFields.TAP }
        check(
            taps.any {
                it.text(UiFields.ACTION) == "chat_send"
            },
            "${seeker.name}'s chat is in his log (${taps.size} taps)",
        )
        check(logs.values.flatten().none { "a secret text" in it.toString() }, "no chat text in any log")
        val heard = logs.getValue(seeker).filter { it.kind == FieldKinds.RX && it.text(RxFields.COUNT) != null }
        check(heard.isNotEmpty(), "${seeker.name}'s radio heard ${hider.name}, thinned to a second")
    }

    private fun zipEntries(zip: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(zip)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                put(entry.name, input.readBytes())
            }
        }
    }

    private val JsonObject.kind: String get() = text(LabFields.K).orEmpty()

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content
}
