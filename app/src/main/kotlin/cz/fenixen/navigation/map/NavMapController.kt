package cz.fenixen.navigation.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.view.Choreographer
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import cz.fenixen.navigation.core.geo.Geo
import cz.fenixen.navigation.core.geo.GeoPoint
import cz.fenixen.navigation.core.geo.Polyline
import cz.fenixen.navigation.core.model.IncidentCategory
import cz.fenixen.navigation.core.model.LocationFix
import cz.fenixen.navigation.core.model.Route
import cz.fenixen.navigation.core.model.TrafficLevel
import cz.fenixen.navigation.core.navigation.CameraCommand
import cz.fenixen.navigation.core.navigation.CameraController
import cz.fenixen.navigation.core.navigation.FollowMode
import cz.fenixen.navigation.core.navigation.MapOrientation
import cz.fenixen.navigation.core.navigation.NavigationSession
import cz.fenixen.navigation.core.navigation.NavigationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/** Unobstructed part of the map view in px (what's not covered by banners, sheets, car cards). */
data class MapViewport(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0)

object MapStyles {
    const val DAY = "https://tiles.openfreemap.org/styles/positron"
    const val NIGHT = "https://tiles.openfreemap.org/styles/dark"
}

/**
 * Adapter between the platform-independent [CameraController] / [NavigationSession] and MapLibre.
 * Used by the phone (MapView in Compose) and by Android Auto (MapView on the car surface).
 *
 * The car marker and the camera are driven by **one frame loop** (Choreographer, 60–120 fps):
 * every frame we interpolate the marker between GPS fixes and place the camera exactly behind it.
 * Marker and camera can never drift apart, and the zoom/padding we command is the zoom you see –
 * nothing else in the stack (no tracking mode, no Activity recreation) can reset it.
 */
class NavMapController(
    private val context: Context,
    private val mapView: MapView,
    private val camera: CameraController,
    private val session: NavigationSession,
    private val scope: CoroutineScope,
    private val darkMode: StateFlow<Boolean>,
    /** TomTom key when the traffic overlay should be shown, null otherwise. */
    private val trafficTilesKey: StateFlow<String?>,
) {
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var dark = darkMode.value
    private var observing = false

    /** Unobstructed area; set by the host UI whenever its overlays change size. */
    var viewport: MapViewport = MapViewport()
        set(value) {
            if (field == value) return
            field = value
            frames.ensure()
        }

    // ---- marker animation state
    private var fromPos: GeoPoint? = null
    private var toPos: GeoPoint? = null
    private var fromBearing = 0.0
    private var toBearing = 0.0
    private var animStart = 0L
    private var animDuration = 1L
    private var lastFixAt = 0L
    private var shownPos: GeoPoint? = null
    private var shownBearing = 0.0
    private var accuracyRadius = 0f
    private var lastPositionKey: Pair<LocationFix?, GeoPoint?>? = null

    // ---- camera animation state
    private var targetZoom = Double.NaN
    private var targetTilt = 0.0
    private var curZoom = Double.NaN
    private var curTilt = 0.0
    private var curCamBearing = 0.0
    private var curPadding = DoubleArray(4)
    private var lastFrameAt = 0L
    private var resumeUntil = 0L
    private var gestureActive = false
    private var userMoved = false
    private var userScaled = false
    private var positionedOnce = false
    /** Whether the frame loop currently owns the camera (FOLLOW applied and not interrupted). */
    private var cameraFollowing = false

    // ---- route drawing state
    private var drawnGeometry: Polyline? = null
    private var drawnFrom = -1.0
    private var drawnTrafficAt = -1L
    private var drawnPreviewKey: String? = null
    private var drawnManeuverKey: String? = null
    private var drawnIncidentsKey: String? = null

    private val frames = FrameLoop()

    fun start() {
        mapView.getMapAsync { m ->
            map = m
            configureMap(m)
            scope.launch {
                darkMode.collect { isDark ->
                    dark = isDark
                    m.setStyle(Style.Builder().fromUri(if (isDark) MapStyles.NIGHT else MapStyles.DAY)) { onStyleReady(it) }
                }
            }
            scope.launch { trafficTilesKey.collect { style?.let(::updateTrafficOverlay) } }
        }
    }

    /** Stop the frame loop (owner is being destroyed). */
    fun release() {
        frames.stop()
    }

    // ------------------------------------------------------------------ user actions

    fun zoomIn() = apply(camera.zoomBy(+1, map?.cameraPosition?.zoom))
    fun zoomOut() = apply(camera.zoomBy(-1, map?.cameraPosition?.zoom))
    fun lockMaxZoom() = apply(camera.lockMaxZoom())
    fun lockCurrentZoom() = apply(camera.lockAt(map?.cameraPosition?.zoom ?: camera.state.value.effectiveZoom))
    fun enableAutoZoom() = apply(camera.enableAutoZoom())
    fun recenter() = apply(camera.recenter())

    fun toggleOrientation(): MapOrientation {
        val o = camera.toggleOrientation()
        if (cameraFollowing) startFollowTransition()
        return o
    }

    fun showOverview() {
        val nav = session.state.value as? NavigationState.Navigating ?: return
        val from = nav.progress?.distanceAlong ?: 0.0
        apply(camera.showOverview(nav.route.geometry.slice(from)))
    }

    /** Car surface pan (Android Auto delivers raw scroll deltas). */
    fun scrollBy(dx: Float, dy: Float) {
        val m = map ?: return
        if (camera.state.value.followMode == FollowMode.FOLLOW) camera.onUserPan()
        cameraFollowing = false
        m.scrollBy(-dx, -dy, 0)
    }

    /** Car pinch / double-tap / rotary zoom. scaleFactor > 1 zooms in. */
    fun scaleBy(scaleFactor: Float, focusX: Float, focusY: Float) {
        val m = map ?: return
        val delta = ln(scaleFactor.toDouble()) / ln(2.0)
        if (camera.state.value.followMode == FollowMode.FOLLOW) {
            apply(camera.onUserZoom(m.cameraPosition.zoom + delta))
        } else {
            m.moveCamera(CameraUpdateFactory.zoomBy(delta, android.graphics.Point(focusX.toInt(), focusY.toInt())))
        }
    }

    // ------------------------------------------------------------------ setup

    private fun configureMap(m: MapLibreMap) {
        m.setMaxZoomPreference(21.0)
        m.uiSettings.isTiltGesturesEnabled = false
        m.uiSettings.isCompassEnabled = false
        m.uiSettings.isLogoEnabled = false
        // Výchozí pohled (střed ČR) místo „celého světa“, než přijde první poloha.
        if (session.lastFix.value == null) {
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(49.8, 15.5), 6.5))
        }

        m.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) gestureActive = true
        }
        m.addOnMoveListener(object : MapLibreMap.OnMoveListener {
            override fun onMoveBegin(detector: org.maplibre.android.gestures.MoveGestureDetector) {
                userMoved = true
            }
            override fun onMove(detector: org.maplibre.android.gestures.MoveGestureDetector) = Unit
            override fun onMoveEnd(detector: org.maplibre.android.gestures.MoveGestureDetector) = Unit
        })
        m.addOnScaleListener(object : MapLibreMap.OnScaleListener {
            override fun onScaleBegin(detector: org.maplibre.android.gestures.StandardScaleGestureDetector) {
                userScaled = true
            }
            override fun onScale(detector: org.maplibre.android.gestures.StandardScaleGestureDetector) = Unit
            override fun onScaleEnd(detector: org.maplibre.android.gestures.StandardScaleGestureDetector) = Unit
        })
        m.addOnCameraIdleListener {
            if (!gestureActive) return@addOnCameraIdleListener
            gestureActive = false
            val pos = m.cameraPosition
            val zoomChanged = curZoom.isNaN() || abs(pos.zoom - curZoom) > 0.05
            curZoom = pos.zoom
            curTilt = pos.tilt
            curCamBearing = pos.bearing
            when {
                // Posun bez pinch → uživatel si prohlíží mapu.
                userMoved && !userScaled -> {
                    camera.onUserPan()
                    cameraFollowing = false
                }
                // Pinch / dvojklik → nový zoom; ve FOLLOW se zamkne (tady vzniká „napořád“).
                zoomChanged -> apply(camera.onUserZoom(pos.zoom))
            }
            userMoved = false
            userScaled = false
            if (cameraFollowing) startFollowTransition()
        }
        scope.launch {
            camera.state.collect { s ->
                m.uiSettings.isRotateGesturesEnabled = s.followMode != FollowMode.FOLLOW
                if (s.followMode != FollowMode.FOLLOW) m.uiSettings.focalPoint = null
            }
        }
    }

    private fun onStyleReady(s: Style) {
        style = s
        MapStyler.apply(s, if (dark) MapPalette.NIGHT else MapPalette.DAY)
        addOwnLayers(s)
        updateTrafficOverlay(s)
        drawnGeometry = null
        drawnPreviewKey = null
        drawnManeuverKey = null
        drawnIncidentsKey = null
        drawnFrom = -1.0
        if (!observing) {
            observing = true
            // Během navigace každý fix mění i stav (progress) → stačí stav. Mimo navigaci poloha.
            scope.launch { session.state.collect { render(it, session.lastFix.value) } }
            scope.launch {
                session.lastFix.collect { fix ->
                    val st = session.state.value
                    if (st !is NavigationState.Navigating) render(st, fix)
                }
            }
        } else {
            render(session.state.value, session.lastFix.value)
        }
        updatePuckLayer()
        frames.ensure()
    }

    private fun addOwnLayers(s: Style) {
        val firstSymbol = s.layers.firstOrNull { it is SymbolLayer && !it.id.startsWith("nav-") }?.id
        fun below(layer: Layer) = if (firstSymbol != null) s.addLayerBelow(layer, firstSymbol) else s.addLayer(layer)

        s.addSource(GeoJsonSource(SRC_ALTERNATIVES))
        s.addSource(GeoJsonSource(SRC_ROUTE, GeoJsonOptions().withLineMetrics(true)))
        s.addSource(GeoJsonSource(SRC_MANEUVER))
        s.addSource(GeoJsonSource(SRC_MANEUVER_HEAD))
        s.addSource(GeoJsonSource(SRC_DESTINATION))
        s.addSource(GeoJsonSource(SRC_INCIDENTS))
        IncidentCategory.entries.forEach { s.addImage(incidentImage(it), PuckBitmaps.incident(context, it)) }
        s.addImage(IMG_DESTINATION, destinationPin())
        s.addImage(IMG_ARROW, PuckBitmaps.arrowHead(context, WHITE, ROUTE_CASING))
        s.addImage(IMG_PICKUP, PuckBitmaps.pickup(context))
        s.addImage(IMG_GLOW, PuckBitmaps.glow(context, if (dark) 0x66FF7A1A else 0x40000000))

        val w = { base: Float -> widthByZoom(base) }
        below(
            LineLayer(LYR_ALTERNATIVES, SRC_ALTERNATIVES).withProperties(
                PropertyFactory.lineColor(if (dark) "#5B6F99".toColorInt() else "#9CB3E0".toColorInt()),
                PropertyFactory.lineWidth(w(0.75f)),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        // Záře pod trasou – na tmavé mapě dělá z trasy „neonovou“ linku, je vidět i periferně.
        below(
            LineLayer(LYR_ROUTE_GLOW, SRC_ROUTE).withProperties(
                PropertyFactory.lineColor(trafficColor()),
                PropertyFactory.lineWidth(w(2.6f)),
                PropertyFactory.lineBlur(w(1.4f)),
                PropertyFactory.lineOpacity(if (dark) 0.45f else 0.22f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        below(
            LineLayer(LYR_ROUTE_CASING, SRC_ROUTE).withProperties(
                PropertyFactory.lineColor(if (dark) WHITE else ROUTE_CASING),
                PropertyFactory.lineWidth(w(1.35f)),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        below(
            LineLayer(LYR_ROUTE, SRC_ROUTE).withProperties(
                PropertyFactory.lineColor(trafficColor()),
                PropertyFactory.lineWidth(w(1f)),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        // Šipka manévru přímo na mapě v místě odbočení.
        s.addLayer(
            LineLayer(LYR_MANEUVER_CASING, SRC_MANEUVER).withProperties(
                PropertyFactory.lineColor(ROUTE_CASING),
                PropertyFactory.lineWidth(w(0.95f)),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ).apply { minZoom = 14.5f },
        )
        s.addLayer(
            LineLayer(LYR_MANEUVER, SRC_MANEUVER).withProperties(
                PropertyFactory.lineColor(WHITE),
                PropertyFactory.lineWidth(w(0.55f)),
                PropertyFactory.lineCap(Property.LINE_CAP_BUTT),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ).apply { minZoom = 14.5f },
        )
        s.addLayer(
            SymbolLayer(LYR_MANEUVER_HEAD, SRC_MANEUVER_HEAD).withProperties(
                PropertyFactory.iconImage(IMG_ARROW),
                PropertyFactory.iconRotate(Expression.get("b")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconSize(Expression.interpolate(Expression.exponential(1.5f), Expression.zoom(), Expression.stop(15, 0.7f), Expression.stop(20, 2.2f))),
            ).apply { minZoom = 14.5f },
        )
        // Dopravní události na trase (nehody, uzavírky, práce…).
        s.addLayer(
            SymbolLayer(LYR_INCIDENTS, SRC_INCIDENTS).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
                PropertyFactory.iconSize(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(9, 0.7f), Expression.stop(15, 1.1f))),
            ),
        )
        s.addLayer(
            SymbolLayer(LYR_DESTINATION, SRC_DESTINATION).withProperties(
                PropertyFactory.iconImage(IMG_DESTINATION),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            ),
        )
        // Auto: jeden bod aktualizovaný každý snímek; pickup leží naplocho v rovině mapy
        // (při náklonu vypadá 3D) a otáčí se podle kurzu.
        s.addSource(GeoJsonSource(SRC_PUCK))
        s.addLayer(
            CircleLayer(LYR_PUCK_ACCURACY, SRC_PUCK).withProperties(
                PropertyFactory.circleRadius(0f),
                PropertyFactory.circleColor(0x26FF7A1A),
                PropertyFactory.circleStrokeColor(0x66FF7A1A),
                PropertyFactory.circleStrokeWidth(1f),
                PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
            ),
        )
        s.addLayer(
            SymbolLayer(LYR_PUCK_GLOW, SRC_PUCK).withProperties(
                PropertyFactory.iconImage(IMG_GLOW),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            ),
        )
        s.addLayer(
            SymbolLayer(LYR_PUCK, SRC_PUCK).withProperties(
                PropertyFactory.iconImage(IMG_PICKUP),
                PropertyFactory.iconRotate(Expression.get("b")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            ),
        )
    }

    private fun widthByZoom(scale: Float): Expression = Expression.interpolate(
        Expression.exponential(1.5f), Expression.zoom(),
        Expression.stop(8, 4f * scale),
        Expression.stop(12, 7f * scale),
        Expression.stop(15, 11f * scale),
        Expression.stop(17, 16f * scale),
        Expression.stop(20, 34f * scale),
    )

    /** Route colour by traffic level of the segment ("c" property). */
    private fun trafficColor(): Expression = Expression.match(
        Expression.get("c"),
        Expression.color(ROUTE_BLUE),
        Expression.stop(TrafficLevel.SLOW.ordinal, Expression.color(TRAFFIC_SLOW)),
        Expression.stop(TrafficLevel.QUEUE.ordinal, Expression.color(TRAFFIC_QUEUE)),
        Expression.stop(TrafficLevel.JAM.ordinal, Expression.color(TRAFFIC_JAM)),
        Expression.stop(TrafficLevel.CLOSED.ordinal, Expression.color(TRAFFIC_CLOSED)),
    )

    private fun updateTrafficOverlay(s: Style) {
        val key = trafficTilesKey.value
        s.getLayer(LYR_TRAFFIC)?.let { s.removeLayer(it) }
        s.getSource(SRC_TRAFFIC)?.let { s.removeSource(it) }
        if (key.isNullOrBlank()) return
        val variant = if (dark) "relative0-dark" else "relative0"
        val url = "https://api.tomtom.com/traffic/map/4/tile/flow/$variant/{z}/{x}/{y}.png?key=$key&tileSize=512&thickness=6"
        s.addSource(RasterSource(SRC_TRAFFIC, TileSet("2.1.0", url), 512))
        val layer = RasterLayer(LYR_TRAFFIC, SRC_TRAFFIC).withProperties(PropertyFactory.rasterOpacity(0.8f))
        if (s.getLayer(LYR_ALTERNATIVES) != null) s.addLayerBelow(layer, LYR_ALTERNATIVES) else s.addLayer(layer)
    }

    // ------------------------------------------------------------------ rendering

    private fun render(state: NavigationState, fix: LocationFix?) {
        val s = style ?: return
        when (state) {
            is NavigationState.Preview -> renderPreview(s, state)
            is NavigationState.Navigating -> renderNavigating(s, state)
            is NavigationState.Arrived -> clearRoutes(s, keepDestination = state.destination.point)
            else -> clearRoutes(s, keepDestination = null)
        }
        updatePosition(state, fix)
    }

    private fun renderPreview(s: Style, state: NavigationState.Preview) {
        val key = state.routes.joinToString { "${it.id}:${it.traffic?.fetchedAtMillis}" } + "#${state.selectedIndex}#${state.destination}"
        if (key == drawnPreviewKey) return
        val firstTime = drawnPreviewKey?.substringAfterLast('#') != state.destination.toString()
        drawnPreviewKey = key
        drawnGeometry = null
        val others = state.routes.filterIndexed { i, _ -> i != state.selectedIndex }
        s.getSourceAs<GeoJsonSource>(SRC_ALTERNATIVES)?.setGeoJson(
            FeatureCollection.fromFeatures(others.map { Feature.fromGeometry(lineString(it.geometry.points)) }),
        )
        s.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(routeFeatures(state.selected, 0.0))
        setManeuver(s, null)
        setDestination(s, state.destination.point)
        if (firstTime) apply(camera.showOverview(state.routes.flatMap { it.geometry.points }))
    }

    private fun renderNavigating(s: Style, state: NavigationState.Navigating) {
        val route = state.route
        val progress = state.progress
        val from = progress?.distanceAlong ?: 0.0
        val newRoute = route.geometry !== drawnGeometry
        val trafficAt = route.traffic?.fetchedAtMillis ?: 0L
        if (newRoute) {
            val cameFromPreview = drawnPreviewKey != null
            drawnPreviewKey = null
            s.getSourceAs<GeoJsonSource>(SRC_ALTERNATIVES)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            setDestination(s, state.destination.point)
            // Start / přepočet → zpátky za auto (se zamčeným zoomem), pokud si uživatel zrovna neprohlíží mapu.
            if (cameFromPreview || camera.state.value.followMode != FollowMode.FREE) apply(camera.recenter())
        }
        // Zbývající trasu překreslíme po ~5 m – projetá část zmizí pod autem.
        if (newRoute || trafficAt != drawnTrafficAt || abs(from - drawnFrom) > 5.0) {
            drawnGeometry = route.geometry
            drawnFrom = from
            drawnTrafficAt = trafficAt
            s.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(routeFeatures(route, from))
        }
        updateIncidents(s, state)
        val upcoming = state.upcoming
        val showArrow = progress != null && upcoming != null && !upcoming.type.isArrival &&
            progress.distanceToUpcoming < 700 && !state.isRerouting
        setManeuver(s, if (showArrow) route to upcoming!!.distanceFromStart else null)
    }

    private fun updateIncidents(s: Style, state: NavigationState.Navigating) {
        val ahead = state.incidents.filter { it.distanceAlong >= (state.progress?.distanceAlong ?: 0.0) - 20 }
        val key = ahead.joinToString { it.incident.id } + "@" + System.identityHashCode(state.route.geometry)
        if (key == drawnIncidentsKey) return
        drawnIncidentsKey = key
        s.getSourceAs<GeoJsonSource>(SRC_INCIDENTS)?.setGeoJson(
            FeatureCollection.fromFeatures(
                ahead.map { ri ->
                    val p = state.route.geometry.pointAt(ri.distanceAlong)
                    Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply { addStringProperty("icon", incidentImage(ri.incident.category)) }
                },
            ),
        )
    }

    private fun incidentImage(c: IncidentCategory) = "nav-incident-${c.name.lowercase()}"

    private fun clearRoutes(s: Style, keepDestination: GeoPoint?) {
        if (drawnGeometry == null && drawnPreviewKey == null && keepDestination == null && drawnManeuverKey == null) return
        drawnGeometry = null
        drawnPreviewKey = null
        val empty = FeatureCollection.fromFeatures(emptyList())
        s.getSourceAs<GeoJsonSource>(SRC_ALTERNATIVES)?.setGeoJson(empty)
        s.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(empty)
        setManeuver(s, null)
        drawnIncidentsKey = null
        s.getSourceAs<GeoJsonSource>(SRC_INCIDENTS)?.setGeoJson(empty)
        if (keepDestination != null) setDestination(s, keepDestination) else s.getSourceAs<GeoJsonSource>(SRC_DESTINATION)?.setGeoJson(empty)
        if (camera.state.value.followMode == FollowMode.OVERVIEW) apply(camera.recenter())
    }

    /**
     * Remaining route split into segments by traffic level (property "c"). Starts 40 m behind the
     * car so the line continues under the marker while it glides between fixes.
     */
    private fun routeFeatures(route: Route, from: Double): FeatureCollection {
        val g = route.geometry
        val start = (from - 40.0).coerceAtLeast(0.0)
        val end = g.length
        val parts = ArrayList<Feature>()
        fun add(a: Double, b: Double, level: TrafficLevel) {
            if (b - a < 0.5) return
            parts += Feature.fromGeometry(lineString(g.slice(a, b))).apply { addNumberProperty("c", level.ordinal) }
        }
        var cursor = start
        route.traffic?.spans.orEmpty()
            .filter { it.endDistance > start && it.level != TrafficLevel.FREE }
            .sortedBy { it.startDistance }
            .forEach { span ->
                val a = maxOf(span.startDistance, cursor)
                val b = minOf(span.endDistance, end)
                if (a > cursor) add(cursor, a, TrafficLevel.FREE)
                if (b > a) add(a, b, span.level)
                cursor = maxOf(cursor, b)
            }
        if (cursor < end) add(cursor, end, TrafficLevel.FREE)
        return FeatureCollection.fromFeatures(parts)
    }

    private fun setManeuver(s: Style, target: Pair<Route, Double>?) {
        val key = target?.let { "${System.identityHashCode(it.first.geometry)}:${it.second}" }
        if (key == drawnManeuverKey) return
        drawnManeuverKey = key
        val empty = FeatureCollection.fromFeatures(emptyList())
        if (target == null) {
            s.getSourceAs<GeoJsonSource>(SRC_MANEUVER)?.setGeoJson(empty)
            s.getSourceAs<GeoJsonSource>(SRC_MANEUVER_HEAD)?.setGeoJson(empty)
            return
        }
        val (route, at) = target
        val g = route.geometry
        val endAt = (at + 22.0).coerceAtMost(g.length)
        s.getSourceAs<GeoJsonSource>(SRC_MANEUVER)?.setGeoJson(lineString(g.slice((at - 28.0).coerceAtLeast(0.0), endAt)))
        val head = g.pointAt(endAt)
        s.getSourceAs<GeoJsonSource>(SRC_MANEUVER_HEAD)?.setGeoJson(
            Feature.fromGeometry(Point.fromLngLat(head.lon, head.lat)).apply { addNumberProperty("b", g.bearingAt(endAt - 1.0)) },
        )
    }

    private fun setDestination(s: Style, p: GeoPoint) {
        s.getSourceAs<GeoJsonSource>(SRC_DESTINATION)?.setGeoJson(Point.fromLngLat(p.lon, p.lat))
    }

    private fun lineString(points: List<GeoPoint>) = LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) })

    // ------------------------------------------------------------------ position + camera

    private fun updatePosition(state: NavigationState, fix: LocationFix?) {
        val nav = state as? NavigationState.Navigating
        val progress = nav?.progress
        val snapped = progress?.takeIf { it.distanceFromRoute < 35.0 }
        // Změna stavu bez nové polohy (doprava, limity) → animaci nerestartujeme.
        val key = fix to snapped?.snappedPoint
        if (key == lastPositionKey) return
        lastPositionKey = key
        val moving = (fix?.speedMps ?: 0.0) > 1.5
        var target = snapped?.snappedPoint ?: fix?.point ?: return
        val bearing = snapped?.snappedBearing ?: fix?.bearingDeg?.takeIf { moving } ?: toBearing

        // Mimo trasu a ve stoji: GPS „bloudí“ – drobné posuny uvnitř přesnosti ignorujeme.
        val current = toPos
        if (snapped == null && !moving && current != null && fix != null &&
            Geo.distance(current, target) < maxOf(6.0, (fix.accuracyM ?: 10.0) * 0.8)
        ) {
            target = current
        }
        accuracyRadius = if (snapped != null) 0f else (fix?.accuracyM ?: 0.0).toFloat().coerceAtMost(60f)

        val now = SystemClock.uptimeMillis()
        val interval = if (lastFixAt == 0L) 1_000L else (now - lastFixAt).coerceIn(250L, 1_500L)
        lastFixAt = now
        if (shownPos == null) {
            // První poloha: žádná animace, rovnou tam (i kamera – ne pomalý let z celé republiky).
            fromPos = target
            shownPos = target
            fromBearing = bearing
            shownBearing = bearing
        } else {
            fromPos = shownPos
            fromBearing = shownBearing
        }
        toPos = target
        toBearing = bearing
        animStart = now
        animDuration = interval

        val command = camera.onPosition(
            position = target,
            bearing = bearing,
            speedMps = fix?.speedMps,
            distanceToManeuver = progress?.distanceToUpcoming,
            navigating = nav != null,
        )
        apply(command)
        frames.ensure()
    }

    private fun apply(command: CameraCommand?) {
        val m = map ?: return
        when (command) {
            null -> Unit
            is CameraCommand.Follow -> {
                targetZoom = command.zoom
                targetTilt = command.tilt
                if (curZoom.isNaN()) curZoom = if (positionedOnce) m.cameraPosition.zoom else command.zoom
                // Návrat ze stavu, kdy kamera nesledovala (posun, přehled) → plynulý přelet, ne skok.
                if (!cameraFollowing) {
                    cameraFollowing = true
                    startFollowTransition()
                }
                frames.ensure()
            }
            is CameraCommand.Fit -> {
                cameraFollowing = false
                val b = command.bounds
                val v = viewport
                val margin = (56 * context.resources.displayMetrics.density).toInt()
                m.easeCamera(
                    CameraUpdateFactory.newLatLngBounds(
                        LatLngBounds.from(b.north, b.east, b.south, b.west), 0.0, 0.0,
                        v.left + margin, v.top + margin, v.right + margin, v.bottom + margin,
                    ),
                    command.animationMs.toInt(),
                )
            }
            is CameraCommand.ZoomTo -> m.animateCamera(CameraUpdateFactory.zoomTo(command.zoom), command.animationMs.toInt())
        }
    }

    /** Smooth hand-over from a free/overview camera (or a gesture) back to following the car. */
    private fun startFollowTransition() {
        val m = map ?: return
        val pos = shownPos ?: return
        if (camera.state.value.followMode != FollowMode.FOLLOW || targetZoom.isNaN()) return
        val pose = followPose(pos, shownBearing, targetZoom, targetTilt, targetPadding())
        if (!positionedOnce) {
            // První poloha po startu: rovnou na místo, žádný let přes celou republiku.
            m.moveCamera(CameraUpdateFactory.newCameraPosition(pose))
            positionedOnce = true
        } else {
            resumeUntil = SystemClock.uptimeMillis() + RESUME_MS
            m.easeCamera(CameraUpdateFactory.newCameraPosition(pose), RESUME_MS.toInt())
        }
        curZoom = targetZoom
        curTilt = targetTilt
        curCamBearing = pose.bearing
        curPadding = targetPadding()
        frames.ensure()
    }

    /** Called every display frame while something moves. Returns true to keep animating. */
    private fun onFrame(now: Long): Boolean {
        val m = map ?: return false
        val from = fromPos ?: return false
        val to = toPos ?: return false
        val t = ((now - animStart).toDouble() / animDuration).coerceIn(0.0, 1.0)
        val pos = Geo.interpolate(from, to, t)
        val bearing = Geo.normalizeBearing(fromBearing + Geo.bearingDelta(fromBearing, toBearing) * t)
        shownPos = pos
        shownBearing = bearing
        updatePuckLayer()

        var animating = t < 1.0
        if (camera.state.value.followMode != FollowMode.FOLLOW) cameraFollowing = false
        val following = cameraFollowing && !targetZoom.isNaN()
        if (following && !gestureActive && now >= resumeUntil) {
            val dt = if (lastFrameAt == 0L) 16.0 else (now - lastFrameAt).toDouble().coerceIn(1.0, 100.0)
            val k = 1.0 - exp(-dt / SMOOTHING_MS)
            curZoom = if (curZoom.isNaN() || !positionedOnce) targetZoom else curZoom + (targetZoom - curZoom) * k
            curTilt += (targetTilt - curTilt) * k
            val padTarget = targetPadding()
            for (i in 0..3) curPadding[i] += (padTarget[i] - curPadding[i]) * k
            val pose = followPose(pos, bearing, curZoom, curTilt, curPadding)
            m.moveCamera(CameraUpdateFactory.newCameraPosition(pose))
            curCamBearing = pose.bearing
            positionedOnce = true
            // Pinch ve FOLLOW zoomuje kolem auta, ne kolem prstů.
            m.uiSettings.focalPoint = m.projection.toScreenLocation(LatLng(pos.lat, pos.lon)).let { PointF(it.x, it.y) }
            animating = animating || abs(targetZoom - curZoom) > 0.002 || abs(targetTilt - curTilt) > 0.05 ||
                (0..3).any { abs(padTarget[it] - curPadding[it]) > 0.5 }
        }
        lastFrameAt = if (animating) now else 0L
        return animating
    }

    private fun followPose(pos: GeoPoint, bearing: Double, zoom: Double, tilt: Double, padding: DoubleArray): CameraPosition {
        val headingUp = camera.state.value.orientation == MapOrientation.HEADING_UP
        return CameraPosition.Builder()
            .target(LatLng(pos.lat, pos.lon))
            .zoom(zoom)
            .bearing(if (headingUp) bearing else 0.0)
            .tilt(tilt)
            .padding(padding.copyOf())
            .build()
    }

    /**
     * Camera padding so the car sits in the **lower third** of the free area when heading-up:
     * the camera centre is the middle of the padded rectangle, so extra top padding pushes the
     * car down and you see the road ahead.
     */
    private fun targetPadding(): DoubleArray {
        val v = viewport
        val free = (mapView.height - v.top - v.bottom).coerceAtLeast(0).toDouble()
        val headingUp = camera.state.value.orientation == MapOrientation.HEADING_UP
        val extraTop = if (headingUp) free * CAR_ANCHOR_SHIFT else 0.0
        return doubleArrayOf(v.left.toDouble(), v.top + extraTop, v.right.toDouble(), v.bottom.toDouble())
    }

    private fun updatePuckLayer() {
        val s = style ?: return
        val pos = shownPos ?: return
        s.getSourceAs<GeoJsonSource>(SRC_PUCK)?.setGeoJson(
            Feature.fromGeometry(Point.fromLngLat(pos.lon, pos.lat)).apply { addNumberProperty("b", shownBearing) },
        )
        // Kruh přesnosti GPS (metry → pixely podle aktuálního zoomu); na trase je 0.
        val m = map ?: return
        val radiusPx = if (accuracyRadius <= 0f) 0f else (accuracyRadius / m.projection.getMetersPerPixelAtLatitude(pos.lat)).toFloat()
        (s.getLayer(LYR_PUCK_ACCURACY) as? CircleLayer)?.setProperties(PropertyFactory.circleRadius(radiusPx))
    }

    private fun destinationPin(): Bitmap {
        val density = context.resources.displayMetrics.density
        val w = (34 * density).toInt()
        val h = (46 * density).toInt()
        val bmp = createBitmap(w, h)
        val c = Canvas(bmp)
        val r = w / 2f
        val path = Path().apply {
            moveTo(r, h.toFloat())
            cubicTo(r * 0.6f, h * 0.7f, 0f, h * 0.56f, 0f, r)
            arcTo(RectF(0f, 0f, w.toFloat(), w.toFloat()), 180f, 180f)
            cubicTo(w.toFloat(), h * 0.56f, r * 1.4f, h * 0.7f, r, h.toFloat())
            close()
        }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF14181F.toInt()
            setShadowLayer(3 * density, 0f, density, 0x66000000)
        })
        c.drawCircle(r, r, r * 0.58f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT })
        c.drawCircle(r, r, r * 0.24f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF14181F.toInt() })
        return bmp
    }

    private inner class FrameLoop : Choreographer.FrameCallback {
        private var scheduled = false

        fun ensure() {
            if (scheduled) return
            scheduled = true
            Choreographer.getInstance().postFrameCallback(this)
        }

        fun stop() {
            if (scheduled) Choreographer.getInstance().removeFrameCallback(this)
            scheduled = false
        }

        override fun doFrame(frameTimeNanos: Long) {
            scheduled = false
            if (onFrame(frameTimeNanos / 1_000_000)) ensure()
        }
    }

    private companion object {
        const val SRC_ROUTE = "nav-route"
        const val SRC_ALTERNATIVES = "nav-alternatives"
        const val SRC_DESTINATION = "nav-destination"
        const val SRC_MANEUVER = "nav-maneuver"
        const val SRC_MANEUVER_HEAD = "nav-maneuver-head"
        const val SRC_TRAFFIC = "nav-traffic"
        const val SRC_INCIDENTS = "nav-incidents"
        const val LYR_INCIDENTS = "nav-incidents-icons"
        const val LYR_ROUTE = "nav-route-line"
        const val LYR_ROUTE_CASING = "nav-route-casing"
        const val LYR_ROUTE_GLOW = "nav-route-glow"
        const val LYR_ALTERNATIVES = "nav-alternatives-line"
        const val LYR_MANEUVER = "nav-maneuver-line"
        const val LYR_MANEUVER_CASING = "nav-maneuver-casing"
        const val LYR_MANEUVER_HEAD = "nav-maneuver-head"
        const val LYR_DESTINATION = "nav-destination-pin"
        const val LYR_TRAFFIC = "nav-traffic-layer"
        const val LYR_PUCK = "nav-puck"
        const val LYR_PUCK_GLOW = "nav-puck-glow"
        const val LYR_PUCK_ACCURACY = "nav-puck-accuracy"
        const val SRC_PUCK = "nav-puck-src"
        const val IMG_DESTINATION = "nav-destination-img"
        const val IMG_ARROW = "nav-arrow-img"
        const val IMG_PICKUP = "nav-pickup-img"
        const val IMG_GLOW = "nav-glow-img"

        val ROUTE_BLUE = "#2F7BFF".toColorInt()
        val ROUTE_CASING = "#0A2E7A".toColorInt()
        val TRAFFIC_SLOW = "#FFC233".toColorInt()
        val TRAFFIC_QUEUE = "#FF7A1A".toColorInt()
        val TRAFFIC_JAM = "#E8253C".toColorInt()
        val TRAFFIC_CLOSED = "#7A0A1E".toColorInt()
        val ACCENT = "#FF8000".toColorInt()
        const val WHITE = 0xFFFFFFFF.toInt()

        /** 0.40 → the car sits at ~70 % of the free height (lower third). */
        const val CAR_ANCHOR_SHIFT = 0.40
        const val SMOOTHING_MS = 260.0
        const val RESUME_MS = 450L
    }
}
