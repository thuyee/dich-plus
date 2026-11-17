package vn.dichplus

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.SystemClock
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

private class LiveBox(val rect: RectF, val text: String)

private val boxBg = Paint().apply { color = AColor.argb(225, 16, 20, 24) }
private val boxText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.WHITE }

private fun drawBox(c: android.graphics.Canvas, r: RectF, t: String, d: Float) {
    c.drawRoundRect(r, 6f * d, 6f * d, boxBg)
    val w = max(r.width().toInt(), 1)
    var size = (r.height() * 0.8f).coerceIn(11f * d, 22f * d)
    var layout: StaticLayout
    while (true) {
        boxText.textSize = size
        layout = StaticLayout.Builder.obtain(t, 0, t.length, boxText, w).build()
        if (layout.height <= r.height() || size <= 10f * d) break
        size -= d
    }
    c.save()
    c.translate(r.left, r.top + max(0f, (r.height() - layout.height) / 2f))
    layout.draw(c)
    c.restore()
}

/** Màn hình dịch bằng máy ảnh: xem trước trực tiếp + bản dịch phủ lên, chụp để dịch ảnh độ nét cao. */
@OptIn(ExperimentalGetImage::class)
@Composable
fun CameraScreen(
    src: String, tgt: String, status: String?,
    onBack: () -> Unit, onSrc: () -> Unit, onTgt: () -> Unit, onSwap: () -> Unit,
    onPickGallery: () -> Unit, onPhoto: (Uri) -> Unit
) {
    val ctx = LocalContext.current
    val owner = ctx as LifecycleOwner
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density

    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) perm.launch(Manifest.permission.CAMERA) }

    var torch by remember { mutableStateOf(false) }
    var liveOn by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var live by remember { mutableStateOf<List<LiveBox>>(emptyList()) }
    var imgW by remember { mutableStateOf(1) }
    var imgH by remember { mutableStateOf(1) }

    val previewView = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
    }
    val executor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(ctx).get().unbindAll() }
            executor.shutdown()
        }
    }

    LaunchedEffect(granted, src, tgt, liveOn) {
        if (!granted) return@LaunchedEffect
        live = emptyList()
        val cache = ConcurrentHashMap<String, String>()
        val busy = AtomicBoolean(false)
        var last = 0L
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            if (liveOn) analysis.setAnalyzer(executor) { proxy ->
                val now = SystemClock.uptimeMillis()
                val media = proxy.image
                if (media == null || now - last < 1500 || !busy.compareAndSet(false, true)) {
                    proxy.close(); return@setAnalyzer
                }
                last = now
                val rot = proxy.imageInfo.rotationDegrees
                val w = if (rot % 180 == 0) proxy.width else proxy.height
                val h = if (rot % 180 == 0) proxy.height else proxy.width
                val input = InputImage.fromMediaImage(media, rot)
                scope.launch(Dispatchers.Default) {
                    try {
                        val text = Engine.recognize(input, src)
                        val blocks = text?.textBlocks.orEmpty()
                            .filter { it.boundingBox != null && it.text.isNotBlank() }.take(30)
                        val res = blocks.map { b ->
                            async {
                                val t = b.text.replace('\n', ' ')
                                val tr = cache[t] ?: (runCatching { Engine.blockTranslate(t, src, tgt) }.getOrNull() ?: "")
                                    .also { cache[t] = it }
                                if (tr.isEmpty()) null else LiveBox(RectF(b.boundingBox!!), tr)
                            }
                        }.awaitAll().filterNotNull()
                        imgW = w; imgH = h; live = res
                    } catch (e: Exception) {
                    } finally {
                        proxy.close(); busy.set(false)
                    }
                }
            }
            provider.unbindAll()
            camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis)
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
            else Text("Cần cấp quyền máy ảnh để dịch bằng camera", color = Color.White,
                textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(32.dp))

            // bản dịch phủ lên khung hình
            Canvas(Modifier.fillMaxSize()) {
                if (live.isEmpty()) return@Canvas
                val sc = max(size.width / imgW, size.height / imgH)
                val ox = (size.width - imgW * sc) / 2f
                val oy = (size.height - imgH * sc) / 2f
                drawIntoCanvas { c ->
                    for (b in live) {
                        val r = RectF(
                            b.rect.left * sc + ox, b.rect.top * sc + oy,
                            b.rect.right * sc + ox, b.rect.bottom * sc + oy
                        )
                        drawBox(c.nativeCanvas, r, b.text, density)
                    }
                }
            }

            // thanh trên
            Box(
                Modifier.fillMaxWidth().height(96.dp)
                    .background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent)))
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại", tint = Color.White) }
                IconButton({ torch = !torch }) {
                    Icon(if (torch) Icons.Filled.FlashOn else Icons.Filled.FlashOff, "Đèn flash", tint = Color.White)
                }
                Text("Ống kính dịch", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                Box {
                    IconButton({ menuOpen = true }) { Icon(Icons.Filled.MoreVert, "Thêm", tint = Color.White) }
                    DropdownMenu(menuOpen, { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (liveOn) "Tắt dịch trực tiếp" else "Bật dịch trực tiếp") },
                            onClick = { liveOn = !liveOn; menuOpen = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Chọn ảnh từ thư viện") },
                            onClick = { menuOpen = false; onPickGallery() }
                        )
                    }
                }
            }

            if (status != null) {
                Box(
                    Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp))
                        .background(Color(0xCC000000)).padding(horizontal = 20.dp, vertical = 14.dp)
                ) { Text(status, color = Color.White, fontSize = 16.sp) }
            } else if (granted && liveOn && live.isEmpty()) {
                Box(
                    Modifier.align(Alignment.TopCenter).padding(top = 76.dp).clip(RoundedCornerShape(16.dp))
                        .background(Color(0x99000000)).padding(horizontal = 16.dp, vertical = 8.dp)
                ) { Text("Hướng camera vào chữ cần dịch", color = Color.White, fontSize = 14.sp) }
            }

            // hàng nút dưới: thư viện | chụp
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
