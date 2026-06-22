package com.example.phoneins.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.example.phoneins.navigation.Vec3
import kotlin.math.min

/** Immutable plot input. Replacing this value always invalidates the Android drawing surface. */
internal data class PositionPlotData(
    val insLocalMetres: List<Vec3>,
    val gnssLocalMetres: List<Vec3>,
    val centreNorthMetres: Double,
    val centreEastMetres: Double,
    val halfSpanMetres: Double,
    val hasOrigin: Boolean,
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
 */
internal class PositionPlotView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var data = PositionPlotData(emptyList(), emptyList(), 0.0, 0.0, 10.0, false)
    private var palette = PositionPlotPalette(0, 0, 0, 0, 0)

    fun render(data: PositionPlotData, palette: PositionPlotPalette) {
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
        }

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
}
