package app.hovanki.client.share

/**
 * The system's «Share» menu: the player picks a messenger (or anything else) and the text goes there. Android: a
 * chooser for `ACTION_SEND`; iOS: `UIActivityViewController`.
 */
interface ShareSheet {
    fun share(text: String)
}

/** No system menu (tests, previews): nothing happens. */
class NoopShareSheet : ShareSheet {
    override fun share(text: String) = Unit
}
