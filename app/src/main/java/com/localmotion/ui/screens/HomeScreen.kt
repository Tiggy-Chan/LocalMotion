package com.localmotion.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.ui.unit.dp
import com.localmotion.BuildConfig
import com.localmotion.data.ArtifactRepository
import com.localmotion.data.RuntimeRepository
import com.localmotion.data.SettingsRepository
import com.localmotion.model.VideoArtifact
import com.localmotion.service.BackendService
import com.localmotion.service.ClipGenerationService
import com.localmotion.service.RuntimeInstallService
import com.localmotion.ui.theme.Clay
import com.localmotion.ui.theme.Copper
import com.localmotion.ui.theme.Graphite
import com.localmotion.ui.theme.Ink
import com.localmotion.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenArtifact: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRepository = remember { SettingsRepository(context) }
    val runtimeRepository = remember { RuntimeRepository(context) }
    val artifactRepository = remember { ArtifactRepository(context) }
    val userSettings by settingsRepository.settings.collectAsState(initial = com.localmotion.data.UserSettings())
    val backendState by BackendService.backendState.collectAsState()
    val generationState by ClipGenerationService.generationState.collectAsState()
    val installState by RuntimeInstallService.installState.collectAsState()

    var selectedUriString by rememberSaveable { mutableStateOf<String?>(null) }
    var prompt by rememberSaveable { mutableStateOf("") }
    var styleStrength by rememberSaveable { mutableStateOf(userSettings.defaultStyleStrength) }
    var artifacts by remember { mutableStateOf(emptyList<VideoArtifact>()) }
    var runtimeStatus by remember { mutableStateOf(runtimeRepository.status()) }
    var runtimeValidationRunning by remember { mutableStateOf(false) }

    val selectedBitmap by produceState<Bitmap?>(initialValue = null, key1 = selectedUriString) {
        value = selectedUriString?.let { uriString ->
            runCatching {
                ImageUtils.loadPreparedBitmap(context, Uri.parse(uriString), 512)
            }.getOrNull()
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        selectedUriString = uri?.toString()
    }

    LaunchedEffect(Unit) {
        artifacts = artifactRepository.loadArtifacts()
        runtimeStatus = runtimeRepository.status()
    }

    LaunchedEffect(generationState) {
        when (generationState) {
            is ClipGenerationService.GenerationState.Completed,
            is ClipGenerationService.GenerationState.Cancelled,
            is ClipGenerationService.GenerationState.Error,
            -> {
                artifacts = artifactRepository.loadArtifacts()
                runtimeStatus = runtimeRepository.status()
            }

            else -> Unit
        }
    }

    LaunchedEffect(installState) {
        when (installState) {
            is RuntimeInstallService.InstallState.Completed -> {
                runtimeStatus = withContext(Dispatchers.IO) { runtimeRepository.verifyInstalledBundle() }
            }

            is RuntimeInstallService.InstallState.Error,
            RuntimeInstallService.InstallState.Cancelled,
            -> {
                runtimeStatus = runtimeRepository.status()
            }

            else -> Unit
        }
    }

    val background = Brush.verticalGradient(
        colors = listOf(
            Graphite,
            Ink,
            Clay,
        ),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        TopAppBar(
            title = {
                Column {
                    Text("LocalMotion", fontWeight = FontWeight.Bold)
                    Text(
                        text = "骁龙 8 Gen 3 本地图生视频工作台",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            },
        )

        SectionCard(title = "运行模式", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = if (runtimeStatus.isReady) {
                    "QNN 运行时已安装"
                } else {
                    "当前为演示模式"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (runtimeStatus.isReady) {
                    "模型包已经在本地通过结构校验。sidecar 会优先尝试进入 QNN 后端，可用性再由 /health 决定。"
                } else {
                    "现在可以直接测试选图、生成、播放和导出流程。安装并校验真实运行时后，这里会切换到 QNN 路线。"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            runtimeStatus.manifest?.let { manifest ->
                Text(
                    text = "已安装版本：${manifest.version}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Bundle：${manifest.displayName}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                text = "模型目录：${runtimeStatus.modelDir.absolutePath}",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            )
            if (runtimeStatus.expectedBytes > 0L) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "已安装大小：${formatBytes(runtimeStatus.installedBytes)} / ${formatBytes(runtimeStatus.expectedBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (runtimeStatus.missingFiles.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "缺失文件：${runtimeStatus.missingFiles.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (runtimeStatus.validationErrors.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = runtimeStatus.validationErrors.joinToString(separator = "\n"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (runtimeValidationRunning) {
                Spacer(Modifier.height(8.dp))
                Text("正在重新校验已安装模型包…", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            when (val state = installState) {
                is RuntimeInstallService.InstallState.Preparing -> {
                    Spacer(Modifier.height(8.dp))
                    Text(state.message, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                is RuntimeInstallService.InstallState.Downloading -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "正在下载 ${state.fileName} · ${(state.overallProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LinearProgressIndicator(
                        progress = { state.overallProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                is RuntimeInstallService.InstallState.Verifying -> {
                    Spacer(Modifier.height(8.dp))
                    Text("正在校验 ${state.fileName}", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(
                        progress = { state.overallProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                is RuntimeInstallService.InstallState.Installing -> {
                    Spacer(Modifier.height(8.dp))
                    Text("正在切换到版本 ${state.version}", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                is RuntimeInstallService.InstallState.Completed -> {
                    Spacer(Modifier.height(8.dp))
                    Text("运行时安装完成：${state.manifest.version}", style = MaterialTheme.typography.bodySmall)
                }

                is RuntimeInstallService.InstallState.Error -> {
                    Spacer(Modifier.height(8.dp))
                    Text("安装失败：${state.message}", style = MaterialTheme.typography.bodySmall)
                }

                RuntimeInstallService.InstallState.Cancelled -> {
                    Spacer(Modifier.height(8.dp))
                    Text("运行时下载已取消", style = MaterialTheme.typography.bodySmall)
                }

                RuntimeInstallService.InstallState.Idle -> Unit
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (installState is RuntimeInstallService.InstallState.Preparing ||
                    installState is RuntimeInstallService.InstallState.Downloading ||
                    installState is RuntimeInstallService.InstallState.Verifying ||
                    installState is RuntimeInstallService.InstallState.Installing
                ) {
                    TextButton(onClick = { RuntimeInstallService.cancel(context) }) {
                        Text("取消下载")
                    }
                } else {
                    val manifestUrlConfigured = BuildConfig.DEFAULT_RUNTIME_MANIFEST_URL.isNotBlank()
                    Button(
                        onClick = { RuntimeInstallService.install(context) },
                        enabled = manifestUrlConfigured && !runtimeValidationRunning,
                    ) {
                        Text(if (runtimeStatus.isReady) "重新下载运行时" else "下载运行时")
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                runtimeValidationRunning = true
                                runtimeStatus = withContext(Dispatchers.IO) {
                                    runtimeRepository.verifyInstalledBundle()
                                }
                                runtimeValidationRunning = false
                            }
                        },
                        enabled = runtimeStatus.manifest != null &&
                            installState == RuntimeInstallService.InstallState.Idle,
                    ) {
                        Text("重新校验")
                    }
                }
            }
            if (BuildConfig.DEFAULT_RUNTIME_MANIFEST_URL.isBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "当前 APK 没有写入默认下载地址。可在项目 local.properties 中设置 localmotion.runtimeManifestUrl。",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "默认清单地址：${BuildConfig.DEFAULT_RUNTIME_MANIFEST_URL}",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                )
            }
        }

        SectionCard(title = "输入", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                ) {
                    Text("选择图片")
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = if (selectedBitmap != null) "已选择图片" else "未选择图片",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(12.dp))
            selectedBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(240.dp)
                        .clip(RoundedCornerShape(24.dp)),
                )
                Spacer(Modifier.height(12.dp))
            }
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("提示词") },
                placeholder = { Text("描述主体、风格、构图或修改意图") },
                supportingText = {
                    Text("当前先聚焦 SD1.5 的文生图/图生图能力，视频链路后续会重做。")
                },
            )
            Spacer(Modifier.height(12.dp))
            Text("风格强度：${"%.2f".format(styleStrength)}")
            Slider(
                value = styleStrength,
                onValueChange = { styleStrength = it },
                valueRange = 0f..1f,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        val uri = selectedUriString?.let(Uri::parse) ?: return@Button
                        scope.launch {
                            settingsRepository.updateStyleStrength(styleStrength)
                        }
                        ClipGenerationService.start(
                            context = context,
                            imageUri = uri,
                            prompt = prompt,
                            styleStrength = styleStrength,
                        )
                    },
                    enabled = selectedUriString != null &&
                        generationState !is ClipGenerationService.GenerationState.Running &&
                        generationState !is ClipGenerationService.GenerationState.Preparing,
                ) {
                    Text("运行当前生成链路")
                }
                TextButton(
                    onClick = { ClipGenerationService.cancel(context) },
                    enabled = generationState is ClipGenerationService.GenerationState.Running ||
                        generationState is ClipGenerationService.GenerationState.Preparing,
                ) {
                    Text("取消")
                }
            }
        }

        SectionCard(title = "状态", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("后端：${backendState.label(runtimeStatus.isReady)}")
            Spacer(Modifier.height(8.dp))
            when (val state = generationState) {
                ClipGenerationService.GenerationState.Idle -> Text("空闲")
                is ClipGenerationService.GenerationState.Preparing -> Text(state.message)
                is ClipGenerationService.GenerationState.Running -> {
                    Text("阶段：${state.stage.prettyStage()}")
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { state.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    state.previewFrameBase64?.let { preview ->
                        val previewBitmap = ImageUtils.base64ToBitmap(preview)
                        if (previewBitmap != null) {
                            Spacer(Modifier.height(12.dp))
                            Image(
                                bitmap = previewBitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(180.dp)
                                    .clip(RoundedCornerShape(18.dp)),
                            )
                        }
                    }
                }

                is ClipGenerationService.GenerationState.Completed -> {
                    Text("已完成：${state.artifact.id}")
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onOpenArtifact(state.artifact.id) }) {
                            Text("打开")
                        }
                    }
                }

                is ClipGenerationService.GenerationState.Cancelled -> Text("已取消")
                is ClipGenerationService.GenerationState.Error -> Text("错误：${state.message}")
            }
        }

        SectionCard(title = "历史记录", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (artifacts.isEmpty()) {
                Text("还没有生成视频。")
            } else {
                artifacts.forEach { artifact ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                text = artifact.id,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text("${artifact.durationMs / 1000f}s - ${artifact.fps}fps")
                            if (artifact.prompt.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = artifact.prompt,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { onOpenArtifact(artifact.id) }) {
                                Text("打开视频")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = Copper,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                content()
            },
        )
    }
}

private fun BackendService.BackendState.label(runtimeReady: Boolean): String {
    if (!runtimeReady) {
        return "演示模式"
    }
    return when (this) {
    BackendService.BackendState.Idle -> "待机"
    BackendService.BackendState.Placeholder -> "演示模式"
    BackendService.BackendState.Starting -> "正在启动"
    BackendService.BackendState.Running -> "运行中"
    is BackendService.BackendState.Error -> "异常：$message"
}
}

private fun String.prettyStage(): String = when (this) {
    "preprocess" -> "输入整理"
    "stylize" -> "首帧风格化"
    "depth" -> "深度估计"
    "render" -> "关键帧渲染"
    "interpolate" -> "中间帧补足"
    "encode" -> "视频编码"
    else -> replace('_', ' ')
}

private fun formatBytes(value: Long): String {
    if (value <= 0L) {
        return "0 B"
    }
    val kib = 1024L
    val mib = kib * 1024L
    val gib = mib * 1024L
    return when {
        value >= gib -> String.format("%.2f GB", value.toDouble() / gib.toDouble())
        value >= mib -> String.format("%.2f MB", value.toDouble() / mib.toDouble())
        value >= kib -> String.format("%.2f KB", value.toDouble() / kib.toDouble())
        else -> "$value B"
    }
}
