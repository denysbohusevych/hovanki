package app.hovanki.shared.lab

/**
 * Feeds a [FieldReportBuilder] a time window at a time from the logs of a game's devices (docs/field-test.md step 6):
 * every device's events are read lazily, in their order, and a window takes from each the events before its end, so
 * no more than a window's events (and the builder's numbers) are ever in memory, whatever the game's length. Windows
 * are aligned to [windowMillis] on the server's clock; an event that comes after its window was done (a phone that
 * uploaded late) goes into the next one. [range]: the events outside it on the server's clock are left out (a log's
 * clock far off can't stretch the game), counted in [outside].
 *
 * Two ways to feed it: [add] a device's whole log as a lazy sequence and [finish] (the full report, the digest), or
 * [feed] the new lines of the devices as they arrive and [advance] to a moment (the live report).
 */
class FieldReportStream(
    private val builder: FieldReportBuilder,
    private val windowMillis: Long = FieldReportBuilder.WINDOW_MILLIS,
    private val range: LongRange? = null,
) {
    init {
        require(windowMillis in 1_000L..MAX_WINDOW) { "A window of $windowMillis ms" }
    }

    private val pool = LabEvents.Pool()
    private val sources = LinkedHashMap<String, Source>()

    /** Where the next window starts at the earliest: the end of the last one. */
    private var nextStart = Long.MIN_VALUE

    var windows = 0
        private set
    var outside = 0L
        private set
    var badLines = 0L
        private set

    /** A device's whole log, read as the windows need it. [key]: the device (its id); [label]: the player, `server`. */
    fun add(key: String, label: String, lines: Sequence<String>) {
        check(key !in sources) { "Device $key added twice" }
        val events = LabEvents.stream(lines, label, pool, bad = { badLines += it })
        sources[key] = Source(events.iterator())
    }

    /**
     * New lines of a device's log (the live report): its events wait for the windows. [clockKnown]: the device's
     * earlier lines measured its clock already, so these are on the server's clock as written.
     */
    fun feed(key: String, label: String, lines: Sequence<String>) {
        val source = sources.getOrPut(key) { Source(null) }
        val events = LabEvents.stream(
            lines,
            label,
            pool,
            bad = { badLines += it },
            maxBeforeClock = if (source.clockKnown) 0 else LabEvents.MAX_BEFORE_CLOCK,
        )
        for (event in events) {
            if (event.k == "clock" && event.fields["offset"] != null) source.clockKnown = true
            source.queue.addLast(event)
        }
        // A log that has not measured its clock for a while goes on with its own times.
        if (source.queue.size >= LabEvents.MAX_BEFORE_CLOCK) source.clockKnown = true
    }

    /** Every window that ends by [untilMillis] (the live report: the moment all phones' uploads are in by). */
    fun advance(untilMillis: Long) = run(untilMillis)

    /** Every window, to the end of every log. */
    fun finish() = run(null)

    private fun run(until: Long?) {
        while (true) {
            var first = Long.MAX_VALUE
            for (source in sources.values) {
                val head = source.peek() ?: continue
                if (head.t < first) first = head.t
            }
            if (first == Long.MAX_VALUE) return
            val aligned = first.floorDiv(windowMillis) * windowMillis
            val start = if (nextStart == Long.MIN_VALUE) aligned else maxOf(aligned, nextStart)
            val end = start + windowMillis
            if (until != null && end > until) return
            val events = ArrayList<LabEvent>()
            for (source in sources.values) {
                while (true) {
                    val head = source.peek() ?: break
                    if (head.t >= end) break
                    events += source.take()
                }
            }
            events.sortWith(ORDER)
            builder.window(start, end, events)
            windows++
            nextStart = end
        }
    }

    /** A device's events: from its lazy log, or the lines fed so far; the ones outside [range] dropped. */
    private inner class Source(private val iterator: Iterator<LabEvent>?) {
        val queue = ArrayDeque<LabEvent>()
        var clockKnown = false

        fun peek(): LabEvent? {
            while (true) {
                if (queue.isEmpty()) {
                    if (iterator == null || !iterator.hasNext()) return null
                    queue.addLast(iterator.next())
                }
                val head = queue.first()
                if (range == null || head.t in range) return head
                queue.removeFirst()
                outside++
            }
        }

        fun take(): LabEvent = queue.removeFirst()
    }

    companion object {
        /** The whole game in one window: the same numbers as the windows give (the tests). */
        const val MAX_WINDOW = Long.MAX_VALUE / 4

        private val ORDER = compareBy<LabEvent>({ it.t }, { it.dev }, { it.mono })
    }
}
