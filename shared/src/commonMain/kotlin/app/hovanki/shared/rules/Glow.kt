package app.hovanki.shared.rules

import app.hovanki.shared.protocol.GameSettings

/** One glow of a game: the seekers see every active hider from [startMillis] until [endMillis] (server time). */
data class GlowWindow(val index: Int, val startMillis: Long, val endMillis: Long) {
    fun isOpenAt(nowMillis: Long): Boolean = nowMillis in startMillis..<endMillis
}

/**
 * When the hiders glow (docs/adr/0009-game-setup-glow-streets.md): every [GameSettings.glowEverySeconds] of the
 * search, for [GameSettings.glowForSeconds]; the first glow a full interval after the search started. The server
 * reveals by it, the app counts down to it: both compute it here.
 */
object Glow {
    fun isOn(settings: GameSettings): Boolean = settings.glowEverySeconds > 0 && settings.glowForSeconds > 0

    /** The glow going on at [nowMillis], or the last one before it; null before the first one or without glows. */
    fun lastStarted(settings: GameSettings, seekingStartedAtMillis: Long, nowMillis: Long): GlowWindow? {
        if (!isOn(settings) || nowMillis < seekingStartedAtMillis) return null
        val index = ((nowMillis - seekingStartedAtMillis) / everyMillis(settings)).toInt()
        return if (index < 1) null else window(settings, seekingStartedAtMillis, index)
    }

    /** The glow going on at [nowMillis]; null between glows. */
    fun openAt(settings: GameSettings, seekingStartedAtMillis: Long, nowMillis: Long): GlowWindow? =
        lastStarted(settings, seekingStartedAtMillis, nowMillis)?.takeIf { it.isOpenAt(nowMillis) }

    /** The next glow that starts after [nowMillis]; null without glows or when the search is over before it. */
    fun next(settings: GameSettings, seekingStartedAtMillis: Long, nowMillis: Long): GlowWindow? {
        if (!isOn(settings)) return null
        val index = if (nowMillis < seekingStartedAtMillis) {
            1
        } else {
            ((nowMillis - seekingStartedAtMillis) / everyMillis(settings)).toInt() + 1
        }
        val window = window(settings, seekingStartedAtMillis, index)
        val seekingEnds = seekingStartedAtMillis + settings.seekingSeconds * 1000L
        return window.takeIf { it.startMillis < seekingEnds }
    }

    private fun window(settings: GameSettings, seekingStartedAtMillis: Long, index: Int): GlowWindow {
        val start = seekingStartedAtMillis + index * everyMillis(settings)
        val length = minOf(settings.glowForSeconds * 1000L, everyMillis(settings))
        return GlowWindow(index, start, start + length)
    }

    private fun everyMillis(settings: GameSettings): Long = settings.glowEverySeconds * 1000L
}
