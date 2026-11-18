package dev.glowcam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.DisplayOrientedMeteringPointFactory
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
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

    private lateinit var previewView: GlPreviewView
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

    // --- focus ring / tap-to-focus ---
    private val ui = Handler(Looper.getMainLooper())
    private val focus = FocusController()
    private val tracker = FocusTracker() // analysis thread only
    private lateinit var focusRing: FocusRing
    @Volatile private var pendingTap: FloatArray? = null
    @Volatile private var pendingStop = false
    @Volatile private var frameW = 0
    @Volatile private var frameH = 0
    @Volatile private var lastAfState: Int? = null
    private var lastSentX = -1f
    private var lastSentY = -1f
    private var lastSentAt = 0L
    private val fadeCheck = Runnable {
        if (focus.tick(SystemClock.uptimeMillis())) renderFocusRing()
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showPermissionPanel(true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.preview)
        previewView.onContextRecreated = { runOnUiThread { if (hasCameraPermission()) startCamera() } }
        previewView.onGlError = { Toast.makeText(this, R.string.camera_failed, Toast.LENGTH_LONG).show() }
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
        zoomControl.onSelect = { camera?.cameraControl?.setZoomRatio(it) }
        zoomControl.onRatioChanged = { camera?.cameraControl?.setZoomRatio(it) }
        focusRing = findViewById(R.id.focus_ring)
        val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onTapToFocus(e.x, e.y)
                return true
            }
        })
        previewView.setOnTouchListener { v, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            taps.onTouchEvent(ev)
        }
        thumb = findViewById(R.id.thumb)
        thumb.setOnClickListener {
            if (hasPhotos) startActivity(android.content.Intent(this, GalleryActivity::class.java))
        }

        if (hasCameraPermission()) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        previewView.onResume()
        refreshThumbnail()
    }

    override fun onPause() {
        previewView.onPause()
        super.onPause()
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
        previewView.params = params
        previewView.requestRender()
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

        // Native preview stream, rendered (with the beauty shader) by GlPreviewView.
        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1440, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                    )
                    .build(),
            )
            .setTargetRotation(rotation)
            .build()
        previewView.mirror = frontFacing
        preview.setSurfaceProvider(previewView.surfaceProvider)

        // Low-resolution frames are only used to follow the tap-to-focus point.
        val analysisBuilder = ImageAnalysis.Builder()
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
        observeAutofocus(analysisBuilder)
        val analysis = analysisBuilder.build()
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
        resetFocus()
        camera?.cameraInfo?.zoomState?.removeObservers(this)
        val cam = provider.bindToLifecycle(this, selector, preview, analysis, capture)
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
            val rot = proxy.imageInfo.rotationDegrees
            val (w, h) = PreviewTransform.displaySize(proxy.width, proxy.height, rot)
            frameW = w
            frameH = h
            if (pendingStop) {
                pendingStop = false
                tracker.stop()
            }
            if (pendingTap == null && !tracker.active) {
                proxy.close()
                return
            }
            val plane = proxy.planes[0]
            val buf = plane.buffer
            buf.rewind()
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            var px = Pixels.fromRgba(bytes, plane.rowStride, proxy.width, proxy.height)
            proxy.close()
            px = px.rotated(rot)
            if (frontFacing) px = px.mirrored()
            trackFocus(px)
        } catch (e: Exception) {
            Log.e(TAG, "analyze failed", e)
            runCatching { proxy.close() }
        }
    }

    // ---------------- focus ----------------

    /** Reports AF state changes from the camera (focus change events). */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun observeAutofocus(builder: ImageAnalysis.Builder) {
        Camera2Interop.Extender(builder).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                val state = result.get(CaptureResult.CONTROL_AF_STATE)
                val event = AfState.isFocusEvent(lastAfState, state)
                lastAfState = state
                if (event) ui.post { onFocusEvent() }
            }
        })
    }

    private fun onFocusEvent() {
        focus.onFocusEvent(SystemClock.uptimeMillis())
        renderFocusRing()
        scheduleFade()
    }

    private fun scheduleFade() {
        ui.removeCallbacks(fadeCheck)
        ui.postDelayed(fadeCheck, FocusController.FADE_MS + 50)
    }

    private fun renderFocusRing() {
        val fw = frameW
        val fh = frameH
        if (!focus.visible || fw == 0 || fh == 0) {
            focusRing.hide()
            return
        }
        val (x, y) = FocusMapping.frameToView(
            focus.nx, focus.ny, previewView.width.toFloat(), previewView.height.toFloat(), fw.toFloat(), fh.toFloat(),
        )
        focusRing.showAt(x, y)
    }

    private fun resetFocus() {
        pendingTap = null
        pendingStop = true
        focus.reset()
        ui.removeCallbacks(fadeCheck)
        lastAfState = null
        focusRing.hide()
    }

    private fun meteringAction(nx: Float, ny: Float): FocusMeteringAction? {
        val cam = camera ?: return null
        val d = display ?: return null
        val fw = frameW
        val fh = frameH
        if (fw == 0 || fh == 0) return null
        val point = DisplayOrientedMeteringPointFactory(d, cam.cameraInfo, fw.toFloat(), fh.toFloat())
            .createPoint(nx * fw, ny * fh)
        return FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .disableAutoCancel() // keep the custom point until we cancel it ourselves
            .build()
    }

    private fun onTapToFocus(x: Float, y: Float) {
        val fw = frameW
        val fh = frameH
        if (fw == 0 || fh == 0) return
        val (nx, ny) = FocusMapping.viewToFrame(
            x, y, previewView.width.toFloat(), previewView.height.toFloat(), fw.toFloat(), fh.toFloat(),
        )
        val action = meteringAction(nx, ny) ?: return
        if (camera?.cameraInfo?.isFocusMeteringSupported(action) != true) return
        camera?.cameraControl?.startFocusAndMetering(action)
        lastSentX = nx
        lastSentY = ny
        lastSentAt = SystemClock.uptimeMillis()
        pendingStop = false
        pendingTap = floatArrayOf(nx, ny)
        focus.onUserTap(nx, ny, lastSentAt)
        ui.removeCallbacks(fadeCheck)
        renderFocusRing()
    }

    /** Runs on the analysis thread for each (display-oriented) frame. */
    private fun trackFocus(px: Pixels) {
        frameW = px.width
        frameH = px.height
        if (pendingStop) {
            pendingStop = false
            tracker.stop()
        }
        val tap = pendingTap
        if (tap != null) {
            pendingTap = null
            tracker.start(FocusTracker.toGray(px), px.width, px.height, (tap[0] * px.width).toInt(), (tap[1] * px.height).toInt())
            return
        }
        if (!tracker.active) return
        val pos = tracker.update(FocusTracker.toGray(px), px.width, px.height)
        if (pos == null) {
            ui.post { onFocusLost() }
        } else {
            val nx = pos.first / px.width.toFloat()
            val ny = pos.second / px.height.toFloat()
            ui.post { onFocusMoved(nx, ny) }
        }
    }

    private fun onFocusMoved(nx: Float, ny: Float) {
        if (!focus.custom) return
        focus.onTrack(nx, ny)
        renderFocusRing()
        // Re-aim the camera once the point has moved noticeably (throttled: each aim restarts AF).
        val now = SystemClock.uptimeMillis()
        val moved = kotlin.math.abs(nx - lastSentX) + kotlin.math.abs(ny - lastSentY)
        if (moved > 0.06f && now - lastSentAt > 400) {
            meteringAction(nx, ny)?.let { camera?.cameraControl?.startFocusAndMetering(it) }
            lastSentX = nx
            lastSentY = ny
            lastSentAt = now
        }
    }

    /** Tracked point left the frame or could not be followed: back to the centre, custom focus off. */
    private fun onFocusLost() {
        if (!focus.custom) return
        pendingStop = true
        camera?.cameraControl?.cancelFocusAndMetering()
        focus.onTrackLost(SystemClock.uptimeMillis())
        renderFocusRing()
        scheduleFade()
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
