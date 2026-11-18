package dev.glowcam

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Full-screen photo viewer. See [GalleryPages] for the page layout. */
class GalleryActivity : ComponentActivity() {

    private val io: ExecutorService = Executors.newFixedThreadPool(2)
    private lateinit var pager: ViewPager2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val photos = PhotoStore.list(this)

        pager = ViewPager2(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            adapter = PageAdapter(photos)
        }
        setContentView(pager)
        pager.setCurrentItem(GalleryPages.newestPosition(photos.size), false)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrollStateChanged(state: Int) {
                // Settled on the camera page: switch back to shooting mode.
                if (state == ViewPager2.SCROLL_STATE_IDLE && GalleryPages.isCamera(pager.currentItem)) {
                    pager.postDelayed({ if (!isFinishing) finish() }, 350)
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }

    private inner class PageAdapter(private val photos: List<Uri>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

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
                        android.view.Gravity.CENTER,
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
            val uri = photos[GalleryPages.photoIndex(position)]
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
