package app.hovanki.radar.link

/**
 * The peers a [GattLink] connected to (the client side), by the OS's id: which to connect to, which to forget, and
 * when a peer's id changed. Pure: the platform links keep one per run and call it from one thread (or under a lock).
 */
internal class LinkPeers(
    private val max: Int = GattLinkRules.MAX_LINKS,
    private val forgetMillis: Long = GattLinkRules.FORGET_MILLIS,
) {
    private class Peer {
        var connected = false
        var token: String? = null

        /** When it was last connected or found; the silence of a disconnected peer counts from here. */
        var seenMillis = 0L
    }

    /** What to do with a peer the scan found. */
    sealed interface Found {
        /** Already in the table: nothing to do (it is connected, or its reconnect is on the way). */
        data object Known : Found

        /** New and there is room: connect. */
        data object Connect : Found

        /** New; [forgotten] was silent the longest and gave its place: stop reconnecting it, then connect. */
        data class Replace(val forgotten: String) : Found

        /** New, but [GattLinkRules.MAX_LINKS] peers are in the table and none is silent long enough. */
        data object Full : Found
    }

    private val peers = linkedMapOf<String, Peer>()

    val ids: Set<String> get() = peers.keys.toSet()

    operator fun contains(id: String): Boolean = id in peers

    fun tokenOf(id: String): String? = peers[id]?.token

    fun isConnected(id: String): Boolean = peers[id]?.connected == true

    fun found(id: String, nowMillis: Long): Found {
        if (id in peers) return Found.Known
        if (peers.size < max) {
            peers[id] = Peer().also { it.seenMillis = nowMillis }
            return Found.Connect
        }
        val silent = peers.entries
            .filter { !it.value.connected && nowMillis - it.value.seenMillis >= forgetMillis }
            .minByOrNull { it.value.seenMillis }
            ?: return Found.Full
        peers.remove(silent.key)
        peers[id] = Peer().also { it.seenMillis = nowMillis }
        return Found.Replace(silent.key)
    }

    fun connected(id: String, nowMillis: Long) {
        val peer = peers[id] ?: return
        peer.connected = true
        peer.seenMillis = nowMillis
    }

    fun disconnected(id: String, nowMillis: Long) {
        val peer = peers[id] ?: return
        if (peer.connected) peer.seenMillis = nowMillis
        peer.connected = false
    }

    /**
     * [id] read [token]. Returns the id of another peer, not connected, whose last token was the same: the same phone
     * under an old id (the OS rotated its address). That one is taken out of the table: stop reconnecting it.
     */
    fun token(id: String, token: String): String? {
        val peer = peers[id] ?: return null
        peer.token = token
        val old = peers.entries.firstOrNull { (other, it) -> other != id && !it.connected && it.token == token }?.key
        if (old != null) peers.remove(old)
        return old
    }

    fun remove(id: String) {
        peers.remove(id)
    }

    fun clear() {
        peers.clear()
    }
}
