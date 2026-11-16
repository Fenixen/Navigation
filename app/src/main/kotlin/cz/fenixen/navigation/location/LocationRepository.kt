package cz.fenixen.navigation.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import cz.fenixen.navigation.core.geo.GeoPoint
import cz.fenixen.navigation.core.model.LocationFix
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Fused Location Provider (GPS + Wi-Fi + cell + IMU sensor fusion in Google Play services).
 * In Android Auto projection the phone's location is used; many head units feed their own
 * GNSS/wheel-speed data into it, which is why it works well in tunnels too.
 */
class LocationRepository(private val context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Cold flow of fixes; location updates run only while collected. */
    @SuppressLint("MissingPermission") // kontrolu oprávnění dělá volající (LocationCoordinator)
    fun updates(intervalMillis: Long = 1_000): Flow<LocationFix> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis)
            .setMinUpdateIntervalMillis(intervalMillis / 2)
            .setWaitForAccurateLocation(false)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { trySend(it.toFix()) }
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(callback) }
    }

    @SuppressLint("MissingPermission")
    suspend fun lastKnown(): LocationFix? =
        if (!hasPermission()) null else runCatching { client.lastLocation.await()?.toFix() }.getOrNull()
}

fun Location.toFix() = LocationFix(
    point = GeoPoint(latitude, longitude),
    timeMillis = time,
    accuracyM = if (hasAccuracy()) accuracy.toDouble() else null,
    bearingDeg = if (hasBearing()) bearing.toDouble() else null,
    speedMps = if (hasSpeed()) speed.toDouble() else null,
)
