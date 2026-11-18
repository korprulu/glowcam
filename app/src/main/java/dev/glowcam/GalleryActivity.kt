package dev.glowcam

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.DateFormat as AndroidDateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Full-screen photo viewer. See [GalleryPages] for the page layout. */
class GalleryActivity : ComponentActivity() {

    private val io: ExecutorService = Executors.newFixedThreadPool(2)
    private lateinit var pager: ViewPager2
    private lateinit var caption: TextView
    private lateinit var bottomBar: View
    private lateinit var adapter: PageAdapter
    private val photos = ArrayList<Photo>()
    private var pendingDelete: Photo? = null

    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            val gone = pendingDelete
            pendingDelete = null
            if (res.resultCode == Activity.RESULT_OK && gone != null) onDeleted(gone)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)
        photos.addAll(PhotoStore.list(this))

        pager = findViewById(R.id.pager)
        caption = findViewById(R.id.caption)
        bottomBar = findViewById(R.id.bottom_bar)
        adapter = PageAdapter()
        pager.adapter = adapter
        pager.setCurrentItem(GalleryPages.newestPosition(photos.size), false)

        val top = findViewById<View>(R.id.top_bar)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.gallery_root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            top.setPadding(top.paddingLeft, bars.top + dp(12), top.paddingRight, top.paddingBottom)
            bottomBar.setPadding(bottomBar.paddingLeft, bottomBar.paddingTop, bottomBar.paddingRight, bars.bottom + dp(16))
            insets
        }

        findViewById<View>(R.id.btn_share).setOnClickListener { currentPhoto()?.let(::share) }
        findViewById<View>(R.id.btn_delete).setOnClickListener { currentPhoto()?.let(::confirmDelete) }

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updateChrome()

            override fun onPageScrollStateChanged(state: Int) {
                // Settled on the camera page: switch back to shooting mode.
                if (state == ViewPager2.SCROLL_STATE_IDLE && GalleryPages.isCamera(pager.currentItem)) {
                    pager.postDelayed({ if (!isFinishing && GalleryPages.isCamera(pager.currentItem)) finish() }, 350)
                }
            }
        })
        updateChrome()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun currentPhoto(): Photo? =
        photos.getOrNull(GalleryPages.photoIndex(pager.currentItem))

    /** Caption (top) and action buttons (bottom) only apply to photo pages. */
    private fun updateChrome() {
        val p = currentPhoto()
        val text = if (p == null) "" else {
            val locale = Locale.getDefault()
            // Date order/wording follows the system locale; 12/24-hour follows the system clock setting.
            val datePattern = AndroidDateFormat.getBestDateTimePattern(locale, "Md")
            val timePattern = AndroidDateFormat.getBestDateTimePattern(
                locale, if (AndroidDateFormat.is24HourFormat(this)) "Hm" else "hm",
            )
            PhotoInfo.caption(p.takenMillis, p.place, SimpleDateFormat(datePattern, locale), SimpleDateFormat(timePattern, locale))
        }
        caption.text = text
        caption.visibility = if (text.isEmpty()) View.INVISIBLE else View.VISIBLE
        bottomBar.visibility = if (p == null) View.INVISIBLE else View.VISIBLE
    }

    private fun share(photo: Photo) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, photo.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    /** The system shows its own confirmation dialog for the delete request. */
    private fun confirmDelete(photo: Photo) {
        try {
            pendingDelete = photo
            val sender = MediaStore.createDeleteRequest(contentResolver, listOf(photo.uri)).intentSender
            deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
        } catch (e: Exception) {
            pendingDelete = null
            Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onDeleted(photo: Photo) {
        val index = photos.indexOf(photo)
        if (index < 0) return
        photos.removeAt(index)
        if (photos.isEmpty()) {
            finish()
            return
        }
        adapter.notifyItemRemoved(index + 1)
        pager.post { updateChrome() }
    }

    private inner class PageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount() = GalleryPages.count(photos.size)

        override fun getItemViewType(position: Int) = if (GalleryPages.isCamera(position)) TYPE_CAMERA else TYPE_PHOTO

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val lp = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            return if (viewType == TYPE_CAMERA) {
                val root = FrameLayout(parent.context).apply {
                    layoutParams = lp
                    setBackgroundColor(0xFF000000.toInt())
                    isClickable = true
                    contentDescription = getString(R.string.back_to_camera)
                    setOnClickListener { finish() }
                }
                root.addView(
                    ImageView(parent.context).apply { setImageResource(R.drawable.ic_camera) },
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER,
                    ),
                )
                object : RecyclerView.ViewHolder(root) {}
            } else {
                val iv = ImageView(parent.context).apply {
                    layoutParams = lp
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(0xFF000000.toInt())
                }
                object : RecyclerView.ViewHolder(iv) {}
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (GalleryPages.isCamera(position)) return
            val iv = holder.itemView as ImageView
            val uri = photos[GalleryPages.photoIndex(position)].uri
            iv.setImageDrawable(null)
            iv.tag = uri
            val side = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
            io.execute {
                val bmp: Bitmap? = PhotoStore.load(this@GalleryActivity, uri, side)
                runOnUiThread { if (iv.tag == uri && bmp != null) iv.setImageBitmap(bmp) }
            }
        }
    }

    private companion object {
        const val TYPE_CAMERA = 0
        const val TYPE_PHOTO = 1
    }
}
