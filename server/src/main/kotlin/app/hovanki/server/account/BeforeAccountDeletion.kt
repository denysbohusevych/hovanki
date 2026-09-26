package app.hovanki.server.account

import app.hovanki.shared.protocol.UserId

/**
 * A bean that must act before an account is deleted, e.g. hand the user's groups over or drop their invitations.
 * [AccountService.delete] calls every such bean inside its transaction, then deletes the user (the foreign keys
 * cascade the rest). Throwing aborts the deletion.
 */
fun interface BeforeAccountDeletion {
    fun beforeDelete(userId: UserId)
}
