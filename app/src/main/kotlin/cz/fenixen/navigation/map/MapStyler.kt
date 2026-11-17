package cz.fenixen.navigation.map

import androidx.core.graphics.toColorInt
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.maps.Style

/**
 * Custom look on top of OpenFreeMap's minimal styles (Positron by day, Dark by night):
 * calm greys so the route and the car are the only saturated things on screen, motorways
 * tinted warm, 3D buildings when zoomed in. Same idea as F1 telemetry screens – data pops,
 * background stays out of the way.
 */
data class MapPalette(
    val background: Int,
    val water: Int,
    val green: Int,
    val residential: Int,
    val building: Int,
    val building3d: Int,
    val minorRoad: Int,
    val majorRoad: Int,
    val roadCasing: Int,
    val motorway: Int,
    val motorwayCasing: Int,
    val rail: Int,
    val label: Int,
    val labelHalo: Int,
) {
    companion object {
        val NIGHT = MapPalette(
            background = "#0C0F14".toColorInt(),
            water = "#0B1B2C".toColorInt(),
            green = "#0F1D18".toColorInt(),
            residential = "#10141B".toColorInt(),
            building = "#161B23".toColorInt(),
            building3d = "#1C222C".toColorInt(),
            minorRoad = "#232A35".toColorInt(),
            majorRoad = "#303A48".toColorInt(),
            roadCasing = "#161B22".toColorInt(),
            motorway = "#5A4027".toColorInt(),
            motorwayCasing = "#2B2017".toColorInt(),
            rail = "#2A303A".toColorInt(),
            label = "#8C96A4".toColorInt(),
            labelHalo = "#0C0F14".toColorInt(),
        )
        val DAY = MapPalette(
            background = "#F1F0EC".toColorInt(),
            water = "#C9DDEE".toColorInt(),
            green = "#DCEBD6".toColorInt(),
            residential = "#ECEAE5".toColorInt(),
            building = "#E2DFD9".toColorInt(),
            building3d = "#E6E2DB".toColorInt(),
            minorRoad = "#FFFFFF".toColorInt(),
            majorRoad = "#FFFFFF".toColorInt(),
            roadCasing = "#D5D1C9".toColorInt(),
            motorway = "#FFD8AE".toColorInt(),
            motorwayCasing = "#EDB37A".toColorInt(),
            rail = "#CFCBC3".toColorInt(),
            label = "#5A616D".toColorInt(),
            labelHalo = "#FFFFFF".toColorInt(),
        )
    }
}

object MapStyler {
    const val BUILDINGS_3D = "nav-buildings-3d"

    fun apply(style: Style, palette: MapPalette) {
        for (layer in style.layers) {
            val id = layer.id
            when (layer) {
                is BackgroundLayer -> layer.setProperties(PropertyFactory.backgroundColor(palette.background))
                is FillLayer -> when {
                    "water" in id -> layer.setProperties(PropertyFactory.fillColor(palette.water))
                    "park" in id || "wood" in id || "grass" in id -> layer.setProperties(PropertyFactory.fillColor(palette.green))
                    "residential" in id -> layer.setProperties(PropertyFactory.fillColor(palette.residential))
                    id == "building" -> layer.setProperties(PropertyFactory.fillColor(palette.building))
                }
                is LineLayer -> when {
                    "waterway" in id -> layer.setProperties(PropertyFactory.lineColor(palette.water))
                    "motorway" in id && "casing" in id -> layer.setProperties(PropertyFactory.lineColor(palette.motorwayCasing))
                    "motorway" in id -> layer.setProperties(PropertyFactory.lineColor(palette.motorway))
                    "casing" in id -> layer.setProperties(PropertyFactory.lineColor(palette.roadCasing))
                    "major" in id -> layer.setProperties(PropertyFactory.lineColor(palette.majorRoad))
                    "minor" in id || "path" in id -> layer.setProperties(PropertyFactory.lineColor(palette.minorRoad))
                    "rail" in id -> layer.setProperties(PropertyFactory.lineColor(palette.rail))
                }
                is SymbolLayer -> if (!id.startsWith("nav-")) {
                    layer.setProperties(
                        PropertyFactory.textColor(palette.label),
                        PropertyFactory.textHaloColor(palette.labelHalo),
                        PropertyFactory.textHaloWidth(1.4f),
                    )
                }
            }
        }
        add3dBuildings(style, palette)
    }

    /** Extruded buildings from OpenMapTiles `render_height`; visible from zoom 15 (where tilt makes sense). */
    private fun add3dBuildings(style: Style, palette: MapPalette) {
        if (style.getLayer(BUILDINGS_3D) != null || style.getSource("openmaptiles") == null) return
        val layer = FillExtrusionLayer(BUILDINGS_3D, "openmaptiles").apply {
            sourceLayer = "building"
            minZoom = 15f
            setProperties(
                PropertyFactory.fillExtrusionColor(palette.building3d),
                PropertyFactory.fillExtrusionHeight(Expression.coalesce(Expression.get("render_height"), Expression.literal(8))),
                PropertyFactory.fillExtrusionBase(Expression.coalesce(Expression.get("render_min_height"), Expression.literal(0))),
                PropertyFactory.fillExtrusionOpacity(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(15, 0f), Expression.stop(16, 0.75f))),
                PropertyFactory.visibility(Property.VISIBLE),
            )
        }
        val firstSymbol = style.layers.firstOrNull { it is SymbolLayer }?.id
        if (firstSymbol != null) style.addLayerBelow(layer, firstSymbol) else style.addLayer(layer)
    }
}
