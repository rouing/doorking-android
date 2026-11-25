package com.doorking.gate.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.os.Trace
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.doorking.gate.core.network.CameraChannelDto
import com.doorking.gate.core.ui.components.ErrorBanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private const val MAX_MJPEG_FRAME_BYTES = 4 * 1024 * 1024
private const val MAX_DECODE_DIMENSION_PX = 1280
private const val MIN_FRAME_INTERVAL_MS = 40L

/**
 * Live MJPEG camera viewer backed by `GET /api/gates/{phone}/cameras/live/{channel}`.
 * Streams authenticated multipart JPEG frames off the main thread while `STARTED` and cleanly aborts on stop/close.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveCameraDialog(
    serverUrl: String,
    gatePhone: String,
    channels: List<CameraChannelDto>,
    okHttpClient: OkHttpClient,
    onDismiss: () -> Unit,
) {
    if (channels.isEmpty()) {
        SideEffect { onDismiss() }
        return
    }
    var selectedChannel by remember { mutableIntStateOf(channels.first().channel) }
    var highQuality by remember { mutableStateOf(false) }
    var retryNonce by remember { mutableIntStateOf(0) }
    var currentFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    var connecting by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    val streamingClient = remember(okHttpClient) {
        okHttpClient.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
    val base = serverUrl.trimEnd('/')
    val streamUrl = "$base/api/gates/$gatePhone/cameras/live/$selectedChannel?quality=${if (highQuality) "high" else "low"}"

    LaunchedEffect(streamUrl, retryNonce, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            connecting = true
            error = null
            val call = streamingClient.newCall(Request.Builder().url(streamUrl).get().build())
            val cancelHandle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
            try {
                withContext(Dispatchers.IO) {
                    val response = call.execute()
                    response.use { res ->
                        if (!res.isSuccessful) {
                            error = "Camera stream failed (HTTP ${res.code})"
                            connecting = false
                            return@withContext
                        }
                        val stream = res.body?.byteStream() ?: run {
                            error = "Empty camera stream"
                            connecting = false
                            return@withContext
                        }
                        var lastFrameMs = Long.MIN_VALUE
                        readMjpegFrames(stream) { bytes, length ->
                            val now = SystemClock.elapsedRealtime()
                            if (lastFrameMs != Long.MIN_VALUE && now - lastFrameMs < MIN_FRAME_INTERVAL_MS) {
                                return@readMjpegFrames isActive
                            }
                            val imageBitmap = decodeDownsampledImageBitmap(bytes, length)
                            if (imageBitmap != null) {
                                lastFrameMs = now
                                currentFrame = imageBitmap
                                connecting = false
                            }
                            isActive
                        }
                    }
                }
            } catch (ce: CancellationException) {
                call.cancel()
                throw ce
            } catch (t: Throwable) {
                if (isActive) {
                    error = t.message ?: "Live camera connection ended"
                    connecting = false
                }
            } finally {
                cancelHandle.dispose()
                call.cancel()
                currentFrame = null
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val activeName = channels.firstOrNull { it.channel == selectedChannel }?.name
                        ?.ifBlank { "Channel $selectedChannel" } ?: "Live Camera"
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Live Camera", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "$activeName · ${if (highQuality) "High quality" else "Standard"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { retryNonce++ }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Reconnect live view")
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close live camera")
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    LiveFrameImage(
                        frameProvider = { currentFrame },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (connecting) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(color = Color.White)
                            Text(
                                "Connecting to camera…",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                            )
                        }
                    }
                }

                if (error != null) {
                    ErrorBanner(error!!, onRetry = { retryNonce++ })
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    channels.forEach { ch ->
                        val sel = ch.channel == selectedChannel
                        Surface(
                            onClick = {
                                if (!sel) {
                                    currentFrame = null
                                    selectedChannel = ch.channel
                                }
                            },
                            shape = CircleShape,
                            color = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = if (sel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        ) {
                            Text(
                                text = ch.name.ifBlank { "Ch ${ch.channel}" },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                    Surface(
                        onClick = { highQuality = !highQuality },
                        shape = CircleShape,
                        color = if (highQuality) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = if (highQuality) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        Text(
                            text = if (highQuality) "HD" else "SD",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveFrameImage(
    frameProvider: () -> ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .semantics { contentDescription = "Live gate camera feed" }
            .drawBehind {
                val frame = frameProvider() ?: return@drawBehind
                val srcW = frame.width
                val srcH = frame.height
                val canvasW = size.width
                val canvasH = size.height
                if (srcW <= 0 || srcH <= 0 || canvasW <= 0f || canvasH <= 0f) return@drawBehind
                val scale = minOf(canvasW / srcW.toFloat(), canvasH / srcH.toFloat())
                val dstW = (srcW * scale).roundToInt().coerceAtLeast(1)
                val dstH = (srcH * scale).roundToInt().coerceAtLeast(1)
                val dstX = ((canvasW - dstW) / 2f).roundToInt()
                val dstY = ((canvasH - dstH) / 2f).roundToInt()
                drawImage(
                    image = frame,
                    dstOffset = IntOffset(dstX, dstY),
                    dstSize = IntSize(dstW, dstH),
                )
            },
    )
}

private class AccessibleByteArrayOutputStream(initialSize: Int) : ByteArrayOutputStream(initialSize) {
    val backingBuffer: ByteArray get() = buf
    val length: Int get() = count
}

private fun decodeDownsampledImageBitmap(
    bytes: ByteArray,
    length: Int,
    maxDimensionPx: Int = MAX_DECODE_DIMENSION_PX,
): ImageBitmap? {
    Trace.beginSection("Doorking:MjpegDecode")
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, length, bounds)
        val srcWidth = bounds.outWidth
        val srcHeight = bounds.outHeight
        if (srcWidth <= 0 || srcHeight <= 0) return null

        var sampleSize = 1
        while ((srcWidth / sampleSize) > maxDimensionPx || (srcHeight / sampleSize) > maxDimensionPx) {
            sampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, length, decodeOpts) ?: return null
        bmp.asImageBitmap()
    } finally {
        Trace.endSection()
    }
}

/**
 * Extracts raw JPEG frames (`0xFF 0xD8` .. `0xFF 0xD9`) from an MJPEG stream into a bounded buffer
 * without per-frame `toByteArray()` copies.
 */
private fun readMjpegFrames(input: InputStream, onFrame: (ByteArray, Int) -> Boolean) {
    val buf = ByteArray(8192)
    val frame = AccessibleByteArrayOutputStream(65536)
    var inFrame = false
    var prev = -1

    while (true) {
        val read = input.read(buf)
        if (read <= 0) break
        for (i in 0 until read) {
            val b = buf[i].toInt() and 0xFF
            if (!inFrame) {
                if (prev == 0xFF && b == 0xD8) {
                    frame.reset()
                    frame.write(0xFF)
                    frame.write(0xD8)
                    inFrame = true
                }
            } else {
                if (frame.length >= MAX_MJPEG_FRAME_BYTES) {
                    frame.reset()
                    inFrame = false
                } else {
                    frame.write(b)
                    if (prev == 0xFF && b == 0xD9) {
                        inFrame = false
                        if (!onFrame(frame.backingBuffer, frame.length)) return
                    }
                }
            }
            prev = b
        }
    }
}
