package app.hovanki.shared.live

import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.Capabilities
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LivePlayersTest {
    private val now = LiveFixtures.NOW
    private val players = LiveFixtures.everything().players

    @Test
    fun theItemsAreThePlayersWhole() {
        val shared = LivePlayers()
        // Twice: built, then kept.
        repeat(2) {
            players.forEachIndexed { i, player ->
                assertEquals(LiveSections.player(player, now), shared.item(i, player, now))
            }
        }
        // A player gone offline since.
        val later = now + LiveLimits.OFFLINE_AFTER_MILLIS + 10_000
        players.forEachIndexed { i, player ->
            assertEquals(LiveSections.player(player, later), shared.item(i, player, later))
        }
    }

    @Test
    fun everyViewerOfAGameGetsTheSameRoster() {
        val shared = LivePlayers()
        val seen = players[0]
        // Another viewer sees nothing of the player: the same roster, and the same whole item to all such viewers.
        val unseen = seen.copy(location = null)
        val forOne = shared.item(0, seen, now)
        val forAnother = shared.item(0, unseen, now)
        assertSame(forOne.roster, forAnother.roster)
        assertSame(forAnother, shared.item(0, unseen, now))
        assertEquals(LiveSections.player(unseen, now), forAnother)
    }

    @Test
    fun aChangedRosterFieldBuildsTheRosterAnew() {
        val base = players[0]
        val changes =
            listOf(
                base.copy(name = "Аня Б."),
                base.copy(role = Role.HIDER),
                base.copy(status = PlayerStatus.CAUGHT),
                base.copy(userId = UserId("u2")),
                base.copy(userId = null),
                base.copy(outAtMillis = now),
                base.copy(caughtBy = PlayerId("p2")),
                base.copy(left = true),
                base.copy(capabilities = base.capabilities!!.copy(bluetooth = BluetoothState.OFF)),
                base.copy(capabilities = Capabilities()),
                base.copy(capabilities = null),
                base.copy(sparks = 8),
                base.copy(sparks = null),
            )
        for (changed in changes) {
            val shared = LivePlayers()
            val before = shared.item(0, base, now).roster
            val after = shared.item(0, changed, now).roster
            assertNotEquals(before, after, "$changed")
            assertEquals(LiveSections.player(changed, now).roster, after, "$changed")
        }
    }

    @Test
    fun theDeltaTakesTheKeptHashes() {
        val json = LiveJson()
        val state = LiveSections.fromSnapshot(LiveFixtures.everything(), json)
        val kept = LiveDelta.between(state, null, rosters = json.game.players)
        val made = LiveDelta.between(state, null)
        assertEquals(made.state, kept.state)
        assertTrue(made.digest.sameAs(kept.digest))
        // Against a base, with one roster changed since.
        val snapshot = LiveFixtures.everything()
        val sam = snapshot.players.indexOfFirst { it.name == "Sam" }
        val list = snapshot.players.toMutableList().apply { this[sam] = this[sam].copy(name = "Sam B.") }
        val renamed = snapshot.copy(players = list)
        val next = LiveSections.fromSnapshot(renamed, json)
        val delta = LiveDelta.between(next, kept.digest, rosters = json.game.players)
        assertEquals(LiveDelta.between(next, made.digest).state, delta.state)
        assertEquals(listOf("p2"), delta.state.players!!.upsert.map { it.id })
    }

    @Test
    fun anotherPlayerInTheSameSlot() {
        val shared = LivePlayers()
        shared.item(0, players[0], now)
        // The same fields but another id, then another player whole: as their own.
        val twin = players[0].copy(id = PlayerId("p9"))
        assertEquals(LiveSections.player(twin, now), shared.item(0, twin, now))
        assertEquals(LiveSections.player(players[1], now), shared.item(0, players[1], now))
    }

    @Test
    fun aSlotFarAhead() {
        val shared = LivePlayers()
        val player = PlayerView(PlayerId("p40"), "Late", Role.HIDER, PlayerStatus.ACTIVE)
        assertEquals(LiveSections.player(player, now), shared.item(39, player, now))
        assertEquals(LiveSections.player(players[2], now), shared.item(2, players[2], now))
    }
}
