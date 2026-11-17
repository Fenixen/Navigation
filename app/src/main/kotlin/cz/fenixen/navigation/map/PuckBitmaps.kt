package cz.fenixen.navigation.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.createBitmap

/**
 * Bitmaps for the car marker. The pickup is drawn top-down in a 40×76 design grid (front = up),
 * so MapLibre can rotate it with the heading and lay it flat on the tilted 3D map.
 */
object PuckBitmaps {
    /** Papaya / "Saber Orange"-like body colour. */
    private const val BODY = 0xFFFF7A1A.toInt()
    private const val BODY_DARK = 0xFFC9520A.toInt()
    private const val BODY_LIGHT = 0xFFFFA55C.toInt()
    private const val GLASS = 0xFF1B2430.toInt()
    private const val GLASS_SHINE = 0xFF3A4A5E.toInt()
    private const val BED = 0xFF2A2F37.toInt()

    fun pickup(context: Context, widthDp: Float = 30f): Bitmap {
        val d = context.resources.displayMetrics.density
        val scale = widthDp * d / 40f
        val bmp = createBitmap((40 * scale).toInt() + 2, (76 * scale).toInt() + 2)
        val c = Canvas(bmp)
        c.translate(1f, 1f)
        c.scale(scale, scale)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // Karoserie: zaoblený obdélník s mírně užší přídí, gradient zleva doprava = objem.
        val body = Path().apply {
            moveTo(9f, 6f)
            quadTo(20f, 0f, 31f, 6f)
            lineTo(35f, 14f)
            lineTo(36f, 68f)
            quadTo(36f, 75f, 29f, 75f)
            lineTo(11f, 75f)
            quadTo(4f, 75f, 4f, 68f)
            lineTo(5f, 14f)
            close()
        }
        p.shader = LinearGradient(4f, 0f, 36f, 0f, intArrayOf(BODY_DARK, BODY_LIGHT, BODY, BODY_DARK), floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(body, p)
        p.shader = null

        // Zrcátka
        p.color = BODY_DARK
        c.drawRoundRect(RectF(0f, 24f, 5f, 29f), 1.5f, 1.5f, p)
        c.drawRoundRect(RectF(35f, 24f, 40f, 29f), 1.5f, 1.5f, p)

        // Kapota – středový prolis
        p.color = BODY_DARK
        p.alpha = 90
        c.drawRect(19.2f, 4f, 20.8f, 18f, p)
        p.alpha = 255

        // Světlomety
        p.color = Color.WHITE
        c.drawRoundRect(RectF(7f, 5f, 13f, 8f), 1.5f, 1.5f, p)
        c.drawRoundRect(RectF(27f, 5f, 33f, 8f), 1.5f, 1.5f, p)

        // Čelní sklo
        val windshield = Path().apply {
            moveTo(8f, 26f); lineTo(32f, 26f); lineTo(30f, 19f); quadTo(20f, 16.5f, 10f, 19f); close()
        }
        p.shader = LinearGradient(0f, 17f, 0f, 26f, GLASS_SHINE, GLASS, Shader.TileMode.CLAMP)
        c.drawPath(windshield, p)
        p.shader = null

        // Střecha kabiny + zadní okno
        p.color = BODY
        c.drawRoundRect(RectF(8f, 26f, 32f, 41f), 3f, 3f, p)
        p.color = GLASS
        c.drawRoundRect(RectF(9f, 41f, 31f, 44f), 1.5f, 1.5f, p)

        // Korba: tmavá vana s žebry a bočnicemi
        p.color = BODY_DARK
        c.drawRoundRect(RectF(6f, 45f, 34f, 73f), 2.5f, 2.5f, p)
        p.color = BED
        c.drawRoundRect(RectF(8.5f, 46.5f, 31.5f, 71f), 1.5f, 1.5f, p)
        p.color = 0xFF3B414B.toInt()
        for (x in listOf(13f, 18f, 22f, 27f)) c.drawRect(x, 48f, x + 1f, 69.5f, p)

        // Koncová světla
        p.color = 0xFFFF2D2D.toInt()
        c.drawRoundRect(RectF(5f, 71f, 9f, 74f), 1f, 1f, p)
        c.drawRoundRect(RectF(31f, 71f, 35f, 74f), 1f, 1f, p)

        // Jemný obrys pro kontrast na světlé mapě
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2f
            color = 0x99331400.toInt()
        }
        c.drawPath(body, outline)
        return bmp
    }

    /** Soft drop shadow / glow under the car – makes it pop on both day and night maps. */
    fun glow(context: Context, color: Int, radiusDp: Float = 34f): Bitmap {
        val r = radiusDp * context.resources.displayMetrics.density
        val size = (r * 2).toInt()
        val bmp = createBitmap(size, size)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(r, r, r, intArrayOf(color, Color.argb(Color.alpha(color) / 3, Color.red(color), Color.green(color), Color.blue(color)), Color.TRANSPARENT), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawCircle(r, r, r, p)
        return bmp
    }

    /** Arrow head for the on-map maneuver arrow (points up, rotated by MapLibre). */
    fun arrowHead(context: Context, fill: Int, stroke: Int): Bitmap {
        val d = context.resources.displayMetrics.density
        val w = 26 * d
        val bmp = createBitmap(w.toInt(), w.toInt())
        val c = Canvas(bmp)
        val path = Path().apply {
            moveTo(w / 2, w * 0.08f)
            lineTo(w * 0.92f, w * 0.88f)
            lineTo(w / 2, w * 0.70f)
            lineTo(w * 0.08f, w * 0.88f)
            close()
        }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = stroke
            style = Paint.Style.STROKE
            strokeWidth = 3 * d
            strokeJoin = Paint.Join.ROUND
            maskFilter = BlurMaskFilter(0.5f * d, BlurMaskFilter.Blur.NORMAL)
        })
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
        return bmp
    }
}
