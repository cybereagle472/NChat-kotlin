package com.nchat.app.core.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * One position, for the "send my location" button.
 *
 * The platform `LocationManager` is used on purpose instead of Play Services: NChat has no Play
 * Services dependency, and a build that only works on phones with GMS would be a regression for
 * every AOSP / Huawei device.
 *
 * Order of preference:
 *  1. a **recent cached fix** (< 10 minutes) from any enabled provider — instant, and accurate
 *     enough for "I'm here" without waking the GPS;
 *  2. otherwise a **single fresh fix**, with a 9-second budget, so the button never hangs the UI
 *     and never leaves a listener registered.
 *
 * Returns null when the permission is missing, location is switched off, or no fix arrives in
 * time — the caller then says so instead of sending `0,0`, which would put the user in the Gulf of
 * Guinea.
 */
object Locator {

    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        if (!hasPermission(context)) return@withContext null
        val manager = ContextCompat.getSystemService(context, LocationManager::class.java)
            ?: return@withContext null
        val providers = runCatching { manager.getProviders(true) }.getOrDefault(emptyList())
        if (providers.isEmpty()) return@withContext null

        val recent = providers
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
        if (recent != null && System.currentTimeMillis() - recent.time < 10 * 60_000L) {
            return@withContext recent.coordinate()
        }

        // GPS is the most accurate, then the network provider, then whatever is enabled.
        val provider = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        ).firstOrNull { it in providers } ?: providers.first()

        val fresh = freshFix(manager, provider) ?: recent
        fresh?.coordinate()
    }

    private fun hasPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    private suspend fun freshFix(manager: LocationManager, provider: String): Location? {
        var listener: LocationListener? = null
        val fix = withTimeoutOrNull(9_000L) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (continuation.isActive) continuation.resume(location)
                    }

                    @Deprecated("Required by the platform before API 29")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                    override fun onProviderEnabled(provider: String) = Unit

                    override fun onProviderDisabled(provider: String) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
                listener = callback
                runCatching {
                    manager.requestLocationUpdates(provider, 0L, 0f, callback, Looper.getMainLooper())
                }.onFailure {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
        // The listener must go whether the fix arrived, the timeout fired or the coroutine died.
        listener?.let { callback -> runCatching { manager.removeUpdates(callback) } }
        return fix
    }

    private fun Location.coordinate(): Pair<Double, Double> = latitude to longitude
}
