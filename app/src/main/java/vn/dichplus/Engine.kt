package vn.dichplus

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import android.text.StaticLayout
import android.text.TextPaint
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlin.math.max
import kotlin.math.min

object Engine {
    data class Result(val text: String, val detected: String?)

    private lateinit var prefs: SharedPreferences
    fun init(ctx: Context) {
        if (!::prefs.isInitialized) prefs = ctx.applicationContext.getSharedPreferences("p", Context.MODE_PRIVATE)
    }
    private fun engine(): String = if (::prefs.isInitialized) prefs.getString("engine", "gtx") ?: "gtx" else "mlkit"

    private val translators = HashMap<String, Translator>()
    private val langId by lazy { LanguageIdentification.getClient() }

    private val latin by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val chinese by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private val japanese by lazy { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) }
    private val korean by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }
    private val devanagari by lazy { TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()) }

    suspend fun detect(text: String): String? {
        val tag = langId.identifyLanguage(text).await()
        if (tag == "und") return null
        return TranslateLanguage.fromLanguageTag(tag)
    }

    /** Dịch theo công cụ đã chọn; online lỗi/mất mạng thì tự rơi về ML Kit. */
    suspend fun translate(text: String, src: String, tgt: String): Result {
        val eng = engine()
        if (eng != "mlkit") {
            try { return online(eng, text, src, tgt) } catch (e: Exception) { /* rơi về ML Kit */ }
        }
        return mlkit(text, src, tgt)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun post(url: String, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = 12000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private suspend fun online(eng: String, text: String, src: String, tgt: String): Result =
        withContext(Dispatchers.IO) {
            fun code(t: String) = if (t == "zh") "zh-CN" else t
            val tl = code(tgt)
            val sl = if (src == "auto") "auto" else code(src)
            val fallbackSrc = src.takeIf { it != "auto" }
            if (eng == "gcloud") {
                val key = prefs.getString("apikey", "").orEmpty().trim()
                if (key.isEmpty()) throw IllegalStateException("thiếu API key")
                var body = "q=${enc(text)}&target=$tl&format=text"
                if (sl != "auto") body += "&source=$sl"
                val res = post("https://translation.googleapis.com/language/translate/v2?key=${enc(key)}", body)
                val t = JSONObject(res).getJSONObject("data").getJSONArray("translations").getJSONObject(0)
                val det = t.optString("detectedSourceLanguage", "").ifEmpty { null }
                Result(t.getString("translatedText"), (det ?: fallbackSrc)?.substringBefore('-'))
            } else {
                val res = post("https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sl&tl=$tl&dt=t", "q=${enc(text)}")
                val arr = JSONArray(res)
                val parts = arr.getJSONArray(0)
                val sb = StringBuilder()
                for (i in 0 until parts.length()) {
                    val p = parts.optJSONArray(i) ?: continue
                    if (!p.isNull(0)) sb.append(p.getString(0))
                }
                if (sb.isEmpty()) throw IllegalStateException("rỗng")
                val det = if (arr.length() > 2 && !arr.isNull(2)) arr.getString(2) else null
                Result(sb.toString(), (det ?: fallbackSrc)?.substringBefore('-'))
            }
        }

    private suspend fun mlkit(text: String, src: String, tgt: String): Result {
        val s = if (src == "auto") detect(text) else src
        if (s == null || s == tgt) return Result(text, s)
        val key = "$s>$tgt"
        val t = synchronized(translators) {
            translators.getOrPut(key) {
                Translation.getClient(
                    TranslatorOptions.Builder().setSourceLanguage(s).setTargetLanguage(tgt).build()
                )
            }
        }
        t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        return Result(t.translate(text).await(), s)
    }

    private fun recognizers(src: String): List<TextRecognizer> = when (src) {
        "zh" -> listOf(chinese)
        "ja" -> listOf(japanese)
        "ko" -> listOf(korean)
        "hi", "mr", "ne" -> listOf(devanagari)
        "auto" -> listOf(latin, chinese, japanese, korean)
        else -> listOf(latin)
    }

    suspend fun recognize(image: InputImage, src: String): Text? {
        var best: Text? = null
        for (r in recognizers(src)) {
            val t = r.process(image).await()
            if (best == null || t.text.length > best.text.length) best = t
        }
        return best
    }

    /** OCR ảnh -> dịch từng khối chữ -> vẽ bản dịch đè lên ảnh gốc. */
    suspend fun translateImage(bmp: Bitmap, src: String, tgt: String): Bitmap {
        val best = recognize(InputImage.fromBitmap(bmp, 0), src)
        val sc = bmp.width / 1080f
        val blocks = best?.textBlocks.orEmpty().filter {
            val bb = it.boundingBox
            bb != null && bb.height() >= 10 * sc && it.text.count { ch -> ch.isLetter() } >= 2
        }
        val gate = Semaphore(4)
        val items = coroutineScope {
            blocks.map { b ->
                async {
                    gate.withPermit {
                        val txt = b.text.replace('\n', ' ')
                        val out = runCatching { blockTranslate(txt, src, tgt) }.getOrNull()
                        if (out == null) null else Item(b.boundingBox!!, out, b.lines.size)
                    }
                }
            }.awaitAll().filterNotNull()
        }
        return render(bmp, items)
    }

    suspend fun blockTranslate(txt: String, src: String, tgt: String): String? {
        if (engine() == "mlkit") {
            val d = detect(txt)
            if (d == tgt) return null
            val from = if (src == "auto") (d ?: return null) else src
            val r = translate(txt, from, tgt)
            return if (r.text == txt) null else r.text
        }
        val r = translate(txt, src, tgt)
        if (r.detected == tgt || r.text == txt) return null
        return r.text
    }

    private class Item(val rect: Rect, val text: String, val lines: Int)

    private fun layoutOf(t: String, tp: TextPaint, size: Float, w: Int): StaticLayout {
        tp.textSize = size
        return StaticLayout.Builder.obtain(t, 0, t.length, tp, max(w, 1)).setLineSpacing(0f, 0.95f).build()
    }

    /** Vẽ bản dịch đè lên ảnh gốc: nền theo màu nền thật, chữ theo màu chữ gốc, cỡ chữ theo cỡ chữ gốc. */
    private fun render(src: Bitmap, items: List<Item>): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val sc = src.width / 1080f
        for (item in items) {
            val pad = max(2, (3 * sc).toInt())
            val r = Rect(item.rect).apply { inset(-pad, -pad / 2 - 1) }
            if (!r.intersect(0, 0, src.width, src.height)) continue

            val lineH = item.rect.height().toFloat() / max(1, item.lines)
            val startSize = (lineH * 0.78f).coerceIn(14 * sc, 64 * sc)
            val minSize = max(12 * sc, startSize * 0.55f)

            var w = r.width()
            var size = startSize
            var layout = layoutOf(item.text, tp, size, w)
            while (layout.height > r.height() && size > minSize) {
                size -= 1f * sc
                layout = layoutOf(item.text, tp, size, w)
            }
            if (layout.height > r.height() * 1.1f) {
                // vẫn dài hơn khung: nới ngang tối đa 1,7 lần (không vượt mép ảnh) rồi vừa cỡ lại
                w = max(w, min(src.width - r.left, (r.width() * 1.7f).toInt()))
                size = startSize
                layout = layoutOf(item.text, tp, size, w)
                while (layout.height > r.height() * 1.1f && size > minSize) {
                    size -= 1f * sc
                    layout = layoutOf(item.text, tp, size, w)
                }
            }
            val box = Rect(r.left, r.top, min(src.width, r.left + w), min(src.height, max(r.bottom, r.top + layout.height + 2)))
            val bgc = bgColor(src, box)
            val fg = fgColor(src, item.rect, bgc)
            bg.color = bgc
            c.drawRoundRect(RectF(box), 3 * sc, 3 * sc, bg)
            tp.color = fg
            c.save()
            c.translate(box.left.toFloat(), box.top + max(0f, (box.height() - layout.height) / 2f))
            layout.draw(c)
            c.restore()
        }
        return out
    }

    private fun median(l: MutableList<Int>): Int { l.sort(); return l[l.size / 2] }

    /** Màu nền: trung vị các điểm ngay sát ngoài khung chữ (không bị lẫn nét chữ). */
    private fun bgColor(b: Bitmap, r: Rect): Int {
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        fun add(x: Int, y: Int) {
            val p = b.getPixel(x.coerceIn(0, b.width - 1), y.coerceIn(0, b.height - 1))
            rs.add(Color.red(p)); gs.add(Color.green(p)); bs.add(Color.blue(p))
        }
        val n = 8
        for (i in 0..n) {
            val x = r.left + r.width() * i / n
            add(x, r.top - 2); add(x, r.bottom + 1)
            val y = r.top + r.height() * i / n
            add(r.left - 2, y); add(r.right + 1, y)
        }
        return Color.rgb(median(rs), median(gs), median(bs))
    }

    /** Màu chữ: lấy từ các điểm trong khung xa màu nền nhất; không đủ tương phản thì dùng đen/trắng. */
    private fun fgColor(b: Bitmap, r: Rect, bg: Int): Int {
        val pts = ArrayList<Int>()
        for (iy in 1..4) for (ix in 0..9) {
            val x = (r.left + r.width() * (ix + 0.5f) / 10f).toInt().coerceIn(0, b.width - 1)
            val y = (r.top + r.height() * iy / 5f).toInt().coerceIn(0, b.height - 1)
            pts.add(b.getPixel(x, y))
        }
        fun dist(p: Int): Int {
            val dr = Color.red(p) - Color.red(bg); val dg = Color.green(p) - Color.green(bg); val db = Color.blue(p) - Color.blue(bg)
            return dr * dr + dg * dg + db * db
        }
        val far = pts.sortedByDescending { dist(it) }.take(max(3, pts.size / 5))
        val fg = Color.rgb(far.sumOf { Color.red(it) } / far.size, far.sumOf { Color.green(it) } / far.size, far.sumOf { Color.blue(it) } / far.size)
        return if (kotlin.math.abs(lum(fg) - lum(bg)) >= 90) fg else if (lum(bg) > 140) Color.BLACK else Color.WHITE
    }

    fun lum(c: Int) = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)

    fun saveToGallery(ctx: Context, bmp: Bitmap): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return runCatching {
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "DichPlus_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DichPlus")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)!!
            ctx.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            true
        }.getOrDefault(false)
    }

    fun shareBitmap(ctx: Context, bmp: Bitmap) {
        runCatching {
            val dir = File(ctx.cacheDir, "images").apply { mkdirs() }
            val f = File(dir, "dich_share.jpg")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fp", f)
            val i = Intent(Intent.ACTION_SEND).setType("image/jpeg")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(i, "Chia sẻ ảnh đã dịch").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun loadBitmap(ctx: Context, uri: Uri, maxSide: Int = 2048): Bitmap? {
        val cr = ctx.contentResolver
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var s = 1
        while (max(opts.outWidth, opts.outHeight) / s > maxSide) s *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = s }
        val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: return null
        val rot = cr.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
        } ?: 1
        val deg = when (rot) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        return if (deg == 0f) bmp
        else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
    }
}
