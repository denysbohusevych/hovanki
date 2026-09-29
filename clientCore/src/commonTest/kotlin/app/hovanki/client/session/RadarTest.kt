package app.hovanki.client.session

import app.hovanki.client.network.testSnapshot
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.protocol.RadarContact
import app.hovanki.shared.protocol.RadarState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RadarTest {
    @Test
    fun theBandsOfTheRadar() {
        val none = testSnapshot()
        assertNull(none.radarBand(), "no radar")
        assertEquals(RadarBand.NONE, none.radarBandOf(PlayerId("anna")))

        val quiet = none.copy(me = none.me.copy(radar = RadarState()))
        assertEquals(RadarBand.NONE, quiet.radarBand())

        val contacts = RadarState(
            listOf(RadarContact(RadarBand.WARM, PlayerId("anna")), RadarContact(RadarBand.BURNING, PlayerId("boris"))),
        )
        val loud = none.copy(me = none.me.copy(radar = contacts))
        assertEquals(RadarBand.BURNING, loud.radarBand())
        assertEquals(RadarBand.WARM, loud.radarBandOf(PlayerId("anna")))
        assertEquals(RadarBand.NONE, loud.radarBandOf(PlayerId("carl")))
    }
}
