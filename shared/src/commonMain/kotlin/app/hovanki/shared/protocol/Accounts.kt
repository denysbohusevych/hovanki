package app.hovanki.shared.protocol

import kotlinx.serialization.Serializable

// Accounts (docs/adr/0004-accounts-friends-chat.md): nickname + email + password, the email confirmed with a code.
// The account token goes to `Authorization: Bearer <token>` like a game token, but on the account routes.

/** New account; its email is unconfirmed until [VerifyEmailRequest]. [language] picks the language of the emails. */
@Serializable
data class RegisterRequest(
    val nickname: String,
    val email: String,
    val password: String,
    /** The app's language (`en`, `ru`, `uk`); anything else means English. */
    val language: String = "en",
)

/** [login] is the email or the nickname. */
@Serializable
data class LoginRequest(val login: String, val password: String)

/** A logged-in device: the account token (a secret, kept in Keystore/Keychain) and who it belongs to. */
@Serializable
data class AccountSession(val token: String, val user: UserProfile)

/** The account as its owner sees it; other players only ever get a [UserSummary]. */
@Serializable
data class UserProfile(
    val id: UserId,
    val nickname: String,
    val email: String,
    val emailVerified: Boolean,
    val createdAtMillis: Long,
)

/** Another user as everyone may see them: no email. */
@Serializable
data class UserSummary(val id: UserId, val nickname: String)

/** The 6-digit code from the email. */
@Serializable
data class VerifyEmailRequest(val code: String)

/** Fixes a mistyped email before it is confirmed; sends a new code there. */
@Serializable
data class ChangeEmailRequest(val email: String)

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

@Serializable
data class DeleteAccountRequest(val password: String)

/** Sends a reset code to [email] if it belongs to an account; the answer is the same either way. */
@Serializable
data class PasswordResetRequest(val email: String)

/** Sets a new password with the emailed code; ends every other session and confirms the email. */
@Serializable
data class PasswordResetConfirmRequest(val email: String, val code: String, val newPassword: String)
