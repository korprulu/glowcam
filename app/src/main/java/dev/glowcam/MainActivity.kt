package dev.glowcam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var previewView: ImageView
    private lateinit var shutter: ImageButton
    private lateinit var permissionPanel: View
    private lateinit var seekSmooth: SeekBar
    private lateinit var seekWhiten: SeekBar
    private lateinit var seekRosy: SeekBar

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val placeExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile private var params = BeautyParams()
    @Volatile private var frontFacing = true
    private var imageCapture: ImageCapture? = null
    private var saving = false
    private var camera: androidx.camera.core.Camera? = null
    private lateinit var zoomControl: ZoomControl
    private var hasPhotos = false
    private var askedLocation = false
    private val locationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* place is optional */ }
    private lateinit var thumb: ImageView

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showPermissionPanel(true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.preview)
        shutter = findViewById(R.id.btn_shutter)
        permissionPanel = findViewById(R.id.permission_panel)
        seekSmooth = findViewById(R.id.seek_smooth)
        seekWhiten = findViewById(R.id.seek_whiten)
        seekRosy = findViewById(R.id.seek_rosy)

        val controls = findViewById<View>(R.id.controls)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            controls.setPadding(
                controls.paddingLeft, controls.paddingTop, controls.paddingRight,
                bars.bottom + (16 * resources.displayMetrics.density).toInt(),
            )
            insets
        }

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) = updateParams()
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        }
        seekSmooth.setOnSeekBarChangeListener(listener)
        seekWhiten.setOnSeekBarChangeListener(listener)
        seekRosy.setOnSeekBarChangeListener(listener)
        updateParams()

        findViewById<View>(R.id.grant).setOnClickListener { permissionLauncher.launch(Manifest.permission.CAMERA) }
        findViewById<View>(R.id.btn_switch).setOnClickListener {
            frontFacing = !frontFacing
            startCamera()
        }
        shutter.setOnClickListener { takePhoto() }
        zoomControl = findViewById(R.id.zoom)
        zoomControl.onTap = {
            val next = ZoomMath.nextPreset(zoomControl.ratio, zoomControl.minRatio, zoomControl.maxRatio)
            zoomControl.ratio = next
            camera?.cameraControl?.setZoomRatio(next)
        }
        zoomControl.onRatioChanged = { camera?.cameraControl?.setZoomRatio(it) }
        thumb = findViewById(R.id.thumb)
        thumb.setOnClickListener {
            if (hasPhotos) startActivity(android.content.Intent(this, GalleryActivity::class.java))
        }

        if (hasCameraPermission()) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        refreshThumbnail()
    }

    private fun refreshThumbnail() {
        val size = (120 * resources.displayMetrics.density).toInt()
        captureExecutor.execute {
            val latest = PhotoStore.list(this).firstOrNull()
            val bmp = latest?.let { PhotoStore.thumbnail(this, it.uri, size) }
            runOnUiThread {
                hasPhotos = bmp != null
                thumb.setImageBitmap(bmp)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        captureExecutor.shutdown()
        placeExecutor.shutdown()
    }

    private fun updateParams() {
        params = BeautyParams.fromProgress(seekSmooth.progress, seekWhiten.progress, seekRosy.progress)
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun showPermissionPanel(show: Boolean) {
        permissionPanel.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** Optional: coarse location only labels photos with a place name; ask once per launch. */
    private fun maybeAskLocation() {
        if (askedLocation) return
        askedLocation = true
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    private fun flashScreen() {
        val flash = findViewById<View>(R.id.flash)
        flash.animate().cancel()
        flash.alpha = 0.85f
        flash.animate().alpha(0f).setDuration(220).start()
    }

    private fun startCamera() {
        showPermissionPanel(false)
        maybeAskLocation()
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                bind(future.get())
            } catch (e: Exception) {
                Log.e(TAG, "camera start failed", e)
                Toast.makeText(this, R.string.camera_failed, Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bind(provider: ProcessCameraProvider) {
        var selector = if (frontFacing) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        if (!provider.hasCamera(selector)) {
            frontFacing = !frontFacing
            selector = if (frontFacing) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        }
        val rotation = display?.rotation ?: 0

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    )
                    .build(),
            )
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(rotation)
            .build()
        analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }

        val capture = ImageCapture.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build(),
            )
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(rotation)
            .build()
        imageCapture = capture

        provider.unbindAll()
        camera?.cameraInfo?.zoomState?.removeObservers(this)
        val cam = provider.bindToLifecycle(this, selector, analysis, capture)
        camera = cam
        cam.cameraInfo.zoomState.observe(this) { zs ->
            zoomControl.minRatio = zs.minZoomRatio
            zoomControl.maxRatio = zs.maxZoomRatio
            // Don't fight the user's finger while the slider is open.
            if (!zoomControl.expanded) zoomControl.ratio = zs.zoomRatio else zoomControl.invalidate()
        }
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            val plane = proxy.planes[0]
            val buf = plane.buffer
            buf.rewind()
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            var px = Pixels.fromRgba(bytes, plane.rowStride, proxy.width, proxy.height)
            val rot = proxy.imageInfo.rotationDegrees
            proxy.close()
            px = px.rotated(rot)
            if (frontFacing) px = px.mirrored()
            px = BeautyFilter.apply(px, params)
            val bmp = Bitmap.createBitmap(px.data, px.width, px.height, Bitmap.Config.ARGB_8888)
            runOnUiThread { previewView.setImageBitmap(bmp) }
        } catch (e: Exception) {
            Log.e(TAG, "analyze failed", e)
            runCatching { proxy.close() }
        }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        if (saving) return
        saving = true
        shutter.isEnabled = false
        val beauty = params
        val mirror = frontFacing
        val takenAt = System.currentTimeMillis()
        val placeFuture = placeExecutor.submit<String?> { PlaceResolver.resolve(applicationContext) }
        capture.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                runOnUiThread { flashScreen() }
                val ok = try {
                    val buf = image.planes[0].buffer
                    val jpeg = ByteArray(buf.remaining()).also { buf.get(it) }
                    val rot = image.imageInfo.rotationDegrees
                    image.close()
                    val place = runCatching { placeFuture.get(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()
                    saveBeautified(jpeg, rot, mirror, beauty, takenAt, place)
                } catch (e: Exception) {
                    Log.e(TAG, "save failed", e)
                    runCatching { image.close() }
                    false
                }
                runOnUiThread {
                    saving = false
                    shutter.isEnabled = true
                    Toast.makeText(
                        this@MainActivity,
                        if (ok) R.string.saved else R.string.save_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                    if (ok) refreshThumbnail()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "capture failed", exception)
                runOnUiThread {
                    saving = false
                    shutter.isEnabled = true
                    Toast.makeText(this@MainActivity, R.string.save_failed, Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun saveBeautified(
        jpeg: ByteArray,
        rotation: Int,
        mirror: Boolean,
        beauty: BeautyParams,
        takenAt: Long,
        place: String?,
    ): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SAVE_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return false
        val w = bmp.width
        val h = bmp.height
        val data = IntArray(w * h)
        bmp.getPixels(data, 0, w, 0, 0, w, h)
        bmp.recycle()

        var px = Pixels(data, w, h).rotated(rotation)
        if (mirror) px = px.mirrored()
        px = BeautyFilter.apply(px, beauty)
        val out = Bitmap.createBitmap(px.data, px.width, px.height, Bitmap.Config.ARGB_8888)

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "GlowCam_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GlowCam")
            put(MediaStore.Images.Media.DATE_TAKEN, takenAt)
            if (!place.isNullOrBlank()) put(MediaStore.Images.Media.DESCRIPTION, place)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) } ?: error("no stream")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        } finally {
            out.recycle()
        }
    }

    private companion object {
        const val TAG = "GlowCam"
        const val MAX_SAVE_SIDE = 2400
    }
}
