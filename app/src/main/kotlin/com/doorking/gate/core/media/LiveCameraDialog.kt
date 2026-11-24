package com.doorking.gate.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.doorking.gate.core.network.CameraChannelDto
import com.doorking.gate.core.ui.components.ErrorBanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Live MJPEG camera viewer backed by `GET /api/gates/{phone}/cameras/live/{channel}`.
 * Streams authenticated multipart JPEG frames off the main thread and cleanly aborts the HTTP call on close.
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
        onDismiss()
        return
    }
    var selectedChannel by remember { mutableIntStateOf(channels.first().channel) }
    var highQuality by remember { mutableStateOf(false) }
    var retryNonce by remember { mutableIntStateOf(0) }
    var currentFrame by remember { mutableStateOf<Bitmap?>(null) }
    var connecting by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    val base = serverUrl.trimEnd('/')
    val streamUrl = "$base/api/gates/$gatePhone/cameras/live/$selectedChannel?quality=${if (highQuality) "high" else "low"}"

    LaunchedEffect(streamUrl, retryNonce) {
        connecting = true
        error = null
        val streamingClient = okHttpClient.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val call = streamingClient.newCall(Request.Builder().url(streamUrl).get().build())
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
                    readMjpegFrames(stream) { jpegBytes ->
                        val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                        if (bmp != null) {
                            currentFrame = bmp
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
            call.cancel()
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
                    val bmp = currentFrame
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Live gate camera feed",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
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

/**
 * Extracts raw JPEG frames (`0xFF 0xD8` .. `0xFF 0xD9`) from an MJPEG stream.
 */
private fun readMjpegFrames(input: InputStream, onFrame: (ByteArray) -> Boolean) {
    val buf = ByteArray(8192)
    val frame = ByteArrayOutputStream(65536)
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
                frame.write(b)
                if (prev == 0xFF && b == 0xD9) {
                    inFrame = false
                    if (!onFrame(frame.toByteArray())) return
                }
            }
            prev = b
        }
    }
}
