package app.hovanki.client.session

import app.hovanki.client.network.testMessage
import app.hovanki.client.network.testSession
import app.hovanki.client.network.testSnapshot
import app.hovanki.shared.protocol.ChatChannel
import app.hovanki.shared.protocol.ChatMessage
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatLinesTest {
    private val me = testSession.playerId
    private val bo = PlayerId("bo")
    private val guest = PlayerId("guest")
    private val players = listOf(
        PlayerView(me, "Anna", Role.HIDER, PlayerStatus.ACTIVE, userId = UserId("u1")),
        PlayerView(bo, "Bo", Role.SEEKER, PlayerStatus.ACTIVE, userId = UserId("u2")),
        PlayerView(guest, "Guest", Role.HIDER, PlayerStatus.ACTIVE),
    )

    @Test
    fun linesCarryTheSenderAndFlags() {
        val messages = listOf(
            testMessage(1, from = me),
            testMessage(2, from = bo, channel = ChatChannel.SEEKERS),
            testMessage(3, from = guest),
        )

        val lines = chatLines(messages, players, me)

        assertEquals(listOf("Anna", "Bo", "Guest"), lines.map { it.senderName })
        assertEquals(listOf(true, false, false), lines.map { it.isMine })
        assertEquals(listOf(false, false, true), lines.map { it.isGuest })
        assertEquals(listOf(false, true, false), lines.map { it.isTeam })
        assertEquals(listOf(UserId("u1"), UserId("u2"), null), lines.map { it.senderUserId })
        assertEquals(
            ChatLine(2, bo, "Bo", UserId("u2"), "message 2", 1_002, ChatChannel.SEEKERS, isMine = false),
            lines[1],
        )
    }

    @Test
    fun blockedUsersAreHidden() {
        val messages = listOf(testMessage(1, from = bo), testMessage(2, from = guest), testMessage(3, from = me))

        val lines = chatLines(messages, players, me, blocked = setOf(UserId("u2")))

        assertEquals(listOf(2L, 3L), lines.map { it.seq })
    }

    @Test
    fun anUnknownSenderHasNoName() {
        val line = chatLines(listOf(testMessage(1, from = PlayerId("gone"))), players, me).single()

        assertNull(line.senderName)
        assertTrue(line.isGuest)
        assertFalse(line.isMine)
    }

    @Test
    fun unreadCountsOnlyNewMessagesOfOthers() {
        val messages = listOf(
            testMessage(1, from = bo),
            testMessage(2, from = me),
            testMessage(3, from = guest),
            testMessage(4, from = bo),
            testMessage(5, from = me),
        )

        assertEquals(3, unreadChatCount(messages, players, me, readSeq = 0))
        assertEquals(2, unreadChatCount(messages, players, me, readSeq = 1))
        assertEquals(0, unreadChatCount(messages, players, me, readSeq = 4), "own messages are never unread")
        assertEquals(1, unreadChatCount(messages, players, me, readSeq = 0, blocked = setOf(UserId("u2"))))
    }

    @Test
    fun sessionStateShortcuts() {
        assertEquals(emptyList(), SessionState().chatLines())
        assertEquals(0, SessionState().unreadChatCount())

        val state = SessionState(
            session = testSession,
            snapshot = testSnapshot(players = players),
            chat = listOf(testMessage(1, from = bo), testMessage(2, from = guest)),
            chatReadSeq = 1,
        )

        assertEquals(listOf("Bo", "Guest"), state.chatLines().map { it.senderName })
        assertEquals(listOf("Guest"), state.chatLines(blocked = setOf(UserId("u2"))).map { it.senderName })
        assertEquals(1, state.unreadChatCount())
    }

    @Test
    fun mergeKeepsOneCopyPerSeqInOrder() {
        val known = listOf(testMessage(1), testMessage(2), testMessage(4))

        assertEquals(known, mergeChat(known, emptyList()))
        val expected = listOf(1L, 2L, 4L, 5L, 6L).map { testMessage(it) }
        assertEquals(expected, mergeChat(known, listOf(testMessage(5), testMessage(6))))
        assertEquals(
            (1L..5).map { testMessage(it) },
            mergeChat(known, listOf(testMessage(5), testMessage(3), testMessage(2))),
            "out of order and overlapping",
        )
    }

    @Test
    fun mergeKeepsOnlyTheNewest() {
        val known = (1L..150).map { testMessage(it) }
        val received = (151L..260).map { testMessage(it) }

        val merged: List<ChatMessage> = mergeChat(known, received)

        assertEquals(200, merged.size)
        assertEquals(61L, merged.first().seq)
        assertEquals(260L, merged.last().seq)
    }
}
