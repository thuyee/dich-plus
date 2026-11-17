package vn.dichplus

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Bong bóng nổi: chạm để chụp màn hình -> OCR -> dịch -> hiện bản dịch đè lên màn hình. */
class ScreenService : Service() {
    companion object {
        const val ACTION_STOP = "vn.dichplus.STOP"
        const val CHANNEL = "screen_translate"
    }

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var bubble: TextView? = null
    private var result: FrameLayout? = null
    private var pending = false
    private var busy = false
    private var requestAt = 0L
    private var reqId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf(); return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        Engine.init(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Dịch màn hình", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(
            this, 0, Intent(this, ScreenService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Dịch màn hình đang bật")
            .setContentText("Chạm bong bóng để dịch. Nhấn giữ bong bóng để tắt.")
            .addAction(0, "Tắt", stopPi)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent.getIntExtra("code", 0)
        val data = IntentCompat.getParcelableExtra(intent, "data", Intent::class.java)
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data).also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, handler)
        }
        setupCapture()
        showBubble()
        return START_NOT_STICKY
    }

    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }

    private fun setupCapture() {
        val (w, h) = screenSize()
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ rd ->
            val img = try { rd.acquireLatestImage() } catch (e: Exception) { null }
            if (img != null) {
                if (pending && SystemClock.uptimeMillis() - requestAt > 300) {
                    pending = false
                    val bmp = toBitmap(img)
                    img.close()
                    onCaptured(bmp)
                } else img.close()
            }
        }, handler)
        reader = r
        display = projection!!.createVirtualDisplay(
            "dichplus", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
        )
    }

    private fun toBitmap(img: Image): Bitmap {
        val p = img.planes[0]
        val rowPad = p.rowStride - p.pixelStride * img.width
        val full = Bitmap.createBitmap(img.width + rowPad / p.pixelStride, img.height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(p.buffer)
        return if (rowPad == 0) full else Bitmap.createBitmap(full, 0, 0, img.width, img.height)
    }

    private fun showBubble() {
        val d = resources.displayMetrics.density
        val size = (52 * d).toInt()
        val tv = TextView(this).apply {
            text = "文A"
            textSize = 15f
            setTextColor(Color.rgb(0x5C, 0x19, 0x00))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(0xFF, 0xB4, 0xA1))
            }
            elevation = 8 * d
        }
        val lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = (220 * d).toInt() }

        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        var moved = false; var downAt = 0L
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY
                    moved = false; downAt = SystemClock.uptimeMillis(); true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - tx; val dy = e.rawY - ty
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    if (moved) { lp.x = sx + dx.toInt(); lp.y = sy + dy.toInt(); wm.updateViewLayout(tv, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (SystemClock.uptimeMillis() - downAt > 800) stopSelf() else capture()
                    }
                    true
                }
                else -> false
            }
        }
        wm.addView(tv, lp)
        bubble = tv
    }

    private fun capture() {
        if (busy) return
        busy = true
        bubble?.visibility = View.INVISIBLE
        pending = true
        requestAt = SystemClock.uptimeMillis()
        val id = ++reqId
        handler.postDelayed({
            if (pending && id == reqId) {
                pending = false; busy = false
                bubble?.visibility = View.VISIBLE
                toast("Không chụp được màn hình, thử lại nhé")
            }
        }, 2500)
    }

    private fun onCaptured(bmp: Bitmap) {
        scope.launch {
            toast("Đang dịch…")
            val prefs = getSharedPreferences("p", MODE_PRIVATE)
            val src = prefs.getString("src", "auto") ?: "auto"
            val tgt = prefs.getString("tgt", "vi") ?: "vi"
            val out = try {
                withContext(Dispatchers.Default) { Engine.translateImage(bmp, src, tgt) }
            } catch (e: Exception) { null }
            if (out == null) {
                toast("Dịch thất bại. Lần đầu cần mạng để tải gói ngôn ngữ.")
                busy = false
                bubble?.visibility = View.VISIBLE
            } else showResult(out)
        }
    }

    private fun showResult(out: Bitmap) {
        val iv = ImageView(this).apply {
            setImageBitmap(out)
            scaleType = ImageView.ScaleType.FIT_XY
        }
        val root = FrameLayout(this).apply {
            addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setOnClickListener { dismissResult() }
        }
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(root, lp)
        result = root
        toast("Chạm vào màn hình để đóng bản dịch")
    }

    private fun dismissResult() {
        result?.let { runCatching { wm.removeView(it) } }
        result = null
        busy = false
        bubble?.visibility = View.VISIBLE
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        result?.let { runCatching { wm.removeView(it) } }
        bubble?.let { runCatching { wm.removeView(it) } }
        display?.release()
        reader?.close()
        projection?.stop()
        super.onDestroy()
    }
}
