package com.doorking.gate.core.media

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
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
                                onClick = { selectedIndex = idx },
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
                    if (active.isVideo) {
                        AuthenticatedVideoPlayer(
                            url = active.url,
                            okHttpClient = okHttpClient,
                            modifier = Modifier.matchParentSize(),
                        )
                    } else {
                        AsyncImage(
                            model = active.url,
                            contentDescription = active.label,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun AuthenticatedVideoPlayer(
    url: String,
    okHttpClient: OkHttpClient,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val player = remember(context, okHttpClient) {
        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
    }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    var resumeOnStart by remember { mutableStateOf(false) }

    LaunchedEffect(player, url) {
        player.setMediaItem(MediaItem.fromUri(url))
        player.playWhenReady = true
        player.prepare()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        resumeOnStart = player.playWhenReady
        player.pause()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (resumeOnStart) {
            player.play()
        }
    }

    DisposableEffect(player) {
        onDispose {
            playerViewRef?.player = null
            playerViewRef = null
            player.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                playerViewRef = this
            }
        },
        update = { view ->
            playerViewRef = view
            if (view.player !== player) {
                view.player = player
            }
        },
        modifier = modifier,
    )
}
