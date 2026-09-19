package com.example.phoneins.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.example.phoneins.BuildConfig
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay
import java.io.File
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Immutable tile-mode plot input. Points are geographic; osmdroid owns projection. */
internal data class MapBasemapData(
    val insPoints: List<GeoPoint>,
    val gnssPoints: List<GeoPoint>,
    val origin: GeoPoint?,
    val insHeadingDeg: Double,
    /** Incremented by the host to request a recenter (re-enables auto-fit). */
    val recenterEpoch: Int = 0,
)

internal data class MapBasemapPalette(
    val ins: Int,
    val gnss: Int,
    val foreground: Int,
    val background: Int,
)

/**
 * Street-map counterpart of [PositionPlotView]. Renders the same INS/GNSS history over cached
 * online raster tiles (CARTO light/dark), with pinch-zoom/pan and auto-fit that yields to the
 * user until the host requests a recenter through [MapBasemapData.recenterEpoch]. Rendering is
 * change-gated so a 10 Hz UI stream does not continuously invalidate the tile pipeline.
 */
internal class MapBasemapView(context: Context, darkTheme: Boolean) : MapView(context) {
    private val density = resources.displayMetrics.density
    private val insTrail = Polyline(this)
    private val gnssTrail = Polyline(this)
    private val originMarker = Marker(this)
    private val gnssMarker = Marker(this)
    private val insMarker = Marker(this)
    private var latestData = MapBasemapData(emptyList(), emptyList(), null, Double.NaN)
    private var lastRenderSignature = Long.MIN_VALUE
    private var lastFitSignature = Long.MIN_VALUE
    private var appliedRecenterEpoch = 0
    private var userNavigated = false
    private var fitting = false

    /** Invoked when the user pans or zooms so the host can offer a recenter control. */
    var onUserNavigation: (() -> Unit)? = null

    init {
        setTileSource(if (darkTheme) CARTO_DARK else CARTO_LIGHT)
        setMinZoomLevel(MIN_ZOOM_LEVEL)
        setMaxZoomLevel(MAX_ZOOM_LEVEL)
        setTilesScaledToDpi(true)
        setMultiTouchControls(true)
        controller.setZoom(INITIAL_ZOOM_LEVEL)
        insTrail.outlinePaint.apply {
            strokeWidth = TRAIL_WIDTH_DP * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        gnssTrail.outlinePaint.apply {
            strokeWidth = GNSS_TRAIL_WIDTH_DP * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            pathEffect = DashPathEffect(floatArrayOf(10f * density, 7f * density), 0f)
        }
        listOf(originMarker, gnssMarker, insMarker).forEach { marker ->
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            marker.isEnabled = false
        }
        overlays.add(insTrail)
        overlays.add(gnssTrail)
        overlays.add(originMarker)
        overlays.add(gnssMarker)
        overlays.add(insMarker)
        overlays.add(ScaleBarOverlay(this).apply { setAlignBottom(true) })
        addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                noteUserNavigation()
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                noteUserNavigation()
                return false
            }
        })
    }

    fun render(data: MapBasemapData, palette: MapBasemapPalette) {
        val signature = renderSignature(data, palette)
        if (signature == lastRenderSignature) return
        lastRenderSignature = signature
        latestData = data
        if (data.recenterEpoch != appliedRecenterEpoch) {
            appliedRecenterEpoch = data.recenterEpoch
            userNavigated = false
            lastFitSignature = Long.MIN_VALUE
        }

        insTrail.outlinePaint.color = palette.ins
        gnssTrail.outlinePaint.color = palette.gnss
        insTrail.setPoints(data.insPoints)
        gnssTrail.setPoints(data.gnssPoints)

        data.origin?.let {
            originMarker.position = it
            originMarker.icon = originIcon(palette)
            originMarker.isEnabled = true
        } ?: run { originMarker.isEnabled = false }

        data.gnssPoints.lastOrNull()?.let {
            gnssMarker.position = it
            gnssMarker.icon = gnssIcon(palette)
            gnssMarker.isEnabled = true
        } ?: run { gnssMarker.isEnabled = false }

        data.insPoints.lastOrNull()?.let {
            insMarker.position = it
            insMarker.icon = insIcon(palette, data.insHeadingDeg)
            insMarker.isEnabled = true
        } ?: run { insMarker.isEnabled = false }

        if (!userNavigated) fitIfNeeded(data)
        invalidate()
    }

    private fun noteUserNavigation() {
        if (fitting) return
        if (!userNavigated) {
            userNavigated = true
            onUserNavigation?.invoke()
        }
    }

    private fun fitIfNeeded(data: MapBasemapData) {
        val signature = data.insPoints.size * 1_000_003L + data.gnssPoints.size
        if (signature == lastFitSignature) return
        lastFitSignature = signature
        val points = ArrayList<GeoPoint>(data.insPoints.size + data.gnssPoints.size + 1)
        data.origin?.let(points::add)
        points.addAll(data.insPoints)
        points.addAll(data.gnssPoints)
        fitting = true
        try {
            when {
                points.isEmpty() -> {
                    controller.setCenter(DEFAULT_CENTER)
                    controller.setZoom(INITIAL_ZOOM_LEVEL)
                }
                points.allEquivalent() -> {
                    controller.setCenter(points.first())
                    if (zoomLevelDouble < SINGLE_POINT_ZOOM_LEVEL) controller.setZoom(SINGLE_POINT_ZOOM_LEVEL)
                }
                else -> zoomToBoundingBox(BoundingBox.fromGeoPoints(points).increaseByScale(FIT_PADDING_SCALE), false)
            }
        } finally {
            fitting = false
        }
    }

    private fun renderSignature(data: MapBasemapData, palette: MapBasemapPalette): Long {
        var result = data.insPoints.size.toLong()
        result = 31L * result + data.gnssPoints.size
        result = 31L * result + (data.origin?.latitude?.toBits() ?: 0L) * 31L + (data.origin?.longitude?.toBits() ?: 0L)
        result = 31L * result + headingKey(data.insHeadingDeg)
        result = 31L * result + data.recenterEpoch
        result = 31L * result + palette.ins
        result = 31L * result + palette.gnss
        result = 31L * result + palette.foreground
        result = 31L * result + palette.background
        return result
    }

    private fun bitmapIcon(sizeDp: Int, draw: (Canvas, Paint) -> Unit): Drawable {
        val sizePx = (sizeDp * density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        draw(Canvas(bitmap), paint)
        return BitmapDrawable(resources, bitmap)
    }

    /** Open circle at the initialization origin, matching the grid plot. */
    private fun originIcon(palette: MapBasemapPalette): Drawable = bitmapIcon(ORIGIN_ICON_DP) { canvas, paint ->
        val centre = canvas.width / 2f
        paint.style = Paint.Style.FILL
        paint.color = palette.background
        canvas.drawCircle(centre, centre, 7f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = palette.foreground
        canvas.drawCircle(centre, centre, 7f * density, paint)
    }

    /** Open GNSS circle at the latest raw fix. */
    private fun gnssIcon(palette: MapBasemapPalette): Drawable = bitmapIcon(GNSS_ICON_DP) { canvas, paint ->
        val centre = canvas.width / 2f
        paint.style = Paint.Style.FILL
        paint.color = palette.background
        canvas.drawCircle(centre, centre, 10f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * density
        paint.color = palette.gnss
        canvas.drawCircle(centre, centre, 10f * density, paint)
    }

    /** Filled INS dot with cross and an optional yaw-rotated heading arrow. */
    private fun insIcon(palette: MapBasemapPalette, headingDeg: Double): Drawable = bitmapIcon(INS_ICON_DP) { canvas, paint ->
        val centre = canvas.width / 2f
        paint.style = Paint.Style.FILL
        paint.color = palette.ins
        canvas.drawCircle(centre, centre, 8f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = android.graphics.Color.WHITE
        canvas.drawLine(centre - 5f * density, centre, centre + 5f * density, centre, paint)
        canvas.drawLine(centre, centre - 5f * density, centre, centre + 5f * density, paint)
        if (headingDeg.isFinite()) {
            val rad = Math.toRadians(headingDeg)
            val dx = sin(rad).toFloat()
            val dy = -cos(rad).toFloat()
            val startX = centre + dx * ARROW_INSET_DP * density
            val startY = centre + dy * ARROW_INSET_DP * density
            val tipX = centre + dx * ARROW_TIP_DP * density
            val tipY = centre + dy * ARROW_TIP_DP * density
            paint.color = palette.foreground
            paint.strokeWidth = 2.5f * density
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(startX, startY, tipX, tipY, paint)
            val backX = -dx * ARROW_HEAD_DP * density
            val backY = -dy * ARROW_HEAD_DP * density
            val sideX = -dy * ARROW_HEAD_DP * 0.6f * density
            val sideY = dx * ARROW_HEAD_DP * 0.6f * density
            canvas.drawLine(tipX, tipY, tipX + backX + sideX, tipY + backY + sideY, paint)
            canvas.drawLine(tipX, tipY, tipX + backX - sideX, tipY + backY - sideY, paint)
        }
    }

    private fun headingKey(headingDeg: Double): Long =
        if (headingDeg.isFinite()) headingDeg.roundToInt().toLong() else Long.MIN_VALUE

    companion object {
        private const val MIN_ZOOM_LEVEL = 3.0
        private const val MAX_ZOOM_LEVEL = 19.0
        private const val INITIAL_ZOOM_LEVEL = 13.0
        private const val SINGLE_POINT_ZOOM_LEVEL = 16.0
        private const val FIT_PADDING_SCALE = 1.24f
        private const val TRAIL_WIDTH_DP = 5f
        private const val GNSS_TRAIL_WIDTH_DP = 3f
        private const val ORIGIN_ICON_DP = 18
        private const val GNSS_ICON_DP = 24
        private const val INS_ICON_DP = 40
        private const val ARROW_INSET_DP = 11f
        private const val ARROW_TIP_DP = 18f
        private const val ARROW_HEAD_DP = 4f

        /** Matches the deterministic simulator and manual-initialization default location. */
        private val DEFAULT_CENTER = GeoPoint(1.3521, 103.8198)

        private val CARTO_LIGHT = XYTileSource(
            "CartoPositron", 0, 20, 256, ".png",
            arrayOf(
                "https://a.basemaps.cartocdn.com/light_all/",
                "https://b.basemaps.cartocdn.com/light_all/",
                "https://c.basemaps.cartocdn.com/light_all/",
            ),
        )

        private val CARTO_DARK = XYTileSource(
            "CartoDarkMatter", 0, 20, 256, ".png",
            arrayOf(
                "https://a.basemaps.cartocdn.com/dark_all/",
                "https://b.basemaps.cartocdn.com/dark_all/",
                "https://c.basemaps.cartocdn.com/dark_all/",
            ),
        )

        @Volatile private var osmdroidConfigured = false

        /**
         * Point osmdroid at app-internal storage (no permissions needed) and identify the app to
         * the tile servers, as required by the OpenStreetMap/Carto tile usage policies.
         *
         * MUST run before the first [MapView] is constructed: the super constructor opens the
         * SQLite tile cache at the currently configured path and the writer is process-static.
         */
        internal fun configureBeforeFirstMapView(context: Context) {
            if (osmdroidConfigured) return
            synchronized(this) {
                if (osmdroidConfigured) return
                val configuration = Configuration.getInstance()
                configuration.userAgentValue = "SmartphoneINS/${BuildConfig.VERSION_NAME}"
                configuration.osmdroidBasePath = File(context.cacheDir, "osmdroid")
                configuration.osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
                osmdroidConfigured = true
            }
        }
    }
}

private fun List<GeoPoint>.allEquivalent(): Boolean {
    if (size < 2) return true
    val first = first()
    return all { it.latitude == first.latitude && it.longitude == first.longitude }
}
