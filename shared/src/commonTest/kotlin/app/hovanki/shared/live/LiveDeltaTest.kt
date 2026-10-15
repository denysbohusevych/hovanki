package app.hovanki.shared.live

import app.hovanki.shared.live.v2.Down
import app.hovanki.shared.live.v2.Keys
import app.hovanki.shared.live.v2.PlayersSection
import app.hovanki.shared.live.v2.State
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.VisibilityReason
import app.hovanki.shared.protocol.VisibleLocation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deltas between states (docs/live-v2/spec.md §8): what goes, and that losses, reorders and repeats mend themselves.
 */
class LiveDeltaTest {
    private val now = LiveFixtures.NOW

    /** One live session: the server's states and the phone's, with the Downs as bytes in between. */
    private class Session {
        val server = LiveServerStates()
        val phone = LiveClientStates()
        val snapshots = LiveSnapshots()
        var lastTime = Long.MIN_VALUE
        var shown: GameSnapshot? = null

        /** The server's Down for [snapshot], after the phone's Up said [ackState] (and maybe [full]). */
        fun reply(snapshot: GameSnapshot, ackState: Int, full: Boolean): ByteArray {
            server.acknowledge(ackState)
            if (full) server.forgetAcknowledged()
            return push(snapshot)
        }

        fun push(snapshot: GameSnapshot, onlyNew: Boolean = false): ByteArray {
            val next = server.next(LiveSections.fromSnapshot(snapshot), push = onlyNew)
            val down =
                Down(
                    server_time_ms = snapshot.serverTimeMillis,
                    state_id = next?.stateId ?: 0,
                    base_state_id = next?.baseStateId ?: 0,
                    state = next?.state,
                )
            return Down.ADAPTER.encode(down)
        }

        /** The phone's algorithm (§8.6) without the chat: true when the Down's state applied (or it had none). */
        fun receive(bytes: ByteArray): Boolean {
            val down = Down.ADAPTER.decode(bytes)
            val applied = phone.apply(down.state_id, down.base_state_id, down.state)
            val state = phone.shown
            if (state != null && down.server_time_ms >= lastTime) {
                lastTime = down.server_time_ms
                shown = snapshots.toGameSnapshot(state, down.server_time_ms)
            }
            return applied
        }
    }

    private fun player(id: String, lat: Double = 50.45) = PlayerView(
        id = PlayerId(id),
        name = id,
        role = Role.HIDER,
        status = PlayerStatus.ACTIVE,
        location = VisibleLocation(GeoPoint(lat, 30.52), 5.0, now, VisibilityReason.TEAMMATE),
        lastSeenMillis = now,
    )

    private fun lobby(vararg players: PlayerView) = LiveFixtures.bare(now).copy(players = players.toList())

    private fun Session.exchange(snapshot: GameSnapshot): Down {
        val bytes = reply(snapshot, phone.ackState, phone.needFull)
        receive(bytes)
        return Down.ADAPTER.decode(bytes)
    }

    @Test
    fun theFirstStateIsFullAndTheSameStateIsNotSentAgain() {
        val session = Session()
        val snapshot = lobby(player("p1"), player("p2"))
        val first = session.exchange(snapshot)
        assertEquals(1, first.state_id)
        assertEquals(0, first.base_state_id)
        assertEquals(listOf("p1", "p2"), first.state?.players?.order?.ids)
        // Nothing changed: no state at all, only the server's time.
        val second = session.exchange(snapshot.copy(serverTimeMillis = now + 3_000))
        assertEquals(0, second.state_id)
        assertNull(second.state)
        assertEquals(LiveFixtures.v1(snapshot.copy(serverTimeMillis = now + 3_000)), session.shown)
    }

    @Test
    fun onlyTheChangedPartOfOnePlayerGoes() {
        val session = Session()
        session.exchange(lobby(player("p1"), player("p2")))
        val moved = lobby(player("p1"), player("p2", lat = 50.46))
        val down = session.exchange(moved)
        val players = assertNotNull(down.state?.players)
        assertEquals(listOf("p2"), players.upsert.map { it.id })
        assertNull(players.upsert.single().roster)
        assertNull(players.upsert.single().presence)
        assertNotNull(players.upsert.single().location)
        assertNull(players.order)
        assertNull(down.state?.game)
        assertNull(down.state?.settings)
        assertEquals(LiveFixtures.v1(moved), session.shown)
    }

    @Test
    fun aPlayerWhoLeftIsRemovedAndTheOrderIsSentOnlyWhenThePhoneCouldNotGuessIt() {
        val session = Session()
        session.exchange(lobby(player("p1"), player("p2"), player("p3")))
        val left = session.exchange(lobby(player("p1"), player("p3")))
        assertEquals(listOf("p2"), left.state?.players?.remove)
        assertNull(left.state?.players?.order)
        val appended = session.exchange(lobby(player("p1"), player("p3"), player("p4")))
        assertNull(appended.state?.players?.order)
        val inserted = lobby(player("p0"), player("p1"), player("p3"), player("p4"))
        val down = session.exchange(inserted)
        assertEquals(listOf("p0", "p1", "p3", "p4"), down.state?.players?.order?.ids)
        assertEquals(LiveFixtures.v1(inserted), session.shown)
    }

    @Test
    fun aPhoneWithoutTheBaseAsksForAFullState() {
        val session = Session()
        session.exchange(lobby(player("p1")))
        // The phone never got state 2 and the server thinks it did: a delta against 2 can't apply.
        session.server.acknowledge(1)
        session.server.next(LiveSections.fromSnapshot(lobby(player("p1"), player("p2"))))
        session.server.acknowledge(2)
        val broken = session.push(lobby(player("p1"), player("p2"), player("p3")))
        assertFalse(session.receive(broken))
        assertTrue(session.phone.needFull)
        val snapshot = lobby(player("p1"), player("p2"), player("p3"))
        val full = session.exchange(snapshot)
        assertEquals(0, full.base_state_id)
        assertFalse(session.phone.needFull)
        assertEquals(LiveFixtures.v1(snapshot), session.shown)
    }

    @Test
    fun anOrderThatDoesNotFitTheBaseIsRefused() {
        val base = LiveSections.fromSnapshot(lobby(player("p1"), player("p2")))
        assertNull(LiveStates.apply(base, State(players = PlayersSection(order = Keys(listOf("p1", "p9"))))))
        assertNull(LiveStates.apply(base, State(players = PlayersSection(order = Keys(listOf("p1"))))))
        assertNull(LiveStates.apply(base, State(players = PlayersSection(order = Keys(listOf("p1", "p1"))))))
        assertNotNull(LiveStates.apply(base, State(players = PlayersSection(order = Keys(listOf("p2", "p1"))))))
    }

    @Test
    fun theAcknowledgedStateIsKeptWhilePushesComeAndGo() {
        val session = Session()
        session.exchange(lobby(player("p1")))
        session.exchange(lobby(player("p1")))
        // Ten pushes nobody acknowledges: the server keeps state 1, so they are all deltas against it.
        repeat(10) {
            val down = Down.ADAPTER.decode(session.push(lobby(player("p1", lat = 50.0 + it))))
            assertEquals(1, down.base_state_id)
        }
    }

    @Test
    fun aPushOfWhatWasSentAlreadyBringsNoState() {
        val session = Session()
        session.exchange(lobby(player("p1")))
        val moved = lobby(player("p1", lat = 50.5))
        val first = Down.ADAPTER.decode(session.push(moved, onlyNew = true))
        assertEquals(2, first.state_id)
        // Another poke, nothing new for this phone: no state (an answer would still carry one, rule 4).
        assertNull(Down.ADAPTER.decode(session.push(moved, onlyNew = true)).state)
        assertEquals(3, Down.ADAPTER.decode(session.push(lobby(player("p1", lat = 50.6)), onlyNew = true)).state_id)
        assertEquals(4, Down.ADAPTER.decode(session.reply(moved, ackState = 1, full = false)).state_id)
    }

    @Test
    fun lossesReordersAndRepeatsMendAfterTheNextExchange() {
        var checked = 0
        repeat(40) { seed ->
            val random = Random(seed)
            val session = Session()
            val late = ArrayList<ByteArray>()
            var time = now
            repeat(120) {
                time += 3_000
                // Sometimes the game changes between the phone's Ups: a push.
                if (random.nextInt(3) == 0) {
                    val pushed = session.push(LiveFixtures.random(random, time - 1_000))
                    when (random.nextInt(4)) {
                        0 -> Unit

                        // lost
                        1 -> late += pushed

                        else -> session.receive(pushed)
                    }
                }
                val snapshot = LiveFixtures.random(random, time)
                val upLost = random.nextInt(6) == 0
                if (upLost) return@repeat
                val bytes = session.reply(snapshot, session.phone.ackState, session.phone.needFull)
                when (random.nextInt(8)) {
                    0 -> return@repeat

                    // the Down is lost
                    1 -> {
                        // Late: it comes after an older one, and once more after that.
                        late += bytes
                        late.forEach(session::receive)
                        late.clear()
                        return@repeat
                    }

                    2 -> session.receive(bytes) // repeated below
                }
                val applied = session.receive(bytes)
                if (random.nextInt(5) == 0) {
                    late.forEach(session::receive)
                    late.clear()
                }
                if (applied) {
                    assertEquals(LiveFixtures.v1(snapshot), session.shown, "seed $seed, $time")
                    checked++
                }
            }
        }
        // Most exchanges get through: the check is not empty.
        assertTrue(checked > 40 * 60, "only $checked checks")
    }
}
