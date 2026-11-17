package vn.dichplus

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/** Activity trong suốt: bật/tắt dịch màn hình từ ô cài đặt nhanh hoặc widget. */
class ProjectionActivity : ComponentActivity() {
    private val proj = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK && r.data != null) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, ScreenService::class.java).putExtra("code", r.resultCode).putExtra("data", r.data)
            )
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Bật quyền “Hiển thị trên ứng dụng khác” rồi thử lại", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            finish(); return
        }
        if (ScreenService.running) {
            stopService(Intent(this, ScreenService::class.java))
            finish(); return
        }
        proj.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }
}
