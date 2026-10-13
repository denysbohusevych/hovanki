package app.hovanki.perf

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.atomic.LongAdder

/**
 * The ladder's judge (docs/perf.md): the syncs of the last seconds, every player's together. The server keeps up while at
 * most [SLOW_PERCENT] % of them take longer than [targetMillis] (the p95 is within it) and at most [FAILED_PERCENT] %
 * fail; [breakdown] says, from [fromMillis] on, when a window breaks either, and at how many players.
 */
class LoadWatch(private val targetMillis: Long, private val fromMillis: Long) {
    /** Players in the game's sync loop now. */
    val players = AtomicInteger()

    private class Second(val epoch: Long) {
        val all = LongAdder()
        val slow = LongAdder()
        val failed = LongAdder()
    }

    private val seconds = AtomicReferenceArray<Second>(SLOTS)

    fun record(tookMillis: Long, failed: Boolean, nowMillis: Long) {
        val second = second(nowMillis / 1000) ?: return
        second.all.increment()
        if (tookMillis > targetMillis) second.slow.increment()
        if (failed) second.failed.increment()
    }

    /** Why the ladder stops now, or null while the server keeps up: the [WINDOW_SECONDS] whole seconds before now. */
    fun breakdown(nowMillis: Long): String? {
        if (nowMillis < fromMillis) return null
        val now = nowMillis / 1000
        var all = 0L
        var slow = 0L
        var failed = 0L
        for (epoch in now - WINDOW_SECONDS until now) {
            val second = seconds[slot(epoch)]?.takeIf { it.epoch == epoch } ?: continue
            all += second.all.sum()
            slow += second.slow.sum()
            failed += second.failed.sum()
        }
        if (all < MIN_SYNCS) return null
        val at = "at ${players.get()} players (${all / WINDOW_SECONDS} syncs/s)"
        return when {
            failed * 100 > all * FAILED_PERCENT ->
                "$at ${percent(failed, all)} of the syncs failed in the last $WINDOW_SECONDS s"
            slow * 100 > all * SLOW_PERCENT ->
                "$at the sync's p95 is over $targetMillis ms: ${percent(slow, all)} were slower in the last " +
                    "$WINDOW_SECONDS s"
            else -> null
        }
    }

    /** The counters of [epoch]: a slot of an older second is taken over, a record late for its second is dropped. */
    private fun second(epoch: Long): Second? {
        val slot = slot(epoch)
        while (true) {
            val current = seconds[slot]
            when {
                current != null && current.epoch == epoch -> return current
                current != null && current.epoch > epoch -> return null
                else -> Second(epoch).let { fresh -> if (seconds.compareAndSet(slot, current, fresh)) return fresh }
            }
        }
    }

    private fun slot(epoch: Long): Int = (epoch % SLOTS).toInt()

    private fun percent(part: Long, all: Long): String = "%.1f %%".format(part * 100.0 / all)

    private companion object {
        const val SLOTS = 64
        const val WINDOW_SECONDS = 10
        const val MIN_SYNCS = 100
        const val SLOW_PERCENT = 5
        const val FAILED_PERCENT = 1
    }
}
