package app.hovanki.e2e.devices

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiTreeTest {
    @Test
    fun findsElementsByTestTagOnAndroid() {
        val output = """
            Running on emulator-5554
            {"attributes":{"resource-id":"","text":""},"children":[
              {"attributes":{"resource-id":"game_screen"},"children":[
                {"attributes":{"resource-id":"catch_code","text":"0427","enabled":"true"},"children":[]},
                {"attributes":{"resource-id":"catch_dispute"},"children":[
                  {"attributes":{"text":"Dispute"},"children":[]}
                ]}
              ]}
            ]}
        """.trimIndent()
        val tree = UiTree.parse(output)

        assertTrue(tree.contains("game_screen"))
        assertEquals("0427", tree.textOf("catch_code"))
        assertEquals("Dispute", tree.textOf("catch_dispute"), "text of a child node")
        assertFalse(tree.contains("home_screen"))
        assertNull(tree.textOf("home_screen"))
        assertEquals("  game_screen\n  catch_code: 0427\n  catch_dispute\n  \"Dispute\"", tree.describe())
    }

    @Test
    fun findsElementsByAccessibilityIdentifierOnIos() {
        val tree = UiTree.parse(
            """{"attributes":{},"children":[{"attributes":{"identifier":"catch_code","accessibilityText":"1234",""" +
                """"bounds":"[16,300][386,348]","enabled":"false","focused":"true"}}]}""",
        )

        assertEquals("1234", tree.textOf("catch_code"))
        assertEquals("  catch_code: 1234 [16,300][386,348] (disabled) (focused)", tree.describe())
    }

    @Test
    fun readsTheCompactScreenOfMaestroMcp() {
        val output = """
            {"ui_schema":{"abbreviations":{"b":"bounds","rid":"resource-id"},"defaults":{"enabled":true}},
             "elements":[{"rid":"game_screen","b":[0,47,390,810],"c":[
               {"rid":"catch_code","a11y":"0427","b":"[20,63][110,95]"},
               {"rid":"catch_dispute","enabled":false,"c":[{"txt":"Dispute"}]}
             ]}]}
        """.trimIndent()
        val tree = UiTree.parseCompact(output)

        assertTrue(tree.contains("game_screen"))
        assertEquals("0427", tree.textOf("catch_code"))
        assertEquals("Dispute", tree.textOf("catch_dispute"))
        assertEquals(
            "  game_screen [0,47][390,810]\n  catch_code: 0427 [20,63][110,95]\n  catch_dispute (disabled)\n  \"Dispute\"",
            tree.describe(),
        )
    }
}
