package vn.dichplus

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File

/** Màn hình máy ảnh: chụp ảnh → dịch → mở ảnh đã dịch (không dịch trực tiếp). */
@Composable
fun CameraScreen(
    src: String, tgt: String, status: String?,
    onBack: () -> Unit, onSrc: () -> Unit, onTgt: () -> Unit, onSwap: () -> Unit,
    onPickGallery: () -> Unit, onPhoto: (Uri) -> Unit
) {
    val ctx = LocalContext.current
    val owner = ctx as LifecycleOwner

    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) perm.launch(Manifest.permission.CAMERA) }

    var torch by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    val previewView = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
    }

    DisposableEffect(Unit) {
        onDispose { runCatching { ProcessCameraProvider.getInstance(ctx).get().unbindAll() } }
    }

    LaunchedEffect(granted) {
        if (!granted) return@LaunchedEffect
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            provider.unbindAll()
            camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
        }, ContextCompat.getMainExecutor(ctx))
    }

    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }

    fun shoot() {
        val f = File(ctx.cacheDir, "cam.jpg")
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(f).build(),
            ContextCompat.getMainExecutor(ctx),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(o: ImageCapture.OutputFileResults) { onPhoto(Uri.fromFile(f)) }
                override fun onError(e: ImageCaptureException) {
                    Toast.makeText(ctx, "Không chụp được ảnh", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF1B1C1E)).systemBarsPadding()) {
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                .background(Color.Black)
        ) {
            if (granted) AndroidView({ previewView }, Modifier.fillMaxSize())
            else Text(
                "Cần cấp quyền máy ảnh để chụp và dịch", color = Color.White, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(32.dp)
            )

            Box(
                Modifier.fillMaxWidth().height(96.dp)
                    .background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent)))
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại", tint = Color.White) }
                IconButton({ torch = !torch }) {
                    Icon(if (torch) Icons.Filled.FlashOn else Icons.Filled.FlashOff, "Đèn flash", tint = Color.White)
                }
                Text(
                    "Ống kính dịch", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(96.dp))
            }

            if (status != null) {
                Box(
                    Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp))
                        .background(Color(0xCC000000)).padding(horizontal = 20.dp, vertical = 14.dp)
                ) { Text(status, color = Color.White, fontSize = 16.sp) }
            }

            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(52.dp).clip(CircleShape).background(Color(0xFF2B2B2B))
                            .border(2.dp, Color.White, CircleShape).clickable { onPickGallery() },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Filled.PhotoLibrary, "Thư viện", tint = Color.White) }
                }
                Box(
                    Modifier.size(100.dp).clip(CircleShape).background(Color(0x66FFFFFF)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier.size(84.dp).clip(CircleShape).background(Color.White).clickable { shoot() },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Outlined.Translate, "Chụp và dịch", tint = Color.Black, modifier = Modifier.size(36.dp)) }
                }
                Spacer(Modifier.weight(1f))
            }
        }

        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CamLang(langName(src), Modifier.weight(1f), onSrc)
            IconButton(onSwap) { Icon(Icons.Filled.SwapHoriz, "Đổi", tint = Color(0xFFE0E0E0)) }
            CamLang(langName(tgt), Modifier.weight(1f), onTgt)
        }
    }
}

@Composable
private fun CamLang(text: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.height(58.dp), shape = RoundedCornerShape(20.dp), color = Color(0xFF3B3F47)) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, color = Color(0xFFD0D4DB), fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}
