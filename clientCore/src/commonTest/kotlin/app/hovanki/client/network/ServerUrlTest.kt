package app.hovanki.client.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerUrlTest {
    @Test
    fun keepsAddressesWithAScheme() {
        assertEquals("http://10.0.2.2:8080", ServerUrl.normalize("http://10.0.2.2:8080"))
        assertEquals("https://abc.trycloudflare.com", ServerUrl.normalize(" https://abc.trycloudflare.com/ "))
    }

    @Test
    fun addsHttpsToABareHost() {
        assertEquals("https://abc.trycloudflare.com", ServerUrl.normalize("abc.trycloudflare.com"))
        assertEquals("https://hovanki.example.org:8443", ServerUrl.normalize("hovanki.example.org:8443/"))
    }

    @Test
    fun anEmptyAddressStaysEmpty() {
        val url = ServerUrl("  ")
        assertEquals("", url.value)
        assertTrue(url.isBlank)

        url.value = "abc.trycloudflare.com"
        assertEquals("https://abc.trycloudflare.com", url.flow.value)
        assertFalse(url.isBlank)
    }
}
