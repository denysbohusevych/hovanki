package app.hovanki.client.ui.chat

import app.hovanki.client.account.AccountState
import app.hovanki.client.session.SessionState
import app.hovanki.shared.protocol.ChatMessage
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatStateTest {
    private val gameId = GameId("g1")
    private val player = PlayerId("p1")

    private fun session(chat: List<ChatMessage> = emptyList(), readSeq: Long = 0) = SessionState(
        session = PlayerSession(gameId, player, "token"),
        chat = chat,
        chatReadSeq = readSeq,
    )

    private fun build(local: ChatLocal, session: SessionState = session()) =
        buildChatUiState(ChatInputs(session, emptySet(), AccountState(), message = null, isBusy = false), local)

    @Test
    fun `the panel is open only for the game it was opened in`() {
        assertTrue(build(ChatLocal(openIn = gameId)).isOpen)
        assertFalse(build(ChatLocal(openIn = GameId("other"))).isOpen)
        assertFalse(build(ChatLocal()).isOpen)
    }

    @Test
    fun `a message can be sent when there is text and nothing on its way`() {
        assertFalse(build(ChatLocal(text = "   ")).canSend)
        assertTrue(build(ChatLocal(text = "hi")).canSend)
        assertFalse(build(ChatLocal(text = "hi", isSending = true)).canSend)
        assertEquals("hi", build(ChatLocal(text = "hi")).text)
    }

    @Test
    fun `unseen messages count only while the panel is open on the game`() {
        val chat = listOf(ChatMessage(seq = 3, playerId = PlayerId("p2"), text = "hey", sentAtMillis = 1L))
        assertTrue(hasUnseen(session(chat), ChatLocal(openIn = gameId)))
        assertFalse(hasUnseen(session(chat, readSeq = 3), ChatLocal(openIn = gameId)))
        assertFalse(hasUnseen(session(chat), ChatLocal()))
    }
}
