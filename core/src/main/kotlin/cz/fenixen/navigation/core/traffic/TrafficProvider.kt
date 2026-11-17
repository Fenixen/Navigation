package cz.fenixen.navigation.core.traffic

import cz.fenixen.navigation.core.geo.GeoPoint
import cz.fenixen.navigation.core.geo.Polyline
import cz.fenixen.navigation.core.model.Route
import cz.fenixen.navigation.core.model.RouteTraffic
import cz.fenixen.navigation.core.model.TrafficLevel
import cz.fenixen.navigation.core.model.TrafficSpan
import cz.fenixen.navigation.core.routing.awaitString
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale

/** Live traffic for a route that was computed by someone else (Valhalla doesn't know traffic). */
interface TrafficProvider {
    /** Traffic for the part of [route] from [fromDistance] (metres along the route) to the end. */
    suspend fun traffic(route: Route, fromDistance: Double = 0.0): RouteTraffic
}

class TrafficException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * TomTom Routing API in "route reconstruction" mode: we send *our* route as supporting points and
 * TomTom returns live travel time, total delay and the congested sections on exactly that route.
 *
 * Why not route with TomTom completely? Valhalla gives us Czech instructions, speed limits and
 * runs self-hosted; TomTom only adds the traffic layer (free tier: 2 500 requests/day).
 */
class TomTomTrafficProvider(
    private val http: OkHttpClient,
    private val apiKey: () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
) : TrafficProvider {

    override suspend fun traffic(route: Route, fromDistance: Double): RouteTraffic {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: throw TrafficException("Missing TomTom API key")
        val points = TomTomTraffic.supportingPoints(route.geometry, fromDistance)
        val first = points.first()
        val last = points.last()
        val locations = String.format(Locale.ROOT, "%.6f,%.6f:%.6f,%.6f", first.lat, first.lon, last.lat, last.lon)
        val url = "https://api.tomtom.com/routing/1/calculateRoute/$locations/json".toHttpUrl().newBuilder()
            .addQueryParameter("key", key)
            .addQueryParameter("traffic", "true")
            .addQueryParameter("travelMode", "car")
            .addQueryParameter("routeType", "fastest")
            .addQueryParameter("sectionType", "traffic")
            .addQueryParameter("computeTravelTimeFor", "all")
            .build()
        val body = TomTomTraffic.requestBody(points)
        val result = try {
            http.awaitString(Request.Builder().url(url).post(body.toRequestBody(JSON)).build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw TrafficException("TomTom unreachable: ${e.message}", e)
        }
        if (result.code !in 200..299) throw TrafficException("TomTom HTTP ${result.code}")
        val remainingLength = route.geometry.length - fromDistance
        return TomTomTraffic.parse(result.body, fromDistance, remainingLength, clock())
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** Pure part of the TomTom integration (unit-tested). */
internal object TomTomTraffic {
    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_SUPPORTING_POINTS = 400

    /** Resampled remaining geometry: dense enough for TomTom to follow our exact roads. */
    fun supportingPoints(geometry: Polyline, fromDistance: Double): List<GeoPoint> {
        val slice = geometry.slice(fromDistance)
        if (slice.size <= MAX_SUPPORTING_POINTS) return slice
        val length = Polyline(slice).length
        val spacing = maxOf(25.0, length / (MAX_SUPPORTING_POINTS - 1))
        val line = Polyline(slice)
        val out = ArrayList<GeoPoint>(MAX_SUPPORTING_POINTS + 1)
        var d = 0.0
        while (d < length) {
            out += line.pointAt(d)
            d += spacing
        }
        out += slice.last()
        return out
    }

    fun requestBody(points: List<GeoPoint>): String = buildJsonObject {
        putJsonArray("supportingPoints") {
            points.forEach { p ->
                addJsonObject {
                    put("latitude", p.lat)
                    put("longitude", p.lon)
                }
            }
        }
    }.toString()

    fun parse(body: String, fromDistance: Double, ourRemainingLength: Double, now: Long): RouteTraffic {
        val response = try {
            json.decodeFromString(TtResponse.serializer(), body)
        } catch (e: Exception) {
            throw TrafficException("Unexpected TomTom response", e)
        }
        val route = response.routes.firstOrNull() ?: throw TrafficException("TomTom returned no route")
        val points = route.legs.flatMap { it.points }.map { GeoPoint(it.latitude, it.longitude) }
        val cumulative = if (points.size >= 2) Polyline(points).cumulative else DoubleArray(points.size)
        val theirLength = cumulative.lastOrNull()?.takeIf { it > 0 } ?: route.summary.lengthInMeters.toDouble()
        // Délky se mezi routery liší o pár procent → převod přes poměr.
        val scale = if (theirLength > 0) ourRemainingLength / theirLength else 1.0

        val spans = route.sections.filter { it.sectionType == "TRAFFIC" }.mapNotNull { s ->
            val start = cumulative.getOrNull(s.startPointIndex) ?: return@mapNotNull null
            val end = cumulative.getOrNull(s.endPointIndex) ?: return@mapNotNull null
            if (end <= start) return@mapNotNull null
            TrafficSpan(
                startDistance = fromDistance + start * scale,
                endDistance = fromDistance + end * scale,
                level = level(s),
                delaySeconds = s.delayInSeconds?.toDouble() ?: 0.0,
                speedKmh = s.effectiveSpeedInKmh,
            )
        }
        return RouteTraffic(
            fromDistance = fromDistance,
            travelTimeSeconds = route.summary.travelTimeInSeconds.toDouble(),
            delaySeconds = route.summary.trafficDelayInSeconds.toDouble(),
            spans = spans,
            fetchedAtMillis = now,
        )
    }

    private fun level(s: TtSection): TrafficLevel = when {
        s.simpleCategory == "ROAD_CLOSURE" || s.magnitudeOfDelay == 4 -> TrafficLevel.CLOSED
        s.magnitudeOfDelay == 3 -> TrafficLevel.JAM
        s.magnitudeOfDelay == 2 -> TrafficLevel.QUEUE
        s.magnitudeOfDelay == 1 -> TrafficLevel.SLOW
        s.simpleCategory == "JAM" -> TrafficLevel.QUEUE
        else -> TrafficLevel.SLOW
    }
}

@Serializable
internal data class TtResponse(val routes: List<TtRoute> = emptyList())

@Serializable
internal data class TtRoute(
    val summary: TtSummary,
    val legs: List<TtLeg> = emptyList(),
    val sections: List<TtSection> = emptyList(),
)

@Serializable
internal data class TtSummary(
    val lengthInMeters: Int = 0,
    val travelTimeInSeconds: Int,
    val trafficDelayInSeconds: Int = 0,
)

@Serializable
internal data class TtLeg(val points: List<TtPoint> = emptyList())

@Serializable
internal data class TtPoint(val latitude: Double, val longitude: Double)

@Serializable
internal data class TtSection(
    val startPointIndex: Int,
    val endPointIndex: Int,
    val sectionType: String,
    val simpleCategory: String? = null,
    val magnitudeOfDelay: Int? = null,
    val delayInSeconds: Int? = null,
    val effectiveSpeedInKmh: Int? = null,
)
