package app.hovanki.shared.live

import app.hovanki.shared.live.v2.State

/**
 * The server's states of one live session (docs/live-v2/spec.md §8.1–8.2): the ids and digests of the states it sent,
 * the newest one the phone acknowledged, and the state of the next Down. Not thread safe: the session's own lock.
 */
class LiveServerStates(private val kept: Int = LiveLimits.SERVER_STATES_KEPT) {
    private class Sent(val id: Int, val digest: LiveDigest)

    /** The state a Down brings: [stateId], a delta against [baseStateId] (0: a full state). */
    class Next(val stateId: Int, val baseStateId: Int, val state: State)

    private val sent = ArrayDeque<Sent>()
    private var nextId = 1
    private var lastSentId = 0

    /** The newest state the phone acknowledged that is still kept; 0: none. */
    var ackedId: Int = 0
        private set

    /** An Up's `ack_state`: never goes back, and only to a state still kept. */
    fun acknowledge(stateId: Int) {
        if (stateId > ackedId && sent.any { it.id == stateId }) ackedId = stateId
    }

    /** The phone lost its states (`Up.full`): the next state is full. */
    fun forgetAcknowledged() {
        ackedId = 0
    }

    /**
     * The next Down's state for the whole [current] state, or null when the Down goes without one: nothing changed
     * since the acknowledged state, and that is also the last one sent (§8.2, rule 4).
     */
    fun next(current: State): Next? {
        val base = sent.firstOrNull { it.id == ackedId }
        val result = LiveDelta.between(current, base?.digest)
        if (base != null && result.isEmpty && lastSentId == base.id) return null
        val id = nextId++
        sent.addLast(Sent(id, result.digest))
        lastSentId = id
        // The acknowledged state stays: the next delta needs it. The oldest of the others goes.
        while (sent.size > kept) {
            val oldest = sent.indexOfFirst { it.id != ackedId }
            sent.removeAt(oldest)
        }
        return Next(stateId = id, baseStateId = base?.id ?: 0, state = result.state)
    }
}

/**
 * The phone's states of one live session (docs/live-v2/spec.md §8.3, §8.6): the newest [LiveLimits.CLIENT_STATES_KEPT]
 * whole states by id, which one is shown, and whether the next Up asks for a full state. A new session starts empty.
 */
class LiveClientStates(private val kept: Int = LiveLimits.CLIENT_STATES_KEPT) {
    private val states = LinkedHashMap<Int, State>()

    /** The id of the state the screens see: the newest applied. */
    var shownId: Int = 0
        private set

    /** The phone could not apply a delta: the next Up says `full`, until a full state comes. */
    var needFull: Boolean = false
        private set

    /** The state the screens see; null before the first. */
    val shown: State? get() = states[shownId]

    /** The next Up's `ack_state`: the newest state applied. */
    val ackState: Int get() = shownId

    /**
     * A Down's state: [stateId] 0 is none. False: dropped, because its base is gone or the delta does not fit it
     * ([needFull] is set). An older state than the shown one is kept as a base but not shown.
     */
    fun apply(stateId: Int, baseStateId: Int, state: State?): Boolean {
        if (stateId == 0 || states.containsKey(stateId)) return true
        val base =
            if (baseStateId == 0) {
                LiveStates.EMPTY
            } else {
                states[baseStateId] ?: return dropped()
            }
        val next = LiveStates.apply(base, state ?: State()) ?: return dropped()
        states[stateId] = next
        if (baseStateId == 0) needFull = false
        if (stateId > shownId) shownId = stateId
        while (states.size > kept) states.remove(states.keys.min())
        return true
    }

    private fun dropped(): Boolean {
        needFull = true
        return false
    }
}
