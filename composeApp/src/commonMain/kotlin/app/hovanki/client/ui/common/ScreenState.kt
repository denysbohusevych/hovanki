package app.hovanki.client.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/**
 * A screen's one state (docs/architecture.md, «Состояние экрана»), collected without a hop: a change the view model
 * makes on the main thread (a text field's edit) reaches the screen before the next frame, as `mutableStateOf` did.
 */
@Composable
fun <T> StateFlow<T>.collectScreenState(): State<T> = collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)
