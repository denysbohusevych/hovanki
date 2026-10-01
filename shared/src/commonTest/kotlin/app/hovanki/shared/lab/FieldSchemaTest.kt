package app.hovanki.shared.lab

import app.hovanki.shared.protocol.FieldJoinRequest
import app.hovanki.shared.protocol.FieldJoinResponse
import app.hovanki.shared.protocol.FieldUpload
import app.hovanki.shared.protocol.LabRunId
import app.hovanki.shared.protocol.UserProfile
import app.hovanki.shared.protocol.protocolJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The field log's schema (docs/adr/0018-field-test-build.md §3.2) and its wire: coordinates and defaults. */
class FieldSchemaTest {
    @Test
    fun coordinatesAreOnlyLatitudeAndLongitude() {
        val fix = buildJsonObject {
            put(LabFields.K, FieldKinds.GPS)
            put(GpsFields.LAT, 50.45)
            put(GpsFields.LON, 30.52)
            put(GpsFields.ACC, 4.0)
            put(GpsFields.SPEED, 1.2)
            put(GpsFields.BEARING, 90.0)
        }
        assertTrue(LabSchema.hasCoordinates(fix))
        val kept = LabSchema.withoutCoordinates(fix)
        assertFalse(LabSchema.hasCoordinates(kept))
        // Accuracy, speed and heading say nothing of where: they stay, as in the lab.
        assertEquals(setOf(LabFields.K, GpsFields.ACC, GpsFields.SPEED, GpsFields.BEARING), kept.keys)

        val tick = buildJsonObject { put(LabFields.K, FieldKinds.TICK) }
        assertSame(tick, LabSchema.withoutCoordinates(tick))
        assertFalse(LabSchema.hasCoordinates(buildJsonObject { put(GpsFields.ACC, 3.0) }))
        assertTrue(LabSchema.hasCoordinates(buildJsonObject { put(GpsFields.LON, 3.0) }))
    }

    @Test
    fun theFieldLogsEventsAreReadLikeTheLabs() {
        val line = """{"t":5,"dt":5,"mono":1,"dev":"p1","k":"gps","app":"active","seq":1,"run":"r","lat":1.5,""" +
            """"lon":2.5,"acc":7.0,"accepted":true}"""
        val (events, bad) = LabEvents.read(sequenceOf(line, """{"k":"survey","dt":6,"rating":4,"broken":["radar"]}"""))
        assertEquals(0, bad)
        assertEquals(listOf(FieldKinds.GPS, FieldKinds.SURVEY), events.map { it.k })
        assertEquals(1.5, events[0].double(GpsFields.LAT))
        assertEquals(true, events[0].boolean(GpsFields.ACCEPTED))
        assertEquals(4, events[1].int(SurveyFields.RATING))
        assertEquals(listOf("radar"), events[1].strings(SurveyFields.BROKEN))
    }

    @Test
    fun theWireHasDefaultsForEverythingNew() {
        // An app of before: no consent in its join; a profile of an older server: no lab access.
        val request = protocolJson.decodeFromString(FieldJoinRequest.serializer(), "{}")
        assertNull(request.consentAtMillis)
        val profile = protocolJson.decodeFromString(
            UserProfile.serializer(),
            """{"id":"u","nickname":"n","email":"e@x","emailVerified":true,"createdAtMillis":1}""",
        )
        assertFalse(profile.labAccess)
        val response = protocolJson.decodeFromString(
            FieldJoinResponse.serializer(),
            """{"runId":"r","deviceId":"d","token":"t","label":"p","salt":"00","serverTimeMillis":7}""",
        )
        assertEquals(LabRunId("r"), response.runId)
        assertEquals(FieldUpload.INTERVAL_MILLIS, response.uploadIntervalMillis)
        assertEquals(FieldUpload.RX_EVERY_MILLIS, response.rxEveryMillis)
        val encoded = Json.parseToJsonElement(protocolJson.encodeToString(FieldJoinResponse.serializer(), response))
        assertEquals("r", encoded.jsonObject["runId"].toString().trim('"'))
    }
}
