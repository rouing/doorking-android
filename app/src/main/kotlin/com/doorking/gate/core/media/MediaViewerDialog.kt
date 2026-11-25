package com.doorking.gate.core.media

import android.view.View
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.doorking.gate.core.ui.components.ErrorBanner
import okhttp3.OkHttpClient

data class MediaItemEntry(
    val label: String,
    val url: String,
    val isVideo: Boolean,
)

/**
 * Full-width modal viewer for authenticated camera snapshots (Coil 3) and 2-minute H.265/HEVC MP4 clips
 * (Jetpack Media3 ExoPlayer backed by the shared authenticated OkHttpClient for byte-range streaming).
 */
@Composable
fun MediaViewerDialog(
    title: String,
    subtitle: String,
    items: List<MediaItemEntry>,
    okHttpClient: OkHttpClient,
    onDismiss: () -> Unit,
) {
    if (items.isEmpty()) return
    var selectedIndex by remember(items) { mutableIntStateOf(0) }
    val active = items.getOrElse(selectedIndex) { items.first() }
    var lastVideoUrl by remember(items) {
        mutableStateOf(items.firstOrNull()?.takeIf { it.isVideo }?.url)
    }
    var videoError by remember(items) { mutableStateOf<String?>(null) }
    var retryNonce by remember(items) { mutableIntStateOf(0) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = title, style = MaterialTheme.typography.titleMedium)
                        if (subtitle.isNotBlank()) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close")
                    }
                }

                if (items.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items.forEachIndexed { idx, entry ->
                            FilterChip(
                                selected = idx == selectedIndex,
                                onClick = {
                                    selectedIndex = idx
                                    if (entry.isVideo) {
                                        lastVideoUrl = entry.url
                                    }
                                },
                                label = { Text(entry.label) },
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(Color.Black, MaterialTheme.shapes.medium),
                    contentAlignment = Alignment.Center,
                ) {
                    val videoUrl = lastVideoUrl
                    if (videoUrl != null) {
                        AuthenticatedVideoPlayer(
                            url = videoUrl,
                            isVisible = active.isVideo,
                            retryNonce = retryNonce,
                            okHttpClient = okHttpClient,
                            onPlaybackError = { videoError = it },
                            modifier = if (active.isVideo) Modifier.matchParentSize() else Modifier.size(0.dp),
                        )
                    }
                    if (!active.isVideo) {
                        AsyncImage(
                            model = active.url,
                            contentDescription = active.label,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                }

                if (active.isVideo && videoError != null) {
                    ErrorBanner(
                        message = videoError!!,
                        onRetry = {
                            videoError = null
                            retryNonce++
                        },
                    )
                }
            }
        }
    }
}

private class VideoPlayerHolder(
    var view: PlayerView? = null,
    var resumeOnStart: Boolean = false,
    var pausedByHide: Boolean = false,
    var isVisible: Boolean = true,
)

@OptIn(UnstableApi::class)
@Composable
private fun AuthenticatedVideoPlayer(
    url: String,
    isVisible: Boolean,
    retryNonce: Int,
    okHttpClient: OkHttpClient,
    onPlaybackError: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val player = remember(context, okHttpClient) {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(5_000, 15_000, 1_500, 3_000)
            .setTargetBufferBytes(8 * 1024 * 1024)
            .build()
        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
    }
    val holder = remember { VideoPlayerHolder(isVisible = isVisible) }
    val latestOnPlaybackError by rememberUpdatedState(onPlaybackError)

    SideEffect(player, url, retryNonce) {
        latestOnPlaybackError(null)
        holder.pausedByHide = false
        player.setMediaItem(MediaItem.fromUri(url))
        player.playWhenReady = isVisible
        player.prepare()
    }

    SideEffect(player, isVisible) {
        holder.isVisible = isVisible
        if (isVisible) {
            if (holder.pausedByHide) {
                holder.pausedByHide = false
                player.play()
            }
        } else {
            holder.pausedByHide = player.playWhenReady
            player.pause()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        holder.resumeOnStart = holder.isVisible && player.playWhenReady
        player.pause()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (holder.resumeOnStart && holder.isVisible) {
            holder.resumeOnStart = false
            player.play()
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val detail = error.message?.takeIf { it.isNotBlank() } ?: error.errorCodeName
                latestOnPlaybackError("Video playback failed ($detail)")
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            holder.view?.player = null
            holder.view = null
            player.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                visibility = if (isVisible) View.VISIBLE else View.GONE
                holder.view = this
            }
        },
        update = { view ->
            holder.view = view
            val targetVisibility = if (isVisible) View.VISIBLE else View.GONE
            if (view.visibility != targetVisibility) {
                view.visibility = targetVisibility
            }
            if (view.player !== player) {
                view.player = player
            }
        },
        modifier = modifier,
    )
}
