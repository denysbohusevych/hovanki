package app.hovanki.client.tracking

import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.rules.Glow

/**
 * What the hider has to react to right now, also with the phone in the pocket (docs/design.md, «Тревоги
 * прячущегося», «Вибрация и звук»).
 */
enum class AlertKind {
    /** Outside the zone: eliminated at the deadline. */
    OUT_OF_ZONE,

    /** Inside a building: the seekers see the hider from the deadline on. */
    IN_BUILDING,

    /** A seeker claims to have found the hider: the code has to be shown (or the claim disputed). */
    CATCH_CLAIM,

    /**
     * A glow starts soon (at the deadline): the seekers will see the hider (docs/adr/0009-game-setup-glow-streets.md).
     */
    GLOW_SOON,

    /** A glow is on until the deadline: the seekers see the hider now. */
    GLOWING,

    /** The radar says a seeker is close (hot or burning): the hider's sense (docs/adr/0012-nearby-radar.md). */
    SEEKER_NEAR,

    /** The radar is required and this phone has Bluetooth off: turn it on before the deadline, or be seen. */
    BLUETOOTH_OFF,
}

/** An alert, until when it lasts (server time) and, for a claim, who made it. */
data class HiderAlert(val kind: AlertKind, val deadlineMillis: Long?, val seekerName: String? = null)

/**
 * The alerts of the viewer of this snapshot: only an active hider in a running round has any, none while it is on pause
 * (docs/adr/0019-pause-and-sos.md): nothing runs out then.
 */
fun GameSnapshot.hiderAlerts(): List<HiderAlert> {
    if (phase != GamePhase.HIDING && phase != GamePhase.SEEKING) return emptyList()
    if (pause != null) return emptyList()
    if (me.status != PlayerStatus.ACTIVE || me.role != Role.HIDER) return emptyList()
    return buildList {
        me.outOfZoneDeadlineMillis?.let { add(HiderAlert(AlertKind.OUT_OF_ZONE, it)) }
        me.insideBuildingRevealAtMillis?.let { add(HiderAlert(AlertKind.IN_BUILDING, it)) }
        catches.firstOrNull { it.hiderId == me.playerId && it.status == CatchStatus.AWAITING_CODE }?.let { claim ->
            val seeker = players.firstOrNull { it.id == claim.seekerId }?.name
            add(HiderAlert(AlertKind.CATCH_CLAIM, claim.deadlineMillis, seeker))
        }
        glowAlert(serverTimeMillis)?.let(::add)
        me.bluetoothDeadlineMillis?.let { add(HiderAlert(AlertKind.BLUETOOTH_OFF, it)) }
        val nearest = me.radar?.contacts?.maxOfOrNull { it.band }
        if (nearest != null && nearest >= RadarBand.HOT) add(HiderAlert(AlertKind.SEEKER_NEAR, null))
    }
}

/**
 * The glow as the hider feels it: [AlertKind.GLOW_SOON] from [GLOW_WARNING_MILLIS] before a glow, then
 * [AlertKind.GLOWING] while it is on. Null outside the search, between glows, and without glows.
 */
fun GameSnapshot.glowAlert(nowMillis: Long): HiderAlert? {
    if (phase != GamePhase.SEEKING || pause != null) return null
    val seekingStart = zoneStartedAtMillis ?: return null
    Glow.openAt(settings, seekingStart, nowMillis)?.let { return HiderAlert(AlertKind.GLOWING, it.endMillis) }
    val next = Glow.next(settings, seekingStart, nowMillis) ?: return null
    return HiderAlert(AlertKind.GLOW_SOON, next.startMillis).takeIf {
        next.startMillis - nowMillis <=
            GLOW_WARNING_MILLIS
    }
}

/** How long before a glow the hider is warned. */
const val GLOW_WARNING_MILLIS = 10_000L

/**
 * When to vibrate for the alerts again (docs/design.md, «Тревоги прячущегося»): right when an alert starts, then out
 * of the zone every 10 s, inside a building every 15 s, a claim once more after 10 s, the glow once when it is near
 * and once when it starts. Fed with every snapshot.
 */
class AlertRepeats {
    private val lastBuzz = mutableMapOf<AlertKind, Long>()
    private val buzzes = mutableMapOf<AlertKind, Int>()

    /** What changed at [nowMillis]: the alerts to vibrate (and show) for now, and the kinds that are over. */
    fun update(alerts: List<HiderAlert>, nowMillis: Long): Update {
        val current = alerts.map { it.kind }.toSet()
        val ended = lastBuzz.keys - current
        ended.forEach {
            lastBuzz.remove(it)
            buzzes.remove(it)
        }
        val buzz = alerts.filter { alert ->
            val last = lastBuzz[alert.kind]
            val count = buzzes[alert.kind] ?: 0
            last == null || (count < maxBuzzes(alert.kind) && nowMillis - last >= repeatMillis(alert.kind))
        }
        buzz.forEach {
            lastBuzz[it.kind] = nowMillis
            buzzes[it.kind] = (buzzes[it.kind] ?: 0) + 1
        }
        return Update(buzz, ended)
    }

    /** Forgets everything: the round is over or left. */
    fun clear(): Set<AlertKind> {
        val ended = lastBuzz.keys.toSet()
        lastBuzz.clear()
        buzzes.clear()
        return ended
    }

    data class Update(val buzz: List<HiderAlert>, val ended: Set<AlertKind>)

    private fun repeatMillis(kind: AlertKind): Long = when (kind) {
        AlertKind.OUT_OF_ZONE -> OUT_OF_ZONE_REPEAT_MILLIS

        AlertKind.IN_BUILDING -> IN_BUILDING_REPEAT_MILLIS

        AlertKind.CATCH_CLAIM -> CLAIM_REPEAT_MILLIS

        // Once each: the warning, then the glow itself.
        AlertKind.GLOW_SOON, AlertKind.GLOWING -> Long.MAX_VALUE

        // The heartbeat from the pocket, as long as the seeker stays close.
        AlertKind.SEEKER_NEAR -> SEEKER_NEAR_REPEAT_MILLIS

        AlertKind.BLUETOOTH_OFF -> BLUETOOTH_OFF_REPEAT_MILLIS
    }

    private fun maxBuzzes(kind: AlertKind): Int = when (kind) {
        AlertKind.CATCH_CLAIM -> CLAIM_BUZZES
        AlertKind.GLOW_SOON, AlertKind.GLOWING -> 1
        else -> Int.MAX_VALUE
    }

    companion object {
        const val OUT_OF_ZONE_REPEAT_MILLIS = 10_000L
        const val IN_BUILDING_REPEAT_MILLIS = 15_000L
        const val CLAIM_REPEAT_MILLIS = 10_000L
        const val CLAIM_BUZZES = 2
        const val SEEKER_NEAR_REPEAT_MILLIS = 5_000L
        const val BLUETOOTH_OFF_REPEAT_MILLIS = 15_000L
    }
}
