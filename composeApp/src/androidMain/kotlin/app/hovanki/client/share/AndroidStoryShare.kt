package app.hovanki.client.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The story picture is written to `cache/story/` only for this (the FileProvider of `:composeApp`'s manifest), then
 * goes to Instagram's «Add to story» or, without Instagram or the app id, to the system chooser. The last picture goes
 * with the next one.
 */
class AndroidStoryShare(private val context: Context, private val facebookAppId: String) : StoryShare {
    override fun share(png: ByteArray, caption: String) {
        val folder = File(context.cacheDir, FOLDER)
        folder.deleteRecursively()
        folder.mkdirs()
        val file = File(folder, FILE).apply { writeBytes(png) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}$AUTHORITY_SUFFIX", file)
        val story = storyIntent(uri)
        if (story != null) {
            context.grantUriPermission(INSTAGRAM, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(story)
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, caption)
            clipData = ClipData.newUri(context.contentResolver, FILE, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Instagram's «Add to story» with the picture as the background; null when it can't be used. */
    private fun storyIntent(uri: Uri): Intent? {
        if (facebookAppId.isEmpty()) return null
        val intent = Intent(ADD_TO_STORY).apply {
            setDataAndType(uri, MIME)
            setPackage(INSTAGRAM)
            putExtra(SOURCE_APPLICATION, facebookAppId)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return intent.takeIf { context.packageManager.resolveActivity(it, 0) != null }
    }

    private companion object {
        const val FOLDER = "story"
        const val FILE = "hovanki-story.png"
        const val MIME = "image/png"

        /** Matches `composeApp/src/androidMain/AndroidManifest.xml`. */
        const val AUTHORITY_SUFFIX = ".story"

        /** Meta's documented contract (developers.facebook.com, «Sharing to Stories»). */
        const val INSTAGRAM = "com.instagram.android"
        const val ADD_TO_STORY = "com.instagram.share.ADD_TO_STORY"
        const val SOURCE_APPLICATION = "source_application"
    }
}

internal actual fun ImageBitmap.encodePng(): ByteArray = ByteArrayOutputStream().use { out ->
    asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
    out.toByteArray()
}

/**
 * The story picture's own provider: a class of its own, so the manifest merger keeps it next to the debug build's lab
 * provider (two `<provider>` entries of one class clash).
 */
class StoryFileProvider : FileProvider()
