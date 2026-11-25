package com.doorking.gate.core.media

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.doorking.gate.core.network.ResidentRowDto
import com.doorking.gate.core.network.TransactionRowDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Saves downloaded PDFs and generated CSV exports into randomized subdirectories under `cacheDir/pdfs/`
 * off the main thread and launches the Android Share Sheet via our non-exported `FileProvider` with
 * `FLAG_GRANT_READ_URI_PERMISSION`.
 */
object PdfShareHelper {

    private const val EXPORTS_DIR_NAME = "pdfs"
    private const val STALE_EXPORT_MAX_AGE_MS = 60L * 60L * 1000L // 1 hour

    /**
     * Deletes all cached PDF and CSV exports safely (e.g. on sign-out).
     */
    fun clearExports(context: Context) {
        runCatching {
            File(context.cacheDir, EXPORTS_DIR_NAME).deleteRecursively()
        }
    }

    private fun prepareExportFile(context: Context, safeName: String): File {
        val root = File(context.cacheDir, EXPORTS_DIR_NAME).apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - STALE_EXPORT_MAX_AGE_MS
        root.listFiles()?.forEach { entry ->
            if (entry.isFile || entry.lastModified() < cutoff) {
                runCatching { entry.deleteRecursively() }
            }
        }
        val subdir = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        return File(subdir, safeName)
    }

    suspend fun sharePdfBytes(
        context: Context,
        filename: String,
        bytes: ByteArray,
    ): Result<Unit> = try {
        val safeName = filename.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "ResidentInstructions.pdf" }
        val uri = withContext(Dispatchers.IO) {
            val file = prepareExportFile(context, safeName).apply { writeBytes(bytes) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Gate Access Instructions")
            clipData = ClipData.newRawUri(safeName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "Share Resident Instructions PDF").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        withContext(Dispatchers.Main.immediate) {
            context.startActivity(chooser)
        }
        Result.success(Unit)
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        Result.failure(t)
    }

    suspend fun shareCsvText(
        context: Context,
        filename: String,
        csvContent: String,
        subject: String = "DoorKing Export",
    ): Result<Unit> = shareCsvContent(context, filename, subject) { csvContent }

    suspend fun shareDirectoryCsv(
        context: Context,
        filename: String,
        rows: List<ResidentRowDto>,
        isVendor: Boolean,
        subject: String = if (isVendor) "Vendors Export" else "Residents Export",
    ): Result<Unit> = shareCsvContent(context, filename, subject) {
        buildDirectoryCsv(rows, isVendor = isVendor)
    }

    suspend fun shareTransactionsCsv(
        context: Context,
        filename: String,
        rows: List<TransactionRowDto>,
        subject: String = "Gate Activity Export",
    ): Result<Unit> = shareCsvContent(context, filename, subject) {
        buildTransactionsCsv(rows)
    }

    private suspend fun shareCsvContent(
        context: Context,
        filename: String,
        subject: String,
        contentProvider: () -> String,
    ): Result<Unit> = try {
        val safeName = filename.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "export.csv" }
        val uri = withContext(Dispatchers.IO) {
            val csvContent = contentProvider()
            val file = prepareExportFile(context, safeName).apply { writeText(csvContent, Charsets.UTF_8) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            clipData = ClipData.newRawUri(safeName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "Export CSV").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        withContext(Dispatchers.Main.immediate) {
            context.startActivity(chooser)
        }
        Result.success(Unit)
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        Result.failure(t)
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
