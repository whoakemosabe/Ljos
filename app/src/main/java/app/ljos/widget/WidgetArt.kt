package app.ljos.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import app.ljos.Fmt
import app.ljos.data.HOUR_MS
import app.ljos.model.HourScore
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws widget backgrounds as bitmaps: night sky, aurora curtains that brighten with the score,
 * and (for the wide widget) tonight's hourly bars. Text is laid over this by Glance so it stays crisp.
 */
object WidgetArt {
    private const val MAX_PIXELS = 220_000f

    fun render(
        wDp: Float,
        hDp: Float,
        score: Int,
        hours: List<HourScore> = emptyList(),
        now: Long = 0L,
        barsLeftDp: Float = 0f,
    ): Bitmap {
        val wd = max(wDp, 40f)
        val hd = max(hDp, 40f)
        val scale = min(2.5f, sqrt(MAX_PIXELS / (wd * hd)))
        val w = (wd * scale).roundToInt().coerceAtLeast(1)
        val h = (hd * scale).roundToInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // Sky
        p.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0xFF050814.toInt(), 0xFF0A1630.toInt(), 0xFF070D18.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null

        // Stars (deterministic so the widget doesn't flicker between updates)
        p.color = 0xFFFFFFFF.toInt()
        var seed = 1337L
        repeat((w * h / 9000).coerceIn(8, 60)) {
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            val x = ((seed ushr 33) % w).toFloat()
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            val y = ((seed ushr 33) % (h * 6 / 10)).toFloat()
            p.alpha = 60 + ((seed ushr 20) % 120).toInt()
            c.drawCircle(x, y, scale * 0.6f, p)
        }
        p.alpha = 255

        // Aurora curtains
        val k = 0.25f + 0.75f * (score.coerceIn(0, 100) / 100f)
        curtain(c, w, h, base = 0.42f, amp = 0.09f, height = 0.38f, color = 0x3DFFA0, alpha = 0.75f * k, phase = 0.3f)
        curtain(c, w, h, base = 0.30f, amp = 0.07f, height = 0.26f, color = 0xA78BFA, alpha = 0.45f * k, phase = 2.1f)

        // Darken the bottom a touch so text reads well
        p.shader = LinearGradient(
            0f, h * 0.45f, 0f, h.toFloat(),
            intArrayOf(0x00000000, 0x99050812.toInt()), null, Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null

        if (hours.isNotEmpty()) bars(c, w, h, scale, hours, now, barsLeftDp)
        return bmp
    }

    private fun curtain(
        c: Canvas, w: Int, h: Int, base: Float, amp: Float, height: Float,
        color: Int, alpha: Float, phase: Float,
    ) {
        val path = Path()
        val steps = 48
        val ys = FloatArray(steps + 1) { i ->
            val x = i / steps.toFloat()
            (base + amp * (sin(x * 5.2f + phase) * 0.7f + sin(x * 11.7f + phase * 1.7f) * 0.3f)) * h
        }
        path.moveTo(0f, ys[0])
        for (i in 1..steps) path.lineTo(w * i / steps.toFloat(), ys[i])
        for (i in steps downTo 0) path.lineTo(w * i / steps.toFloat(), ys[i] - height * h)
        path.close()

        val a = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
        val top = (base - amp - height) * h
        val bottom = (base + amp) * h
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(color, (a shl 24) or color),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawPath(path, p)

        // A bright lower edge, like a real curtain
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1.5f, h * 0.012f)
            this.color = ((a * 0.9f).roundToInt() shl 24) or color
        }
        val edgePath = Path().apply {
            moveTo(0f, ys[0])
            for (i in 1..steps) lineTo(w * i / steps.toFloat(), ys[i])
        }
        c.drawPath(edgePath, edge)
    }

    private fun bars(c: Canvas, w: Int, h: Int, scale: Float, hours: List<HourScore>, now: Long, leftDp: Float) {
        val left = leftDp * scale
        val right = w - 16f * scale
        val top = 18f * scale
        val labelH = 16f * scale
        val bottom = h - 14f * scale - labelH
        if (right - left < 40f * scale) return

        val n = hours.size
        val slot = (right - left) / n
        val barW = slot * 0.6f
        val radius = barW / 2f
        val chartH = bottom - top
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x99E8F1FF.toInt()
            textSize = 10f * scale
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        hours.forEachIndexed { i, hs ->
            val x = left + i * slot + (slot - barW) / 2f
            // Track
            p.shader = null
            p.color = 0x14FFFFFF
            c.drawRoundRect(RectF(x, top, x + barW, bottom), radius, radius, p)
            // Bar
            val bh = max(chartH * hs.score / 100f, barW)
            val col = scoreColor(hs.score)
            p.shader = LinearGradient(
                0f, bottom - bh, 0f, bottom,
                intArrayOf(col, (0x55 shl 24) or (col and 0xFFFFFF)), null, Shader.TileMode.CLAMP,
            )
            c.drawRoundRect(RectF(x, bottom - bh, x + barW, bottom), radius, radius, p)
            p.shader = null
            // Current hour marker
            if (now >= hs.time && now < hs.time + HOUR_MS) {
                p.color = 0xFFFFFFFF.toInt()
                c.drawCircle(x + barW / 2f, bottom + 5f * scale, 2f * scale, p)
            }
            if (i % 3 == 0) {
                c.drawText(Fmt.hour(hs.time), x + barW / 2f, h - 12f * scale, txt)
            }
        }
    }

    /** Teal for low scores, green in the middle, violet at the top. */
    fun scoreColor(score: Int): Int {
        val s = score.coerceIn(0, 100)
        return when {
            s < 40 -> lerp(0xFF2E4A60.toInt(), 0xFF2FD3C6.toInt(), s / 40f)
            s < 80 -> lerp(0xFF2FD3C6.toInt(), 0xFF3DFFA0.toInt(), (s - 40) / 40f)
            else -> lerp(0xFF3DFFA0.toInt(), 0xFFB79CFF.toInt(), (s - 80) / 20f)
        }
    }

    private fun lerp(a: Int, b: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        fun ch(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * f).roundToInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
