package app.hovanki.client.ui.verify

import app.hovanki.client.ui.common.FormMessage

/**
 * Confirming the email (docs/architecture.md, «Состояние экрана»): the panel, and what the main screen shows of it
 * (the card on «Play», the notice once confirmed).
 */
data class VerifyEmailUiState(
    /** The panel is open. */
    val isOpen: Boolean = false,
    /** «Later» on the card: it stays hidden until the app starts again (or another account logs in). */
    val isCardDismissed: Boolean = false,
    /** The email was just confirmed in the panel: the main screen shows a short notice. */
    val showConfirmed: Boolean = false,
    /** Where the code went. */
    val email: String = "",
    val code: String = "",
    /** The inline "change the email" form is open. */
    val isChangingEmail: Boolean = false,
    val newEmail: String = "",
    val password: String = "",
    /** Seconds until the code may be sent again; 0: now (the server says when it was too soon). */
    val resendSecondsLeft: Long = 0,
    val message: FormMessage? = null,
    val isBusy: Boolean = false,
)

/** What the player does with the email's confirmation: everything goes through [VerifyEmailViewModel.onEvent]. */
sealed interface VerifyEmailEvent {
    data object Open : VerifyEmailEvent

    data object Close : VerifyEmailEvent

    /** «Later» on the card. */
    data object DismissCard : VerifyEmailEvent

    data object DismissConfirmed : VerifyEmailEvent

    data class CodeChanged(val value: String) : VerifyEmailEvent

    data class NewEmailChanged(val value: String) : VerifyEmailEvent

    data class PasswordChanged(val value: String) : VerifyEmailEvent

    /** The panel closes by itself once the email is confirmed. */
    data object Verify : VerifyEmailEvent

    data object Resend : VerifyEmailEvent

    data object StartChangingEmail : VerifyEmailEvent

    data object CancelChangingEmail : VerifyEmailEvent

    /** The code goes to the new address; the old code no longer works. Needs the current password. */
    data object SaveEmail : VerifyEmailEvent

    data object DismissMessage : VerifyEmailEvent
}

/** The panel's own part: the fields are cleared when the panel closes. */
internal data class VerifyEmailLocal(
    val isOpen: Boolean = false,
    val isCardDismissed: Boolean = false,
    val showConfirmed: Boolean = false,
    val code: String = "",
    val isChangingEmail: Boolean = false,
    val newEmail: String = "",
    val password: String = "",
    val resendSecondsLeft: Long = 0,
)
