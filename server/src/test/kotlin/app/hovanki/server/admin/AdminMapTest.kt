package app.hovanki.server.admin

import app.hovanki.server.game.GameException
import app.hovanki.server.map.MapProperties
import app.hovanki.server.map.Mvt
import app.hovanki.server.map.MvtGeometryType
import app.hovanki.server.map.MvtWriter
import app.hovanki.server.map.TilePoint
import app.hovanki.server.map.VectorTiles
import app.hovanki.server.map.square
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.UserRole
import app.hovanki.shared.protocol.protocolJson
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The admin's map (docs/adr/0010-big-games.md): what of a tile the page draws. */
class AdminMapTest {
    private val map = AdminMap(VectorTiles(MapProperties(), protocolJson, Clock.systemUTC()))

    @Test
    fun streetsHousesWaterAndGreen() {
        val tile = Mvt.decode(
            MvtWriter()
                .layer(
                    "transportation",
                    listOf(
                        Triple(
                            MvtGeometryType.LINESTRING,
                            mapOf("class" to "primary"),
                            listOf(listOf(TilePoint(0, 0), TilePoint(100, 0))),
                        ),
                        Triple(
                            MvtGeometryType.LINESTRING,
                            mapOf("class" to "minor"),
                            listOf(listOf(TilePoint(0, 10), TilePoint(100, 10))),
                        ),
                        Triple(
                            MvtGeometryType.LINESTRING,
                            mapOf("class" to "minor", "brunnel" to "tunnel"),
                            listOf(listOf(TilePoint(0, 20), TilePoint(100, 20))),
                        ),
                    ),
                )
                .layer("building", listOf(Triple(MvtGeometryType.POLYGON, emptyMap(), listOf(square(10, 10, 20, 20)))))
                .layer(
                    "water",
                    listOf(Triple(MvtGeometryType.POLYGON, mapOf("class" to "lake"), listOf(square(30, 30, 40, 40)))),
                )
                .layer(
                    "landcover",
                    listOf(
                        Triple(MvtGeometryType.POLYGON, mapOf("class" to "wood"), listOf(square(50, 50, 60, 60))),
                        Triple(MvtGeometryType.POLYGON, mapOf("class" to "sand"), listOf(square(70, 70, 80, 80))),
                    ),
                )
                .bytes(),
        )

        val admin = map.toAdmin(tile)

        assertEquals(listOf(true, false), admin.streets.map { it.major }, "no tunnels")
        assertEquals(listOf(0, 0, 100, 0), admin.streets.first().points)
        assertEquals(1, admin.buildings.size)
        assertEquals(listOf(10, 10, 20, 10, 20, 20, 10, 20, 10, 10), admin.buildings.single().single())
        assertEquals(1, admin.water.size)
        assertEquals(1, admin.green.size, "woods and parks, not sand")
    }

    @Test
    fun adminsOnly() {
        val moderator = Staff(UserId("m"), "mod", UserRole.MODERATOR, "hash", Instant.now())

        val error = assertFailsWith<GameException> { map.tile(moderator, 14, 1, 1) }
        assertEquals(ErrorCode.FORBIDDEN, error.code)
    }
}
