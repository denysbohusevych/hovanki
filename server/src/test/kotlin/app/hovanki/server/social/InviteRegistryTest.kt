package app.hovanki.server.social

import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.InviteId
import app.hovanki.shared.protocol.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [InviteRegistry] alone: time is passed in. */
class InviteRegistryTest {
    private val registry = InviteRegistry()
    private val alice = UserId("alice")
    private val bob = UserId("bob")
    private val carol = UserId("carol")
    private val game = GameId("game")
    private var nextId = 0

    @Test
    fun oneInvitationPerGameAndInviteeTheLatest() {
        registry.add(invite(from = alice, to = carol))
        val latest = invite(from = bob, to = carol, at = 10)
        registry.add(latest)
        registry.add(invite(from = alice, to = carol, game = GameId("other")))

        assertEquals(listOf(latest.id, InviteId("i3")), registry.of(carol, nowMillis = 20).map { it.id })
        assertEquals(2, registry.size())
    }

    @Test
    fun invitationsExpire() {
        val invite = invite(from = alice, to = bob, at = 0)
        registry.add(invite)

        assertEquals(listOf(invite), registry.of(bob, invite.expiresAtMillis - 1))
        assertEquals(emptyList(), registry.of(bob, invite.expiresAtMillis))
        assertEquals(1, registry.sweep(invite.expiresAtMillis) { true })
        assertEquals(0, registry.size())
    }

    @Test
    fun sweepDropsTheInvitationsOfGamesOutOfTheirLobby() {
        val started = GameId("started")
        registry.add(invite(from = alice, to = bob, game = started))
        val open = invite(from = alice, to = bob)
        registry.add(open)

        val asked = mutableListOf<GameId>()
        assertEquals(
            1,
            registry.sweep(nowMillis = 1) {
                asked += it
                it != started
            },
        )
        assertEquals(listOf(open), registry.of(bob, nowMillis = 1))
        assertEquals(setOf(game, started), asked.toSet())
    }

    @Test
    fun onlyTheInviteeDismisses() {
        val invite = invite(from = alice, to = bob)
        registry.add(invite)

        assertFalse(registry.dismiss(alice, invite.id))
        assertFalse(registry.dismiss(bob, InviteId("unknown")))
        assertTrue(registry.dismiss(bob, invite.id))
        assertEquals(emptyList(), registry.of(bob, nowMillis = 0))
    }

    @Test
    fun joiningAnswersTheInvitation() {
        registry.add(invite(from = alice, to = bob))
        val other = invite(from = alice, to = bob, game = GameId("other"))
        registry.add(other)

        registry.removeInvitee(game, bob)
        assertEquals(listOf(other), registry.of(bob, nowMillis = 0))
    }

    @Test
    fun deletedAccountsTakeTheirInvitationsBothWays() {
        registry.add(invite(from = alice, to = bob))
        registry.add(invite(from = bob, to = carol))
        val unrelated = invite(from = carol, to = alice)
        registry.add(unrelated)

        registry.beforeDelete(bob)
        assertEquals(emptyList(), registry.of(bob, nowMillis = 0))
        assertEquals(emptyList(), registry.of(carol, nowMillis = 0))
        assertEquals(listOf(unrelated), registry.of(alice, nowMillis = 0))
    }

    @Test
    fun theOldestGoBeyondTheLimits() {
        val small = InviteRegistry(maxPerUser = 2, maxTotal = 3)
        val games = List(3) { GameId("g$it") }
        games.forEach { small.add(invite(from = alice, to = bob, game = it)) }
        assertEquals(games.drop(1), small.of(bob, nowMillis = 0).map { it.gameId })

        small.add(invite(from = alice, to = carol))
        small.add(invite(from = bob, to = alice))
        assertEquals(3, small.size())
        assertEquals(listOf(games[2]), small.of(bob, nowMillis = 0).map { it.gameId })
    }

    private fun invite(from: UserId, to: UserId, game: GameId = this.game, at: Long = 0) = Invite(
        id = InviteId("i${++nextId}"),
        gameId = game,
        joinCode = "ABCDEF",
        inviterId = from,
        inviteeId = to,
        createdAtMillis = at,
        expiresAtMillis = at + SocialLimits.INVITE_TTL.toMillis(),
    )
}
