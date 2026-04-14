package com.localmotion.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.localmotion.data.ArtifactRepository
import com.localmotion.util.FileExports
import java.io.File

@Composable
fun ArtifactScreen(
    artifactId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val decodedId = remember(artifactId) { Uri.decode(artifactId) }
    val artifact by produceState<com.localmotion.model.ImageArtifact?>(initialValue = null, key1 = decodedId) {
        value = ArtifactRepository(context).loadArtifact(decodedId)
    }
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = artifact?.imagePath) {
        value = artifact?.imagePath?.let(BitmapFactory::decodeFile)
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onBack) {
                Text("返回")
            }

            if (artifact == null || bitmap == null) {
                Text("未找到生成结果。", style = MaterialTheme.typography.titleMedium)
                return@Column
            }

            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp)),
            )

            Text("提示词：${artifact?.prompt?.ifBlank { "（空）" }}")
            artifact?.negativePrompt?.takeIf { it.isNotBlank() }?.let {
                Text("负向提示词：$it")
            }
            Text("模式：${artifact?.sourceMode?.uppercase()}")
            Text("CFG：${"%.1f".format(artifact?.guidanceScale ?: 7.5f)}")
            Text("步数：${artifact?.inferenceSteps}")
            if (artifact?.sourceMode == "img2img") {
                Text("重绘强度：${"%.2f".format(artifact?.strength ?: 0.75f)}")
            }
            artifact?.seed?.let { Text("Seed：$it") }
            Text("输出：${artifact?.width}x${artifact?.height}")
            Text("生成耗时：${artifact?.generationTimeMs} ms")
            Text("文件路径：${artifact?.imagePath}")

            Button(
                onClick = {
                    artifact?.let { FileExports.shareImage(context, File(it.imagePath)) }
                },
            ) {
                Text("分享")
            }

            Button(
                onClick = {
                    artifact?.let { FileExports.exportImageToMediaStore(context, File(it.imagePath)) }
                },
            ) {
                Text("导出到相册")
            }
        }
    }
}
