package cz.fenixen.navigation.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.LruCache
import androidx.core.graphics.createBitmap
import cz.fenixen.navigation.core.model.Maneuver
import cz.fenixen.navigation.core.model.ManeuverType
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws maneuver arrows into bitmaps – one renderer for the phone banner and the
 * Android Auto cards (CarIcon). Geometry lives in a 96×96 design grid and is scaled.
 */
object ManeuverIcons {
    private val cache = LruCache<String, Bitmap>(48)

    fun bitmap(maneuver: Maneuver, sizePx: Int, color: Int = Color.WHITE): Bitmap {
        val key = "${maneuver.type}-${maneuver.roundaboutExitCount}-$sizePx-$color"
        cache.get(key)?.let { return it }
        val bmp = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bmp)
        canvas.scale(sizePx / 96f, sizePx / 96f)
        draw(canvas, maneuver.type, maneuver.roundaboutExitCount, color)
        cache.put(key, bmp)
        return bmp
    }

    private fun stroke(color: Int, width: Float = 11f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    private fun draw(c: Canvas, type: ManeuverType, exit: Int?, color: Int) {
        val dim = Color.argb(110, Color.red(color), Color.green(color), Color.blue(color))
        when (type) {
            ManeuverType.UTURN_LEFT -> uTurn(c, color, left = true)
            ManeuverType.UTURN_RIGHT -> uTurn(c, color, left = false)
            ManeuverType.ROUNDABOUT_ENTER, ManeuverType.ROUNDABOUT_EXIT -> roundabout(c, color, exit)
            ManeuverType.ARRIVE, ManeuverType.ARRIVE_LEFT, ManeuverType.ARRIVE_RIGHT -> destination(c, color, type)
            ManeuverType.KEEP_LEFT, ManeuverType.EXIT_LEFT, ManeuverType.RAMP_LEFT -> fork(c, color, dim, left = true)
            ManeuverType.KEEP_RIGHT, ManeuverType.EXIT_RIGHT, ManeuverType.RAMP_RIGHT -> fork(c, color, dim, left = false)
            ManeuverType.MERGE_LEFT -> merge(c, color, dim, fromRight = true)
            ManeuverType.MERGE, ManeuverType.MERGE_RIGHT -> merge(c, color, dim, fromRight = false)
            else -> turn(c, color, angleFor(type))
        }
    }

    private fun angleFor(type: ManeuverType): Float = when (type) {
        ManeuverType.SLIGHT_LEFT -> -45f
        ManeuverType.LEFT, ManeuverType.DEPART_LEFT -> -90f
        ManeuverType.SHARP_LEFT -> -135f
        ManeuverType.SLIGHT_RIGHT -> 45f
        ManeuverType.RIGHT, ManeuverType.DEPART_RIGHT -> 90f
        ManeuverType.SHARP_RIGHT -> 135f
        else -> 0f
    }

    /** Stem from the bottom, bend at the junction, arrow head in the turn direction. */
    private fun turn(c: Canvas, color: Int, angleDeg: Float) {
        val rad = Math.toRadians(angleDeg.toDouble())
        val sharp = kotlin.math.abs(angleDeg) > 100f
        val jx = 48f
        // Ostré odbočení: křižovatka výš, ať se šipka vracející se dolů neslije se stonkem.
        val jy = when {
            angleDeg == 0f -> 30f
            sharp -> 34f
            else -> 50f
        }
        val len = when {
            angleDeg == 0f -> 0f
            sharp -> 32f
            else -> 30f
        }
        val ex = jx + (len * sin(rad)).toFloat()
        val ey = jy - (len * cos(rad)).toFloat()
        val path = Path().apply {
            moveTo(48f, 90f)
            lineTo(jx, jy)
            if (len > 0) lineTo(ex, ey)
        }
        c.drawPath(path, stroke(color))
        arrowHead(c, color, ex, ey, angleDeg)
    }

    private fun arrowHead(c: Canvas, color: Int, tipBaseX: Float, tipBaseY: Float, angleDeg: Float) {
        val rad = Math.toRadians(angleDeg.toDouble())
        val dx = sin(rad).toFloat()
        val dy = -cos(rad).toFloat()
        val tipX = tipBaseX + dx * 14f
        val tipY = tipBaseY + dy * 14f
        val px = -dy
        val py = dx
        val path = Path().apply {
            moveTo(tipX, tipY)
            lineTo(tipBaseX + px * 14f - dx * 2f, tipBaseY + py * 14f - dy * 2f)
            lineTo(tipBaseX - px * 14f - dx * 2f, tipBaseY - py * 14f - dy * 2f)
            close()
        }
        c.drawPath(path, fill(color).apply { strokeJoin = Paint.Join.ROUND })
    }

    private fun uTurn(c: Canvas, color: Int, left: Boolean) {
        val (up, down) = if (left) 62f to 34f else 34f to 62f
        val path = Path().apply {
            moveTo(up, 90f)
            lineTo(up, 40f)
            val oval = RectF(minOf(up, down), 26f, maxOf(up, down), 54f)
            arcTo(oval, if (left) 0f else 180f, if (left) -180f else 180f)
            lineTo(down, 58f)
        }
        c.drawPath(path, stroke(color))
        arrowHead(c, color, down, 58f, 180f)
    }

    /**
     * Roundabout for right-hand traffic: the car enters at the bottom and drives counter-clockwise
     * (bold arc over the right side) to the exit at the top. Exit number in the middle.
     */
    private fun roundabout(c: Canvas, color: Int, exit: Int?) {
        val dim = Color.argb(110, Color.red(color), Color.green(color), Color.blue(color))
        val ring = RectF(28f, 26f, 68f, 66f) // střed (48, 46), r = 20
        c.drawOval(ring, stroke(dim, 7f))
        c.drawLine(48f, 90f, 48f, 66f, stroke(color))
        // Na plátně rostou úhly po směru hodin; dole = 90°, záporný sweep = proti směru hodin.
        c.drawArc(ring, 90f, -180f, false, stroke(color, 9f))
        c.drawLine(48f, 26f, 48f, 16f, stroke(color))
        arrowHead(c, color, 48f, 16f, 0f)
        if (exit != null) {
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            c.drawText(exit.toString(), 48f, 55f, text)
        }
    }

    private fun destination(c: Canvas, color: Int, type: ManeuverType) {
        val offset = when (type) {
            ManeuverType.ARRIVE_LEFT -> -14f
            ManeuverType.ARRIVE_RIGHT -> 14f
            else -> 0f
        }
        val cx = 48f + offset
        // Špendlík: kapka s otvorem
        val pin = Path().apply {
            moveTo(cx, 84f)
            cubicTo(cx - 6f, 70f, cx - 24f, 56f, cx - 24f, 38f)
            arcTo(RectF(cx - 24f, 14f, cx + 24f, 62f), 180f, 180f)
            cubicTo(cx + 24f, 56f, cx + 6f, 70f, cx, 84f)
            close()
        }
        c.drawPath(pin, fill(color))
        c.drawCircle(cx, 38f, 9f, fill(Color.TRANSPARENT).apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        })
    }

    /** Y-junction: the branch to take is solid, the other one dimmed. */
    private fun fork(c: Canvas, color: Int, dim: Int, left: Boolean) {
        val sign = if (left) -1f else 1f
        c.drawPath(Path().apply { moveTo(48f, 62f); lineTo(48f - sign * 22f, 26f) }, stroke(dim, 9f))
        c.drawPath(Path().apply { moveTo(48f, 90f); lineTo(48f, 62f); lineTo(48f + sign * 22f, 30f) }, stroke(color))
        arrowHead(c, color, 48f + sign * 22f, 30f, sign * 33f)
    }

    private fun merge(c: Canvas, color: Int, dim: Int, fromRight: Boolean) {
        val sign = if (fromRight) 1f else -1f
        c.drawLine(48f, 90f, 48f, 30f, stroke(dim, 9f))
        c.drawPath(Path().apply { moveTo(48f + sign * 26f, 90f); lineTo(48f + sign * 26f, 70f); lineTo(48f, 42f) }, stroke(color))
        c.drawLine(48f, 42f, 48f, 28f, stroke(color))
        arrowHead(c, color, 48f, 28f, 0f)
    }
}
