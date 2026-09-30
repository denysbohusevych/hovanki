@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.hovanki.client.lab

import app.hovanki.client.share.presentShareSheet
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.writeToFile

/**
 * The lab's files into the system «Share» (docs/radio-lab.md §4.2): written into the temporary folder only for that,
 * deleted when the sheet closes.
 */
class IosLabFiles : LabFiles {
    override fun share(files: List<LabFile>) {
        val folder = "${NSTemporaryDirectory()}lab"
        val manager = NSFileManager.defaultManager
        manager.removeItemAtPath(folder, error = null)
        manager.createDirectoryAtPath(folder, withIntermediateDirectories = true, attributes = null, error = null)
        val urls = files.map { file ->
            val path = "$folder/${file.name}"
            NSString.create(string = file.text)
                .writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
            NSURL.fileURLWithPath(path)
        }
        presentShareSheet(urls) { manager.removeItemAtPath(folder, error = null) }
    }
}
