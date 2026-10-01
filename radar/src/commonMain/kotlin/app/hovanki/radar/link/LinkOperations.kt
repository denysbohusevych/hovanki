package app.hovanki.radar.link

/**
 * GATT operations of one connection, one at a time: Android's `BluetoothGatt` drops a read, a write or an RSSI read
 * started while another is waiting for its callback (it returns false), so each waits for the one before. An
 * operation the stack refused to start ([add]'s `start` returned false) goes to [refused] by its name and the next
 * one starts; one without an answer for [timeoutMillis] is given up at the next [tick]. An operation already waiting
 * under the same name is not queued twice (a slow link doesn't pile up writes). Not thread-safe: the caller locks.
 */
internal class LinkOperations(
    private val refused: (String) -> Unit,
    private val timeoutMillis: Long = GattLinkRules.OPERATION_TIMEOUT_MILLIS,
) {
    private class Operation(val name: String, val start: () -> Boolean)

    private val queue = ArrayDeque<Operation>()
    private var current: Operation? = null
    private var startedMillis = 0L

    /** The operation waiting for its callback; null: none. */
    val running: String? get() = current?.name

    val waiting: List<String> get() = queue.map { it.name }

    /** Queues [start] as [name]; starts it at once when nothing runs. False: one of that name is already waiting. */
    fun add(name: String, nowMillis: Long, start: () -> Boolean): Boolean {
        if (queue.any { it.name == name }) return false
        queue.addLast(Operation(name, start))
        if (current == null) next(nowMillis)
        return true
    }

    /** The running operation's callback came: the next one starts. */
    fun done(nowMillis: Long) {
        current = null
        next(nowMillis)
    }

    /** Gives up the running operation if it waited too long; returns its name then. */
    fun tick(nowMillis: Long): String? {
        val running = current ?: return null
        if (nowMillis - startedMillis < timeoutMillis) return null
        done(nowMillis)
        return running.name
    }

    fun clear() {
        queue.clear()
        current = null
    }

    private fun next(nowMillis: Long) {
        while (current == null) {
            val operation = queue.removeFirstOrNull() ?: return
            val started = runCatching { operation.start() }.getOrDefault(false)
            if (started) {
                current = operation
                startedMillis = nowMillis
            } else {
                refused(operation.name)
            }
        }
    }
}
