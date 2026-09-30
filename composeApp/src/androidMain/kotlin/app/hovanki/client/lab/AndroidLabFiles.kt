package app.hovanki.client.lab

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * The lab's files into the system «Share» (docs/radio-lab.md §4.2): written to `cache/lab/` only for that, through the
 * FileProvider only the debug build declares (`androidApp/src/debug`). Old exports go with the next one.
 */
class AndroidLabFiles(private val context: Context) : LabFiles {
    override fun share(files: List<LabFile>) {
        val folder = File(context.cacheDir, FOLDER)
        folder.deleteRecursively()
        folder.mkdirs()
        val uris = try {
            files.map { file ->
                val written = File(folder, file.name).apply { writeText(file.text) }
                FileProvider.getUriForFile(context, "${context.packageName}$AUTHORITY_SUFFIX", written)
            }
        } catch (e: IllegalArgumentException) {
            // No provider: not a debug build.
            Log.w(TAG, "No file provider for the lab", e)
            return
        }
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
            clipData = ClipData.newUri(context.contentResolver, files.first().name, uris.first()).also { clip ->
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    private companion object {
        const val TAG = "LabFiles"
        const val FOLDER = "lab"

        /** Matches `androidApp/src/debug/AndroidManifest.xml`. */
        const val AUTHORITY_SUFFIX = ".lab"
    }
}
