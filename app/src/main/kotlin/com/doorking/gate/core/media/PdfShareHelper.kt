package com.doorking.gate.core.media

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.doorking.gate.core.network.ResidentRowDto
import com.doorking.gate.core.network.TransactionRowDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Saves downloaded PDFs and generated CSV exports into `cacheDir/pdfs/` off the main thread and launches
 * the Android Share Sheet via our non-exported `FileProvider` with `FLAG_GRANT_READ_URI_PERMISSION`.
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

    suspend fun shareCsvText(
        context: Context,
        filename: String,
        csvContent: String,
        subject: String = "DoorKing Export",
    ): Result<Unit> = runCatching {
        val safeName = filename.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "export.csv" }
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "pdfs").apply { mkdirs() }
            File(dir, safeName).apply { writeText(csvContent, Charsets.UTF_8) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "Export CSV").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private fun csvCell(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"${value.replace("\"", "\"\"")}\"" else value
    }

    fun buildDirectoryCsv(rows: List<ResidentRowDto>, isVendor: Boolean): String {
        val sb = StringBuilder()
        if (isVendor) {
            sb.appendLine("Directory Code,Name,Entry Code,Cards,Note")
            for (r in rows) {
                sb.appendLine(
                    listOf(
                        r.directoryCode,
                        r.name,
                        r.entryCode,
                        r.cards.joinToString(" "),
                        r.note.orEmpty(),
                    ).joinToString(",") { csvCell(it) },
                )
            }
        } else {
            sb.appendLine("Directory Code,Name,Hidden,Phone,Entry Code,Cards,Note")
            for (r in rows) {
                val fullPhone = "${r.areaCode}${r.phone}".trim()
                sb.appendLine(
                    listOf(
                        r.directoryCode,
                        r.name,
                        if (r.hidden) "Yes" else "No",
                        fullPhone,
                        r.entryCode,
                        r.cards.joinToString(" "),
                        r.note.orEmpty(),
                    ).joinToString(",") { csvCell(it) },
                )
            }
        }
        return sb.toString()
    }

    fun buildTransactionsCsv(rows: List<TransactionRowDto>): String {
        val sb = StringBuilder()
        sb.appendLine("Date,Time,Event Type,Number,Name,Access,Relay,Directory Code")
        for (r in rows) {
            sb.appendLine(
                listOf(
                    r.date,
                    r.time,
                    r.eventType,
                    r.number.orEmpty(),
                    r.name,
                    r.access,
                    r.relay,
                    r.directoryCode,
                ).joinToString(",") { csvCell(it) },
            )
        }
        return sb.toString()
    }
}
