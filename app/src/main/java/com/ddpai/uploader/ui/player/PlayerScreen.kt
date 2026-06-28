package com.ddpai.uploader.ui.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(fileName: String, vm: PlayerViewModel = viewModel()) {
    val ctx = LocalContext.current
    var path by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(fileName) {
        path = vm.localPathFor(fileName)
        loaded = true
    }

    val player = remember { ExoPlayer.Builder(ctx).build() }

    DisposableEffect(path) {
        if (path != null) {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path!!))))
            player.prepare()
            player.playWhenReady = true
        }
        onDispose {
            player.release()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Video Player: $fileName",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )

        if (!loaded) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (path == null) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = "File not available locally (removed after upload).",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }
        } else {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            ) {
                AndroidView(
                    factory = {
                        PlayerView(it).apply {
                            this.player = player
                            this.useController = true
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
