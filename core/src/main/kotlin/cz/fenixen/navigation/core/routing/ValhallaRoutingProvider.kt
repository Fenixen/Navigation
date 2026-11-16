package cz.fenixen.navigation.core.routing

import cz.fenixen.navigation.core.format.CzechText
import cz.fenixen.navigation.core.geo.GeoPoint
import cz.fenixen.navigation.core.geo.Polyline
import cz.fenixen.navigation.core.geo.PolylineCodec
import cz.fenixen.navigation.core.model.Maneuver
import cz.fenixen.navigation.core.model.ManeuverSign
import cz.fenixen.navigation.core.model.ManeuverType
import cz.fenixen.navigation.core.model.Route
import cz.fenixen.navigation.core.model.RouteRequest
import cz.fenixen.navigation.core.model.SpeedLimitSpan
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Routing via a Valhalla server (https://github.com/valhalla/valhalla).
 *
 * Valhalla internally runs a bidirectional A* over a *hierarchical* tiled graph
 * (local / arterial / highway levels), which is why it answers cross-country queries in ms.
 * Default endpoint is the public FOSSGIS instance; for daily use point [baseUrl] at your own
 * instance (e.g. on the Raspberry Pi behind `tailscale serve`).
 */
class ValhallaRoutingProvider(
    private val http: OkHttpClient,
    private val baseUrl: () -> String,
    private val language: String = "cs-CZ",
) : RoutingProvider {

    override suspend fun route(request: RouteRequest): List<Route> {
        val body = ValhallaParser.buildRouteRequest(request, language).toString()
        val result = post("route", body)
        return ValhallaParser.parseRouteResponse(result.body)
    }

    override suspend fun speedLimits(route: Route): List<SpeedLimitSpan> {
        val points = route.geometry.points
        if (points.size < 2) return emptyList()
        // Velké trasy posíláme po kusech – server má limit na počet bodů v jednom trace požadavku.
        val spans = ArrayList<SpeedLimitSpan>()
        var start = 0
        while (start < points.size - 1) {
            val end = minOf(start + TRACE_CHUNK_POINTS, points.size - 1)
            val chunk = points.subList(start, end + 1)
            val body = ValhallaParser.buildTraceAttributesRequest(chunk).toString()
            val result = post("trace_attributes", body)
            spans += ValhallaParser.parseSpeedLimits(result.body, distanceOffset = route.geometry.cumulative[start])
            start = end
        }
        return ValhallaParser.mergeSpans(spans)
    }

    private suspend fun post(endpoint: String, json: String): HttpResult {
        val url = baseUrl().trimEnd('/') + "/" + endpoint
        val request = Request.Builder()
            .url(url)
            .post(json.toRequestBody(JSON_MEDIA))
            .build()
        val result = try {
            http.awaitString(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw RoutingException(RoutingException.Kind.NETWORK, "Routing server unreachable: ${e.message}", e)
        }
        if (result.code !in 200..299) throw ValhallaParser.parseError(result)
        return result
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val TRACE_CHUNK_POINTS = 8_000
    }
}

/** Pure (network-free) part of the Valhalla integration – unit-tested against real responses. */
internal object ValhallaParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun buildRouteRequest(request: RouteRequest, language: String): JsonObject = buildJsonObject {
        putJsonArray("locations") {
            addJsonObject {
                put("lat", request.origin.lat)
                put("lon", request.origin.lon)
                put("type", "break")
                request.originBearing?.let {
                    // Preferuj pokračování ve směru jízdy – jinak by přepočet často posílal na otočku.
                    put("heading", it.toInt())
                    put("heading_tolerance", 45)
                }
            }
            addJsonObject {
                put("lat", request.destination.lat)
                put("lon", request.destination.lon)
                put("type", "break")
            }
        }
        put("costing", "auto")
        putJsonObject("costing_options") {
            putJsonObject("auto") {
                put("use_tolls", if (request.options.avoidTolls) 0.0 else 0.5)
                put("use_highways", if (request.options.avoidHighways) 0.0 else 1.0)
                put("use_ferry", if (request.options.avoidFerries) 0.0 else 0.5)
            }
        }
        putJsonObject("directions_options") {
            put("language", language)
            put("units", "kilometers")
        }
        if (request.options.alternatives > 0) put("alternates", request.options.alternatives)
    }

    fun buildTraceAttributesRequest(points: List<GeoPoint>): JsonObject = buildJsonObject {
        put("encoded_polyline", PolylineCodec.encode(points, 6))
        // edge_walk = tvar přesně sleduje hrany grafu (je to přece výstup routeru) → žádný HMM map-matching.
        put("shape_match", "edge_walk")
        put("costing", "auto")
        putJsonObject("filters") {
            putJsonArray("attributes") {
                add("edge.speed_limit")
                add("edge.begin_shape_index")
                add("edge.end_shape_index")
                add("shape")
            }
            put("action", "include")
        }
    }

    fun parseRouteResponse(body: String): List<Route> {
        val response = try {
            json.decodeFromString(VRouteResponse.serializer(), body)
        } catch (e: Exception) {
            throw RoutingException(RoutingException.Kind.SERVER, "Unexpected routing response", e)
        }
        val trips = listOf(response.trip) + response.alternates.map { it.trip }
        return trips.mapIndexed { index, trip -> tripToRoute(trip, "r$index") }
    }

    fun parseError(result: HttpResult): RoutingException {
        val error = runCatching { json.decodeFromString(VError.serializer(), result.body) }.getOrNull()
        // 442 = "No path could be found", 171 = "No suitable edges near location".
        val noRoute = error?.errorCode in setOf(170, 171, 442, 443)
        return RoutingException(
            if (noRoute) RoutingException.Kind.NO_ROUTE else RoutingException.Kind.SERVER,
            error?.error ?: "HTTP ${result.code}",
        )
    }

    private fun tripToRoute(trip: VTrip, id: String): Route {
        // Více nohou (legs) spojíme do jedné geometrie; indexy tvaru posuneme o offset.
        val points = ArrayList<GeoPoint>()
        val legOffsets = IntArray(trip.legs.size)
        trip.legs.forEachIndexed { i, leg ->
            val decoded = PolylineCodec.decode(leg.shape, 6)
            legOffsets[i] = if (points.isEmpty()) 0 else points.size - 1
            points += if (points.isEmpty()) decoded else decoded.drop(1)
        }
        val geometry = Polyline(points)
        val lastIndex = points.size - 1

        val maneuvers = ArrayList<Maneuver>()
        trip.legs.forEachIndexed { legIndex, leg ->
            leg.maneuvers.forEachIndexed { mIndex, m ->
                val isIntermediateDepart = legIndex > 0 && mIndex == 0
                if (isIntermediateDepart) return@forEachIndexed
                val begin = (legOffsets[legIndex] + m.beginShapeIndex).coerceIn(0, lastIndex)
                val end = (legOffsets[legIndex] + m.endShapeIndex).coerceIn(begin, lastIndex)
                maneuvers += Maneuver(
                    type = mapType(m.type),
                    instruction = CzechText.normalizeValhalla(m.instruction),
                    verbalAlert = m.verbalAlert?.let(CzechText::normalizeValhalla),
                    verbalPre = m.verbalPre?.let(CzechText::normalizeValhalla),
                    streetNames = m.streetNames,
                    sign = m.sign?.toModel(),
                    roundaboutExitCount = m.roundaboutExitCount,
                    beginShapeIndex = begin,
                    distanceFromStart = geometry.cumulative[begin],
                    lengthMeters = geometry.cumulative[end] - geometry.cumulative[begin],
                    durationSeconds = m.time,
                    isToll = m.toll,
                    isHighway = m.highway,
                )
            }
        }

        return Route(
            id = id,
            geometry = geometry,
            maneuvers = maneuvers,
            distanceMeters = trip.summary.length * 1000.0,
            durationSeconds = trip.summary.time,
            summary = summarize(maneuvers),
            hasToll = trip.summary.hasToll,
            hasHighway = trip.summary.hasHighway,
            hasFerry = trip.summary.hasFerry,
        )
    }

    /** Two road names with the most driven distance – "přes D1, D46". */
    private fun summarize(maneuvers: List<Maneuver>): String {
        val byName = HashMap<String, Double>()
        for (m in maneuvers) {
            val name = m.primaryName ?: continue
            byName[name] = (byName[name] ?: 0.0) + m.lengthMeters
        }
        return byName.entries.sortedByDescending { it.value }.take(2).joinToString(", ") { it.key }
    }

    fun parseSpeedLimits(body: String, distanceOffset: Double): List<SpeedLimitSpan> {
        val response = json.decodeFromString(VTraceAttributes.serializer(), body)
        val shape = response.shape?.let { Polyline(PolylineCodec.decode(it, 6)) } ?: return emptyList()
        val last = shape.points.size - 1
        return response.edges.mapNotNull { e ->
            val begin = e.beginShapeIndex?.coerceIn(0, last) ?: return@mapNotNull null
            val end = e.endShapeIndex?.coerceIn(begin, last) ?: return@mapNotNull null
            // 0 = neznámý limit, 255 = bez omezení (Autobahn) – obojí neukazujeme.
            val kph = e.speedLimit?.intOrNull?.takeIf { it in 1..249 }
            SpeedLimitSpan(distanceOffset + shape.cumulative[begin], distanceOffset + shape.cumulative[end], kph)
        }
    }

    /** Sorts and merges neighbouring spans with equal limits (fewer spans = faster lookups). */
    fun mergeSpans(spans: List<SpeedLimitSpan>): List<SpeedLimitSpan> {
        val sorted = spans.filter { it.endDistance > it.startDistance }.sortedBy { it.startDistance }
        val out = ArrayList<SpeedLimitSpan>(sorted.size)
        for (s in sorted) {
            val prev = out.lastOrNull()
            if (prev != null && prev.kph == s.kph && s.startDistance <= prev.endDistance + 1.0) {
                out[out.size - 1] = prev.copy(endDistance = maxOf(prev.endDistance, s.endDistance))
            } else if (prev != null && s.startDistance < prev.endDistance) {
                out += s.copy(startDistance = prev.endDistance)
            } else {
                out += s
            }
        }
        return out.filter { it.endDistance > it.startDistance }
    }

    /** Valhalla maneuver type codes – see valhalla/proto/directions.proto (TripDirections.Maneuver.Type). */
    fun mapType(code: Int): ManeuverType = when (code) {
        1 -> ManeuverType.DEPART
        2 -> ManeuverType.DEPART_RIGHT
        3 -> ManeuverType.DEPART_LEFT
        4 -> ManeuverType.ARRIVE
        5 -> ManeuverType.ARRIVE_RIGHT
        6 -> ManeuverType.ARRIVE_LEFT
        7 -> ManeuverType.NAME_CHANGE
        8 -> ManeuverType.CONTINUE
        9 -> ManeuverType.SLIGHT_RIGHT
        10 -> ManeuverType.RIGHT
        11 -> ManeuverType.SHARP_RIGHT
        12 -> ManeuverType.UTURN_RIGHT
        13 -> ManeuverType.UTURN_LEFT
        14 -> ManeuverType.SHARP_LEFT
        15 -> ManeuverType.LEFT
        16 -> ManeuverType.SLIGHT_LEFT
        17 -> ManeuverType.RAMP_STRAIGHT
        18 -> ManeuverType.RAMP_RIGHT
        19 -> ManeuverType.RAMP_LEFT
        20 -> ManeuverType.EXIT_RIGHT
        21 -> ManeuverType.EXIT_LEFT
        22 -> ManeuverType.KEEP_STRAIGHT
        23 -> ManeuverType.KEEP_RIGHT
        24 -> ManeuverType.KEEP_LEFT
        25 -> ManeuverType.MERGE
        26 -> ManeuverType.ROUNDABOUT_ENTER
        27 -> ManeuverType.ROUNDABOUT_EXIT
        28 -> ManeuverType.FERRY_ENTER
        29 -> ManeuverType.FERRY_EXIT
        37 -> ManeuverType.MERGE_RIGHT
        38 -> ManeuverType.MERGE_LEFT
        else -> ManeuverType.UNKNOWN
    }

    private fun VSign.toModel() = ManeuverSign(
        exitNumber = exitNumber.firstOrNull()?.text,
        exitName = exitName.firstOrNull()?.text,
        branch = branch.map { it.text },
        toward = toward.map { it.text },
    )
}

// ---- DTOs (only the fields we use; unknown keys are ignored) ----

@Serializable
internal data class VRouteResponse(val trip: VTrip, val alternates: List<VAlternate> = emptyList())

@Serializable
internal data class VAlternate(val trip: VTrip)

@Serializable
internal data class VTrip(val legs: List<VLeg>, val summary: VSummary)

@Serializable
internal data class VLeg(val maneuvers: List<VManeuver>, val shape: String)

@Serializable
internal data class VSummary(
    val time: Double,
    val length: Double,
    @SerialName("has_toll") val hasToll: Boolean = false,
    @SerialName("has_highway") val hasHighway: Boolean = false,
    @SerialName("has_ferry") val hasFerry: Boolean = false,
)

@Serializable
internal data class VManeuver(
    val type: Int,
    val instruction: String = "",
    @SerialName("verbal_transition_alert_instruction") val verbalAlert: String? = null,
    @SerialName("verbal_pre_transition_instruction") val verbalPre: String? = null,
    @SerialName("street_names") val streetNames: List<String> = emptyList(),
    val time: Double = 0.0,
    val length: Double = 0.0,
    @SerialName("begin_shape_index") val beginShapeIndex: Int = 0,
    @SerialName("end_shape_index") val endShapeIndex: Int = 0,
    @SerialName("roundabout_exit_count") val roundaboutExitCount: Int? = null,
    val toll: Boolean = false,
    val highway: Boolean = false,
    val sign: VSign? = null,
)

@Serializable
internal data class VSign(
    @SerialName("exit_number_elements") val exitNumber: List<VSignElement> = emptyList(),
    @SerialName("exit_name_elements") val exitName: List<VSignElement> = emptyList(),
    @SerialName("exit_branch_elements") val branch: List<VSignElement> = emptyList(),
    @SerialName("exit_toward_elements") val toward: List<VSignElement> = emptyList(),
)

@Serializable
internal data class VSignElement(val text: String)

@Serializable
internal data class VError(
    @SerialName("error_code") val errorCode: Int? = null,
    val error: String? = null,
)

@Serializable
internal data class VTraceAttributes(val shape: String? = null, val edges: List<VEdge> = emptyList())

@Serializable
internal data class VEdge(
    // Číslo (km/h), ale některé verze serveru posílají i text ("unlimited") – parsujeme tolerantně.
    @SerialName("speed_limit") val speedLimit: JsonPrimitive? = null,
    @SerialName("begin_shape_index") val beginShapeIndex: Int? = null,
    @SerialName("end_shape_index") val endShapeIndex: Int? = null,
)
