package app.hovanki.shared.live

import app.hovanki.shared.live.v2.State
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.PlayerId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A snapshot cut into sections, sent as bytes and put together again is what v1 gives (docs/live-v2/spec.md §9, §18).
 */
class LiveSnapshotsTest {
    private fun throughV2(snapshot: GameSnapshot): GameSnapshot {
        val state = LiveSections.fromSnapshot(snapshot)
        val received = State.ADAPTER.decode(State.ADAPTER.encode(state))
        val chat = snapshot.chat.map { LiveDownlink.chatMessage(LiveDownlink.chatMessage(it)) }
        return LiveSnapshots().toGameSnapshot(received, snapshot.serverTimeMillis, chat)
    }

    @Test
    fun everyFieldComesBack() {
        val snapshot = LiveFixtures.everything()
        assertEquals(LiveFixtures.v1(snapshot), throughV2(snapshot))
    }

    @Test
    fun defaultsComeBack() {
        val snapshot = LiveFixtures.bare()
        assertEquals(LiveFixtures.v1(snapshot), throughV2(snapshot))
    }

    @Test
    fun randomSnapshotsComeBack() {
        val random = Random(26)
        repeat(300) {
            val snapshot = LiveFixtures.random(random, LiveFixtures.NOW + it * 3_000L)
            assertEquals(LiveFixtures.v1(snapshot), throughV2(snapshot))
        }
    }

    @Test
    fun aConnectedPlayerWasSeenAtTheServersTime() {
        val now = LiveFixtures.NOW
        val snapshot = LiveFixtures.everything(now)
        val later = now + 3_000
        val players = LiveSnapshots().toGameSnapshot(LiveSections.fromSnapshot(snapshot), later).players
        // Silent for 1 s: connected, so the Down's own time; silent for 45 s: the real time.
        assertEquals(later, players.single { it.id == PlayerId("p1") }.lastSeenMillis)
        assertEquals(now - 45_000, players.single { it.id == PlayerId("p2") }.lastSeenMillis)
        // A player the server never saw is connected too, as v1's lobby reads a null.
        assertEquals(later, players.single { it.id == PlayerId("p3") }.lastSeenMillis)
    }

    @Test
    fun noRadarIsNotAnEmptyRadar() {
        val snapshot = LiveFixtures.everything()
        val withoutRadar = snapshot.copy(me = snapshot.me.copy(radar = null))
        assertNull(throughV2(withoutRadar).me.radar)
        val emptyRadar = snapshot.copy(me = snapshot.me.copy(radar = app.hovanki.shared.protocol.RadarState()))
        assertEquals(emptyList(), throughV2(emptyRadar).me.radar?.contacts)
    }

    @Test
    fun theStateOfEverythingIsSmall() {
        // Not a limit of the protocol: a guard against a section that suddenly grows.
        val bytes = State.ADAPTER.encode(LiveSections.fromSnapshot(LiveFixtures.everything())).size
        val json = app.hovanki.shared.protocol.protocolJson.encodeToString(
            GameSnapshot.serializer(),
            LiveFixtures.everything(),
        ).length
        println("Everything: v2 state $bytes bytes, v1 JSON $json bytes")
        kotlin.test.assertTrue(bytes < json, "$bytes ≥ $json")
    }
}
