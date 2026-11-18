package cz.fenixen.navigation.core.traffic

import cz.fenixen.navigation.core.geo.GeoBounds
import cz.fenixen.navigation.core.geo.GeoPoint
import cz.fenixen.navigation.core.geo.Polyline
import cz.fenixen.navigation.core.model.IncidentCategory
import cz.fenixen.navigation.core.model.Route
import cz.fenixen.navigation.core.model.RouteOptions
import cz.fenixen.navigation.core.model.RouteTraffic
import cz.fenixen.navigation.core.model.TrafficLevel
import cz.fenixen.navigation.core.model.TrafficIncident
import cz.fenixen.navigation.core.model.TrafficRoute
import cz.fenixen.navigation.core.model.TrafficSpan
import cz.fenixen.navigation.core.routing.awaitString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
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

    /** Current incidents (accidents, closures, road works …) inside [bounds]. */
    suspend fun incidents(bounds: GeoBounds): List<TrafficIncident> = emptyList()

    /** Fastest route with current traffic – used to detect a faster alternative while driving. */
    suspend fun fastestRoute(origin: GeoPoint, bearing: Double?, destination: GeoPoint, options: RouteOptions): TrafficRoute? = null
}

class TrafficException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Health of the traffic connection – shown in settings so a bad API key is obvious. */
data class TrafficStatus(val lastOkMillis: Long? = null, val lastError: String? = null, val lastErrorMillis: Long? = null) {
    val isHealthy: Boolean get() = lastOkMillis != null && (lastErrorMillis == null || lastOkMillis >= lastErrorMillis)
}

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
    private val _status = MutableStateFlow(TrafficStatus())
    val status: StateFlow<TrafficStatus> = _status.asStateFlow()

    /** Records success / a human-readable error for every TomTom call. */
    private suspend fun <T> tracked(block: suspend () -> T): T = try {
        block().also { _status.update { s -> s.copy(lastOkMillis = clock()) } }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        _status.update { s -> s.copy(lastError = TomTomTraffic.explain(e), lastErrorMillis = clock()) }
        throw e
    }

    override suspend fun traffic(route: Route, fromDistance: Double): RouteTraffic = tracked { trafficImpl(route, fromDistance) }

    private suspend fun trafficImpl(route: Route, fromDistance: Double): RouteTraffic {
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
        if (result.code !in 200..299) throw TrafficException("HTTP ${result.code}")
        val remainingLength = route.geometry.length - fromDistance
        return TomTomTraffic.parse(result.body, fromDistance, remainingLength, clock())
    }

    override suspend fun incidents(bounds: GeoBounds): List<TrafficIncident> = tracked { incidentsImpl(bounds) }

    private suspend fun incidentsImpl(bounds: GeoBounds): List<TrafficIncident> {
        val url = "https://api.tomtom.com/traffic/services/5/incidentDetails".toHttpUrl().newBuilder()
            .addQueryParameter("key", key())
            .addQueryParameter(
                "bbox",
                String.format(Locale.ROOT, "%.5f,%.5f,%.5f,%.5f", bounds.west, bounds.south, bounds.east, bounds.north),
            )
            .addQueryParameter("fields", TomTomTraffic.INCIDENT_FIELDS)
            .addQueryParameter("language", "cs-CZ")
            .addQueryParameter("timeValidityFilter", "present")
            .build()
        return TomTomTraffic.parseIncidents(get(url.toString()))
    }

    override suspend fun fastestRoute(origin: GeoPoint, bearing: Double?, destination: GeoPoint, options: RouteOptions): TrafficRoute? =
        tracked { fastestRouteImpl(origin, bearing, destination, options) }

    private suspend fun fastestRouteImpl(origin: GeoPoint, bearing: Double?, destination: GeoPoint, options: RouteOptions): TrafficRoute? {
        val locations = String.format(Locale.ROOT, "%.6f,%.6f:%.6f,%.6f", origin.lat, origin.lon, destination.lat, destination.lon)
        val url = "https://api.tomtom.com/routing/1/calculateRoute/$locations/json".toHttpUrl().newBuilder()
            .addQueryParameter("key", key())
            .addQueryParameter("traffic", "true")
            .addQueryParameter("travelMode", "car")
            .addQueryParameter("routeType", "fastest")
            .apply {
                bearing?.let { addQueryParameter("vehicleHeading", it.toInt().coerceIn(0, 359).toString()) }
                if (options.avoidTolls) addQueryParameter("avoid", "tollRoads")
                if (options.avoidHighways) addQueryParameter("avoid", "motorways")
                if (options.avoidFerries) addQueryParameter("avoid", "ferries")
            }
            .build()
        return TomTomTraffic.parseRoute(get(url.toString()))
    }

    private fun key(): String = apiKey()?.takeIf { it.isNotBlank() } ?: throw TrafficException("Missing TomTom API key")

    private suspend fun get(url: String): String {
        val result = try {
            http.awaitString(Request.Builder().url(url).get().build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw TrafficException("TomTom unreachable: ${e.message}", e)
        }
        if (result.code !in 200..299) throw TrafficException("HTTP ${result.code}")
        return result.body
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

    fun explain(e: Exception): String = when {
        e.message == "Missing TomTom API key" -> "Chybí API klíč"
        e.message == "HTTP 403" -> "Klíč odmítnut (HTTP 403) – zkontroluj ho, příp. povolená API v portálu TomTom"
        e.message == "HTTP 401" -> "Neplatný klíč (HTTP 401)"
        e.message == "HTTP 429" -> "Překročen denní limit dotazů (HTTP 429)"
        e.message?.startsWith("HTTP ") == true -> "Server TomTom vrátil ${e.message}"
        e.message?.startsWith("TomTom unreachable") == true -> "TomTom není dostupný – zkontroluj připojení"
        else -> "Neočekávaná odpověď TomTomu"
    }

    /** Only what we need from the Incident Details v5 response. */
    const val INCIDENT_FIELDS =
        "{incidents{type,geometry{type,coordinates},properties{id,iconCategory,magnitudeOfDelay,events{description,code,iconCategory},length,delay,roadNumbers}}}"

    fun parseIncidents(body: String): List<TrafficIncident> {
        val response = try {
            json.decodeFromString(TtIncidents.serializer(), body)
        } catch (e: Exception) {
            throw TrafficException("Unexpected TomTom incidents response", e)
        }
        return response.incidents.mapNotNull { f ->
            val geometry = f.geometry.points()
            if (geometry.isEmpty()) return@mapNotNull null
            val p = f.properties
            TrafficIncident(
                id = p.id ?: geometry.first().toString(),
                category = category(p.iconCategory),
                geometry = geometry,
                description = p.events.firstOrNull()?.description,
                delaySeconds = p.delay?.toDouble(),
                lengthMeters = p.length,
                roadNumbers = p.roadNumbers,
            )
        }
    }

    fun parseRoute(body: String): TrafficRoute? {
        val response = try {
            json.decodeFromString(TtResponse.serializer(), body)
        } catch (e: Exception) {
            throw TrafficException("Unexpected TomTom route response", e)
        }
        val route = response.routes.firstOrNull() ?: return null
        val points = route.legs.flatMap { it.points }.map { GeoPoint(it.latitude, it.longitude) }
        if (points.size < 2) return null
        return TrafficRoute(points, route.summary.travelTimeInSeconds.toDouble(), route.summary.trafficDelayInSeconds.toDouble())
    }

    /** TomTom iconCategory → our category (see Incident Details API docs). */
    fun category(code: Int?): IncidentCategory = when (code) {
        1 -> IncidentCategory.ACCIDENT
        6 -> IncidentCategory.JAM
        7 -> IncidentCategory.LANE_CLOSED
        8 -> IncidentCategory.ROAD_CLOSED
        9 -> IncidentCategory.ROAD_WORKS
        14 -> IncidentCategory.BROKEN_DOWN_VEHICLE
        3 -> IncidentCategory.DANGER
        2, 4, 5, 10, 11 -> IncidentCategory.WEATHER
        else -> IncidentCategory.OTHER
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
internal data class TtIncidents(val incidents: List<TtIncident> = emptyList())

@Serializable
internal data class TtIncident(val geometry: TtGeometry, val properties: TtIncidentProperties)

@Serializable
internal data class TtGeometry(val type: String, val coordinates: JsonElement) {
    /** Point → [lon, lat]; LineString → [[lon, lat], …]. */
    fun points(): List<GeoPoint> = when (type) {
        "Point" -> listOfNotNull(coordinates.jsonArray.toPoint())
        "LineString" -> coordinates.jsonArray.mapNotNull { it.jsonArray.toPoint() }
        else -> emptyList()
    }

    private fun JsonArray.toPoint(): GeoPoint? {
        if (size < 2) return null
        val lon = this[0].jsonPrimitive.doubleOrNull ?: return null
        val lat = this[1].jsonPrimitive.doubleOrNull ?: return null
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) GeoPoint(lat, lon) else null
    }
}

@Serializable
internal data class TtIncidentProperties(
    val id: String? = null,
    val iconCategory: Int? = null,
    val magnitudeOfDelay: Int? = null,
    val events: List<TtEvent> = emptyList(),
    val length: Double? = null,
    val delay: Int? = null,
    val roadNumbers: List<String> = emptyList(),
)

@Serializable
internal data class TtEvent(val description: String? = null, val code: Int? = null, val iconCategory: Int? = null)

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
