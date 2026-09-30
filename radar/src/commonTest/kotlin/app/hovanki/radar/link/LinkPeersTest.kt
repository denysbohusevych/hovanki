package app.hovanki.radar.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkPeersTest {
    @Test
    fun aPeerIsConnectedOnceAndTheTableHasRoomForFive() {
        val peers = LinkPeers()
        for (i in 1..GattLinkRules.MAX_LINKS) assertEquals(LinkPeers.Found.Connect, peers.found("p$i", 0))
        assertEquals(LinkPeers.Found.Known, peers.found("p1", 10))
        assertEquals(LinkPeers.Found.Full, peers.found("p6", 10))
        assertEquals((1..5).map { "p$it" }.toSet(), peers.ids)
    }

    @Test
    fun aPeerSilentLongEnoughGivesItsPlace() {
        val peers = LinkPeers(max = 2, forgetMillis = 1_000)
        peers.found("a", 0)
        peers.found("b", 0)
        peers.connected("a", 0)
        peers.connected("b", 0)
        peers.disconnected("a", 100)
        peers.disconnected("b", 500)
        // Silent, but not long enough; connected peers never give their place.
        assertEquals(LinkPeers.Found.Full, peers.found("c", 1_000))
        // The one silent the longest goes first.
        assertEquals(LinkPeers.Found.Replace("a"), peers.found("c", 1_100))
        assertEquals(setOf("b", "c"), peers.ids)
        peers.connected("b", 1_200)
        peers.connected("c", 1_200)
        assertEquals(LinkPeers.Found.Full, peers.found("d", 5_000))
    }

    @Test
    fun aNeverConnectedPeerCountsItsSilenceFromWhenItWasFound() {
        val peers = LinkPeers(max = 1, forgetMillis = 1_000)
        peers.found("a", 0)
        assertEquals(LinkPeers.Found.Full, peers.found("b", 999))
        assertEquals(LinkPeers.Found.Replace("a"), peers.found("b", 1_000))
    }

    @Test
    fun theSameTokenUnderANewIdIsTheSamePhone() {
        val peers = LinkPeers()
        peers.found("old", 0)
        peers.connected("old", 0)
        assertNull(peers.token("old", "0a1bff00"))
        assertEquals("0a1bff00", peers.tokenOf("old"))
        peers.disconnected("old", 10)
        peers.found("new", 20)
        peers.connected("new", 20)
        assertEquals("old", peers.token("new", "0a1bff00"))
        assertEquals(setOf("new"), peers.ids)
        assertTrue(peers.isConnected("new"))
        assertFalse("old" in peers)
    }

    @Test
    fun twoConnectedPeersWithOneTokenAreNotMerged() {
        val peers = LinkPeers()
        peers.found("a", 0)
        peers.found("b", 0)
        peers.connected("a", 0)
        peers.connected("b", 0)
        peers.token("a", "0a1bff00")
        assertNull(peers.token("b", "0a1bff00"))
        assertEquals(setOf("a", "b"), peers.ids)
    }

    @Test
    fun unknownPeersAreIgnored() {
        val peers = LinkPeers()
        peers.connected("x", 0)
        peers.disconnected("x", 0)
        assertNull(peers.token("x", "0a1bff00"))
        assertTrue(peers.ids.isEmpty())
        peers.found("a", 0)
        peers.remove("a")
        assertEquals(LinkPeers.Found.Connect, peers.found("a", 1))
        peers.clear()
        assertTrue(peers.ids.isEmpty())
    }
}
