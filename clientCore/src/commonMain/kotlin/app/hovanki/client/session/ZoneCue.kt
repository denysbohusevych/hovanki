package app.hovanki.client.session

import app.hovanki.shared.protocol.ZoneSchedule

/**
 * What the zone is doing, for the game screen's animations and vibration (docs/design.md, «Зона — главная
 * анимация»). A hint for the interface, not a rule: the zone itself is [app.hovanki.shared.rules.stateAt].
 */
enum class ZoneCue {
    /** Nothing happens for a while (or the zone has not started yet). */
    CALM,

    /** The last minute before shrinking (or the whole hold, if shorter). */
    SOON,

    /** The last seconds before shrinking. */
    COUNTDOWN,

    SHRINKING,

    /** Just done shrinking. */
    SHRUNK,

    /** No more stages: the zone stays as it is. */
    FINAL,
}

/** The zone's [cue], with the time left in the current hold or shrink and that segment's length. */
data class ZoneMoment(val cue: ZoneCue, val millisLeft: Long?, val segmentMillis: Long?) {
    /** How much of the current segment is left, from 1 to 0; null when there is no segment. */
    val fractionLeft: Float?
        get() {
            val left = millisLeft ?: return null
            val total = segmentMillis?.takeIf { it > 0 } ?: return null
            return (left.toFloat() / total).coerceIn(0f, 1f)
        }

    companion object {
        /** Before the zone starts (the hiding phase). */
        val NOT_STARTED = ZoneMoment(ZoneCue.CALM, millisLeft = null, segmentMillis = null)
    }
}

/** How long before shrinking the zone warns ([ZoneCue.SOON]). */
const val ZONE_SOON_MILLIS = 60_000L

/** How long before shrinking the zone counts down ([ZoneCue.COUNTDOWN]). */
const val ZONE_COUNTDOWN_MILLIS = 10_000L

/** How long [ZoneCue.SHRUNK] lasts after a shrink ends. */
const val ZONE_SHRUNK_MILLIS = 1_500L

/** The zone's moment [elapsedMillis] after its schedule started; null: it has not started yet. */
fun ZoneSchedule.momentAt(elapsedMillis: Long?): ZoneMoment {
    if (elapsedMillis == null) return ZoneMoment.NOT_STARTED
    var start = 0L
    val elapsed = elapsedMillis.coerceAtLeast(0)
    var shrinkJustEnded: Long? = null
    for (stage in stages) {
        val holdMillis = stage.holdSeconds * 1000L
        val holdEnd = start + holdMillis
        if (elapsed < holdEnd) {
            val left = holdEnd - elapsed
            val cue = when {
                shrinkJustEnded != null && elapsed - shrinkJustEnded < ZONE_SHRUNK_MILLIS -> ZoneCue.SHRUNK
                left <= ZONE_COUNTDOWN_MILLIS -> ZoneCue.COUNTDOWN
                left <= ZONE_SOON_MILLIS -> ZoneCue.SOON
                else -> ZoneCue.CALM
            }
            return ZoneMoment(cue, left, holdMillis)
        }
        val shrinkMillis = stage.shrinkSeconds * 1000L
        val shrinkEnd = holdEnd + shrinkMillis
        if (elapsed < shrinkEnd) return ZoneMoment(ZoneCue.SHRINKING, shrinkEnd - elapsed, shrinkMillis)
        start = shrinkEnd
        shrinkJustEnded = shrinkEnd
    }
    val cue = if (shrinkJustEnded != null && elapsed - shrinkJustEnded < ZONE_SHRUNK_MILLIS) {
        ZoneCue.SHRUNK
    } else {
        ZoneCue.FINAL
    }
    return ZoneMoment(cue, millisLeft = null, segmentMillis = null)
}
