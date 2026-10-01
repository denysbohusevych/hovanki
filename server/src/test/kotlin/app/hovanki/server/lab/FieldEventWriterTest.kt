package app.hovanki.server.lab

import app.hovanki.server.game.FieldEvents
import app.hovanki.shared.lab.FieldKinds
import app.hovanki.shared.lab.LabFields
import app.hovanki.shared.lab.ServerKinds
import app.hovanki.shared.protocol.GameId
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The server's events into the games' field runs ([FieldEventWriter], docs/field-test.md step 3). */
class FieldEventWriterTest {
    private val meters = SimpleMeterRegistry()
    private val store = FakeStore()
    private val game = GameId("g1")

    /** A writer without its thread: the test drains it. */
    private fun writer(queue: Int = 100) = FieldEventWriter(store, meters, queue, flushMillis = 1_000, start = false)

    private fun events(vararg kinds: String, at: Long = 1_000): FieldEvents = FieldEvents().apply {
        kinds.forEachIndexed { index, kind -> add(at + index, kind) { put("n", index) } }
    }

    private fun dropped(): Double = meters.counter(FieldEventWriter.DROPPED_COUNTER).count()

    @Test
    fun eventsWaitForTheRunThenGoInOrderWithTheirSeq() {
        val writer = writer()
        writer.add(game, events(ServerKinds.PHASE, ServerKinds.CLAIM))
        writer.awaitIdle()
        assertEquals(emptyList(), store.lines, "no run yet: the events wait")

        store.runs[game.value] = "run1"
        writer.add(game, events(ServerKinds.CATCH, at = 2_000))
        writer.awaitIdle()
        writer.add(game, events(ServerKinds.PHASE, at = 3_000))
        writer.srv(4_000, buildJsonObject { put("games", 1) })
        writer.awaitIdle()

        assertEquals(
            listOf(
                "session",
                "clock",
                ServerKinds.PHASE,
                ServerKinds.CLAIM,
                ServerKinds.CATCH,
                ServerKinds.PHASE,
                "srv",
            ),
            store.lines.map { it.text(LabFields.K) },
        )
        assertEquals((0L until 7L).toList(), store.lines.map { it.long(LabFields.SEQ) }, "seq without a gap")
        assertTrue(
            store.lines.all {
                it.text(LabFields.DEV) == FieldKinds.SERVER_DEVICE &&
                    it.text(LabFields.RUN) == "run1"
            },
        )
        assertEquals(
            listOf(1_000L, 1_000L, 1_000L, 1_001L, 2_000L, 3_000L, 4_000L),
            store.lines.map {
                it.long(LabFields.T)
            },
        )
        assertEquals(1, store.devices.size, "one server device per run")
        // Every chunk's numbers follow the last one's.
        assertEquals(store.chunks.zipWithNext().map { (a, b) -> a.second + 1 }, store.chunks.drop(1).map { it.first })
    }

    @Test
    fun aFullQueueDropsAndCountsNeverWaits() {
        val writer = writer(queue = 2)
        store.runs[game.value] = "run1"
        repeat(5) { writer.add(game, events(ServerKinds.FIXES, ServerKinds.FIXES)) }
        assertEquals(6.0, dropped(), "three batches of two had no room")
        assertEquals(6L, writer.takeDropped())
        assertEquals(0L, writer.takeDropped(), "counted once for the srv")
        writer.drain()
        writer.awaitIdle()
        assertEquals(2 + 4, store.lines.size, "the header and what fitted")
    }

    @Test
    fun aFailedWriteKeepsTheEventsAndTheirNumbers() {
        val writer = writer()
        store.runs[game.value] = "run1"
        store.failing = true
        writer.add(game, events(ServerKinds.PHASE))
        runCatching { writer.awaitIdle() }
        store.failing = false
        writer.add(game, events(ServerKinds.CLAIM))
        writer.awaitIdle()
        assertEquals(
            listOf("session", "clock", ServerKinds.PHASE, ServerKinds.CLAIM),
            store.lines.map {
                it.text(LabFields.K)
            },
        )
        assertEquals(listOf(0L, 1L, 2L, 3L), store.lines.map { it.long(LabFields.SEQ) })
    }

    @Test
    fun aClosedRunTakesNothingMoreAndAGoneGameIsForgotten() {
        val writer = writer()
        store.runs[game.value] = "run1"
        writer.add(game, events(ServerKinds.PHASE))
        writer.awaitIdle()
        store.closed = true
        writer.add(game, events(ServerKinds.CLAIM, ServerKinds.CATCH))
        writer.awaitIdle()
        assertEquals(2.0, dropped())
        writer.forget { false }
        writer.add(GameId("g2"), events(ServerKinds.PHASE))
        writer.srv(5_000, buildJsonObject { put("games", 0) })
        writer.awaitIdle()
        assertEquals(3, store.lines.size, "only the first phase and its header")
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private class FakeStore : FieldLogStore {
        val runs = HashMap<String, String>()
        val devices = ArrayList<String>()
        val lines = ArrayList<JsonObject>()
        val chunks = ArrayList<Pair<Long, Long>>()
        var failing = false
        var closed = false

        override fun runOf(gameId: String): String? = runs[gameId]

        override fun addServerDevice(runId: String): String = "dev${devices.size}".also { devices += it }

        override fun append(
            runId: String,
            deviceId: String,
            events: List<JsonObject>,
            seqFrom: Long,
            seqTo: Long,
        ): FieldLogStore.Append {
            check(!failing) { "The database is down" }
            if (closed) return FieldLogStore.Append.CLOSED
            check(seqTo - seqFrom + 1 == events.size.toLong())
            lines += events
            chunks += seqFrom to seqTo
            return FieldLogStore.Append.STORED
        }
    }
}
