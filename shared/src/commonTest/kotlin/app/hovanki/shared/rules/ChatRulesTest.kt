package app.hovanki.shared.rules

import app.hovanki.shared.protocol.ChatChannel
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatRulesTest {
    @Test
    fun controlCharactersAreRemoved() {
        assertEquals("behind the old oak", ChatRules.clean("  behind\nthe\told\u0000 oak\u0007  "))
        // A right-to-left override would make the text read differently than it is written.
        assertEquals("abc", ChatRules.clean("a‮b⁦c"))
    }

    @Test
    fun lengthAfterCleaning() {
        assertFalse(ChatRules.isValid("   \n\t"))
        assertTrue(ChatRules.isValid("x".repeat(ChatRules.MAX_LENGTH)))
        assertFalse(ChatRules.isValid("x".repeat(ChatRules.MAX_LENGTH + 1)))
        assertTrue(ChatRules.isValid("  " + "x".repeat(ChatRules.MAX_LENGTH) + "  "))
    }

    @Test
    fun theLobbyHasOnlyTheCommonChannel() {
        assertEquals(ChatChannel.ALL, ChatRules.channelFor(GamePhase.LOBBY, Role.SEEKER, team = true))
        assertFalse(ChatRules.hasTeamChannel(GamePhase.LOBBY))
    }

    @Test
    fun teamChannelsFollowTheRole() {
        for (phase in listOf(GamePhase.HIDING, GamePhase.SEEKING, GamePhase.FINISHED)) {
            assertEquals(ChatChannel.SEEKERS, ChatRules.channelFor(phase, Role.SEEKER, team = true))
            assertEquals(ChatChannel.HIDERS, ChatRules.channelFor(phase, Role.HIDER, team = true))
            assertEquals(ChatChannel.ALL, ChatRules.channelFor(phase, Role.HIDER, team = false))
            assertTrue(ChatRules.hasTeamChannel(phase))
        }
    }

    @Test
    fun whoSeesWhat() {
        assertTrue(ChatRules.canSee(ChatChannel.ALL, Role.HIDER))
        assertTrue(ChatRules.canSee(ChatChannel.SEEKERS, Role.SEEKER))
        assertFalse(ChatRules.canSee(ChatChannel.SEEKERS, Role.HIDER))
        assertTrue(ChatRules.canSee(ChatChannel.HIDERS, Role.HIDER))
        assertFalse(ChatRules.canSee(ChatChannel.HIDERS, Role.SEEKER))
    }
}
