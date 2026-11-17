package cz.fenixen.navigation.car

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import cz.fenixen.navigation.AppGraph
import cz.fenixen.navigation.R
import cz.fenixen.navigation.core.navigation.NavigationState
import cz.fenixen.navigation.data.MapTheme
import cz.fenixen.navigation.map.MapViewport
import cz.fenixen.navigation.map.NavMapController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.maplibre.android.maps.MapView
import kotlin.math.roundToInt

/**
 * Renders the MapLibre map onto the Android Auto surface.
 *
 * Android Auto gives a navigation app a raw [android.view.Surface]. The trick (used by most
 * nav SDKs): create a private [VirtualDisplay] backed by that surface and show a [Presentation]
 * (a Dialog for a secondary display) on it. Inside we can use regular Views – here MapView plus a
 * speed widget. Templates (cards, buttons) are drawn by the host *on top* of our surface.
 */
class CarMapRenderer(
    private val carContext: CarContext,
    lifecycle: Lifecycle,
    private val graph: AppGraph,
) : SurfaceCallback, DefaultLifecycleObserver {

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var mapView: MapView? = null
    private var speedView: CarSpeedView? = null
    private var scope: CoroutineScope? = null
    private var visibleArea: Rect? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private val dark = MutableStateFlow(carContext.isDarkMode)

    var controller: NavMapController? = null
        private set

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
        release()
    }

    fun onCarConfigurationChanged() {
        dark.value = resolveDark(graph.settings.current.value.mapTheme)
    }

    private fun resolveDark(theme: MapTheme) = when (theme) {
        MapTheme.AUTO -> carContext.isDarkMode
        MapTheme.DAY -> false
        MapTheme.NIGHT -> true
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface ?: return
        release()
        surfaceWidth = surfaceContainer.width
        surfaceHeight = surfaceContainer.height
        val display = carContext.getSystemService(DisplayManager::class.java).createVirtualDisplay(
            "NavigaceCarMap",
            surfaceContainer.width,
            surfaceContainer.height,
            surfaceContainer.dpi,
            surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        )
        virtualDisplay = display
        val pres = Presentation(carContext, display.display)
        val ctx = pres.context
        val mv = MapView(ctx)
        val speed = CarSpeedView(ctx)
        val attribution = TextView(ctx).apply {
            setText(R.string.osm_copyright)
            textSize = 10f
            setTextColor(Color.argb(180, 60, 60, 60))
            setShadowLayer(2f, 0f, 0f, Color.WHITE)
        }
        val root = FrameLayout(ctx).apply {
            addView(mv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(speed, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
            addView(attribution, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
        }
        pres.setContentView(root)
        mv.onCreate(null)
        mv.onStart()
        mv.onResume()
        pres.show()
        presentation = pres
        mapView = mv
        speedView = speed

        val s = MainScope()
        scope = s
        controller = NavMapController(ctx, mv, graph.carCamera, graph.session, s, dark, graph.trafficTilesKey).also {
            it.viewport = viewport()
            it.start()
        }
        layoutOverlays(attribution)
        s.launch { graph.settings.current.collect { dark.value = resolveDark(it.mapTheme) } }
        s.launch {
            combine(graph.session.state, graph.session.lastFix) { state, fix -> state to fix }.collect { (state, fix) ->
                val nav = state as? NavigationState.Navigating
                speed.update(
                    speedKmh = ((fix?.speedMps ?: 0.0) * 3.6).roundToInt(),
                    limitKph = nav?.progress?.speedLimitKph,
                    speeding = nav?.isSpeeding == true,
                    visible = nav != null || (fix?.speedMps ?: 0.0) > 1.0,
                )
            }
        }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = Rect(visibleArea)
        controller?.viewport = viewport()
        speedView?.let { layoutOverlays(null) }
    }

    override fun onStableAreaChanged(stableArea: Rect) = Unit

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        release()
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        controller?.scrollBy(distanceX, distanceY)
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        controller?.scaleBy(scaleFactor, focusX, focusY)
    }

    /** Area of the surface not covered by Android Auto cards → camera padding. */
    private fun viewport(): MapViewport {
        val v = visibleArea ?: return MapViewport()
        return MapViewport(
            left = v.left,
            top = v.top,
            right = (surfaceWidth - v.right).coerceAtLeast(0),
            bottom = (surfaceHeight - v.bottom).coerceAtLeast(0),
        )
    }

    private fun layoutOverlays(attribution: View?) {
        val vp = viewport()
        val margin = (12 * carContext.resources.displayMetrics.density).toInt()
        speedView?.let { view ->
            (view.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.leftMargin = vp.left + margin
                it.bottomMargin = vp.bottom + margin
                view.layoutParams = it
            }
        }
        attribution?.let { view ->
            (view.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.rightMargin = margin
                it.bottomMargin = margin / 2
                view.layoutParams = it
            }
        }
    }

    private fun release() {
        controller?.release()
        scope?.cancel()
        scope = null
        controller = null
        mapView?.let {
            it.onPause()
            it.onStop()
            it.onDestroy()
        }
        mapView = null
        speedView = null
        presentation?.dismiss()
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
    }
}

/** Speed gauge + Czech limit sign drawn with Canvas (car surface has no Compose) – same look as the phone HUD. */
private class CarSpeedView(context: Context) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val circle = 64 * density
    private val sign = 40 * density
    private var speedKmh = 0
    private var limit: Int? = null
    private var speeding = false
    private var show = false

    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 13, 16, 22)
        setShadowLayer(6 * density, 0f, 2 * density, Color.argb(120, 0, 0, 0))
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(22, 255, 255, 255)
    }
    private val ringPaint = Paint(trackPaint).apply { color = Color.rgb(255, 128, 0) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.create("sans-serif-condensed", Typeface.NORMAL), 800, false)
    }
    private val signRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(215, 38, 46)
        strokeWidth = 5 * density
    }

    fun update(speedKmh: Int, limitKph: Int?, speeding: Boolean, visible: Boolean) {
        val changed = speedKmh != this.speedKmh || limitKph != limit || speeding != this.speeding || visible != show
        this.speedKmh = speedKmh
        this.limit = limitKph
        this.speeding = speeding
        this.show = visible
        if (changed) invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((circle + sign * 0.6f).toInt(), (circle + sign * 0.35f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (!show) return
        val r = circle / 2
        val cx = r
        val cy = height - r
        canvas.drawCircle(cx, cy, r - density, glassPaint)
        val inset = 7 * density
        val oval = android.graphics.RectF(cx - r + inset, cy - r + inset, cx + r - inset, cy + r - inset)
        canvas.drawArc(oval, 135f, 270f, false, trackPaint)
        val fraction = (speedKmh / (limit ?: 130).toFloat()).coerceIn(0f, 1.25f) / 1.25f
        ringPaint.color = if (speeding) Color.rgb(255, 69, 58) else Color.rgb(255, 128, 0)
        if (fraction > 0f) canvas.drawArc(oval, 135f, 270f * fraction, false, ringPaint)
        textPaint.color = if (speeding) Color.rgb(255, 69, 58) else Color.rgb(244, 246, 249)
        textPaint.textSize = 24 * density
        canvas.drawText("$speedKmh", cx, cy + 6 * density, textPaint)
        textPaint.textSize = 8 * density
        textPaint.color = Color.rgb(154, 163, 175)
        canvas.drawText("km/h", cx, cy + 17 * density, textPaint)

        val l = limit ?: return
        val sr = sign / 2
        val sx = width - sr - density
        val sy = sr + density
        canvas.drawCircle(sx, sy, sr - density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        canvas.drawCircle(sx, sy, sr - 3.5f * density, signRing)
        textPaint.color = Color.BLACK
        textPaint.textSize = (if (l >= 100) 13 else 16) * density
        canvas.drawText("$l", sx, sy + 5.5f * density, textPaint)
    }
}
