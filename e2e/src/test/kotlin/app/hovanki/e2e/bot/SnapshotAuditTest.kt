package app.hovanki.e2e.bot

import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.shared.protocol.ChatChannel
import app.hovanki.shared.protocol.ChatMessage
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.MyState
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.VisibilityReason
import app.hovanki.shared.protocol.VisibleLocation
import app.hovanki.shared.protocol.protocolJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnapshotAuditTest {
    private val seeker = PlayerId("seeker")
    private val hider = PlayerId("hider")

    @Test
    fun chatAndNamesSayingLocationAreNoPosition() {
        val snapshot = snapshot(
            viewer = hider,
            role = Role.HIDER,
            players = listOf(PlayerView(seeker, "location", Role.SEEKER, PlayerStatus.ACTIVE)),
            chat = listOf(message(1, "location"), message(2, "my \"location\""), message(3, "\"location\":{}")),
        )

        assertEquals(emptyList(), audit(snapshot))
    }

    @Test
    fun aPositionSentToAHiderIsFoundOnTheWire() {
        // A field the client doesn't know is dropped when decoding: only the raw JSON shows it.
        val raw = protocolJson.encodeToString(GameSnapshot.serializer(), snapshot(hider, Role.HIDER))
            .replace("\"players\":[]", "\"players\":[{\"id\":\"x\",\"extra\":{\"location\":{}}}]")

        val problems = SnapshotAudit.check(snapshot(hider, Role.HIDER), raw)

        assertEquals(listOf("hider hider received a position in SEEKING"), problems)
    }

    @Test
    fun teamMessagesOnlyReachTheTeam() {
        val chat = listOf(
            message(1, "all"),
            message(2, "seekers", ChatChannel.SEEKERS),
            message(3, "hiders", ChatChannel.HIDERS),
        )

        assertEquals(
            listOf("HIDER hider received chat message 2 of channel SEEKERS in SEEKING"),
            audit(snapshot(hider, Role.HIDER, chat = chat)),
        )
        assertEquals(
            listOf("SEEKER seeker received chat message 3 of channel HIDERS in SEEKING"),
            audit(snapshot(seeker, Role.SEEKER, chat = chat)),
        )
    }

    @Test
    fun theLobbyHasNoTeams() {
        val chat = listOf(message(1, "all"), message(2, "hiders", ChatChannel.HIDERS))

        val problems = audit(snapshot(hider, Role.HIDER, phase = GamePhase.LOBBY, chat = chat))

        assertEquals(listOf("HIDER hider received chat message 2 of channel HIDERS in LOBBY"), problems)
    }

    @Test
    fun aRevealedHiderIsNoViolation() {
        val location = VisibleLocation(GameSetups.PARK, 5.0, 0, VisibilityReason.STALE_SIGNAL)
        val players = listOf(PlayerView(hider, "Anna", Role.HIDER, PlayerStatus.ACTIVE, location))

        assertTrue(audit(snapshot(seeker, Role.SEEKER, players = players)).isEmpty())
        assertEquals(1, audit(snapshot(seeker, Role.SEEKER, phase = GamePhase.FINISHED, players = players)).size)
    }

    private fun audit(snapshot: GameSnapshot): List<String> =
        SnapshotAudit.check(snapshot, protocolJson.encodeToString(GameSnapshot.serializer(), snapshot))

    private fun snapshot(
        viewer: PlayerId,
        role: Role,
        phase: GamePhase = GamePhase.SEEKING,
        players: List<PlayerView> = emptyList(),
        chat: List<ChatMessage> = emptyList(),
    ) = GameSnapshot(
        gameId = GameId("game"),
        joinCode = "ABC234",
        hostId = seeker,
        phase = phase,
        settings = GameSetups.fast(),
        serverTimeMillis = 1_000,
        players = players,
        me = MyState(viewer, role, PlayerStatus.ACTIVE),
        chat = chat,
    )

    private fun message(seq: Long, text: String, channel: ChatChannel = ChatChannel.ALL) =
        ChatMessage(seq, seeker, text, sentAtMillis = seq, channel = channel)
}
