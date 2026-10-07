package app.hovanki.client.share

import androidx.compose.ui.graphics.ImageBitmap

/**
 * A finished game's story picture (docs/adr/0024-instagram-stories.md): straight into Instagram Stories when the phone
 * has Instagram and the build has Meta's app id (`BuildConstants.FACEBOOK_APP_ID`), else into the system «Share» with
 * [caption] next to it, where Instagram is one of the choices.
 */
interface StoryShare {
    fun share(png: ByteArray, caption: String)
}

/** No system menu (tests, previews): nothing happens. */
class NoopStoryShare : StoryShare {
    override fun share(png: ByteArray, caption: String) = Unit
}

/** The picture as PNG bytes. */
internal expect fun ImageBitmap.encodePng(): ByteArray
