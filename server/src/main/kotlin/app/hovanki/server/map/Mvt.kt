package app.hovanki.server.map

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * A Mapbox Vector Tile (https://github.com/mapbox/vector-tile-spec, version 2), decoded: layers, feature types,
 * properties and geometry in tile coordinates. Only what the server reads from the map tiles, without a protobuf
 * library: the format is a handful of messages.
 */
class MvtTile(val layers: Map<String, MvtLayer>)

class MvtLayer(val name: String, val extent: Int, val features: List<MvtFeature>)

enum class MvtGeometryType { UNKNOWN, POINT, LINESTRING, POLYGON }

class MvtFeature(
    val type: MvtGeometryType,
    val properties: Map<String, Any>,
    /**
     * Points (POINT), lines (LINESTRING) or rings (POLYGON) in tile coordinates: 0..extent, y pointing down. Rings are
     * closed (the first point repeated at the end); an outer ring winds clockwise on screen, a hole counterclockwise.
     */
    val parts: List<List<TilePoint>>,
) {
    fun string(key: String): String? = properties[key] as? String

    fun number(key: String): Double? = (properties[key] as? Number)?.toDouble()
}

data class TilePoint(val x: Int, val y: Int)

object Mvt {
    /** Decodes [bytes] (gzipped or not), keeping only [wanted] layers (null: all). */
    fun decode(bytes: ByteArray, wanted: Set<String>? = null): MvtTile {
        val data = if (bytes.size >= 2 && bytes[0] == GZIP_0 && bytes[1] == GZIP_1) gunzip(bytes) else bytes
        val reader = ProtoReader(data)
        val layers = LinkedHashMap<String, MvtLayer>()
        while (reader.hasMore) {
            val (field, wireType) = reader.readTag()
            if (field == TILE_LAYERS && wireType == WIRE_LENGTH_DELIMITED) {
                val layer = readLayer(reader.readMessage(), wanted) ?: continue
                layers[layer.name] = layer
            } else {
                reader.skip(wireType)
            }
        }
        return MvtTile(layers)
    }

    /**
     * Signed area of a ring in tile coordinates (surveyor's formula): positive for an outer ring, negative for a hole.
     */
    fun signedArea(ring: List<TilePoint>): Double {
        var sum = 0.0
        for (i in 0 until ring.size - 1) {
            sum += ring[i].x.toDouble() * ring[i + 1].y - ring[i + 1].x.toDouble() * ring[i].y
        }
        return sum / 2
    }

    private fun readLayer(reader: ProtoReader, wanted: Set<String>?): MvtLayer? {
        var name = ""
        var extent = DEFAULT_EXTENT
        val keys = ArrayList<String>()
        val values = ArrayList<Any>()
        val rawFeatures = ArrayList<ProtoReader>()
        while (reader.hasMore) {
            val (field, wireType) = reader.readTag()
            when {
                field == LAYER_NAME && wireType == WIRE_LENGTH_DELIMITED -> name = reader.readString()
                field == LAYER_FEATURES && wireType == WIRE_LENGTH_DELIMITED -> rawFeatures += reader.readMessage()
                field == LAYER_KEYS && wireType == WIRE_LENGTH_DELIMITED -> keys += reader.readString()
                field == LAYER_VALUES && wireType == WIRE_LENGTH_DELIMITED -> values += readValue(reader.readMessage())
                field == LAYER_EXTENT && wireType == WIRE_VARINT -> extent = reader.readVarint().toInt()
                else -> reader.skip(wireType)
            }
        }
        if (wanted != null && name !in wanted) return null
        require(extent > 0) { "Layer $name has extent $extent" }
        return MvtLayer(name, extent, rawFeatures.map { readFeature(it, keys, values) })
    }

    private fun readFeature(reader: ProtoReader, keys: List<String>, values: List<Any>): MvtFeature {
        var type = MvtGeometryType.UNKNOWN
        var tags: List<Long> = emptyList()
        var geometry: List<Long> = emptyList()
        while (reader.hasMore) {
            val (field, wireType) = reader.readTag()
            when {
                field == FEATURE_TAGS && wireType == WIRE_LENGTH_DELIMITED -> tags = reader.readPackedVarints()

                field == FEATURE_TYPE && wireType == WIRE_VARINT ->
                    type = MvtGeometryType.entries.getOrElse(reader.readVarint().toInt()) { MvtGeometryType.UNKNOWN }

                field == FEATURE_GEOMETRY && wireType == WIRE_LENGTH_DELIMITED -> geometry = reader.readPackedVarints()

                else -> reader.skip(wireType)
            }
        }
        val properties = HashMap<String, Any>(tags.size / 2)
        for (i in 0 until tags.size - 1 step 2) {
            val key = keys.getOrNull(tags[i].toInt()) ?: continue
            val value = values.getOrNull(tags[i + 1].toInt()) ?: continue
            properties[key] = value
        }
        return MvtFeature(type, properties, decodeGeometry(geometry, type))
    }

    private fun readValue(reader: ProtoReader): Any {
        var value: Any = ""
        while (reader.hasMore) {
            val (field, wireType) = reader.readTag()
            value = when (field) {
                VALUE_STRING -> reader.readString()

                VALUE_FLOAT -> Float.fromBits(reader.readFixed32()).toDouble()

                VALUE_DOUBLE -> Double.fromBits(reader.readFixed64())

                VALUE_INT, VALUE_UINT -> reader.readVarint()

                VALUE_SINT -> zigzag(reader.readVarint())

                VALUE_BOOL -> reader.readVarint() != 0L

                else -> {
                    reader.skip(wireType)
                    continue
                }
            }
        }
        return value
    }

    /** The command stream of a feature: MoveTo, LineTo and ClosePath with zigzag-encoded deltas. */
    private fun decodeGeometry(commands: List<Long>, type: MvtGeometryType): List<List<TilePoint>> {
        val parts = ArrayList<List<TilePoint>>()
        var current = ArrayList<TilePoint>()
        var x = 0
        var y = 0
        var i = 0
        while (i < commands.size) {
            val command = commands[i++].toInt()
            val id = command and COMMAND_MASK
            val count = command ushr COMMAND_BITS
            when (id) {
                MOVE_TO, LINE_TO -> {
                    require(i + 2 * count <= commands.size) { "Truncated geometry" }
                    repeat(count) {
                        x += zigzag(commands[i++]).toInt()
                        y += zigzag(commands[i++]).toInt()
                        if (id == MOVE_TO) {
                            if (type == MvtGeometryType.POINT) {
                                parts += listOf(TilePoint(x, y))
                            } else {
                                // A ring only counts once closed (ClosePath); a line ends where the next one starts.
                                if (type == MvtGeometryType.LINESTRING && current.size >= 2) parts += current
                                current = arrayListOf(TilePoint(x, y))
                            }
                        } else {
                            current += TilePoint(x, y)
                        }
                    }
                }

                CLOSE_PATH -> {
                    if (current.size >= 3) {
                        current += current.first()
                        parts += current
                    }
                    current = ArrayList()
                }

                else -> throw IllegalArgumentException("Unknown geometry command $id")
            }
        }
        if (type == MvtGeometryType.LINESTRING && current.size >= 2) parts += current
        return parts
    }

    private fun zigzag(value: Long): Long = (value ushr 1) xor -(value and 1)

    private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use {
        it.readBytes()
    }

    private const val GZIP_0 = 0x1f.toByte()
    private const val GZIP_1 = 0x8b.toByte()
    private const val DEFAULT_EXTENT = 4096

    private const val TILE_LAYERS = 3
    private const val LAYER_NAME = 1
    private const val LAYER_FEATURES = 2
    private const val LAYER_KEYS = 3
    private const val LAYER_VALUES = 4
    private const val LAYER_EXTENT = 5
    private const val FEATURE_TAGS = 2
    private const val FEATURE_TYPE = 3
    private const val FEATURE_GEOMETRY = 4
    private const val VALUE_STRING = 1
    private const val VALUE_FLOAT = 2
    private const val VALUE_DOUBLE = 3
    private const val VALUE_INT = 4
    private const val VALUE_UINT = 5
    private const val VALUE_SINT = 6
    private const val VALUE_BOOL = 7

    private const val COMMAND_BITS = 3
    private const val COMMAND_MASK = 0x7
    private const val MOVE_TO = 1
    private const val LINE_TO = 2
    private const val CLOSE_PATH = 7

    const val WIRE_VARINT = 0
    const val WIRE_FIXED64 = 1
    const val WIRE_LENGTH_DELIMITED = 2
    const val WIRE_FIXED32 = 5
}

/** Protocol Buffers wire format over [bytes] from [start] until [end]. */
internal class ProtoReader(private val bytes: ByteArray, start: Int = 0, private val end: Int = bytes.size) {
    private var position = start

    val hasMore: Boolean get() = position < end

    /** Field number and wire type of the next field. */
    fun readTag(): Pair<Int, Int> {
        val tag = readVarint()
        return (tag ushr 3).toInt() to (tag and 0x7).toInt()
    }

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            require(position < end) { "Truncated varint" }
            val byte = bytes[position++].toInt()
            result = result or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
            require(shift < 64) { "Varint too long" }
        }
    }

    fun readFixed32(): Int {
        require(position + 4 <= end) { "Truncated fixed32" }
        var result = 0
        for (i in 0 until 4) result = result or ((bytes[position + i].toInt() and 0xff) shl (8 * i))
        position += 4
        return result
    }

    fun readFixed64(): Long {
        require(position + 8 <= end) { "Truncated fixed64" }
        var result = 0L
        for (i in 0 until 8) result = result or ((bytes[position + i].toLong() and 0xff) shl (8 * i))
        position += 8
        return result
    }

    /** A length-delimited field as a reader of its own. */
    fun readMessage(): ProtoReader {
        val length = readLength()
        val message = ProtoReader(bytes, position, position + length)
        position += length
        return message
    }

    fun readString(): String {
        val length = readLength()
        val text = String(bytes, position, length, Charsets.UTF_8)
        position += length
        return text
    }

    fun readPackedVarints(): List<Long> {
        val packed = readMessage()
        val values = ArrayList<Long>()
        while (packed.hasMore) values += packed.readVarint()
        return values
    }

    fun skip(wireType: Int) {
        when (wireType) {
            Mvt.WIRE_VARINT -> readVarint()
            Mvt.WIRE_FIXED64 -> position += 8
            Mvt.WIRE_LENGTH_DELIMITED -> position += readLength()
            Mvt.WIRE_FIXED32 -> position += 4
            else -> throw IllegalArgumentException("Unsupported wire type $wireType")
        }
        require(position <= end) { "Truncated field" }
    }

    private fun readLength(): Int {
        val length = readVarint()
        require(length >= 0 && position + length <= end) { "Truncated field of length $length" }
        return length.toInt()
    }
}
