package cz.fenixen.navigation.core.search

import cz.fenixen.navigation.core.geo.GeoPoint
import java.net.URLDecoder

/**
 * Parsed `geo:` URI (RFC 5870 + Android's `?q=` extension), e.g.
 *  - `geo:49.1951,16.6068`
 *  - `geo:0,0?q=Kounicova 10, Brno`
 *  - `geo:0,0?q=49.19,16.61(Hlavní nádraží)`
 */
data class GeoIntent(val point: GeoPoint?, val query: String?, val label: String?)

object GeoUri {
    private val coordinates = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)""")
    private val labelled = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*\((.*)\)\s*$""")

    fun parse(uri: String): GeoIntent? {
        if (!uri.startsWith("geo:", ignoreCase = true)) return null
        val body = uri.substring(4)
        val path = body.substringBefore('?')
        val query = body.substringAfter('?', "")
            .split('&')
            .firstOrNull { it.startsWith("q=") }
            ?.removePrefix("q=")
            // Varianta decode(String, Charset) je na Androidu až od API 33.
            ?.let { URLDecoder.decode(it, "UTF-8") }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        // q="lat,lon(label)" má přednost před cestou (Google Maps to tak posílá).
        if (query != null) {
            labelled.find(query)?.let { m ->
                point(m.groupValues[1], m.groupValues[2])?.let { return GeoIntent(it, null, m.groupValues[3].ifBlank { null }) }
            }
            coordinates.matchEntire(query.trim())?.let { m ->
                point(m.groupValues[1], m.groupValues[2])?.let { return GeoIntent(it, null, null) }
            }
        }
        val pathPoint = coordinates.find(path)?.let { point(it.groupValues[1], it.groupValues[2]) }
            ?.takeUnless { it.lat == 0.0 && it.lon == 0.0 }
        if (pathPoint == null && query == null) return null
        return GeoIntent(pathPoint, query, null)
    }

    private fun point(lat: String, lon: String): GeoPoint? {
        val la = lat.toDoubleOrNull() ?: return null
        val lo = lon.toDoubleOrNull() ?: return null
        return if (la in -90.0..90.0 && lo in -180.0..180.0) GeoPoint(la, lo) else null
    }
}
