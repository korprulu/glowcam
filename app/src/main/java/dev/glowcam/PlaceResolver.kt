package dev.glowcam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Best-effort place name for the current position (coarse location, reverse geocoded).
 * Only the place name text is kept; coordinates are never stored. Returns null on any failure.
 * Blocking: call from a background thread.
 */
object PlaceResolver {

    fun resolve(context: Context): String? = try {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            null
        } else {
            locate(context)?.let { geocode(context, it) }
        }
    } catch (e: Exception) {
        null
    }

    private fun locate(context: Context): Location? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .firstOrNull { it in lm.allProviders } ?: return null
        var result: Location? = null
        val latch = CountDownLatch(1)
        val cancel = CancellationSignal()
        val direct = Executor { it.run() }
        lm.getCurrentLocation(provider, cancel, direct) { loc ->
            result = loc
            latch.countDown()
        }
        if (!latch.await(3, TimeUnit.SECONDS)) cancel.cancel()
        return result ?: lm.getLastKnownLocation(provider)
    }

    private fun geocode(context: Context, loc: Location): String? {
        if (!Geocoder.isPresent()) return null
        var name: String? = null
        val latch = CountDownLatch(1)
        Geocoder(context, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1, object : Geocoder.GeocodeListener {
            override fun onGeocode(addresses: MutableList<android.location.Address>) {
                val a = addresses.firstOrNull()
                if (a != null) {
                    name = PhotoInfo.placeName(a.locality ?: a.subAdminArea ?: a.adminArea, a.subLocality)
                }
                latch.countDown()
            }

            override fun onError(errorMessage: String?) {
                latch.countDown()
            }
        })
        latch.await(3, TimeUnit.SECONDS)
        return name
    }
}
