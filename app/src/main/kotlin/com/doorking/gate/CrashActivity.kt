package com.doorking.gate

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * File-backed crash reporter that survives Android 14–16 Background Activity Launch restrictions,
 * scrubs sensitive tokens/URLs/PII from stack traces, and uploads reports only when the user
 * explicitly taps "Send crash report".
 */
object CrashStore {
    private const val FILE_NAME = "last_crash.txt"
    private const val UPLOAD_PENDING_FILE = "pending_crash_upload.json"

    private val BEARER_RE = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+")
    private val DKS_TOKEN_RE = Regex("\\bdks_[A-Za-z0-9_-]{8,}\\b")
    private val URL_RE = Regex("https?://[^\\s\"'<>)]+")
    private val EMAIL_RE = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val FORMATTED_PHONE_RE = Regex("(?<!\\d)(?:\\+?1[-.\\s])?(?:\\(\\d{3}\\)|\\d{3})[-.\\s]\\d{3}[-.\\s]\\d{4}(?!\\d)")
    private val TEN_DIGIT_PHONE_RE = Regex("(?<![\\dA-Fa-f])\\d{10}(?![\\dA-Fa-f])")

    fun scrub(raw: String): String = raw
        .replace(BEARER_RE, "Bearer [REDACTED]")
        .replace(DKS_TOKEN_RE, "[REDACTED_TOKEN]")
        .replace(URL_RE, "[REDACTED_URL]")
        .replace(EMAIL_RE, "[REDACTED_EMAIL]")
        .replace(FORMATTED_PHONE_RE, "[REDACTED_PHONE]")
        .replace(TEN_DIGIT_PHONE_RE, "[REDACTED_PHONE]")

    fun format(threadName: String, throwable: Throwable): String {
        val sw = StringWriter()
        sw.append("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
        sw.append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT}, ${Build.SUPPORTED_ABIS.joinToString()})\n")
        sw.append("Thread: $threadName\n\n")
        throwable.printStackTrace(PrintWriter(sw))
        return scrub(sw.toString())
    }

    fun record(context: Context, threadName: String, throwable: Throwable): String {
        val text = format(threadName, throwable)
        runCatching {
            File(context.filesDir, FILE_NAME).writeText(text)
            val payload = buildJsonObject {
                put("appVersion", JsonPrimitive(BuildConfig.VERSION_NAME))
                put("versionCode", JsonPrimitive(BuildConfig.VERSION_CODE))
                put("device", JsonPrimitive("${Build.MANUFACTURER} ${Build.MODEL} (${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()})"))
                put("sdkInt", JsonPrimitive(Build.VERSION.SDK_INT))
                put("thread", JsonPrimitive(threadName))
                put("trace", JsonPrimitive(text))
            }.toString()
            File(context.filesDir, UPLOAD_PENDING_FILE).writeText(payload)
        }
        return text
    }

    fun read(context: Context): String? = runCatching {
        val f = File(context.filesDir, FILE_NAME)
        if (f.exists()) f.readText().takeIf { it.isNotBlank() } else null
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
        runCatching { File(context.filesDir, UPLOAD_PENDING_FILE).delete() }
    }

    /** Uploads any pending crash JSON to `${serverUrl}/api/client-crashes` on a background thread. */
    fun uploadPendingAsync(
        context: Context,
        serverUrl: String,
        onStatus: ((String) -> Unit)? = null,
    ) {
        val cleanServer = serverUrl.trim().trimEnd('/')
        if (cleanServer.isBlank()) {
            onStatus?.invoke("No server configured.")
            return
        }
        val pendingFile = File(context.filesDir, UPLOAD_PENDING_FILE)
        val payload = runCatching {
            if (pendingFile.exists()) {
                pendingFile.readText().takeIf { it.isNotBlank() }
            } else {
                read(context)?.let { trace ->
                    buildJsonObject {
                        put("appVersion", JsonPrimitive(BuildConfig.VERSION_NAME))
                        put("versionCode", JsonPrimitive(BuildConfig.VERSION_CODE))
                        put("device", JsonPrimitive("${Build.MANUFACTURER} ${Build.MODEL} (${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()})"))
                        put("sdkInt", JsonPrimitive(Build.VERSION.SDK_INT))
                        put("thread", JsonPrimitive("unknown"))
                        put("trace", JsonPrimitive(trace))
                    }.toString()
                }
            }
        }.getOrNull() ?: run {
            onStatus?.invoke("No pending crash report to send.")
            return
        }
        val target = "$cleanServer/api/client-crashes"
        thread(name = "doorking-crash-upload", isDaemon = true) {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            val outcome = try {
                val req = Request.Builder()
                    .url(target)
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                client.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        runCatching { pendingFile.delete() }
                        "Crash report sent."
                    } else {
                        "Upload failed (HTTP ${res.code})."
                    }
                }
            } catch (_: Throwable) {
                "Could not reach server. Try again when online."
            } finally {
                runCatching { client.dispatcher.executorService.shutdown() }
                runCatching { client.connectionPool.evictAll() }
            }
            onStatus?.invoke(outcome)
        }
    }

    fun installHandler(app: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            record(app, thread.name, throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun showInlineCrashView(
        activity: Activity,
        trace: String,
        serverUrl: String? = null,
        onRetry: () -> Unit,
    ) {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val topPad = (52 * activity.resources.displayMetrics.density).toInt()
        val targetUrl = serverUrl?.trim()?.takeIf { it.isNotBlank() }
        val targetHost = targetUrl?.toHttpUrlOrNull()?.host ?: targetUrl

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, topPad, pad, pad)
        }

        val title = TextView(activity).apply {
            text = "Doorking Gate Crash Report"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
        }

        val statusView = TextView(activity).apply {
            text = if (targetHost != null) {
                "Crash report saved locally (tokens & URLs scrubbed). Tap \"Send crash report\" to upload to $targetHost."
            } else {
                "Crash report saved locally (tokens & URLs scrubbed). Copy the report to share it."
            }
            textSize = 13f
            setPadding(0, pad / 3, 0, pad / 3)
        }

        val buttonRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, pad / 4, 0, pad / 2)
        }

        if (targetUrl != null) {
            val sendBtn = Button(activity).apply {
                text = "Send crash report"
                setOnClickListener { btn ->
                    btn.isEnabled = false
                    statusView.text = "Sending crash report to $targetHost…"
                    uploadPendingAsync(activity, targetUrl) { status ->
                        activity.runOnUiThread {
                            statusView.text = status
                            if (!status.startsWith("Crash report sent")) {
                                btn.isEnabled = true
                            }
                        }
                    }
                }
            }
            buttonRow.addView(sendBtn)
        }

        val copyBtn = Button(activity).apply {
            text = "Copy"
            setOnClickListener {
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Doorking Crash", trace))
                Toast.makeText(activity, "Crash report copied", Toast.LENGTH_SHORT).show()
            }
        }

        val retryBtn = Button(activity).apply {
            text = "Clear & Retry"
            setOnClickListener {
                clear(activity)
                onRetry()
            }
        }

        buttonRow.addView(copyBtn)
        buttonRow.addView(retryBtn)

        val body = TextView(activity).apply {
            text = trace
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, pad / 2, 0, pad)
        }

        val scroll = ScrollView(activity).apply {
            addView(body)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }

        root.addView(title)
        root.addView(statusView)
        root.addView(buttonRow)
        root.addView(scroll)
        activity.setContentView(root)
    }
}

class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thread(name = "doorking-crash-read", isDaemon = true) {
            val trace = CrashStore.read(this) ?: "No crash trace recorded."
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    CrashStore.showInlineCrashView(this, trace, serverUrl = null) { finish() }
                }
            }
        }
    }
}
