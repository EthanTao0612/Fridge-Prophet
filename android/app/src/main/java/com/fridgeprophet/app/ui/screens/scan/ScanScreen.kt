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
import java.io.File
import java.io.FileOutputStream

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
    onImageReady: (File) -> Unit,
    onDismissError: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

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
        runCatching { copyToCache(context, uri) }
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
                OutlinedButton(onClick = { galleryLauncher.launch("image/*") }) {
                    Text("相册", color = Color.White)
                }

                // 快门
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable(enabled = imageCapture != null) {
                            val capture = imageCapture ?: return@clickable
                            val file = File(context.cacheDir, "scan_${System.currentTimeMillis()}.jpg")
                            val options = ImageCapture.OutputFileOptions.Builder(file).build()
                            capture.takePicture(
                                options,
                                executor,
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                                        onImageReady(file)
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

/** 相册选中的图片复制到缓存目录，统一走上传逻辑 */
private fun copyToCache(context: Context, uri: Uri): File {
    val target = File(context.cacheDir, "scan_${System.currentTimeMillis()}.jpg")
    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "无法打开所选图片" }
        FileOutputStream(target).use { output -> input.copyTo(output) }
    }
    return target
}
