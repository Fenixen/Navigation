package cz.fenixen.navigation.core.search

import cz.fenixen.navigation.core.geo.GeoPoint
import cz.fenixen.navigation.core.model.Place
import cz.fenixen.navigation.core.routing.awaitString
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

interface Geocoder {
    /** Free-text search, biased towards [near]. Safe to call on every keystroke (supports cancellation). */
    suspend fun search(query: String, near: GeoPoint? = null, limit: Int = 8): List<Place>

    suspend fun reverse(point: GeoPoint): Place?
}

class GeocodingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Photon (https://photon.komoot.io) – OSM geocoder built for search-as-you-type.
 * Nominatim's usage policy forbids autocomplete, Photon is designed for it.
 */
class PhotonGeocoder(
    private val http: OkHttpClient,
    private val baseUrl: () -> String,
) : Geocoder {

    override suspend fun search(query: String, near: GeoPoint?, limit: Int): List<Place> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()
        val url = (baseUrl().trimEnd('/') + "/api/").toHttpUrl().newBuilder()
            .addQueryParameter("q", trimmed)
            .addQueryParameter("limit", limit.toString())
            .apply {
                near?.let {
                    addQueryParameter("lat", it.lat.toString())
                    addQueryParameter("lon", it.lon.toString())
                }
            }
            .build()
        return PhotonParser.parse(get(url.toString()))
    }

    override suspend fun reverse(point: GeoPoint): Place? {
        val url = (baseUrl().trimEnd('/') + "/reverse").toHttpUrl().newBuilder()
            .addQueryParameter("lat", point.lat.toString())
            .addQueryParameter("lon", point.lon.toString())
            .build()
        return PhotonParser.parse(get(url.toString())).firstOrNull()
    }

    private suspend fun get(url: String): String {
        val result = try {
            http.awaitString(Request.Builder().url(url).get().build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw GeocodingException("Geocoder unreachable: ${e.message}", e)
        }
        if (result.code !in 200..299) throw GeocodingException("Geocoder HTTP ${result.code}")
        return result.body
    }
}

internal object PhotonParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): List<Place> =
        json.decodeFromString(PFeatureCollection.serializer(), body).features.mapNotNull { it.toPlace() }

    private fun PFeature.toPlace(): Place? {
        val coords = geometry.coordinates
        if (coords.size < 2) return null
        val p = properties
        val streetLine = listOfNotNull(p.street, p.housenumber).joinToString(" ").ifBlank { null }
        val title = p.name ?: streetLine ?: p.city ?: return null
        val locality = p.city ?: p.district ?: p.county
        val address = listOfNotNull(streetLine.takeIf { it != title }, locality.takeIf { it != title })
            .joinToString(", ")
            .ifBlank { null }
        return Place(
            name = title,
            point = GeoPoint(coords[1], coords[0]),
            address = address,
            category = p.osmValue,
        )
    }
}

@Serializable
internal data class PFeatureCollection(val features: List<PFeature> = emptyList())

@Serializable
internal data class PFeature(val geometry: PGeometry, val properties: PProperties)

@Serializable
internal data class PGeometry(val coordinates: List<Double> = emptyList())

@Serializable
internal data class PProperties(
    val name: String? = null,
    val street: String? = null,
    val housenumber: String? = null,
    val city: String? = null,
    val district: String? = null,
    val county: String? = null,
    @kotlinx.serialization.SerialName("osm_value") val osmValue: String? = null,
)
