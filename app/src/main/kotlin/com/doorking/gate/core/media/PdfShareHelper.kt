package com.doorking.gate.core.media

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Saves downloaded PDF bytes into `cacheDir/pdfs/` off the main thread and launches the Android Share Sheet
 * using our non-exported `FileProvider` with `FLAG_GRANT_READ_URI_PERMISSION`.
 */
object PdfShareHelper {

    suspend fun sharePdfBytes(
        context: Context,
        filename: String,
        bytes: ByteArray,
    ): Result<Unit> = runCatching {
        val safeName = filename.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "ResidentInstructions.pdf" }
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "pdfs").apply { mkdirs() }
            File(dir, safeName).apply { writeBytes(bytes) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Gate Access Instructions")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "Share Resident Instructions PDF").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
