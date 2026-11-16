package cz.fenixen.navigation.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.location.Location
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import cz.fenixen.navigation.core.geo.GeoPoint
import cz.fenixen.navigation.core.geo.Polyline
import cz.fenixen.navigation.core.model.LocationFix
import cz.fenixen.navigation.core.model.Route
import cz.fenixen.navigation.core.navigation.CameraCommand
import cz.fenixen.navigation.core.navigation.CameraController
import cz.fenixen.navigation.core.navigation.FollowMode
import cz.fenixen.navigation.core.navigation.MapOrientation
import cz.fenixen.navigation.core.navigation.NavigationSession
import cz.fenixen.navigation.core.navigation.NavigationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponent
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.abs

/** Unobstructed part of the map view in px (what's not covered by banners, sheets, car cards). */
data class MapViewport(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0)

object MapStyles {
    const val DAY = "https://tiles.openfreemap.org/styles/liberty"
    const val NIGHT = "https://tiles.openfreemap.org/styles/dark"
}

/**
 * Adapter between the platform-independent [CameraController] / [NavigationSession] and MapLibre.
 * Used by the phone (MapView in Compose) and by Android Auto (MapView rendered into the car surface).
 *
 * Camera following is delegated to MapLibre's LocationComponent in TRACKING_GPS mode: it
 * interpolates the puck *and* the camera in the same animation frame, so the car arrow sits
 * rock-solid in place instead of jittering between 1 Hz GPS fixes.
 */
class NavMapController(
    private val context: Context,
    private val mapView: MapView,
    private val camera: CameraController,
    private val session: NavigationSession,
    private val scope: CoroutineScope,
    private val darkMode: StateFlow<Boolean>,
) {
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var observeJob: Job? = null

    /** Unobstructed area; set by the host UI whenever its overlays change size. */
    var viewport: MapViewport = MapViewport()
        set(value) {
            if (field == value) return
            field = value
            lastPadding = null
            rerender()
        }

    private var drawnRouteGeometry: Polyline? = null
    private var drawnPreviewKey: List<String>? = null
    private var lastTrackedZoom = Double.NaN
    private var lastTrackedTilt = Double.NaN
    private var lastPadding: DoubleArray? = null
    private var lastBearing = 0.0
    private var lastFraction = -1.0

    fun start() {
        mapView.getMapAsync { m ->
            map = m
            configureMap(m)
            scope.launch {
                darkMode.collect { dark -> m.setStyle(if (dark) MapStyles.NIGHT else MapStyles.DAY) { onStyleReady(m, it) } }
            }
        }
    }

    // ---------------------------------------------------------------- user actions

    fun zoomIn() = apply(camera.zoomBy(+1, map?.cameraPosition?.zoom))
    fun zoomOut() = apply(camera.zoomBy(-1, map?.cameraPosition?.zoom))
    fun lockMaxZoom() = apply(camera.lockMaxZoom())
    fun lockCurrentZoom() = apply(camera.lockAt(map?.cameraPosition?.zoom ?: camera.state.value.effectiveZoom))
    fun enableAutoZoom() = apply(camera.enableAutoZoom())
    fun recenter() = apply(camera.recenter())

    fun toggleOrientation(): MapOrientation {
        val o = camera.toggleOrientation()
        lastTrackedZoom = Double.NaN
        map?.locationComponent?.takeIf { it.isLocationComponentActivated }?.cameraMode = CameraMode.NONE
        rerender()
        return o
    }

    fun showOverview() {
        val nav = session.state.value as? NavigationState.Navigating ?: return
        val progress = nav.progress
        val points = progress?.let { nav.route.geometry.slice(it.distanceAlong) } ?: nav.route.geometry.points
        apply(camera.showOverview(points))
    }

    /** Car surface pan (Android Auto delivers raw scroll deltas). */
    fun scrollBy(dx: Float, dy: Float) {
        val m = map ?: return
        if (camera.state.value.followMode == FollowMode.FOLLOW) {
            camera.onUserPan()
            m.locationComponent.takeIf { it.isLocationComponentActivated }?.cameraMode = CameraMode.NONE
        }
        m.scrollBy(-dx, -dy, 0)
    }

    /** Car pinch / double-tap / rotary zoom. scaleFactor > 1 zooms in. */
    fun scaleBy(scaleFactor: Float, focusX: Float, focusY: Float) {
        val m = map ?: return
        val delta = Math.log(scaleFactor.toDouble()) / Math.log(2.0)
        if (camera.state.value.followMode == FollowMode.FOLLOW) {
            apply(camera.onUserZoom(m.cameraPosition.zoom + delta))
        } else {
            m.moveCamera(CameraUpdateFactory.zoomBy(delta, android.graphics.Point(focusX.toInt(), focusY.toInt())))
        }
    }

    // ---------------------------------------------------------------- map setup

    private fun configureMap(m: MapLibreMap) {
        m.setMaxZoomPreference(21.0)
        m.uiSettings.isTiltGesturesEnabled = false
        m.uiSettings.isCompassEnabled = false
        m.uiSettings.isLogoEnabled = false
        m.addOnScaleListener(object : MapLibreMap.OnScaleListener {
            override fun onScaleBegin(detector: org.maplibre.android.gestures.StandardScaleGestureDetector) = Unit
            override fun onScale(detector: org.maplibre.android.gestures.StandardScaleGestureDetector) = Unit
            override fun onScaleEnd(detector: org.maplibre.android.gestures.StandardScaleGestureDetector) {
                // Prstem nastavený zoom → zámek (ve FOLLOW). Tady vzniká to „napořád“.
                apply(camera.onUserZoom(m.cameraPosition.zoom))
            }
        })
        scope.launch {
            camera.state.collect { s ->
                // V režimu sledování nedovolíme rotaci gestem – mapa se točí podle směru jízdy.
                m.uiSettings.isRotateGesturesEnabled = s.followMode != FollowMode.FOLLOW
            }
        }
    }

    @SuppressLint("MissingPermission") // poloha se do komponenty jen vkládá (forceLocationUpdate)
    private fun onStyleReady(m: MapLibreMap, s: Style) {
        style = s
        drawnRouteGeometry = null
        drawnPreviewKey = null
        lastFraction = -1.0
        addRouteLayers(s)

        val options = LocationComponentOptions.builder(context)
            .trackingGesturesManagement(true)
            .foregroundTintColor(PUCK_COLOR)
            .bearingTintColor(PUCK_COLOR)
            .accuracyAlpha(0.12f)
            .build()
        val lc = m.locationComponent
        lc.activateLocationComponent(
            LocationComponentActivationOptions.builder(context, s)
                .useDefaultLocationEngine(false)
                .locationComponentOptions(options)
                .build(),
        )
        lc.isLocationComponentEnabled = true
        lc.renderMode = RenderMode.GPS
        lc.addOnCameraTrackingChangedListener(object : OnCameraTrackingChangedListener {
            override fun onCameraTrackingDismissed() {
                // Uživatel posunul mapu → přestáváme sledovat (zoom-lock zůstává).
                camera.onUserPan()
            }

            override fun onCameraTrackingChanged(currentMode: Int) = Unit
        })
        lastTrackedZoom = Double.NaN
        lastTrackedTilt = Double.NaN
        lastPadding = null

        observeJob?.cancel()
        observeJob = scope.launch {
            // Během navigace každý fix přepíše i stav (progress) → stačí poslouchat stav.
            // Mimo navigaci se stav nemění, poloha ano → posloucháme i lastFix.
            launch { session.state.collect { render(it, session.lastFix.value) } }
            launch {
                session.lastFix.collect { fix ->
                    val state = session.state.value
                    if (state !is NavigationState.Navigating) render(state, fix)
                }
            }
        }
    }

    private fun addRouteLayers(s: Style) {
        // Trasa pod popisky ulic, ale nad silnicemi – jako u Google Map.
        val firstSymbol = s.layers.firstOrNull { it is SymbolLayer }?.id
        s.addSource(GeoJsonSource(SRC_ALTERNATIVES))
        s.addSource(GeoJsonSource(SRC_ROUTE, GeoJsonOptions().withLineMetrics(true)))
        s.addSource(GeoJsonSource(SRC_DESTINATION))
        s.addImage(IMG_DESTINATION, destinationPin())

        val alternatives = LineLayer(LYR_ALTERNATIVES, SRC_ALTERNATIVES).withProperties(
            PropertyFactory.lineColor(ALT_COLOR),
            PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(8, 3f), Expression.stop(18, 12f))),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
        val casing = LineLayer(LYR_ROUTE_CASING, SRC_ROUTE).withProperties(
            PropertyFactory.lineColor(ROUTE_CASING_COLOR),
            PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(8, 6f), Expression.stop(18, 20f))),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
        val line = LineLayer(LYR_ROUTE, SRC_ROUTE).withProperties(
            PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(8, 4f), Expression.stop(18, 14f))),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineGradient(progressGradient(0.0)),
        )
        val destination = SymbolLayer(LYR_DESTINATION, SRC_DESTINATION).withProperties(
            PropertyFactory.iconImage(IMG_DESTINATION),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        )
        if (firstSymbol != null) {
            s.addLayerBelow(alternatives, firstSymbol)
            s.addLayerBelow(casing, firstSymbol)
            s.addLayerBelow(line, firstSymbol)
        } else {
            s.addLayer(alternatives)
            s.addLayer(casing)
            s.addLayer(line)
        }
        s.addLayer(destination)
    }

    /** Traveled part grey, remaining part blue – one cheap paint property instead of new geometry. */
    private fun progressGradient(fraction: Double): Expression =
        Expression.step(
            Expression.lineProgress(),
            Expression.color(TRAVELED_COLOR),
            Expression.stop(fraction.coerceIn(0.0, 1.0), Expression.color(ROUTE_COLOR)),
        )

    // ---------------------------------------------------------------- rendering

    private fun rerender() {
        if (style == null) return
        render(session.state.value, session.lastFix.value)
    }

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
        val key = state.routes.map { it.id } + state.selectedIndex.toString() + state.destination.name
        if (key == drawnPreviewKey) return
        drawnPreviewKey = key
        drawnRouteGeometry = null
        val others = state.routes.filterIndexed { i, _ -> i != state.selectedIndex }
        s.getSourceAs<GeoJsonSource>(SRC_ALTERNATIVES)?.setGeoJson(FeatureCollection.fromFeatures(others.map(::routeFeature)))
        s.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(routeFeature(state.selected))
        setFraction(s, 0.0)
        setDestination(s, state.destination.point)
        apply(camera.showOverview(state.routes.flatMap { it.geometry.points }))
    }

    private fun renderNavigating(s: Style, state: NavigationState.Navigating) {
        if (state.route.geometry !== drawnRouteGeometry) {
            val cameFromPreview = drawnPreviewKey != null
            drawnRouteGeometry = state.route.geometry
            drawnPreviewKey = null
            s.getSourceAs<GeoJsonSource>(SRC_ALTERNATIVES)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            s.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(routeFeature(state.route))
            setDestination(s, state.destination.point)
            setFraction(s, 0.0)
            // Nová trasa (start / přepočet) → zpátky za autem, ale se zamčeným zoomem.
            if (cameFromPreview || camera.state.value.followMode != FollowMode.FREE) apply(camera.recenter())
        }
        state.progress?.let { p ->
            val length = state.route.geometry.length
            if (length > 0) setFraction(s, p.distanceAlong / length)
        }
    }

    private fun clearRoutes(s: Style, keepDestination: GeoPoint?) {
        if (drawnRouteGeometry == null && drawnPreviewKey == null && keepDestination == null) return
        drawnRouteGeometry = null
        drawnPreviewKey = null
        val empty = FeatureCollection.fromFeatures(emptyList())
        s.getSourceAs<GeoJsonSource>(SRC_ALTERNATIVES)?.setGeoJson(empty)
        s.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(empty)
        if (keepDestination != null) setDestination(s, keepDestination)
        else s.getSourceAs<GeoJsonSource>(SRC_DESTINATION)?.setGeoJson(empty)
        if (camera.state.value.followMode == FollowMode.OVERVIEW) apply(camera.recenter())
    }

    private fun setFraction(s: Style, fraction: Double) {
        // Aktualizace jen po ~1 m kroku, ať zbytečně nepřestavujeme výraz.
        if (abs(fraction - lastFraction) < 0.0005) return
        lastFraction = fraction
        s.getLayerAs<LineLayer>(LYR_ROUTE)?.setProperties(PropertyFactory.lineGradient(progressGradient(fraction)))
    }

    private fun setDestination(s: Style, p: GeoPoint) {
        s.getSourceAs<GeoJsonSource>(SRC_DESTINATION)?.setGeoJson(Point.fromLngLat(p.lon, p.lat))
    }

    private fun routeFeature(route: Route): Feature =
        Feature.fromGeometry(LineString.fromLngLats(route.geometry.points.map { Point.fromLngLat(it.lon, it.lat) }))

    private fun updatePosition(state: NavigationState, fix: LocationFix?) {
        val m = map ?: return
        val nav = state as? NavigationState.Navigating
        val progress = nav?.progress
        val snapped = progress?.takeIf { it.distanceFromRoute < 35.0 }
        val onRoute = snapped != null
        val position = snapped?.snappedPoint ?: fix?.point ?: return
        val moving = (fix?.speedMps ?: 0.0) > 1.5
        val bearing = snapped?.snappedBearing ?: fix?.bearingDeg?.takeIf { moving } ?: lastBearing
        lastBearing = bearing

        val lc = m.locationComponent
        if (lc.isLocationComponentActivated) {
            lc.forceLocationUpdate(
                Location("navigation").apply {
                    latitude = position.lat
                    longitude = position.lon
                    this.bearing = bearing.toFloat()
                    speed = (fix?.speedMps ?: 0.0).toFloat()
                    accuracy = if (onRoute) 3f else (fix?.accuracyM ?: 10.0).toFloat()
                    time = System.currentTimeMillis()
                },
            )
        }
        val command = camera.onPosition(
            position = position,
            bearing = bearing,
            speedMps = fix?.speedMps,
            distanceToManeuver = progress?.distanceToUpcoming,
            navigating = nav != null,
        )
        apply(command)
    }

    // ---------------------------------------------------------------- camera

    private fun apply(command: CameraCommand?) {
        val m = map ?: return
        when (command) {
            null -> Unit
            is CameraCommand.Follow -> follow(m, command)
            is CameraCommand.Fit -> {
                m.locationComponent.takeIf { it.isLocationComponentActivated }?.cameraMode = CameraMode.NONE
                val b = command.bounds
                val bounds = LatLngBounds.from(b.north, b.east, b.south, b.west)
                val v = viewport
                val margin = (48 * context.resources.displayMetrics.density).toInt()
                m.easeCamera(
                    CameraUpdateFactory.newLatLngBounds(bounds, 0.0, 0.0, v.left + margin, v.top + margin, v.right + margin, v.bottom + margin),
                    command.animationMs.toInt(),
                )
            }
            is CameraCommand.ZoomTo -> m.animateCamera(CameraUpdateFactory.zoomTo(command.zoom), command.animationMs.toInt())
        }
    }

    private fun follow(m: MapLibreMap, cmd: CameraCommand.Follow) {
        val lc = m.locationComponent
        val navigating = session.state.value is NavigationState.Navigating
        val padding = followPadding(navigating)
        if (!lc.isLocationComponentActivated) {
            m.easeCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(cmd.center.lat, cmd.center.lon))
                        .zoom(cmd.zoom).bearing(cmd.bearing).tilt(cmd.tilt).padding(padding).build(),
                ),
                cmd.animationMs.toInt(),
            )
            return
        }
        val headingUp = camera.state.value.orientation == MapOrientation.HEADING_UP
        val mode = if (headingUp) CameraMode.TRACKING_GPS else CameraMode.TRACKING_GPS_NORTH
        if (lc.cameraMode != mode) {
            lc.setCameraMode(mode, TRANSITION_MS, cmd.zoom, if (headingUp) null else 0.0, cmd.tilt, null)
            lastTrackedZoom = cmd.zoom
            lastTrackedTilt = cmd.tilt
        }
        if (abs(cmd.zoom - lastTrackedZoom) > 0.01 || lastTrackedZoom.isNaN()) {
            lc.zoomWhileTracking(cmd.zoom, ZOOM_ANIMATION_MS)
            lastTrackedZoom = cmd.zoom
        }
        if (abs(cmd.tilt - lastTrackedTilt) > 0.5 || lastTrackedTilt.isNaN()) {
            lc.tiltWhileTracking(cmd.tilt, ZOOM_ANIMATION_MS)
            lastTrackedTilt = cmd.tilt
        }
        if (!padding.contentEquals(lastPadding)) {
            lc.paddingWhileTracking(padding, ZOOM_ANIMATION_MS)
            lastPadding = padding
        }
    }

    /**
     * When navigating heading-up, the car arrow sits in the lower part of the free area so
     * you see more road ahead (extra top padding pushes the camera centre down).
     */
    private fun followPadding(navigating: Boolean): DoubleArray {
        val v = viewport
        val freeHeight = (mapView.height - v.top - v.bottom).coerceAtLeast(0)
        val headingUp = camera.state.value.orientation == MapOrientation.HEADING_UP
        val extraTop = if (navigating && headingUp) freeHeight * 0.38 else 0.0
        return doubleArrayOf(v.left.toDouble(), v.top + extraTop, v.right.toDouble(), v.bottom.toDouble())
    }

    private fun destinationPin(): Bitmap {
        val density = context.resources.displayMetrics.density
        val w = (36 * density).toInt()
        val h = (48 * density).toInt()
        val bmp = createBitmap(w, h)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DESTINATION_COLOR }
        val r = w / 2f
        val path = Path().apply {
            moveTo(r, h.toFloat())
            cubicTo(r * 0.6f, h * 0.7f, 0f, h * 0.55f, 0f, r)
            arcTo(RectF(0f, 0f, w.toFloat(), w.toFloat()), 180f, 180f)
            cubicTo(w.toFloat(), h * 0.55f, r * 1.4f, h * 0.7f, r, h.toFloat())
            close()
        }
        paint.setShadowLayer(3 * density, 0f, density, Color.argb(90, 0, 0, 0))
        c.drawPath(path, paint)
        c.drawCircle(r, r, r * 0.38f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        return bmp
    }

    private companion object {
        const val SRC_ROUTE = "nav-route"
        const val SRC_ALTERNATIVES = "nav-alternatives"
        const val SRC_DESTINATION = "nav-destination"
        const val LYR_ROUTE = "nav-route-line"
        const val LYR_ROUTE_CASING = "nav-route-casing"
        const val LYR_ALTERNATIVES = "nav-alternatives-line"
        const val LYR_DESTINATION = "nav-destination-pin"
        const val IMG_DESTINATION = "nav-destination-img"

        val ROUTE_COLOR = "#2F6FED".toColorInt()
        val ROUTE_CASING_COLOR = "#1A3E8C".toColorInt()
        val TRAVELED_COLOR = "#9AA5B4".toColorInt()
        val ALT_COLOR = "#9DB8F2".toColorInt()
        val PUCK_COLOR = "#2F6FED".toColorInt()
        val DESTINATION_COLOR = "#E5484D".toColorInt()

        const val TRANSITION_MS = 750L
        const val ZOOM_ANIMATION_MS = 500L
    }
}
