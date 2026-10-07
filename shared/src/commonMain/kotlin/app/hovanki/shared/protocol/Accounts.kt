package app.hovanki.shared.protocol

import kotlinx.serialization.Serializable

// Accounts (docs/adr/0004-accounts-friends-chat.md): nickname + email + password, the email confirmed with a code.
// The account token goes to `Authorization: Bearer <token>` like a game token, but on the account routes.

/**
 * New account, usable right away. Its email stays unconfirmed until [VerifyEmailRequest] (or a password reset):
 * confirming is optional, a code is emailed at once. [language] picks the language of the emails.
 */
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
    /** Optional: the account works either way, a confirmed email is known to reach its owner (password reset). */
    val emailVerified: Boolean,
    val createdAtMillis: Long,
    /**
     * The player agreed to keep the routes of their games (docs/adr/0007-game-history-and-routes.md); off by default
     * and from servers that don't know it. Changed with [PrivacyRequest].
     */
    val saveRoutes: Boolean = false,
    /**
     * The radio lab's screen is for this account in the field build (docs/adr/0018-field-test-build.md §4.D): staff,
     * while the server has [ServerFeature.RADIO_LAB] on. Never set by the account itself; off from servers that don't
     * know it. Debug builds show the lab whatever it says.
     */
    val labAccess: Boolean = false,
    /**
     * The player's city for the city leaderboard (`Cities.IDS`), where the phone found itself (`Cities.at`); null: none
     * (the default, and from servers that don't know it). Changed with [CityRequest] (docs/adr/0022-city-leaderboard.md).
     */
    val city: String? = null,
)

/** [ApiRoutes.ME_CITY]: the player's city, one of `Cities.IDS`, or null to forget it. */
@Serializable
data class CityRequest(val city: String? = null)

/** Another user as everyone may see them: no email. */
@Serializable
data class UserSummary(val id: UserId, val nickname: String)

/** The 6-digit code from the email. */
@Serializable
data class VerifyEmailRequest(val code: String)

/**
 * Fixes a mistyped email before it is confirmed; sends a new code there. Needs the current [password]: an unconfirmed
 * account can live on for years, and whoever controls its email can take it over with a password reset.
 */
@Serializable
data class ChangeEmailRequest(val email: String, val password: String)

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
