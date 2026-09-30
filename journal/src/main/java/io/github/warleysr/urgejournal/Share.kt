package io.github.warleysr.urgejournal

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Shares text as a real file. Putting a whole journal into an intent's text goes through Binder,
 * which has a size limit of about a megabyte, and a long-kept journal can pass it and crash the
 * app; a file has no such limit.
 */
object Share {
    /** Writes [text] to [fileName] in the cache and opens the share sheet for it. False if it could not. */
    fun file(context: Context, fileName: String, mime: String, text: String): Boolean = try {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        // Earlier exports are not needed any more; only the one being shared now stays.
        ExportFiles.cleanup(dir, olderThanMs = 0L)
        val file = File(dir, fileName).apply { writeText(text) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }

    /** Removes exports left in the cache. Called at launch; the share sheet has long since read anything older than a few minutes. */
    fun cleanup(context: Context, olderThanMs: Long = 5L * 60 * 1000) {
        ExportFiles.cleanup(File(context.cacheDir, "exports"), olderThanMs)
    }

    /** A short plain-text message, safe because it is small. */
    fun text(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text.take(20_000))
        }
        try {
            context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }
}
