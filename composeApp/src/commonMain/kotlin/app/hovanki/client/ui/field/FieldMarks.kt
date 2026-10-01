package app.hovanki.client.ui.field

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the «Something is wrong» dialog is open (docs/adr/0018-field-test-build.md §5). One for the app: the game's
 * menu and the shaking of the phone both [request] it, [FieldMarkLayer] shows it. Main thread.
 */
class FieldMarks {
    private val mutableOpen = MutableStateFlow(false)
    val isOpen: StateFlow<Boolean> = mutableOpen.asStateFlow()

    fun request() {
        mutableOpen.value = true
    }

    fun dismiss() {
        mutableOpen.value = false
    }
}
