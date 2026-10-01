package app.hovanki.server.lab

import app.hovanki.server.game.FieldEvent
import app.hovanki.server.game.FieldEvents
import app.hovanki.shared.lab.FieldKinds
import app.hovanki.shared.lab.LabFields
import app.hovanki.shared.lab.LabSchema
import app.hovanki.shared.lab.SrvFields
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.LabUpload
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Writes the server's own events into the field runs of its games (docs/adr/0018-field-test-build.md §3.3,
 * docs/field-test.md step 3) as one more device of each run, [FieldKinds.SERVER_DEVICE], in the phones' JSONL (schema
 * 2: `t` on the server's clock, `k`, `dev`, `seq`…), so `LabMerge` puts them on the same timeline.
 *
 * [app.hovanki.server.game.GameService] hands it what a game collected ([FieldEvents]) after the game's lock; this
 * never waits: the events go into a bounded queue, and a full queue drops them and counts them
 * ([DROPPED_COUNTER], and [SrvFields.DROPPED] in the next `srv`). One thread of its own takes them in order and puts
 * them into the database every [flushMillis] ([FieldLogStore]): a game's events wait in memory until its run exists
 * (the first phone opens it, [FieldRunService.join]), at most [MAX_WAITING] of them. A game gone from memory is
 * forgotten ([forget]) after its last events are written. A database that is down costs the events that were waiting
 * when there were too many, never a request.
 */
class FieldEventWriter(
    private val store: FieldLogStore,
    meters: MeterRegistry,
    queueSize: Int,
    private val flushMillis: Long,
    start: Boolean = true,
) : DisposableBean {
    private val log = LoggerFactory.getLogger(javaClass)
    private val queue = LinkedBlockingQueue<Item>(queueSize)
    private val droppedCounter = Counter.builder(DROPPED_COUNTER)
        .description("The server's field log events dropped: the queue to the database was full, or the run was")
        .register(meters)
    private val unloggedCounter = Counter.builder(UNLOGGED_COUNTER)
        .description("The server's field log events of games no phone logs (yet), beyond the first ones kept")
        .register(meters)
    private val droppedSinceTaken = AtomicLong()

    /** The worker's own: never touched by another thread. */
    private val games = LinkedHashMap<String, GameLog>()

    /** The flushes go by the process's own clock: the injected one may stand still in tests. */
    private var lastFlushNanos = System.nanoTime()

    @Volatile
    private var running = true
    private val worker = if (start) thread(isDaemon = true, name = "field-events") { loop() } else null

    /** [gameId]'s events, collected under its lock ([app.hovanki.server.game.Game.takeFieldEvents]). Never waits. */
    fun add(gameId: GameId, events: FieldEvents) {
        if (events.dropped > 0) dropped(events.dropped.toLong())
        if (events.list.isEmpty()) return
        if (!queue.offer(Item.Events(gameId.value, events.list.toList()))) dropped(events.list.size.toLong())
    }

    /** One `srv` event, the server's numbers, into every game's run that exists ([FieldServerSampler]). */
    fun srv(atMillis: Long, fields: JsonObject) {
        if (!queue.offer(Item.Srv(atMillis, fields))) dropped(1)
    }

    /** The games no longer [isLive] are written out and forgotten (the janitor's sweep). */
    fun forget(isLive: (GameId) -> Boolean) {
        queue.offer(Item.Forget(isLive))
    }

    /** The events dropped since the last call, for the next `srv`. */
    fun takeDropped(): Long = droppedSinceTaken.getAndSet(0)

    /** Waits until everything handed over so far is in the store (tests). */
    fun awaitIdle(timeoutMillis: Long = 10_000) {
        val done = CountDownLatch(1)
        check(queue.offer(Item.Flush(done), timeoutMillis, TimeUnit.MILLISECONDS)) { "Field events queue full" }
        if (worker == null) drain()
        check(done.await(timeoutMillis, TimeUnit.MILLISECONDS)) { "Field events not written within $timeoutMillis ms" }
    }

    /** Handles whatever is queued now, on the caller's thread: only a writer made without its thread (tests). */
    internal fun drain() {
        check(worker == null) { "The writer has its own thread" }
        while (true) handle(queue.poll() ?: break)
    }

    private fun loop() {
        while (running || queue.isNotEmpty()) {
            try {
                val item = queue.poll(flushMillis, TimeUnit.MILLISECONDS)
                if (item != null) handle(item)
                if (System.nanoTime() - lastFlushNanos >= flushMillis * 1_000_000) flushAll()
            } catch (e: InterruptedException) {
                running = false
            } catch (e: Exception) {
                // Only the kind: a database's message may quote a row.
                log.warn("Field log: the server's events failed: {}", e.javaClass.simpleName)
            }
        }
        runCatching { flushAll() }
    }

    private fun handle(item: Item) {
        when (item) {
            is Item.Events -> {
                val game = games.getOrPut(item.gameId) { GameLog(item.gameId) }
                game.keep(item.events)
            }

            is Item.Srv -> {
                val event = FieldEvent(item.atMillis, FieldKinds.SRV, item.fields)
                for (game in games.values) {
                    if (game.closed) continue
                    // A game whose run a phone opened since the last flush gets it too.
                    if (game.runId == null) game.runId = runCatching { store.runOf(game.gameId) }.getOrNull()
                    if (game.runId != null) game.keep(listOf(event))
                }
            }

            is Item.Forget -> {
                val gone = games.values.filter { !item.isLive(GameId(it.gameId)) }
                for (game in gone) {
                    runCatching { flush(game) }
                    if (game.pending.isNotEmpty()) dropped(game.pending.size.toLong())
                    games.remove(game.gameId)
                }
            }

            is Item.Flush -> {
                flushAll()
                item.done.countDown()
            }
        }
    }

    private fun flushAll() {
        lastFlushNanos = System.nanoTime()
        for (game in games.values) {
            try {
                flush(game)
            } catch (e: Exception) {
                log.warn(
                    "Field log: could not write the server's events of game {}: {}",
                    game.gameId,
                    e.javaClass.simpleName,
                )
            }
        }
    }

    /** [game]'s waiting events into its run, once it has one; the header (`session`, `clock`) first. */
    private fun flush(game: GameLog) {
        if (game.pending.isEmpty()) return
        if (game.closed) {
            dropped(game.pending.size.toLong())
            game.pending.clear()
            return
        }
        val runId = game.runId ?: store.runOf(game.gameId)?.also { game.runId = it } ?: return
        val deviceId = game.deviceId ?: store.addServerDevice(runId)?.also { game.deviceId = it }
        if (deviceId == null) {
            game.close()
            return
        }
        val header = if (game.headerWritten) emptyList() else header(game.pending.first().atMillis)
        val events = header + game.pending
        for ((index, part) in events.chunked(LabUpload.MAX_EVENTS).withIndex()) {
            val seqFrom = game.nextSeq
            val lines = part.mapIndexed { offset, event -> line(event, runId, seqFrom + offset) }
            val seqTo = seqFrom + lines.size - 1
            when (store.append(runId, deviceId, lines, seqFrom, seqTo)) {
                FieldLogStore.Append.STORED -> {
                    game.nextSeq = seqTo + 1
                    game.headerWritten = true
                    // Only what went in leaves the waiting list (the header is in the first part): a failure keeps the
                    // rest for the next flush, under the same numbers.
                    game.pending.subList(0, part.size - if (index == 0) header.size else 0).clear()
                }

                FieldLogStore.Append.CLOSED -> {
                    game.close()
                    return
                }

                FieldLogStore.Append.FULL -> {
                    dropped(game.pending.size.toLong())
                    game.pending.clear()
                    return
                }
            }
        }
    }

    private fun header(atMillis: Long): List<FieldEvent> = listOf(
        FieldEvent(
            atMillis,
            "session",
            buildJsonObject {
                put("schema", LabSchema.VERSION)
                put("model", FieldKinds.SERVER_DEVICE)
                put("os", "JVM ${Runtime.version().feature()}")
                put("mode", "field")
                put("label", FieldKinds.SERVER_DEVICE)
            },
        ),
        // The server's clock is the log's: its offset is nothing.
        FieldEvent(
            atMillis,
            "clock",
            buildJsonObject {
                put("offset", 0)
                put("rtt", 0)
                put("samples", 1)
            },
        ),
    )

    private fun line(event: FieldEvent, runId: String, seq: Long): JsonObject = buildJsonObject {
        put(LabFields.T, event.atMillis)
        put(LabFields.DT, event.atMillis)
        put(LabFields.MONO, event.atMillis)
        put(LabFields.DEV, FieldKinds.SERVER_DEVICE)
        put(LabFields.K, event.kind)
        put(LabFields.SEQ, seq)
        put(LabFields.RUN, runId)
        for ((key, value) in event.fields) put(key, value)
    }

    private fun dropped(count: Long) {
        droppedCounter.increment(count.toDouble())
        droppedSinceTaken.addAndGet(count)
    }

    /** Lets the queued events be written on shutdown, for a few seconds. */
    override fun destroy() {
        running = false
        worker?.interrupt()
        worker?.join(SHUTDOWN_MILLIS)
    }

    /** A game's part of the log: its run and device once known, the events waiting, the next `seq`. */
    private inner class GameLog(val gameId: String) {
        var runId: String? = null
        var deviceId: String? = null
        var nextSeq = 0L
        var headerWritten = false

        /** The run is over: whatever comes is dropped. */
        var closed = false
            private set
        val pending = ArrayList<FieldEvent>()

        /**
         * Keeps [events] until they are written: with a run, up to [MAX_WAITING], the rest is dropped and counted;
         * without one yet (nobody logs the game, or not yet), the first [MAX_BEFORE_RUN] (the round's start), the rest
         * only counted as [UNLOGGED_COUNTER]: a game nobody logs is no loss.
         */
        fun keep(events: List<FieldEvent>) {
            val limit = if (runId == null) MAX_BEFORE_RUN else MAX_WAITING
            val room = (limit - pending.size).coerceAtLeast(0)
            if (events.size > room) {
                val lost = (events.size - room).toLong()
                if (runId == null) unloggedCounter.increment(lost.toDouble()) else dropped(lost)
            }
            if (room > 0) pending += events.take(room)
        }

        fun close() {
            closed = true
            if (pending.isNotEmpty()) dropped(pending.size.toLong())
            pending.clear()
        }
    }

    private sealed interface Item {
        class Events(val gameId: String, val events: List<FieldEvent>) : Item

        class Srv(val atMillis: Long, val fields: JsonObject) : Item

        class Forget(val isLive: (GameId) -> Boolean) : Item

        class Flush(val done: CountDownLatch) : Item
    }

    companion object {
        const val DROPPED_COUNTER = "hovanki.field.events.dropped"
        const val UNLOGGED_COUNTER = "hovanki.field.events.unlogged"

        /** A game's events waiting for the database, at most. */
        const val MAX_WAITING = 20_000

        /** A game's events waiting for its run, at most: its first phone joins with the round's start. */
        const val MAX_BEFORE_RUN = 2_000
        private const val SHUTDOWN_MILLIS = 10_000L
    }
}

/** The [FieldEventWriter] of the server, with its thread. */
@Configuration(proxyBeanMethods = false)
class FieldEventWriterConfig {
    @Bean
    fun fieldEventWriter(store: FieldLogStore, meters: MeterRegistry, properties: FieldProperties) =
        FieldEventWriter(store, meters, properties.serverQueue, properties.serverFlush.toMillis())
}
