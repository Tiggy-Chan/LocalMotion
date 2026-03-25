package com.localmotion.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.localmotion.data.ArtifactRepository
import com.localmotion.util.FileExports
import java.io.File

@Composable
fun PlayerScreen(
    artifactId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val decodedId = remember(artifactId) { Uri.decode(artifactId) }
    val artifact by produceState<com.localmotion.model.VideoArtifact?>(initialValue = null, key1 = decodedId) {
        value = ArtifactRepository(context).loadArtifact(decodedId)
    }

    val player = remember(artifact?.mp4Path) {
        artifact?.let { item ->
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(File(item.mp4Path))))
                prepare()
                playWhenReady = false
            }
        }
    }

    DisposableEffect(player) {
        onDispose {
            player?.release()
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onBack) {
                Text("返回")
            }

            if (artifact == null || player == null) {
                Text("未找到视频记录。", style = MaterialTheme.typography.titleMedium)
                return@Column
            }

            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        this.player = player
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp),
            )

            Text("提示词：${artifact?.prompt?.ifBlank { "（空）" }}")
            Text("输出：${artifact?.width}x${artifact?.height} - ${artifact?.fps}fps - ${artifact?.durationMs}ms")
            Text("文件路径：${artifact?.mp4Path}")

            Button(
                onClick = {
                    artifact?.let { FileExports.shareVideo(context, File(it.mp4Path)) }
                },
            ) {
                Text("分享")
            }

            Button(
                onClick = {
                    artifact?.let { FileExports.exportToMediaStore(context, File(it.mp4Path)) }
                },
            ) {
                Text("导出到相册")
            }
        }
    }
}
