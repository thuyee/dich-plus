package vn.dichplus

import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.net.Uri
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
        val blocks = best?.textBlocks.orEmpty().filter { it.boundingBox != null && it.text.isNotBlank() }
        val gate = Semaphore(4)
        val items = coroutineScope {
            blocks.map { b ->
                async {
                    gate.withPermit {
                        val rect = b.boundingBox!!
                        val txt = b.text.replace('\n', ' ')
                        val out = runCatching { blockTranslate(txt, src, tgt) }.getOrNull()
                        if (out == null) null else rect to out
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

    private fun render(src: Bitmap, items: List<Pair<Rect, String>>): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val bg = Paint()
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val sc = src.width / 1080f
        for ((r0, t) in items) {
            val r = Rect(r0)
            r.inset(-(4 * sc).toInt(), -(2 * sc).toInt())
            if (!r.intersect(0, 0, src.width, src.height)) continue
            val col = sample(src, r)
            bg.color = col
            c.drawRect(r, bg)
            tp.color = if (lum(col) > 140) Color.BLACK else Color.WHITE
            val w = max(r.width(), 1)
            val minSize = 18 * sc
            var size = (r.height() * 0.9f).coerceIn(minSize, 56 * sc)
            var layout: StaticLayout
            while (true) {
                tp.textSize = size
                layout = StaticLayout.Builder.obtain(t, 0, t.length, tp, w).build()
                if (layout.height <= r.height() || size <= minSize) break
                size -= 1.5f * sc
            }
            c.save()
            c.translate(r.left.toFloat(), r.top.toFloat())
            layout.draw(c)
            c.restore()
        }
        return out
    }

    private fun sample(b: Bitmap, r: Rect): Int {
        val mx = (r.left + r.right) / 2
        val pts = listOf(
            r.left to r.top, r.right - 1 to r.top, r.left to r.bottom - 1,
            r.right - 1 to r.bottom - 1, mx to r.top, mx to r.bottom - 1
        )
        var rr = 0; var gg = 0; var bb = 0
        for ((x, y) in pts) {
            val p = b.getPixel(x.coerceIn(0, b.width - 1), y.coerceIn(0, b.height - 1))
            rr += Color.red(p); gg += Color.green(p); bb += Color.blue(p)
        }
        return Color.rgb(rr / pts.size, gg / pts.size, bb / pts.size)
    }

    private fun lum(c: Int) = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)

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
