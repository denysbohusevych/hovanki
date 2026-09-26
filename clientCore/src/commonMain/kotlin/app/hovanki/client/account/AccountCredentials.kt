package app.hovanki.client.account

/**
 * What a game session needs from the player's account: the token to create and join games with, and a way to report
 * that the server no longer accepts it. [AccountManager] implements it; [None] is a guest.
 */
interface AccountCredentials {
    /** Account token of the logged-in user (confirmed email or not); null for a guest. */
    val accountToken: String?

    /** The server answered 401 to a call made with [token]: the account session is gone, log out locally. */
    fun onTokenRejected(token: String)

    /** No account: games are created and joined as a guest. */
    data object None : AccountCredentials {
        override val accountToken: String? = null

        override fun onTokenRejected(token: String) = Unit
    }
}
