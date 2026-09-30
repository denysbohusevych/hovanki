package app.hovanki.radar.channel.name

import app.hovanki.radar.AdPart
import app.hovanki.radar.Decoded
import app.hovanki.radar.RadarRole
import app.hovanki.radar.RadarService
import app.hovanki.radar.SightingVia
import app.hovanki.radar.TOKEN
import app.hovanki.radar.frameOf
import kotlin.test.Test
import kotlin.test.assertEquals

class NameChannelTest {
    private val heard = listOf(Decoded(TOKEN, SightingVia.NAME))

    @Test
    fun theHiderIsTheServiceAndTheTokenAsTheName() {
        val parts = NameChannel.advertise(TOKEN, RadarRole.HIDER)
        assertEquals(listOf(AdPart.ServiceUuid(RadarService.UUID), AdPart.LocalName(TOKEN)), parts)
        assertEquals(heard, NameChannel.decode(frameOf(parts)))
    }

    @Test
    fun theFirstAppsPrefixIsStillRead() {
        val frame = frameOf(listOf(AdPart.ServiceUuid(RadarService.UUID), AdPart.LocalName("hv$TOKEN")))
        assertEquals(heard, NameChannel.decode(frame))
    }

    @Test
    fun otherNamesAndFramesWithoutTheServiceReadAsNothing() {
        assertEquals(emptyList(), NameChannel.advertise(TOKEN, RadarRole.SEEKER))
        val notAToken = frameOf(listOf(AdPart.ServiceUuid(RadarService.UUID), AdPart.LocalName("Pixel 9")))
        assertEquals(emptyList(), NameChannel.decode(notAToken))
        assertEquals(emptyList(), NameChannel.decode(frameOf(listOf(AdPart.LocalName(TOKEN)))))
    }
}
