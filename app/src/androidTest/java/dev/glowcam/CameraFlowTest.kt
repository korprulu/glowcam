package dev.glowcam

import android.Manifest
import android.provider.MediaStore
import android.widget.ImageView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraFlowTest {

    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(200)
        }
        return cond()
    }

    private fun savedCount(): Int {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("%GlowCam%"),
            null,
        )?.use { return it.count }
        return 0
    }

    @Test
    fun livePreviewShowsBeautifiedFramesAndShutterSavesPhoto() {
        val before = savedCount()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var preview: ImageView? = null
            scenario.onActivity { preview = it.findViewById(R.id.preview) }
            assertTrue(
                "no preview frame within 30s",
                waitUntil(30_000) {
                    var has = false
                    scenario.onActivity { has = preview?.drawable != null }
                    has
                },
            )
            scenario.onActivity { it.findViewById<android.view.View>(R.id.btn_shutter).performClick() }
            assertTrue("photo not saved", waitUntil(30_000) { savedCount() > before })
        }
    }
}
