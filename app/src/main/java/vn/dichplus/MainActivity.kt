package vn.dichplus

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Collator
import java.util.Locale

// ---------- màu (giống giao diện tối của Google Dịch) ----------
private val BgOuter = Color(0xFF1E1E1E)
private val BgCard = Color(0xFF131313)
private val BtnDark = Color(0xFF131313)
private val Salmon = Color(0xFFFFB4A1)
private val SalmonDark = Color(0xFF5C1900)
private val SideBtn = Color(0xFF4A2C24)
private val SideIcon = Color(0xFFFFDBD0)
private val Hint = Color(0xFFC8C8C8)
private val OutBlue = Color(0xFFA8C7FA)

// ---------- ngôn ngữ ----------
private val viLocale: Locale = Locale.forLanguageTag("vi")

fun langName(tag: String): String =
    if (tag == "auto") "Phát hiện ngôn ngữ"
    else Locale.forLanguageTag(tag).getDisplayName(viLocale)
        .removePrefix("Tiếng ").replaceFirstChar { it.uppercase() }

private val allLangs: List<String> by lazy {
    val col = Collator.getInstance(viLocale)
    TranslateLanguage.getAllLanguages().sortedWith(Comparator<String> { x, y -> col.compare(langName(x), langName(y)) })
}

private fun loadSaved(p: SharedPreferences): List<Pair<String, String>> =
    (p.getString("saved", "") ?: "").split('\u0002').filter { it.isNotBlank() }.mapNotNull {
        val s = it.split('\u0001'); if (s.size == 2) s[0] to s[1] else null
    }

private fun storeSaved(p: SharedPreferences, l: List<Pair<String, String>>) {
    p.edit().putString("saved", l.joinToString("\u0002") { "${it.first}\u0001${it.second}" }).apply()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { App() } }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val prefs = remember { ctx.getSharedPreferences("p", Context.MODE_PRIVATE) }

    var src by remember { mutableStateOf(prefs.getString("src", "en") ?: "en") }
    var tgt by remember { mutableStateOf(prefs.getString("tgt", "vi") ?: "vi") }
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var detected by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var pick by remember { mutableStateOf<String?>(null) }
    var screen by remember { mutableStateOf("main") }
    var showCamera by remember { mutableStateOf(false) }
    var showSaved by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var resultBmp by remember { mutableStateOf<Bitmap?>(null) }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    val saved = remember { mutableStateListOf<Pair<String, String>>().apply { addAll(loadSaved(prefs)) } }

    LaunchedEffect(src, tgt) { prefs.edit().putString("src", src).putString("tgt", tgt).apply() }

    // dịch tự động khi gõ
    LaunchedEffect(input, src, tgt) {
        if (input.isBlank()) { output = ""; status = null; return@LaunchedEffect }
        delay(500)
        status = "Đang dịch…"
        try {
            val r = Engine.translate(input.trim(), src, tgt)
            output = r.text; detected = r.detected; status = null
        } catch (e: Exception) {
            output = ""
            status = "Không dịch được. Lần đầu cần mạng để tải gói ngôn ngữ."
        }
    }

    // ---------- launchers ----------
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val t = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (t != null) input = t
    }

    fun processUri(uri: Uri) {
        scope.launch {
            status = "Đang đọc & dịch ảnh…"
            try {
                val out = withContext(Dispatchers.Default) {
                    val b = Engine.loadBitmap(ctx, uri) ?: throw IllegalStateException("bitmap")
                    Engine.translateImage(b, src, tgt)
                }
                resultBmp = out; status = null
            } catch (e: Exception) {
                status = "Không dịch được ảnh. Lần đầu cần mạng để tải gói ngôn ngữ."
            }
        }
    }

    val takePic = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) photoUri?.let { processUri(it) }
    }
    val pickPic = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) processUri(uri)
    }

    val projLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            val i = Intent(ctx, ScreenService::class.java).putExtra("code", r.resultCode).putExtra("data", r.data)
            ContextCompat.startForegroundService(ctx, i)
            (ctx as? Activity)?.moveTaskToBack(true)
        }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
        projLauncher.launch(mpm.createScreenCaptureIntent())
    }
    val overlayLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    fun requestOverlay() {
        overlayLauncher.launch(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
        )
    }

    fun startScreenTranslate() {
        if (!Settings.canDrawOverlays(ctx)) {
            Toast.makeText(ctx, "Bật quyền “Hiển thị trên ứng dụng khác” rồi bấm lại nhé", Toast.LENGTH_LONG).show()
            requestOverlay(); return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else projLauncher.launch(ctx.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }

    fun listen(tag: String?) {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Hãy nói…")
        if (tag != null) i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        try { speech.launch(i) } catch (e: Exception) {
            Toast.makeText(ctx, "Máy chưa có dịch vụ nhận diện giọng nói", Toast.LENGTH_LONG).show()
        }
    }

    val tts = remember { TextToSpeech(ctx) { } }
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }

    // ---------- giao diện ----------
    if (screen == "conv") {
        BackHandler { screen = "main" }
        ConversationScreen(a = if (src == "auto") "en" else src, b = tgt, onBack = { screen = "main" })
        return
    }

    Column(Modifier.fillMaxSize().background(BgOuter).systemBarsPadding()) {
        Column(
            Modifier.weight(1f).fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp))
                .background(BgCard)
        ) {
            // thanh trên: ★ | Dịch + nút Dịch màn hình | cài đặt
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                IconButton({ showSaved = true }, Modifier.align(Alignment.TopStart)) {
                    Icon(Icons.Filled.Star, "Đã lưu", tint = Color(0xFFD6D6D6))
                }
                Column(Modifier.align(Alignment.TopCenter), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Dịch Plus", fontSize = 26.sp, color = Color.White, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        onClick = { startScreenTranslate() },
                        shape = RoundedCornerShape(20.dp), color = Color(0xFF262626)
                    ) {
                        Row(Modifier.padding(horizontal = 18.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Translate, null, tint = Salmon, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Dịch màn hình", color = Color(0xFFE0E0E0), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                IconButton({ showSettings = true }, Modifier.align(Alignment.TopEnd)) {
                    Icon(Icons.Outlined.Settings, "Cài đặt", tint = Color(0xFFD6D6D6))
                }
            }

            // vùng nhập + kết quả
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.weight(1f)) {
                        BasicTextField(
                            value = input, onValueChange = { input = it },
                            textStyle = TextStyle(color = Color.White, fontSize = 34.sp),
                            cursorBrush = SolidColor(Color.White), modifier = Modifier.fillMaxWidth()
                        )
                        if (input.isEmpty()) Text("Dịch văn bản", fontSize = 34.sp, color = Hint)
                    }
                    if (input.isEmpty()) {
                        IconButton({ clipboard.getText()?.text?.let { input = it } }) {
                            Icon(Icons.Outlined.Draw, "Dán", tint = Color(0xFFD6D6D6))
                        }
                    } else {
                        IconButton({ input = ""; output = "" }) {
                            Icon(Icons.Filled.Close, "Xóa", tint = Color(0xFFD6D6D6))
                        }
                    }
                }
                if (output.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 20.dp), color = Color(0xFF333333))
                    Text(output, fontSize = 30.sp, color = OutBlue)
                    Row(Modifier.padding(top = 8.dp)) {
                        IconButton({ clipboard.setText(AnnotatedString(output)) }) {
                            Icon(Icons.Outlined.ContentCopy, "Sao chép", tint = Color(0xFFD6D6D6))
                        }
                        IconButton({
                            tts.language = Locale.forLanguageTag(tgt)
                            tts.speak(output, TextToSpeech.QUEUE_FLUSH, null, "o")
                        }) { Icon(Icons.Outlined.VolumeUp, "Đọc", tint = Color(0xFFD6D6D6)) }
                        IconButton({
                            saved.add(0, input.trim() to output); storeSaved(prefs, saved)
                            Toast.makeText(ctx, "Đã lưu", Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Outlined.StarBorder, "Lưu", tint = Color(0xFFD6D6D6)) }
                    }
                }
                status?.let { Text(it, color = Color(0xFFFFB4AB), modifier = Modifier.padding(top = 12.dp)) }
                Spacer(Modifier.height(16.dp))
            }
            Box(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.width(100.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF444444)))
            }
        }

        // hàng chọn ngôn ngữ
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            LangButton(langName(src), Modifier.weight(1f)) { pick = "src" }
            IconButton({
                val ns = if (src == "auto") detected else src
                if (ns != null) { val t = tgt; tgt = ns; src = t; input = output; output = "" }
            }) { Icon(Icons.Filled.SwapHoriz, "Đổi", tint = Color.White) }
            LangButton(langName(tgt), Modifier.weight(1f)) { pick = "tgt" }
        }

        // hàng nút dưới: Dịch trực tiếp | mic | Máy ảnh
        Row(
            Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
        ) {
            SideButton(Icons.Outlined.RecordVoiceOver, "Dịch trực tiếp") { screen = "conv" }
            Box(
                Modifier.size(92.dp).clip(CircleShape).background(Salmon)
                    .clickable { listen(if (src == "auto") null else src) },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Mic, "Nói", tint = SalmonDark, modifier = Modifier.size(38.dp)) }
            SideButton(Icons.Filled.CameraAlt, "Máy ảnh") { showCamera = true }
        }
    }

    // ---------- hộp thoại ----------
    pick?.let { which ->
        val list = if (which == "src") listOf("auto") + allLangs else allLangs
        Dialog({ pick = null }) {
            Surface(shape = RoundedCornerShape(24.dp), color = Color(0xFF2B2B2B)) {
                LazyColumn(Modifier.heightIn(max = 520.dp).padding(vertical = 8.dp)) {
                    items(list) { tag ->
                        Text(
                            langName(tag), color = Color.White, fontSize = 17.sp,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { if (which == "src") src = tag else tgt = tag; pick = null }
                                .padding(horizontal = 24.dp, vertical = 14.dp)
                        )
                    }
                }
            }
        }
    }

    if (showCamera) {
        AlertDialog(
            onDismissRequest = { showCamera = false },
            title = { Text("Dịch bằng ảnh") },
            text = { Text("Chụp ảnh hoặc chọn ảnh có chữ, bản dịch sẽ được vẽ đè lên ảnh.") },
            confirmButton = {
                TextButton({
                    showCamera = false
                    val dir = File(ctx.cacheDir, "images").apply { mkdirs() }
                    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fp", File(dir, "cap.jpg"))
                    photoUri = uri
                    takePic.launch(uri)
                }) { Text("Chụp ảnh") }
            },
            dismissButton = {
                TextButton({
                    showCamera = false
                    pickPic.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("Chọn từ thư viện") }
            }
        )
    }

    if (showSaved) {
        Dialog({ showSaved = false }) {
            Surface(shape = RoundedCornerShape(24.dp), color = Color(0xFF2B2B2B)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Đã lưu", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    if (saved.isEmpty()) Text("Chưa có mục nào. Bấm ☆ dưới bản dịch để lưu.", color = Hint, modifier = Modifier.padding(top = 12.dp))
                    LazyColumn(Modifier.heightIn(max = 440.dp)) {
                        items(saved.toList()) { item ->
                            Row(
                                Modifier.fillMaxWidth().clickable { input = item.first; showSaved = false }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.first, color = Color.White, maxLines = 2)
                                    Text(item.second, color = OutBlue, maxLines = 2)
                                }
                                IconButton({ saved.remove(item); storeSaved(prefs, saved) }) {
                                    Icon(Icons.Filled.Close, "Xóa", tint = Hint)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Dịch màn hình") },
            text = {
                Text(
                    "• Bấm “Dịch màn hình” → cho phép chụp màn hình → bong bóng nổi xuất hiện.\n" +
                        "• Chạm bong bóng để dịch màn hình hiện tại, chạm lại để đóng.\n" +
                        "• Nhấn giữ bong bóng (hoặc nút Tắt ở thông báo) để tắt.\n" +
                        "• Ngôn ngữ nguồn/đích lấy theo hai nút ở màn hình chính.\n" +
                        "• Không quảng cáo. Dịch chạy ngay trên máy sau khi tải gói ngôn ngữ."
                )
            },
            confirmButton = { TextButton({ showSettings = false }) { Text("Đóng") } },
            dismissButton = { TextButton({ requestOverlay() }) { Text("Cấp quyền hiển thị") } }
        )
    }

    resultBmp?.let { bmp ->
        Dialog({ resultBmp = null }, DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                IconButton({ resultBmp = null }, Modifier.align(Alignment.TopEnd).statusBarsPadding()) {
                    Icon(Icons.Filled.Close, "Đóng", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun LangButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.height(58.dp), shape = RoundedCornerShape(20.dp), color = BtnDark) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
private fun SideButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(110.dp)) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(SideBtn).clickable { onClick() },
            contentAlignment = Alignment.Center
        ) { Icon(icon, label, tint = SideIcon, modifier = Modifier.size(26.dp)) }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color(0xFFE0E0E0), fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

// ---------- Dịch trực tiếp (hội thoại 2 chiều) ----------
@Composable
fun ConversationScreen(a: String, b: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val msgs = remember { mutableStateListOf<Triple<Boolean, String, String>>() }
    var side by remember { mutableStateOf(true) }
    val tts = remember { TextToSpeech(ctx) { } }
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (text != null) {
            val from = if (side) a else b
            val to = if (side) b else a
            scope.launch {
                val res = runCatching { Engine.translate(text, from, to) }.getOrNull()
                msgs.add(Triple(side, text, res?.text ?: "(không dịch được)"))
                if (res != null) {
                    tts.language = Locale.forLanguageTag(to)
                    tts.speak(res.text, TextToSpeech.QUEUE_FLUSH, null, "c")
                }
            }
        }
    }

    fun listen(isA: Boolean) {
        side = isA
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (isA) a else b)
        try { launcher.launch(i) } catch (e: Exception) {
            Toast.makeText(ctx, "Máy chưa có dịch vụ nhận diện giọng nói", Toast.LENGTH_LONG).show()
        }
    }

    Column(Modifier.fillMaxSize().background(BgCard).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại", tint = Color.White) }
            Text("Dịch trực tiếp", color = Color.White, fontSize = 22.sp)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
            items(msgs.toList()) { m ->
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalAlignment = if (m.first) Alignment.Start else Alignment.End
                ) {
                    Text(m.second, color = Hint, fontSize = 15.sp)
                    Text(m.third, color = OutBlue, fontSize = 24.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button({ listen(true) }, Modifier.weight(1f).height(64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Salmon, contentColor = SalmonDark)) {
                Icon(Icons.Filled.Mic, null); Spacer(Modifier.width(8.dp)); Text(langName(a))
            }
            Button({ listen(false) }, Modifier.weight(1f).height(64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SideBtn, contentColor = SideIcon)) {
                Icon(Icons.Filled.Mic, null); Spacer(Modifier.width(8.dp)); Text(langName(b))
            }
        }
    }
}
