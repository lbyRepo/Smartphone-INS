package com.example.phoneins.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.phoneins.BuildConfig
import com.example.phoneins.data.InsRepository
import com.example.phoneins.data.InsUiState
import com.example.phoneins.navigation.*
import com.example.phoneins.service.NavigationService
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

private val LightColors = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF006A65),
    secondary = androidx.compose.ui.graphics.Color(0xFF4A635F),
    tertiary = androidx.compose.ui.graphics.Color(0xFF456179),
)
private val DarkColors = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF4FDAD1),
    secondary = androidx.compose.ui.graphics.Color(0xFFB1CCC7),
    tertiary = androidx.compose.ui.graphics.Color(0xFFACCBE5),
)

@Composable
fun PhoneInsTheme(content: @Composable () -> Unit) {
    val dark = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = Typography(), content = content)
}

internal enum class Destination(val label: String, val glyph: String) {
    DASHBOARD("INS", "◎"), RAW("Raw", "≋"), INITIALIZE("Init", "⌖"), SETTINGS("More", "⚙")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneInsApp(sendAction: (String, Map<String, Any>) -> Unit) {
    val state by InsRepository.state.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(Destination.DASHBOARD) }
    PhoneInsScaffold(state, destination, { destination = it }, sendAction)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneInsScaffold(
    state: InsUiState,
    destination: Destination,
    selectDestination: (Destination) -> Unit,
    sendAction: (String, Map<String, Any>) -> Unit,
) {
    val wide = LocalConfiguration.current.screenWidthDp >= 700
    val fontScale = LocalDensity.current.fontScale
    val compactHeader = !wide && fontScale >= 1.8f
    val positionMapState = remember { PositionMapState() }
    LaunchedEffect(state.solution.sequence, state.solution.initialized, state.solution.latestGnss?.monotonicNanos) {
        positionMapState.update(state.solution)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (compactHeader) "Phone INS" else "Smartphone INS", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                },
                actions = { ServiceBadge(state.serviceRunning, state.simulated, compactHeader) },
            )
        },
        bottomBar = {
            if (!wide) NavigationBar {
                Destination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { selectDestination(item) },
                        icon = { Text(item.glyph, fontSize = 20.sp) },
                        label = { Text(item.label, fontSize = 14.sp, maxLines = 1) },
                        modifier = Modifier
                            .testTag("nav_${item.name.lowercase()}")
                            .semantics { contentDescription = "${item.label} tab" }
                    )
                }
            }
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (wide) NavigationRail {
                Spacer(Modifier.height(8.dp))
                Destination.entries.forEach { item ->
                    NavigationRailItem(
                        selected = destination == item,
                        onClick = { selectDestination(item) },
                        icon = { Text(item.glyph, fontSize = 20.sp) },
                        label = if (fontScale < 1.5f || destination == item) {
                            { Text(item.label, fontSize = 14.sp, maxLines = 1) }
                        } else null,
                        alwaysShowLabel = fontScale < 1.5f,
                        modifier = Modifier
                            .testTag("nav_${item.name.lowercase()}")
                            .semantics { contentDescription = "${item.label} tab" },
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (destination) {
                    Destination.DASHBOARD -> DashboardScreen(state, positionMapState)
                    Destination.RAW -> RawDataScreen(state)
                    Destination.INITIALIZE -> InitializationScreen(state, sendAction)
                    Destination.SETTINGS -> SessionsScreen(state, sendAction)
                }
            }
        }
    }
}

@Composable
private fun ServiceBadge(running: Boolean, simulated: Boolean, compact: Boolean) {
    val text = when {
        !running -> if (compact) "STOP" else "STOPPED"
        simulated -> if (compact) "SIM" else "SIMULATED"
        else -> "LIVE"
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(end = 12.dp).heightIn(min = 48.dp),
    ) {
        Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text(text, fontSize = 14.sp, maxLines = 1)
        }
    }
}

@Composable
internal fun DashboardScreen(state: InsUiState) {
    val positionMapState = remember { PositionMapState() }
    LaunchedEffect(state.solution.sequence, state.solution.initialized, state.solution.latestGnss?.monotonicNanos) {
        positionMapState.update(state.solution)
    }
    DashboardScreen(state, positionMapState)
}

@Composable
private fun DashboardScreen(state: InsUiState, positionMapState: PositionMapState) {
    val solution = state.solution
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("dashboard"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StatusBanner(solution)
        }
        item {
            PositionMapCard(solution, positionMapState)
        }
        item {
            SectionTitle("INS solution")
            AdaptiveMetricGrid(
                listOf(
                    Metric("Latitude", solution.latitudeDeg.f(7), "deg"),
                    Metric("Longitude", solution.longitudeDeg.f(7), "deg"),
                    Metric("Ellipsoid altitude", solution.altitudeEllipsoidM.f(2), "m"),
                    Metric("Position σ (N/E/D)", solution.positionStdNedM.f3(1), "m"),
                ),
            )
        }
        item {
            SectionTitle("Velocity · NED")
            AdaptiveMetricGrid(
                listOf(
                    Metric("North", solution.velocityNedMps.x.f(3), "m/s"),
                    Metric("East", solution.velocityNedMps.y.f(3), "m/s"),
                    Metric("Down", solution.velocityNedMps.z.f(3), "m/s"),
                    Metric("Velocity σ", solution.velocityStdNedMps.f3(2), "m/s"),
                ),
            )
        }
        item {
            SectionTitle("Attitude · NED")
            AdaptiveMetricGrid(
                listOf(
                    Metric("Roll", solution.eulerDeg.x.f(2), "deg"),
                    Metric("Pitch", solution.eulerDeg.y.f(2), "deg"),
                    Metric("Yaw", solution.eulerDeg.z.f(2), "deg true"),
                    Metric("Attitude σ", solution.attitudeStdDeg.f3(1), "deg"),
                ),
            )
        }
        item {
            SectionTitle("Pipeline")
            AdaptiveMetricGrid(
                listOf(
                    Metric("Output", state.outputRateHz.f(1), "Hz"),
                    Metric("IMU input", state.imuRateHz.f(1), "Hz"),
                    Metric("Packets sent", state.packetsSent.toString(), "frames"),
                    Metric("Dropped", state.droppedPackets.toString(), "frames"),
                ),
            )
        }
    }
}

@Composable
private fun StatusBanner(solution: NavigationSolution) {
    val color = when {
        !solution.initialized -> MaterialTheme.colorScheme.surfaceVariant
        solution.qualityFlags and QualityFlag.GNSS_AIDING_SUSPENDED != 0 -> MaterialTheme.colorScheme.tertiaryContainer
        solution.degraded -> MaterialTheme.colorScheme.errorContainer
        solution.qualityFlags and QualityFlag.GNSS_UPDATE_REJECTED != 0 -> MaterialTheme.colorScheme.errorContainer
        solution.qualityFlags and QualityFlag.GNSS_POSITION_VALID == 0 -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val title = when {
        !solution.initialized -> "Waiting for initialization"
        solution.qualityFlags and QualityFlag.GNSS_AIDING_SUSPENDED != 0 -> "GNSS visible · aiding paused"
        solution.degraded -> "Dead reckoning · GNSS stale"
        solution.qualityFlags and QualityFlag.GNSS_UPDATE_REJECTED != 0 -> "GNSS fix present · aiding rejected"
        solution.qualityFlags and QualityFlag.STATIONARY_CONSTRAINT != 0 -> "GNSS-aided · stationary hold"
        solution.qualityFlags and QualityFlag.HEADING_COURSE_AIDED != 0 -> "GNSS-aided · course heading"
        solution.qualityFlags and QualityFlag.GNSS_POSITION_VALID != 0 -> "GNSS-aided · heading coasting"
        else -> "GNSS fix present · awaiting correction"
    }
    Card(colors = CardDefaults.cardColors(containerColor = color), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("${solution.fixStatus.name.replace('_', ' ')} · ${solution.motionProfile.name.lowercase().replaceFirstChar { it.titlecase() }} profile", fontSize = 14.sp)
        }
    }
}

internal data class TrailPoint(val monotonicNanos: Long, val latitudeDeg: Double, val longitudeDeg: Double)

internal class PositionMapState {
    val insTrail = mutableStateListOf<TrailPoint>()
    val gnssTrail = mutableStateListOf<TrailPoint>()
    var origin by mutableStateOf<TrailPoint?>(null)
        private set
    private var lastSequence: Long? = null

    internal fun update(solution: NavigationSolution) {
        val sequenceReset = lastSequence?.let { solution.sequence < it } == true
        if (!solution.initialized || sequenceReset) {
            clear()
            if (!solution.initialized) return
        }
        val insValid = solution.latitudeDeg.isFinite() && solution.longitudeDeg.isFinite()
        if (insValid) {
            val point = TrailPoint(solution.monotonicNanos, solution.latitudeDeg, solution.longitudeDeg)
            if (origin == null) origin = point
            appendTrail(insTrail, point)
        }
        solution.latestGnss?.takeIf { it.latitudeDeg.isFinite() && it.longitudeDeg.isFinite() }?.let {
            appendTrail(gnssTrail, TrailPoint(it.monotonicNanos, it.latitudeDeg, it.longitudeDeg))
        }
        lastSequence = solution.sequence
    }

    private fun clear() {
        insTrail.clear()
        gnssTrail.clear()
        origin = null
        lastSequence = null
    }
}

@Composable
private fun PositionMapCard(solution: NavigationSolution, mapState: PositionMapState) {
    val insTrail = mapState.insTrail
    val gnssTrail = mapState.gnssTrail
    val insValid = solution.initialized && solution.latitudeDeg.isFinite() && solution.longitudeDeg.isFinite()
    val gnss = solution.latestGnss
    val gnssValid = gnss != null && gnss.latitudeDeg.isFinite() && gnss.longitudeDeg.isFinite()
    val currentIns = if (insValid) TrailPoint(solution.monotonicNanos, solution.latitudeDeg, solution.longitudeDeg) else null
    val currentGnss = if (gnssValid) TrailPoint(gnss.monotonicNanos, gnss.latitudeDeg, gnss.longitudeDeg) else null
    val separationMetres = if (currentIns != null && currentGnss != null) {
        currentIns.toLocalMetres(currentGnss).norm()
    } else {
        null
    }
    val gnssBoundMetres = gnss?.horizontalAccuracyM?.takeIf { it.isFinite() }?.let { max(2.0, 3.0 * it) }
    val positionAided = solution.qualityFlags and QualityFlag.GNSS_POSITION_VALID != 0

    val insColor = MaterialTheme.colorScheme.primary
    val gnssColor = MaterialTheme.colorScheme.tertiary
    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant
    val arrowColor = MaterialTheme.colorScheme.onSurface
    val gridColor = arrowColor.copy(alpha = 0.16f)
    val allPoints = remember(insTrail.size, gnssTrail.size, solution.monotonicNanos, gnss?.monotonicNanos, mapState.origin) {
        buildList {
            mapState.origin?.let(::add)
            addAll(insTrail)
            addAll(gnssTrail)
        }.ifEmpty {
            buildList {
                currentIns?.let(::add)
                currentGnss?.let(::add)
            }
        }
    }
    val reference = mapState.origin ?: allPoints.firstOrNull()
    val localPoints = reference?.let { origin -> allPoints.map { it.toLocalMetres(origin) } }.orEmpty()
    val extent = localPoints.maxOfOrNull { max(abs(it.x), abs(it.y)) } ?: 0.0
    val halfSpanMetres = niceMapSpan(max(10.0, extent * 1.25))

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Position map")
            Spacer(Modifier.weight(1f))
            Text("±${halfSpanMetres.f(0)} m", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Card(Modifier.fillMaxWidth().testTag("position_map")) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp)
                    .semantics { contentDescription = "Local map comparing INS and raw GNSS positions from the initialization origin" },
            ) {
                drawRect(backgroundColor)
                val center = Offset(size.width / 2f, size.height / 2f)
                val pixelsPerMetre = (min(size.width, size.height) * 0.42 / halfSpanMetres).toFloat()
                for (fraction in listOf(-1f, -0.5f, 0f, 0.5f, 1f)) {
                    val x = center.x + fraction * halfSpanMetres.toFloat() * pixelsPerMetre
                    val y = center.y + fraction * halfSpanMetres.toFloat() * pixelsPerMetre
                    drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
                fun map(point: TrailPoint): Offset {
                    val local = reference?.let { point.toLocalMetres(it) } ?: Vec3.ZERO
                    return Offset(center.x + local.y.toFloat() * pixelsPerMetre, center.y - local.x.toFloat() * pixelsPerMetre)
                }
                fun trail(points: List<TrailPoint>, color: Color) {
                    points.zipWithNext().forEach { (a, b) ->
                        drawLine(color.copy(alpha = 0.65f), map(a), map(b), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                    }
                }
                trail(gnssTrail, gnssColor)
                trail(insTrail, insColor)
                if (reference != null) {
                    drawCircle(arrowColor.copy(alpha = 0.75f), 5.dp.toPx(), center, style = Stroke(2.dp.toPx()))
                }
                (gnssTrail.lastOrNull() ?: currentGnss)?.let { point ->
                    drawCircle(gnssColor, 10.dp.toPx(), map(point), style = Stroke(3.dp.toPx()))
                }
                (insTrail.lastOrNull() ?: currentIns)?.let { point ->
                    val marker = map(point)
                    drawCircle(insColor, 6.dp.toPx(), marker)
                    drawLine(Color.White, marker - Offset(4.dp.toPx(), 0f), marker + Offset(4.dp.toPx(), 0f), 2.dp.toPx())
                    drawLine(Color.White, marker - Offset(0f, 4.dp.toPx()), marker + Offset(0f, 4.dp.toPx()), 2.dp.toPx())
                }
                drawLine(arrowColor, Offset(size.width - 28.dp.toPx(), 34.dp.toPx()), Offset(size.width - 28.dp.toPx(), 14.dp.toPx()), 2.dp.toPx())
                drawLine(arrowColor, Offset(size.width - 28.dp.toPx(), 14.dp.toPx()), Offset(size.width - 34.dp.toPx(), 22.dp.toPx()), 2.dp.toPx())
                drawLine(arrowColor, Offset(size.width - 28.dp.toPx(), 14.dp.toPx()), Offset(size.width - 22.dp.toPx(), 22.dp.toPx()), 2.dp.toPx())
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            MapLegend(insColor, "INS position", filled = true)
            MapLegend(gnssColor, "Raw GNSS", filled = false)
        }
        mapState.origin?.let { origin ->
            Text(
                "Origin ${origin.latitudeDeg.f(7)}, ${origin.longitudeDeg.f(7)}",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("position_map_origin"),
            )
        }
        Text(
            "History · INS ${insTrail.size} points · GNSS ${gnssTrail.size} points",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("position_history_count"),
        )
        if (separationMetres != null) {
            Text(
                "INS–GNSS separation ${separationMetres.f(1)} m",
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.testTag("position_separation"),
            )
            val boundText = when {
                positionAided && gnssBoundMetres != null && separationMetres <= gnssBoundMetres + 0.05 ->
                    "GNSS bound active · within ${gnssBoundMetres.f(1)} m"
                positionAided && gnssBoundMetres != null ->
                    "AIDING LIMIT EXCEEDED · expected within ${gnssBoundMetres.f(1)} m"
                else -> "GNSS correction is not currently applied"
            }
            Text(
                boundText,
                fontSize = 12.sp,
                color = if (boundText.startsWith("AIDING LIMIT")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("position_bound_status"),
            )
        }
        if (!insValid && !gnssValid) {
            Text("Start and initialize navigation to plot positions.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MapLegend(color: Color, label: String, filled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(16.dp)) {
            if (filled) drawCircle(color, size.minDimension / 2) else drawCircle(color, size.minDimension / 2 - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
        }
        Text(label, fontSize = 14.sp)
    }
}

private fun appendTrail(trail: MutableList<TrailPoint>, point: TrailPoint) {
    val previous = trail.lastOrNull()
    val elapsed = previous?.let { point.monotonicNanos - it.monotonicNanos } ?: Long.MAX_VALUE
    val distance = previous?.let { point.toLocalMetres(it).let { local -> kotlin.math.hypot(local.x, local.y) } } ?: Double.POSITIVE_INFINITY
    if (previous == null || elapsed >= 1_000_000_000L || (elapsed >= 100_000_000L && distance >= 1.0)) {
        trail += point
    }
}

private fun TrailPoint.toLocalMetres(origin: TrailPoint): Vec3 {
    val north = (latitudeDeg - origin.latitudeDeg) * 111_132.0
    val east = (longitudeDeg - origin.longitudeDeg) * 111_320.0 * cos(Math.toRadians(origin.latitudeDeg))
    return Vec3(north, east, 0.0)
}

internal fun TrailPoint.toLocalMetresForTest(origin: TrailPoint): Vec3 = toLocalMetres(origin)

private fun niceMapSpan(required: Double): Double = listOf(10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1_000.0)
    .firstOrNull { it >= required } ?: required

private data class Metric(val label: String, val value: String, val unit: String)

@Composable
private fun AdaptiveMetricGrid(metrics: List<Metric>) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = when {
            fontScale >= 1.8f && maxWidth >= 700.dp -> 2
            fontScale >= 1.8f -> 1
            fontScale >= 1.25f && maxWidth >= 700.dp -> 3
            maxWidth >= 720.dp -> 4
            maxWidth >= 330.dp -> 2
            else -> 1
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            metrics.chunked(columns).forEach { rowMetrics ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowMetrics.forEach { metric -> MetricCard(metric, Modifier.weight(1f)) }
                    repeat(columns - rowMetrics.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun MetricCard(metric: Metric, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(metric.label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(metric.value, fontSize = 18.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
            Text(metric.unit, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun RawDataScreen(state: InsUiState) {
    val imu = state.solution.latestImu
    val mag = state.latestMag
    val gnss = state.solution.latestGnss
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("raw_data"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionTitle("Raw IMU · body FRD") }
        item {
            AdaptiveMetricGrid(listOf(
                Metric("Ax / Ay / Az", imu?.accelerationMps2?.f3(4) ?: "—", "m/s²"),
                Metric("Gx / Gy / Gz", imu?.angularRateRadps?.f3(5) ?: "—", "rad/s"),
                Metric("Sample age", imu?.let { ((state.solution.monotonicNanos - it.monotonicNanos).coerceAtLeast(0L) / 1e6).f(1) } ?: "—", "ms"),
                Metric("Freshness", if (state.solution.qualityFlags and QualityFlag.IMU_FRESH != 0) "FRESH" else "HELD", "quality"),
            ))
        }
        item { SectionTitle("Raw magnetometer · body FRD") }
        item {
            AdaptiveMetricGrid(listOf(
                Metric("Mx / My / Mz", mag?.fieldMicroTesla?.f3(2) ?: "—", "µT"),
                Metric("Field magnitude", mag?.fieldMicroTesla?.norm()?.f(2) ?: "—", "µT"),
                Metric("Sample age", mag?.let { ((state.solution.monotonicNanos - it.monotonicNanos).coerceAtLeast(0L) / 1e6).f(1) } ?: "—", "ms"),
                Metric("Navigation use", if (state.solution.initialized) "LOCKED OUT" else "INIT ONLY", "heading policy"),
            ))
        }
        item { SectionTitle("Raw GNSS fix") }
        item {
            AdaptiveMetricGrid(listOf(
                Metric("Latitude", gnss?.latitudeDeg?.f(7) ?: "—", "deg"),
                Metric("Longitude", gnss?.longitudeDeg?.f(7) ?: "—", "deg"),
                Metric("Ellipsoid altitude", gnss?.altitudeEllipsoidM?.f(2) ?: "—", "m"),
                Metric("Velocity N/E/D", gnss?.velocityNedMps?.f3(3) ?: "—", "m/s"),
                Metric("Horizontal accuracy", gnss?.horizontalAccuracyM?.f(1) ?: "—", "m (68%)"),
                Metric("Vertical accuracy", gnss?.verticalAccuracyM?.f(1) ?: "—", "m (68%)"),
                Metric("Satellites used", gnss?.satellitesUsed?.toString() ?: "—", "SV"),
                Metric("Fix status", state.solution.fixStatus.name, "status"),
                Metric(
                    "INS aiding",
                    when {
                        state.solution.qualityFlags and QualityFlag.GNSS_UPDATE_REJECTED != 0 -> "REJECTED"
                        state.solution.qualityFlags and QualityFlag.GNSS_AIDING_SUSPENDED != 0 -> "PAUSED"
                        state.solution.qualityFlags and QualityFlag.STATIONARY_CONSTRAINT != 0 -> "STATIONARY HOLD"
                        state.solution.qualityFlags and QualityFlag.GNSS_POSITION_VALID != 0 -> "APPLIED"
                        else -> "NOT APPLIED"
                    },
                    "filter update",
                ),
            ))
        }
        item {
            Text("Raw GNSS is the Android PVT fix. Down velocity is derived from recent altitude fixes and explicitly quality-flagged.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InitializationScreen(state: InsUiState, sendAction: (String, Map<String, Any>) -> Unit) {
    var manual by rememberSaveable { mutableStateOf(false) }
    var latitude by rememberSaveable { mutableStateOf("1.3521000") }
    var longitude by rememberSaveable { mutableStateOf("103.8198000") }
    var altitude by rememberSaveable { mutableStateOf("25.0") }
    var roll by rememberSaveable { mutableStateOf("0.0") }
    var pitch by rememberSaveable { mutableStateOf("0.0") }
    var yaw by rememberSaveable { mutableStateOf("0.0") }
    val values = listOf(latitude, longitude, altitude, roll, pitch, yaw).map { it.toDoubleOrNull() }
    val valid = values.all { it != null } && values[0]!! in -90.0..90.0 && values[1]!! in -180.0..180.0 && values[4]!! in -90.0..90.0

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("initialization"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("Initialize navigation")
        Text("Place the phone screen-up with its top edge pointing forward. Keep it still while gyro and accelerometer biases are measured.", fontSize = 14.sp)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(selected = !manual, onClick = { manual = false }, shape = SegmentedButtonDefaults.itemShape(0, 2), modifier = Modifier.heightIn(min = 56.dp).testTag("init_auto_mode")) { Text("Automatic", fontSize = 14.sp) }
            SegmentedButton(selected = manual, onClick = { manual = true }, shape = SegmentedButtonDefaults.itemShape(1, 2), modifier = Modifier.heightIn(min = 56.dp).testTag("init_manual_mode")) { Text("Manual", fontSize = 14.sp) }
        }
        if (!manual) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Automatic sources", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Text("Position: GNSS\nRoll and pitch: gravity\nInitial yaw: magnetometer + declination\nMoving yaw: GNSS course only", fontSize = 14.sp)
                }
            }
            Button(
                onClick = { sendAction(NavigationService.ACTION_INITIALIZE_AUTO, emptyMap()) },
                enabled = state.serviceRunning,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("initialize_auto"),
            ) { Text("Begin automatic initialization", fontSize = 14.sp) }
        } else {
            ManualField("Latitude", latitude, { latitude = it }, "degrees", "manual_latitude")
            ManualField("Longitude", longitude, { longitude = it }, "degrees", "manual_longitude")
            ManualField("WGS84 ellipsoid altitude", altitude, { altitude = it }, "metres", "manual_altitude")
            ManualField("Roll", roll, { roll = it }, "degrees", "manual_roll")
            ManualField("Pitch", pitch, { pitch = it }, "degrees", "manual_pitch")
            ManualField("Yaw from true north", yaw, { yaw = it }, "degrees", "manual_yaw")
            if (!valid) Text("Enter valid numeric coordinates and Euler angles.", color = MaterialTheme.colorScheme.error, fontSize = 14.sp, modifier = Modifier.testTag("manual_error"))
            Button(
                onClick = {
                    sendAction(NavigationService.ACTION_INITIALIZE_MANUAL, mapOf(
                        "latitude" to values[0]!!, "longitude" to values[1]!!, "altitude" to values[2]!!,
                        "roll" to values[3]!!, "pitch" to values[4]!!, "yaw" to values[5]!!,
                    ))
                },
                enabled = state.serviceRunning && valid,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("initialize_manual"),
            ) { Text("Apply manual initialization", fontSize = 14.sp) }
        }
        OutlinedButton(onClick = { sendAction(NavigationService.ACTION_RESET, emptyMap()) }, enabled = state.serviceRunning, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Reset filter", fontSize = 14.sp)
        }
    }
}

@Composable
private fun ManualField(label: String, value: String, changed: (String) -> Unit, suffix: String, tag: String) {
    OutlinedTextField(
        value = value,
        onValueChange = changed,
        label = { Text(label, fontSize = 14.sp) },
        supportingText = { Text(suffix, fontSize = 12.sp) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(tag),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionsScreen(state: InsUiState, sendAction: (String, Map<String, Any>) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("sessions"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Run control")
        Button(
            onClick = { sendAction(if (state.serviceRunning) NavigationService.ACTION_STOP else NavigationService.ACTION_START, emptyMap()) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("service_toggle"),
            colors = if (state.serviceRunning) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
        ) { Text(if (state.serviceRunning) "Stop navigation service" else "Start navigation service", fontSize = 14.sp) }

        if (BuildConfig.SIMULATOR_AVAILABLE) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Deterministic simulator", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Text("Debug builds only. Exercises motion, GNSS loss and recovery.", fontSize = 14.sp)
                    }
                    Switch(checked = state.simulated, onCheckedChange = { sendAction(NavigationService.ACTION_SIMULATION, mapOf("enabled" to it)) }, enabled = state.serviceRunning)
                }
            }
        }

        SectionTitle("Motion profile")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            MotionProfile.entries.forEachIndexed { index, profile ->
                SegmentedButton(
                    selected = state.solution.motionProfile == profile,
                    onClick = { sendAction(NavigationService.ACTION_PROFILE, mapOf("profile" to profile.name)) },
                    shape = SegmentedButtonDefaults.itemShape(index, MotionProfile.entries.size),
                    modifier = Modifier.heightIn(min = 56.dp),
                ) { Text(profile.name.lowercase().replaceFirstChar { it.titlecase() }, fontSize = 14.sp) }
            }
        }

        SectionTitle("Recording and replay")
        FilledTonalButton(onClick = { sendAction(NavigationService.ACTION_RECORDING, mapOf("enabled" to !state.recording)) }, enabled = state.serviceRunning, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (state.recording) "Stop recording" else "Record session", fontSize = 14.sp)
        }
        if (BuildConfig.SIMULATOR_AVAILABLE) {
            OutlinedButton(
                onClick = { sendAction(NavigationService.ACTION_PUBLIC_DATASET, emptyMap()) },
                enabled = state.serviceRunning && !state.replaying,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("public_dataset_replay"),
            ) { Text("Replay public Pixel 4 dataset", fontSize = 14.sp) }
        }
        if (state.sessionNames.isEmpty()) {
            Text("No recorded sessions yet.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            state.sessionNames.take(5).forEach { name ->
                OutlinedButton(onClick = { sendAction(NavigationService.ACTION_REPLAY, mapOf("name" to name)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Replay $name", fontSize = 14.sp, maxLines = 1)
                }
            }
        }

        Text("Output links", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp).testTag("output_links"))
        KeyValueRow("TCP server", if (state.tcpConnected) "Client connected · :29500" else "Listening on :29500")
        KeyValueRow("Android accessory", if (state.aoaConnected) "Connected" else "Not connected")
        KeyValueRow("Protocol", "INS1 · 224-byte binary · CRC-32")
        KeyValueRow("Mounting", "Screen up · top edge forward")
        KeyValueRow("App version", BuildConfig.VERSION_NAME)
    }
}

@Composable private fun SectionTitle(text: String) = Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))

@Composable
private fun KeyValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.42f))
        Text(value, fontSize = 14.sp, modifier = Modifier.weight(0.58f))
    }
}

private fun Double.f(decimals: Int): String = if (isFinite()) String.format(Locale.US, "%.${decimals}f", this) else "—"
private fun Vec3.f3(decimals: Int) = "${x.f(decimals)} / ${y.f(decimals)} / ${z.f(decimals)}"

@Preview(name = "Dashboard light", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun DashboardPreview() = PhoneInsTheme { DashboardScreen(previewState()) }

@Preview(name = "Dashboard 200% font", showBackground = true, widthDp = 393, heightDp = 852, fontScale = 2f)
@Composable
private fun DashboardLargeFontPreview() = PhoneInsTheme { DashboardScreen(previewState()) }

@Preview(name = "Raw measurements", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun RawPreview() = PhoneInsTheme { RawDataScreen(previewState()) }

internal fun previewState(): InsUiState {
    val now = 12_345_000_000L
    val imu = TimedImuSample(now - 4_000_000, Vec3(0.12, -0.04, -9.79), Vec3(0.001, 0.002, -0.003))
    val mag = MagSample(now - 8_000_000, Vec3(31.2, -4.1, 34.0))
    val gnss = GnssFix(
        now - 180_000_000, 1_750_000_000_000, 1.3521, 103.8198, 25.4,
        Vec3(8.2, 1.4, -0.1), 2.1, 4.2, 0.18, 3.8, 18, hasDerivedDownVelocity = true,
    )
    val solution = NavigationSolution(
        now, gnss.unixMillis, 1.3521008, 103.8198012, 25.7,
        Vec3(8.18, 1.43, -0.08), Vec3(0.8, -1.1, 9.9),
        Vec3(1.8, 1.9, 3.7), Vec3(0.16, 0.17, 0.4), Vec3(0.7, 0.8, 2.4),
        imu, gnss, InitSource.AUTOMATIC, MotionProfile.VEHICLE, FixStatus.FIX_3D,
        QualityFlag.INITIALIZED or QualityFlag.IMU_FRESH or QualityFlag.GNSS_FRESH or
            QualityFlag.GNSS_POSITION_VALID or QualityFlag.GNSS_HORIZONTAL_VELOCITY_VALID or
            QualityFlag.GNSS_DOWN_VELOCITY_VALID or QualityFlag.HEADING_COURSE_AIDED,
        12_345,
    )
    return InsUiState(
        solution = solution, latestMag = mag, serviceRunning = true, simulated = true, tcpConnected = true,
        recording = true, outputRateHz = 100.0, imuRateHz = 198.7, packetsSent = 12_345,
        statusMessage = "GNSS valid · course-aided heading",
    )
}
