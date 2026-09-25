package app.hovanki.client.network

import app.hovanki.shared.protocol.LocationSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/**
 * Location samples waiting to be sent with the next sync. Thread-safe: the location callback adds,
 * the connection drains. Only the newest [capacity] samples are kept (the server accepts at most 100 per sync,
 * and after a long offline stretch the recent positions matter most).
 */
class LocationOutbox(private val capacity: Int = DEFAULT_CAPACITY) {
    private val samples = MutableStateFlow<List<LocationSample>>(emptyList())

    val size: Int get() = samples.value.size

    fun add(sample: LocationSample) {
        samples.update { (it + sample).takeLast(capacity) }
    }

    /** Takes everything queued so far. */
    fun drain(): List<LocationSample> = samples.getAndUpdate { emptyList() }

    /** Puts back samples whose upload failed, in front of the ones queued meanwhile. */
    fun requeue(failed: List<LocationSample>) {
        if (failed.isEmpty()) return
        samples.update { (failed + it).takeLast(capacity) }
    }

    fun clear() {
        samples.value = emptyList()
    }

    companion object {
        const val DEFAULT_CAPACITY = 100
    }
}
