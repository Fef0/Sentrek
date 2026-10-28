package app.sentrek.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import app.sentrek.geo.overlay.OsmcSymbol

/**
 * Waymarks of hiking routes, drawn the way they're painted on rocks and signs. Map images
 * named `route-<osmc:symbol>`, drawn when the map first asks for one.
 */
object RouteMarks {
    const val PREFIX = "route-"

    /** The mark for the image [name], or null when it isn't one. */
    fun image(name: String, density: Float): Bitmap? {
        if (!name.startsWith(PREFIX)) return null
        val symbol = OsmcSymbol.parse(name.removePrefix(PREFIX)) ?: return null
        val size = SIZE_DP * density
        val bitmap = createBitmap(size.toInt(), size.toInt())
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val box = RectF(0f, 0f, size, size)
        val background = symbol.background
        val round = background?.kind in setOf("round", "circle")

        // The background, white unless given, with a dark rim so white marks show on snow.
        paint.color = color(background?.takeIf { it.kind in setOf("", "round") }?.color ?: "white")
        if (round) canvas.drawOval(box, paint) else canvas.drawRect(box, paint)
        background?.takeIf { it.kind in setOf("circle", "frame") }?.let { rim ->
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = size * 0.14f
            paint.color = color(rim.color)
            val inset = RectF(box).apply { inset(size * 0.07f, size * 0.07f) }
            if (round) canvas.drawOval(inset, paint) else canvas.drawRect(inset, paint)
            paint.style = Paint.Style.FILL
        }

        for (shape in symbol.foregrounds) {
            paint.color = color(shape.color)
            canvas.save()
            if (round) canvas.clipPath(Path().apply { addOval(box, Path.Direction.CW) })
            foreground(canvas, shape.kind, size, paint)
            canvas.restore()
        }

        symbol.text?.let { text ->
            paint.color = color(symbol.textColor ?: "black")
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = size * 0.55f
            while (paint.measureText(text) > size * 0.9f && paint.textSize > size * 0.25f) paint.textSize *= 0.9f
            val y = size / 2 - (paint.descent() + paint.ascent()) / 2
            canvas.drawText(text, size / 2, y, paint)
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = RIM
        val rim = RectF(box).apply { inset(density / 2, density / 2) }
        if (round) canvas.drawOval(rim, paint) else canvas.drawRect(rim, paint)
        return bitmap
    }

    /** The common foreground shapes; others are left out (the colours still tell the route). */
    private fun foreground(canvas: Canvas, kind: String, s: Float, paint: Paint) {
        val third = s / 3
        when (kind) {
            "bar" -> canvas.drawRect(0f, third, s, 2 * third, paint)
            "stripe" -> canvas.drawRect(third, 0f, 2 * third, s, paint)
            "lower" -> canvas.drawRect(0f, s / 2, s, s, paint)
            "upper" -> canvas.drawRect(0f, 0f, s, s / 2, paint)
            "left" -> canvas.drawRect(0f, 0f, s / 2, s, paint)
            "right" -> canvas.drawRect(s / 2, 0f, s, s, paint)
            "rectangle" -> canvas.drawRect(s * 0.25f, s * 0.25f, s * 0.75f, s * 0.75f, paint)
            "dot" -> canvas.drawCircle(s / 2, s / 2, s * 0.3f, paint)
            "circle", "frame" -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = s * 0.12f
                if (kind == "circle") canvas.drawCircle(s / 2, s / 2, s * 0.3f, paint)
                else canvas.drawRect(s * 0.12f, s * 0.12f, s * 0.88f, s * 0.88f, paint)
                paint.style = Paint.Style.FILL
            }
            "cross" -> {
                canvas.drawRect(s * 0.4f, s * 0.15f, s * 0.6f, s * 0.85f, paint)
                canvas.drawRect(s * 0.15f, s * 0.4f, s * 0.85f, s * 0.6f, paint)
            }
            "x", "backslash", "slash" -> {
                paint.strokeWidth = s * 0.16f
                if (kind != "slash") canvas.drawLine(s * 0.15f, s * 0.15f, s * 0.85f, s * 0.85f, paint)
                if (kind != "backslash") canvas.drawLine(s * 0.85f, s * 0.15f, s * 0.15f, s * 0.85f, paint)
            }
            "triangle", "triangle_turned", "diamond", "corner" -> canvas.drawPath(
                Path().apply {
                    when (kind) {
                        "triangle" -> { moveTo(s / 2, s * 0.15f); lineTo(s * 0.85f, s * 0.8f); lineTo(s * 0.15f, s * 0.8f) }
                        "triangle_turned" -> { moveTo(s / 2, s * 0.85f); lineTo(s * 0.85f, s * 0.2f); lineTo(s * 0.15f, s * 0.2f) }
                        "diamond" -> { moveTo(s / 2, s * 0.1f); lineTo(s * 0.9f, s / 2); lineTo(s / 2, s * 0.9f); lineTo(s * 0.1f, s / 2) }
                        else -> { moveTo(0f, 0f); lineTo(s, 0f); lineTo(0f, s) }
                    }
                    close()
                },
                paint,
            )
        }
    }

    private fun color(name: String): Int = when (name) {
        "black" -> 0xFF000000
        "blue" -> 0xFF1E5BC6
        "brown" -> 0xFF8B5A2B
        "gray" -> 0xFF8A8A8A
        "green" -> 0xFF2E8B3A
        "orange" -> 0xFFF07D00
        "purple" -> 0xFF8E24AA
        "red" -> 0xFFD32F2F
        "yellow" -> 0xFFFFD21F
        else -> 0xFFFFFFFF
    }.toInt()

    private const val RIM = 0xFF3A3A3A.toInt()
    private const val SIZE_DP = 20
}
