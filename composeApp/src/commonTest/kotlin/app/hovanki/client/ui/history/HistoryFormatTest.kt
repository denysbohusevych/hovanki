package app.hovanki.client.ui.history

import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryFormatTest {
    @Test
    fun oneDecimalRoundsAndUsesTheLanguageSeparator() {
        assertEquals("1,2", oneDecimal(1.24, ","))
        assertEquals("1.3", oneDecimal(1.25, "."))
        assertEquals("0,0", oneDecimal(0.04, ","))
        assertEquals("12,0", oneDecimal(11.96, ","))
    }
}
