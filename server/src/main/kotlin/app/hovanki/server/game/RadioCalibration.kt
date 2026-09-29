package app.hovanki.server.game

import app.hovanki.shared.protocol.Carry
import app.hovanki.shared.protocol.PlayerId

/**
 * How loud one kind of phone hears another (docs/adr/0012-nearby-radar.md, «Калибровка»): the radar's raw readings
 * of a game counted by the two phones' models, where they were (in the hand, in the pocket), an [anchor] and the
 * signal. The history keeps the counts across games (`radio_calibration`), so the offsets by model can be learned
 * from real games instead of guessed. Never a player, a game or a position: numbers by model only. Pure: [Game]
 * owns it and passes the time in.
 */
internal class RadioCalibration {
    private val counts = HashMap<Key, Int>()

    /** The last [CATCH_WINDOW_MILLIS] of readings per ordered pair, to count them for a catch when one comes. */
    private val recent = HashMap<Pair<PlayerId, PlayerId>, ArrayDeque<Recent>>()

    /**
     * [hearer]'s phone heard [heard]'s at [rssi] (as heard, before any correction). Phones without a model (the
     * bots, older apps) are skipped. [far]: GPS says the two are far apart for sure, a sample of the noise floor.
     */
    fun add(
        hearer: PlayerId,
        heard: PlayerId,
        rssi: Int,
        atMillis: Long,
        hearerModel: String?,
        heardModel: String?,
        hearerCarry: Carry,
        heardCarry: Carry,
        far: Boolean,
    ) {
        if (hearerModel.isNullOrBlank() || heardModel.isNullOrBlank()) return
        val level = rssi.coerceIn(MIN_DBM, MAX_DBM)
        val key = Key(hearerModel.take(MODEL_LENGTH), heardModel.take(MODEL_LENGTH), hearerCarry, heardCarry, level)
        count(key, CalibrationAnchor.ALL)
        if (far) count(key, CalibrationAnchor.FAR)
        val pair = recent.getOrPut(hearer to heard) { ArrayDeque() }
        pair.addLast(Recent(key, atMillis))
        while (pair.isNotEmpty() &&
            (pair.first().atMillis < atMillis - CATCH_WINDOW_MILLIS || pair.size > RECENT_MAX)
        ) {
            pair.removeFirst()
        }
    }

    /**
     * [seeker] caught [hider] at [atMillis] (the code was right): the pair stood next to each other, so their readings
     * of the last [CATCH_WINDOW_MILLIS] are what «a metre away» sounds like for these two phones.
     */
    fun onCatch(seeker: PlayerId, hider: PlayerId, atMillis: Long) {
        for (pair in listOf(seeker to hider, hider to seeker)) {
            val readings = recent.remove(pair) ?: continue
            for (reading in readings) {
                if (atMillis - reading.atMillis <= CATCH_WINDOW_MILLIS) count(reading.key, CalibrationAnchor.CATCH)
            }
        }
    }

    /** What the game counted, for the history. */
    fun summary(): List<CalibrationBucket> = counts.entries
        .sortedWith(compareBy({ it.key.hearerModel }, { it.key.heardModel }, { it.key.anchor }, { it.key.rssiDbm }))
        .map { (key, readings) ->
            CalibrationBucket(
                key.hearerModel,
                key.heardModel,
                key.hearerCarry,
                key.heardCarry,
                key.anchor,
                key.rssiDbm,
                readings,
            )
        }

    private fun count(key: Key, anchor: CalibrationAnchor) {
        val full = key.copy(anchor = anchor)
        counts[full] = (counts[full] ?: 0) + 1
    }

    private data class Key(
        val hearerModel: String,
        val heardModel: String,
        val hearerCarry: Carry,
        val heardCarry: Carry,
        val rssiDbm: Int,
        val anchor: CalibrationAnchor = CalibrationAnchor.ALL,
    )

    private class Recent(val key: Key, val atMillis: Long)

    companion object {
        const val CATCH_WINDOW_MILLIS = 30_000L
        const val MODEL_LENGTH = 60
        private const val RECENT_MAX = 200
        private const val MIN_DBM = -120
        private const val MAX_DBM = 0
    }
}

/** Under what circumstances the readings of a [CalibrationBucket] were taken. */
enum class CalibrationAnchor {
    /** Every reading. */
    ALL,

    /** GPS said the two phones were far apart for sure: the floor of what is heard at all. */
    FAR,

    /** The seconds before a confirmed catch: the two phones next to each other. */
    CATCH,
}

/** One row of the calibration: so many readings of this signal between phones of these models, held like this. */
data class CalibrationBucket(
    val hearerModel: String,
    val heardModel: String,
    val hearerCarry: Carry,
    val heardCarry: Carry,
    val anchor: CalibrationAnchor,
    val rssiDbm: Int,
    val readings: Int,
)
