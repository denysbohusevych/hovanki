package app.hovanki.server.map

import java.io.ByteArrayOutputStream

/**
 * Writes Mapbox Vector Tiles for tests: layers of features with string and number properties and geometry in tile
 * coordinates, encoded as the spec says (so [Mvt] is tested against the format, not against itself).
 */
class MvtWriter {
    private val layers = ArrayList<ByteArray>()

    /** A layer [name] with [features]: (type, properties, parts); polygons as closed rings. */
    fun layer(
        name: String,
        features: List<Triple<MvtGeometryType, Map<String, Any>, List<List<TilePoint>>>>,
        extent: Int = 4096,
    ): MvtWriter {
        val keys = ArrayList<String>()
        val values = ArrayList<Any>()
        val out = Proto()
        out.string(1, name)
        for ((type, properties, parts) in features) {
            val tags = ArrayList<Long>()
            for ((key, value) in properties) {
                val k = keys.indexOf(key).takeIf { it >= 0 } ?: keys.size.also { keys += key }
                val v = values.indexOf(value).takeIf { it >= 0 } ?: values.size.also { values += value }
                tags += k.toLong()
                tags += v.toLong()
            }
            val feature = Proto()
            feature.packed(2, tags)
            feature.varint(3, type.ordinal.toLong())
            feature.packed(4, geometry(type, parts))
            out.message(2, feature)
        }
        for (key in keys) out.string(3, key)
        for (value in values) {
            val encoded = Proto()
            when (value) {
                is String -> encoded.string(1, value)
                is Double -> encoded.fixed64(3, value.toRawBits())
                is Int -> encoded.varint(4, value.toLong())
                is Boolean -> encoded.varint(7, if (value) 1 else 0)
                else -> error("Unsupported value $value")
            }
            out.message(4, encoded)
        }
        out.varint(5, extent.toLong())
        out.varint(15, 2)
        layers += out.bytes()
        return this
    }

    fun bytes(): ByteArray {
        val tile = Proto()
        for (layer in layers) tile.bytes(3, layer)
        return tile.bytes()
    }

    private fun geometry(type: MvtGeometryType, parts: List<List<TilePoint>>): List<Long> {
        val commands = ArrayList<Long>()
        var x = 0
        var y = 0
        fun point(p: TilePoint) {
            commands += zigzag((p.x - x).toLong())
            commands += zigzag((p.y - y).toLong())
            x = p.x
            y = p.y
        }
        for (part in parts) {
            // A ring is written without its closing point, then ClosePath.
            val points = if (type == MvtGeometryType.POLYGON) part.dropLast(1) else part
            commands += command(1, 1)
            point(points.first())
            if (points.size > 1) {
                commands += command(2, points.size - 1)
                points.drop(1).forEach(::point)
            }
            if (type == MvtGeometryType.POLYGON) commands += command(7, 1)
        }
        return commands
    }

    private fun command(id: Int, count: Int): Long = ((id and 0x7) or (count shl 3)).toLong()

    private fun zigzag(value: Long): Long = (value shl 1) xor (value shr 63)

    private class Proto {
        private val out = ByteArrayOutputStream()

        fun varint(field: Int, value: Long) {
            tag(field, 0)
            raw(value)
        }

        fun fixed64(field: Int, value: Long) {
            tag(field, 1)
            for (i in 0 until 8) out.write(((value ushr (8 * i)) and 0xff).toInt())
        }

        fun string(field: Int, value: String) = bytes(field, value.toByteArray())

        fun message(field: Int, message: Proto) = bytes(field, message.bytes())

        fun bytes(field: Int, value: ByteArray) {
            tag(field, 2)
            raw(value.size.toLong())
            out.write(value)
        }

        fun packed(field: Int, values: List<Long>) {
            val inner = Proto()
            values.forEach(inner::raw)
            bytes(field, inner.bytes())
        }

        fun bytes(): ByteArray = out.toByteArray()

        private fun tag(field: Int, wireType: Int) = raw(((field shl 3) or wireType).toLong())

        private fun raw(value: Long) {
            var rest = value
            while (true) {
                if (rest and 0x7fL.inv() == 0L) {
                    out.write(rest.toInt())
                    return
                }
                out.write(((rest and 0x7f) or 0x80).toInt())
                rest = rest ushr 7
            }
        }
    }
}

/** A square ring (closed) with its corners at [x0], [y0] and [x1], [y1], clockwise on screen (an outer ring). */
fun square(x0: Int, y0: Int, x1: Int, y1: Int): List<TilePoint> =
    listOf(TilePoint(x0, y0), TilePoint(x1, y0), TilePoint(x1, y1), TilePoint(x0, y1), TilePoint(x0, y0))
