package com.fridgeprophet.app.ui.screens.scan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fridgeprophet.app.R
import com.fridgeprophet.app.ui.components.EmptyState
import com.fridgeprophet.app.ui.components.shrinkCapturedPhoto
import com.fridgeprophet.app.ui.components.uriToCacheFile
import java.io.File

@Composable
fun ScanScreen(
    onBack: () -> Unit,
    onFinished: () -> Unit,
    viewModel: ScanViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        when (state.phase) {
            ScanPhase.CAMERA -> CameraPhase(
                onBack = onBack,
                aiEnabled = state.aiEnabled,
                hint = state.message,
                error = state.error,
                quotaLeft = state.quotaLeft,
                quotaLow = state.quotaLow,
                onImageReady = viewModel::analyze,
                onDismissError = viewModel::clearError,
            )

            ScanPhase.ANALYZING -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = "AI 正在识别食材…",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        // 耗时和画面里的食材数量成正比 —— 模型是一个一个往下写的，
                        // 写得越多越慢。2026-09-29 实测（真实 AI）：
                        //   1 样 → 3 秒；40 样 → 51 秒
                        // 原来这里写的是「通常需要 5-20 秒」，对拍满一整箱的情况是错的，
                        // 会让人以为卡住了。所以改成按数量分档说明。
                        text = "模型要逐项判断种类和数量，食材越多越慢：几样大约 3 秒，十几样约 20 秒。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            ScanPhase.RESULT -> ResultPhase(
                state = state,
                onBack = onBack,
                onRetake = viewModel::backToCamera,
                onToggle = viewModel::toggleInclude,
                onRename = viewModel::rename,
                onQuantity = viewModel::setQuantity,
                onUnit = viewModel::setUnit,
                onLocation = viewModel::setLocation,
                onPurchaseDate = viewModel::setPurchaseDate,
                onExpiryDate = viewModel::setExpiryDate,
                onRemove = viewModel::removeFood,
                onAddManual = viewModel::addManualFood,
                onConfirm = { viewModel.confirm(onFinished) },
                onDismissError = viewModel::clearError,
            )
        }
    }
}

/* ------------------------------------------------------------------
 *  阶段一：拍照
 * ------------------------------------------------------------------ */

@Composable
private fun CameraPhase(
    onBack: () -> Unit,
    aiEnabled: Boolean,
    hint: String,
    error: String?,
    quotaLeft: Int,
    quotaLow: Boolean,
    onImageReady: (File) -> Unit,
    onDismissError: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 额度**确实**用完了（-1 = 还不知道，不算用完）。
    // 用完就把拍照/相册都禁用 —— 让用户按下去再弹一个 429 错误，
    // 比一开始就告诉他「今天不能用了」体验差得多。
    val quotaExhausted = quotaLeft == 0
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val executor = remember { ContextCompat.getMainExecutor(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // 相册兜底：模拟器或相机不可用时也能演示
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        // 走和头像同一个函数：它会按需压缩（原图可能十几 MB，直接传会超上限）
        runCatching {
            uriToCacheFile(context, uri, "scan")?.first ?: error("无法读取所选图片")
        }
            .onSuccess(onImageReady)
            .onFailure { cameraError = "读取图片失败：${it.message}" }
    }

    DisposableEffect(hasPermission) {
        if (!hasPermission) return@DisposableEffect onDispose { }

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                imageCapture = capture
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture,
                )
                cameraError = null
            } catch (e: Exception) {
                cameraError = "相机启动失败：${e.message ?: "未知错误"}。可以改用相册选图。"
            }
        }, executor)

        onDispose {
            runCatching {
                val f = ProcessCameraProvider.getInstance(context)
                if (f.isDone) f.get().unbindAll()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasPermission) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    title = "需要相机权限",
                    description = "拍摄冰箱照片需要用到相机。授权后即可开始扫描。",
                    actionText = "重新授权",
                    onAction = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                )
            }
        }

        // 顶部返回 + 提示
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onBack) {
                Text("← 返回", color = Color.White)
            }
            if (!aiEnabled) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xCC000000))
                        .padding(10.dp),
                ) {
                    Text(
                        text = "当前是演示模式：后端未配置 AI 密钥，会返回示例食材。",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                    )
                }
            }
            // 今日剩余 AI 额度。
            //
            // 为什么要在拍照前就显示：识别是要花额度的（后端按天限流），
            // 用户被 429 拦下来时才第一次知道有「额度」这回事，
            // 会觉得「昨天还好好的，怎么突然不行了」。
            //
            // `quotaLeft < 0` 表示还没拿到状态（接口失败/后端版本旧），
            // 这时候**什么都不显示** —— 显示「还剩 -1 次」比不显示更糟。
            if (aiEnabled && quotaLeft >= 0) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (quotaExhausted) Color(0xCC7A2E2E) else Color(0x99000000)
                        )
                        .padding(10.dp),
                ) {
                    Text(
                        text = when {
                            quotaExhausted ->
                                "今天的 AI 识别额度用完了，明天恢复。\n" +
                                    "现在仍然可以手动添加食材，或者看看「推荐」菜谱。"
                            quotaLow -> "今日 AI 识别还剩 $quotaLeft 次，省着用"
                            else -> "今日 AI 识别还剩 $quotaLeft 次"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                    )
                }
            }
            if (hint.isNotBlank() && aiEnabled) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x99000000))
                        .padding(10.dp),
                ) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                    )
                }
            }
        }

        // 底部操作区
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xCC000000))
                .padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            (cameraError ?: error)?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFFFB4AB),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                )
            }

            Text(
                text = "对准冰箱内部或台面上的食材，尽量拍清楚",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { galleryLauncher.launch("image/*") },
                    // 额度用完就别让他选了 —— 选完照样会被后端拦下
                    enabled = !quotaExhausted,
                ) {
                    Text("相册", color = if (quotaExhausted) Color(0x66FFFFFF) else Color.White)
                }

                // 快门
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(if (quotaExhausted) Color(0x66FFFFFF) else Color.White)
                        .clickable(enabled = imageCapture != null && !quotaExhausted) {
                            val capture = imageCapture ?: return@clickable
                            val file = File(context.cacheDir, "scan_${System.currentTimeMillis()}.jpg")
                            val options = ImageCapture.OutputFileOptions.Builder(file).build()
                            capture.takePicture(
                                options,
                                executor,
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                                        // 相机原图也要压。CameraX 是直接往 File 里写的，
                                        // 不走相册那条 Uri 路径 —— 漏了这一句，
                                        // 最常用的拍照入口传的还是十几 MB 的原图。
                                        onImageReady(shrinkCapturedPhoto(context, file))
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        cameraError = "拍照失败：${exception.message}"
                                    }
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_camera),
                        contentDescription = "拍照",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp),
                    )
                }

                // 占位，保持快门居中
                Box(modifier = Modifier.size(72.dp)) {
                    if (cameraError != null) {
                        TextButton(onClick = { cameraError = null; onDismissError() }) {
                            Text("重试", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

// 原来这里有个 copyToCache()，是 uriToCacheFile() 的重复实现，而且不做压缩。
// 现在两处都走 components/PickedImage.kt 里的 uriToCacheFile() ——
// 压缩、EXIF 补正、MIME 判定都在那一处，不会再出现「改了一边漏了另一边」。
