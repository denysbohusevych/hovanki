package app.hovanki.client.share

import android.content.Context
import android.content.Intent

/** The system chooser for plain text; started from the application context, so in a task of its own. */
class AndroidShareSheet(private val context: Context) : ShareSheet {
    override fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
