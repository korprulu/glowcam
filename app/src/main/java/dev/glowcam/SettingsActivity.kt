package dev.glowcam

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Settings page. For now it only shows the app version. */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val root = findViewById<android.view.View>(R.id.settings_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        findViewById<android.view.View>(R.id.btn_back).setOnClickListener { finish() }

        val info = runCatching { packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)) }.getOrNull()
        findViewById<TextView>(R.id.version_value).text =
            VersionInfo.format(info?.versionName, info?.longVersionCode)
    }
}
