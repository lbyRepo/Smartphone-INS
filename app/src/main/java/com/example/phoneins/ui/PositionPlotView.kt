package com.example.phoneins.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.example.phoneins.navigation.Vec3
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.cos

/** Immutable plot input. Replacing this value always invalidates the Android drawing surface. */
internal data class PositionPlotData(
    val insLocalMetres: List<Vec3>,
    val gnssLocalMetres: List<Vec3>,
    val centreNorthMetres: Double,
    val centreEastMetres: Double,
    val halfSpanMetres: Double,
    val hasOrigin: Boolean,
    val insHeadingDeg: Double = Double.NaN,
)

internal data class PositionPlotPalette(
    val background: Int,
    val grid: Int,
    val foreground: Int,
    val ins: Int,
    val gnss: Int,
)

/**
 * A deliberately small native plotter. Compose owns the immutable history snapshot while this
 * view owns draw invalidation, avoiding stale Canvas display lists during rapid navigation updates.
 * [render] is change-gated so a 10 Hz UI stream only redraws when the plotted data changed.
 */
internal class PositionPlotView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var data = PositionPlotData(emptyList(), emptyList(), 0.0, 0.0, 10.0, false)
    private var palette = PositionPlotPalette(0, 0, 0, 0, 0)

    fun render(data: PositionPlotData, palette: PositionPlotPalette) {
        if (data == this.data && palette == this.palette) return
        this.data = data
        this.palette = palette
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(palette.background)
        if (width <= 0 || height <= 0) return

        val density = resources.displayMetrics.density
        val centreX = width * 0.5f
        val centreY = height * 0.5f
        val pixelsPerMetre = (min(width, height) * 0.42 / data.halfSpanMetres).toFloat()

        fun map(point: Vec3): Pair<Float, Float> = Pair(
            centreX + (point.y - data.centreEastMetres).toFloat() * pixelsPerMetre,
            centreY - (point.x - data.centreNorthMetres).toFloat() * pixelsPerMetre,
        )

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = palette.grid
        paint.pathEffect = null
        for (fraction in listOf(-1f, -0.5f, 0f, 0.5f, 1f)) {
            val offset = fraction * data.halfSpanMetres.toFloat() * pixelsPerMetre
            canvas.drawLine(centreX + offset, 0f, centreX + offset, height.toFloat(), paint)
            canvas.drawLine(0f, centreY + offset, width.toFloat(), centreY + offset, paint)
        }

        fun drawTrail(points: List<Vec3>, color: Int, widthDp: Float, dashed: Boolean) {
            if (points.size < 2) return
            path.reset()
            points.forEachIndexed { index, point ->
                val (x, y) = map(point)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = widthDp * density
            paint.color = color
            paint.pathEffect = if (dashed) DashPathEffect(floatArrayOf(10f * density, 7f * density), 0f) else null
            canvas.drawPath(path, paint)
            paint.pathEffect = null
        }

        drawTrail(data.gnssLocalMetres, palette.gnss, 3f, dashed = true)
        drawTrail(data.insLocalMetres, palette.ins, 5f, dashed = false)

        if (data.hasOrigin) {
            val (x, y) = map(Vec3.ZERO)
            paint.style = Paint.Style.FILL
            paint.color = palette.background
            canvas.drawCircle(x, y, 7f * density, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * density
            paint.color = palette.foreground
            canvas.drawCircle(x, y, 7f * density, paint)
        }

        data.gnssLocalMetres.lastOrNull()?.let { point ->
            val (x, y) = map(point)
            paint.style = Paint.Style.FILL
            paint.color = palette.background
            canvas.drawCircle(x, y, 11f * density, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f * density
            paint.color = palette.gnss
            canvas.drawCircle(x, y, 10f * density, paint)
        }

        data.insLocalMetres.lastOrNull()?.let { point ->
            val (x, y) = map(point)
            paint.style = Paint.Style.FILL
            paint.color = palette.ins
            canvas.drawCircle(x, y, 8f * density, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * density
            paint.color = android.graphics.Color.WHITE
            canvas.drawLine(x - 5f * density, y, x + 5f * density, y, paint)
            canvas.drawLine(x, y - 5f * density, x, y + 5f * density, paint)
            if (data.insHeadingDeg.isFinite()) {
                val rad = Math.toRadians(data.insHeadingDeg)
                val dx = sin(rad).toFloat()
                val dy = -cos(rad).toFloat()
                val startX = x + dx * 12f * density
                val startY = y + dy * 12f * density
                val tipX = x + dx * 22f * density
                val tipY = y + dy * 22f * density
                paint.color = palette.foreground
                paint.strokeWidth = 2.5f * density
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(startX, startY, tipX, tipY, paint)
                val backX = -dx * 6f * density
                val backY = -dy * 6f * density
                val sideX = -dy * 3.6f * density
                val sideY = dx * 3.6f * density
                canvas.drawLine(tipX, tipY, tipX + backX + sideX, tipY + backY + sideY, paint)
                canvas.drawLine(tipX, tipY, tipX + backX - sideX, tipY + backY - sideY, paint)
            }
        }

        drawScaleBar(canvas, pixelsPerMetre, density)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = palette.foreground
        val arrowX = width - 28f * density
        val arrowTop = 14f * density
        canvas.drawLine(arrowX, 34f * density, arrowX, arrowTop, paint)
        canvas.drawLine(arrowX, arrowTop, arrowX - 6f * density, 22f * density, paint)
        canvas.drawLine(arrowX, arrowTop, arrowX + 6f * density, 22f * density, paint)
    }

    /** Graphical scale reference bottom-left, choosing a round metre length that fits nicely. */
    private fun drawScaleBar(canvas: Canvas, pixelsPerMetre: Float, density: Float) {
        val metres = scaleBarMetres(pixelsPerMetre, density) ?: return
        val lengthPx = (metres * pixelsPerMetre).toFloat()
        val left = 12f * density
        val bottom = height - 12f * density
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.strokeCap = Paint.Cap.ROUND
        paint.pathEffect = null
        paint.color = palette.foreground
        canvas.drawLine(left, bottom, left + lengthPx, bottom, paint)
        canvas.drawLine(left, bottom - 4f * density, left, bottom + 4f * density, paint)
        canvas.drawLine(left + lengthPx, bottom - 4f * density, left + lengthPx, bottom + 4f * density, paint)
        val label = if (metres >= 1.0) "${metres.toInt()} m" else "$metres m"
        paint.style = Paint.Style.FILL
        paint.textSize = 12f * resources.displayMetrics.scaledDensity
        canvas.drawText(label, left, bottom - 8f * density, paint)
    }
}
