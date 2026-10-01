package app.hovanki.client.ui.field

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import app.hovanki.client.lab.FieldSession
import app.hovanki.shared.lab.UiFields

/**
 * Where the screens tell the field log that a screen opened or closed (docs/adr/0018-field-test-build.md §3.2, the
 * `ui` event): by a name from a closed set of plain words (`chat`, `settings`…), never a text, a title, a game's code
 * or a player. Does nothing while the log is off ([FieldSession.ui] checks), and everywhere but the field build the
 * log is never on.
 */
class FieldUi(private val session: FieldSession?) {
    fun opened(screen: String) {
        session?.ui(screen, UiFields.OPEN)
    }

    fun closed(screen: String) {
        session?.ui(screen, UiFields.CLOSE)
    }

    companion object {
        val None = FieldUi(null)
    }
}

val LocalFieldUi = compositionLocalOf { FieldUi.None }

/** [screen] is open while this is in the composition: `ui` `open` now, `close` when it leaves. */
@Composable
fun TrackScreen(screen: String, ui: FieldUi = LocalFieldUi.current) {
    DisposableEffect(screen, ui) {
        ui.opened(screen)
        onDispose { ui.closed(screen) }
    }
}

/**
 * The app's current screen ([screen], the crash trail's word) as `ui` events: `open` of the new one, `close` of the old.
 * A screen that is up when the log starts (the round's) is written at its start ([FieldSession] keeps what is open).
 */
@Composable
fun FieldScreenTrail(screen: String, ui: FieldUi) {
    TrackScreen(screen, ui)
}
