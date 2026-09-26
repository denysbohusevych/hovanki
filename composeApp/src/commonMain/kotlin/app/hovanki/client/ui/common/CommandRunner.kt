package app.hovanki.client.ui.common

import app.hovanki.client.network.ApiResult
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.error_wrong_login
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

/**
 * Runs a view model's account and social commands ([app.hovanki.client.account.AccountManager],
 * [app.hovanki.client.social.SocialManager]): one at a time (repeated taps are ignored), with what went wrong as a
 * [FormMessage] for the screen.
 */
class CommandRunner(private val scope: CoroutineScope) {
    private val mutableMessage = MutableStateFlow<FormMessage?>(null)
    val message: StateFlow<FormMessage?> = mutableMessage.asStateFlow()

    private val mutableBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = mutableBusy.asStateFlow()

    /**
     * Runs [command]; [onSuccess] or, with the failure shown, [onFailure] afterwards. [wrongCredentials]: the text for
     * a wrong password (see [notice]).
     */
    fun execute(
        command: suspend () -> ApiResult<*>,
        wrongCredentials: StringResource = Res.string.error_wrong_login,
        onFailure: (ApiResult<*>) -> Unit = {},
        onSuccess: () -> Unit = {},
    ) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        mutableMessage.value = null
        scope.launch {
            try {
                val result = command()
                val notice = result.notice(wrongCredentials)
                if (notice == null) {
                    onSuccess()
                } else {
                    mutableMessage.value = FormMessage(notice)
                    onFailure(result)
                }
            } finally {
                mutableBusy.value = false
            }
        }
    }

    /** A form check failed, or news for the player ([isError] false). */
    fun show(notice: Notice, isError: Boolean = true) {
        mutableMessage.value = FormMessage(notice, isError)
    }

    fun dismiss() {
        mutableMessage.value = null
    }
}
