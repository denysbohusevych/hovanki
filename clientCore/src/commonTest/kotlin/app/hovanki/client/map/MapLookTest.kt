package app.hovanki.client.map

import app.hovanki.client.storage.ClientStorage
import app.hovanki.client.storage.FakeSecureStore
import app.hovanki.shared.protocol.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapLookTest {
    private val kyiv = GeoPoint(50.45, 30.52)
    private val lviv = GeoPoint(49.84, 24.03)

    @Test
    fun theSunSetsInKyivAsTheAlmanacSays() {
        // Midsummer: sunrise 04:47, sunset 21:13 Kyiv time (UTC+3).
        assertTrue(Daylight.isNight(kyiv, utc(2026, 6, 21, 1, 30)))
        assertFalse(Daylight.isNight(kyiv, utc(2026, 6, 21, 2, 5)))
        assertFalse(Daylight.isNight(kyiv, utc(2026, 6, 21, 17, 55)))
        assertTrue(Daylight.isNight(kyiv, utc(2026, 6, 21, 18, 30)))
        // Midwinter: sunrise 07:57, sunset 15:56 Kyiv time (UTC+2).
        assertTrue(Daylight.isNight(kyiv, utc(2026, 12, 21, 5, 40)))
        assertFalse(Daylight.isNight(kyiv, utc(2026, 12, 21, 6, 15)))
        assertFalse(Daylight.isNight(kyiv, utc(2026, 12, 21, 13, 40)))
        assertTrue(Daylight.isNight(kyiv, utc(2026, 12, 21, 14, 15)))
    }

    @Test
    fun theSunSetsLaterFurtherWest() {
        // An autumn evening: dark in Kyiv already, Lviv still has its sunset ahead (18:50 there, 18:24 in Kyiv).
        val evening = utc(2026, 10, 7, 15, 40)
        assertTrue(Daylight.isNight(kyiv, evening))
        assertFalse(Daylight.isNight(lviv, evening))
    }

    @Test
    fun autoIsDarkAtNightAndLightByDay() {
        val auto = MapLook(theme = MapTheme.AUTO)
        assertEquals(MapTheme.LIGHT, auto.themeAt(kyiv, utc(2026, 10, 7, 9, 0)))
        assertEquals(MapTheme.DARK, auto.themeAt(kyiv, utc(2026, 10, 7, 20, 0)))
        // A chosen theme stays whatever the time.
        assertEquals(MapTheme.MINIMAL, MapLook(theme = MapTheme.MINIMAL).themeAt(kyiv, utc(2026, 10, 7, 20, 0)))
        assertEquals(MapTheme.LIGHT, MapLook().themeAt(kyiv, utc(2026, 10, 7, 20, 0)))
    }

    @Test
    fun theLookIsKeptOnThePhone() {
        val store = FakeSecureStore()
        val settings = MapSettings(ClientStorage(store))
        assertEquals(MapLook(), settings.look.value, "light, with 3D houses and relief, until the player chooses")

        val dark = MapLook(theme = MapTheme.DARK, buildings3d = false, relief = true)
        settings.update(dark)
        assertEquals(dark, settings.look.value)
        assertEquals(dark, MapSettings(ClientStorage(store)).look.value, "a new app process draws the same maps")
    }

    @Test
    fun aThemeOfANewerAppIsLightTheRestKept() {
        val store = FakeSecureStore()
        store.values["mapLook"] = """{"theme":"SATELLITE","buildings3d":false}"""

        assertEquals(MapLook(buildings3d = false), MapSettings(ClientStorage(store)).look.value)
    }

    @Test
    fun anUnreadableLookIsTheDefault() {
        val store = FakeSecureStore()
        store.values["mapLook"] = "{not json"

        assertEquals(MapLook(), MapSettings(ClientStorage(store)).look.value)
        assertFalse("mapLook" in store.values)
    }

    @Test
    fun aBrokenStoreStillDrawsMaps() {
        val store = FakeSecureStore(failing = true)
        val settings = MapSettings(ClientStorage(store))
        settings.update(MapLook(theme = MapTheme.DARK))

        assertEquals(MapTheme.DARK, settings.look.value.theme, "this launch keeps the choice in memory")
    }

    private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val days = daysFromCivil(year, month, day)
        return ((days * 24 + hour) * 60 + minute) * 60_000L
    }

    /** Days since 1970-01-01 (Howard Hinnant's algorithm), to keep the test free of a date library. */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yearOfEra = y - era * 400
        val dayOfYear = (153 * (month + if (month > 2) -3 else 9) + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097L + dayOfEra - 719_468L
    }
}
