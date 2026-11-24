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
import com.doorking.gate.core.auth.DEFAULT_SERVER_URL
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
 * File-backed crash reporter that survives Android 14–16 Background Activity Launch restrictions and automatically
 * uploads crash traces to the DoorKing server (`POST /api/client-crashes`) on the next launch.
 */
object CrashStore {
    private const val FILE_NAME = "last_crash.txt"
    private const val UPLOAD_PENDING_FILE = "pending_crash_upload.json"

    fun format(threadName: String, throwable: Throwable): String {
        val sw = StringWriter()
        sw.append("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
        sw.append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT}, ${Build.SUPPORTED_ABIS.joinToString()})\n")
        sw.append("Thread: $threadName\n\n")
        throwable.printStackTrace(PrintWriter(sw))
        return sw.toString()
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
    }

    /** Uploads any pending crash JSON to `${serverUrl}/api/client-crashes` on a background thread. */
    fun uploadPendingAsync(
        context: Context,
        serverUrl: String = DEFAULT_SERVER_URL,
        onStatus: ((String) -> Unit)? = null,
    ) {
        val pendingFile = File(context.filesDir, UPLOAD_PENDING_FILE)
        if (!pendingFile.exists()) return
        val payload = runCatching { pendingFile.readText() }.getOrNull() ?: return
        val target = "${serverUrl.trimEnd('/')}/api/client-crashes"
        thread(name = "doorking-crash-upload", isDaemon = true) {
            val outcome = runCatching {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder()
                    .url(target)
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                client.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        pendingFile.delete()
                        "Sent automatically to server."
                    } else {
                        "Upload failed (HTTP ${res.code})."
                    }
                }
            }.getOrElse { "Upload queued (offline)." }
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
        serverUrl: String = DEFAULT_SERVER_URL,
        onRetry: () -> Unit,
    ) {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val topPad = (52 * activity.resources.displayMetrics.density).toInt()

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
            text = "Uploading crash report to server…"
            textSize = 13f
            setPadding(0, pad / 3, 0, pad / 3)
        }

        val buttonRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, pad / 4, 0, pad / 2)
        }

        val copyBtn = Button(activity).apply {
            text = "Copy report"
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

        uploadPendingAsync(activity, serverUrl) { status ->
            activity.runOnUiThread { statusView.text = status }
        }
    }
}

class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val trace = CrashStore.read(this) ?: "No crash trace recorded."
        CrashStore.showInlineCrashView(this, trace) { finish() }
    }
}
