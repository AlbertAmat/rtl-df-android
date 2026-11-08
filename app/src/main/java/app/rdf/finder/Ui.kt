package app.rdf.finder

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

/** Basemap choice (Settings). Tiles are cached per style by osmdroid. */
enum class MapStyle(val label: String, val attribution: String) {
    TOPO("Topographic (OpenTopoMap)", "© OpenTopoMap (CC-BY-SA) · © OpenStreetMap contributors"),
    STREET("Street (OpenStreetMap)", "© OpenStreetMap contributors");

    fun source() = if (this == TOPO) TileSourceFactory.OpenTopo else TileSourceFactory.MAPNIK
}

/** Darkens the tiles to sit in the dark UI; in night mode turns them into red monochrome. */
private fun mapTint(night: Boolean): ColorMatrixColorFilter {
    val cm = ColorMatrix()
    if (night) {
        cm.set(
            floatArrayOf(
                0.30f, 0.59f, 0.11f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
    } else {
        cm.setSaturation(0.55f)
        cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    0.80f, 0f, 0f, 0f, 0f,
                    0f, 0.82f, 0f, 0f, 0f,
                    0f, 0f, 0.76f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    }
    return ColorMatrixColorFilter(cm)
}

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

/** Shared reference to the MapView (used to recenter and download areas). */
class MapHolder {
    var map: MapView? = null
    var centered = false
}

private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)

@Composable
fun RdfApp(vm: MainViewModel) {
    TacticalTheme(night = vm.f("night_mode") == "true") {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val holder = remember { MapHolder() }
            val snackbar = remember { SnackbarHostState() }
            // collectLatest cancels the snackbar being shown: the newest message wins, none queue up
            LaunchedEffect(Unit) { vm.messages.collectLatest { snackbar.showSnackbar(it) } }

            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                containerColor = MaterialTheme.colorScheme.background,
            ) { pad ->
                var tab by rememberSaveable { mutableIntStateOf(0) } // 0 HUNT, 1 MAP, 2 SCAN, 3 LOG, 4 SET
                Column(Modifier.padding(pad).fillMaxSize()) {
                    Header(vm)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        // the map stays composed under the other screens so it keeps its position and tiles
                        Box(Modifier.fillMaxSize().clipToBounds()) { MapPane(vm, holder) }
                        if (tab == 1) {
                            Column(Modifier.align(Alignment.TopStart).background(Color(0xDD000000)).padding(8.dp)) { TargetLine(vm, big = true) }
                        } else {
                            Column(
                                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                                    .verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                when (tab) {
                                    0 -> HuntTab(vm, holder)
                                    2 -> ScanTab(vm) { tab = 0 }
                                    3 -> SessionsTab(vm)
                                    else -> SettingsTab(vm, holder)
                                }
                            }
                        }
                    }
                    BottomNav(tab) { tab = it }
                }
            }
        }
    }
}

/** Big bottom navigation: filled = current screen. */
@Composable
fun BottomNav(tab: Int, onTab: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).border(1.dp, MaterialTheme.colorScheme.outline)) {
        listOf("HUNT", "MAP", "SCAN", "LOG", "SET").forEachIndexed { i, t ->
            Box(
                Modifier.weight(1f).height(56.dp)
                    .background(if (tab == i) ACCENT else MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { onTab(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(t, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                    color = if (tab == i) MaterialTheme.colorScheme.onPrimary else MUTED)
            }
        }
    }
}

/** Distance and bearing to the estimated emitter from the current GPS position, plus the next fix when [big]. */
@Composable
fun TargetLine(vm: MainViewModel, big: Boolean) {
    val data by vm.session.collectAsStateWithLifecycle()
    val fix by vm.gps.fix.collectAsStateWithLifecycle()
    val e = data.estimate
    val me = fix
    if (e == null || me == null) {
        Text(if (big) "NO ESTIMATE: RECORD 2+ FIXES FROM DIFFERENT PLACES" else "TARGET: --", fontSize = if (big) 14.sp else 15.sp, color = MUTED, fontWeight = FontWeight.Bold)
        return
    }
    val (d, b) = FieldGuide.distBearing(me.lat, me.lon, e.lat, e.lon)
    val dist = if (d >= 1000) String.format(Locale.US, "%.2f km", d / 1000) else String.format(Locale.US, "%.0f m", d)
    Text(
        "TARGET $dist @ ${b.roundToInt()}° ${FieldGuide.compass8(b)}",
        fontSize = if (big) 24.sp else 18.sp, fontWeight = FontWeight.Bold, color = OK,
    )
    if (big) {
        if (e.ellipseMajorM > 0) Text(String.format(Locale.US, "±%.0f m (95%%)", e.ellipseMajorM / 2), fontSize = 14.sp, color = MUTED)
        val next = FieldGuide.nextFix(data.points.map { Geo.Ray(it.lat, it.lon, it.bearingTrue) }, e.lat, e.lon, me.lat, me.lon)
        if (next != null && d > 40) {
            Text(
                String.format(Locale.US, "NEXT FIX %.0f m @ %.0f° %s", next.distFromMeM, next.bearingFromMe, FieldGuide.compass8(next.bearingFromMe)),
                fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ACCENT,
            )
        }
    }
}

// ------------------------------------------------------------------ header and map
/** Status indicator: a square "LED" and an upper-case readout in a bordered box. */
@Composable
fun Chip(text: String, color: Color) {
    Row(
        Modifier.border(1.dp, MaterialTheme.colorScheme.outline)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 11.sp, letterSpacing = 0.5.sp, maxLines = 1)
    }
}

/** Small section caption: upper case, spaced, muted. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier, fontSize = 11.sp, letterSpacing = 1.5.sp, color = MUTED, fontWeight = FontWeight.Bold)
}

@Composable
fun Header(vm: MainViewModel) {
    val gpsValid by vm.gpsValid.collectAsStateWithLifecycle()
    val gpsEnabled by vm.gps.enabled.collectAsStateWithLifecycle()
    val fix by vm.gps.fix.collectAsStateWithLifecycle()
    val connected by vm.sdrConnected.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val battery by vm.battery.collectAsStateWithLifecycle()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val acc = fix?.accM?.roundToInt()?.toString() ?: "?"
        val sats = fix?.sats?.toString() ?: "?"
        Chip(
            when {
                !gpsEnabled -> "GPS OFF"
                gpsValid -> "GPS ±${acc}m ${sats}SAT"
                else -> "GPS NO FIX"
            },
            if (gpsValid) OK else BAD,
        )
        Chip(if (connected) "SDR LINK" else "SDR OFF", if (connected) OK else BAD)
        Chip(
            when (mode) {
                Mode.Idle -> "STANDBY"
                Mode.Scan -> "SCANNING"
                Mode.Track -> "TRACKING"
            },
            NEUTRAL,
        )
        battery?.let { Chip("BAT $it%", if (it <= 15) BAD else if (it <= 30) WARN else NEUTRAL) }
    }
}

@Composable
fun MapPane(vm: MainViewModel, holder: MapHolder) {
    val ctx = LocalContext.current
    val data by vm.session.collectAsStateWithLifecycle()
    val fix by vm.gps.fix.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val offlineOnly = vm.f("map_offline") == "true"
    val style = runCatching { MapStyle.valueOf(vm.f("map_style")) }.getOrDefault(MapStyle.TOPO)
    val night = vm.f("night_mode") == "true"
    val now by produceState(Instant.now()) {
        while (true) {
            value = Instant.now()
            delay(1000)
        }
    }

    val mapView = remember {
        MapView(ctx).apply {
            setTileSource(MapStyle.TOPO.source())
            setMultiTouchControls(true)
            controller.setZoom(6.0)
            controller.setCenter(GeoPoint(40.4, -3.7))
            holder.map = this
        }
    }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            mapView.onDetach()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { m ->
                m.setUseDataConnection(!offlineOnly)
                val src = style.source()
                if (m.tileProvider.tileSource.name() != src.name()) m.setTileSource(src)
                m.overlayManager.tilesOverlay.setColorFilter(mapTint(night))
                val fx = fix
                if (!holder.centered && fx != null) {
                    m.controller.setZoom(15.0)
                    m.controller.setCenter(GeoPoint(fx.lat, fx.lon))
                    holder.centered = true
                }
                MapOverlays.render(m, ctx, data, fx, night)
            },
        )
        Text(
            style.attribution,
            Modifier.align(Alignment.BottomStart).background(Color(0xBB000000)).padding(2.dp),
            fontSize = 9.sp,
            color = Color(0xFFCCCCCC),
        )
        // Grid-style readout: UTC clock and own position
        Column(
            Modifier.align(Alignment.TopEnd).background(Color(0xBB000000)).padding(horizontal = 6.dp, vertical = 3.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(CLOCK.format(now), fontSize = 12.sp, color = ACCENT, fontWeight = FontWeight.Bold)
            Text(
                fix?.let { String.format(Locale.US, "%.5f  %.5f", it.lat, it.lon) } ?: "-- no position --",
                fontSize = 10.sp,
                color = Color(0xFFDDDDDD),
            )
        }
        TacButton(
            onClick = {
                val fx = fix
                if (fx != null) mapView.controller.animateTo(GeoPoint(fx.lat, fx.lon)) else vm.postMessage("No GPS fix")
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
        ) { Text("⌖", fontSize = 18.sp) }
    }
}

// ------------------------------------------------------------------ components
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NumField(
    vm: MainViewModel,
    key: String,
    label: String,
    modifier: Modifier = Modifier,
    type: KeyboardType = KeyboardType.Decimal,
) {
    val interaction = remember { MutableInteractionSource() }
    val value = vm.f(key)
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = ACCENT,
        focusedLabelColor = ACCENT,
        cursorColor = ACCENT,
        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    )
    BasicTextField(
        value = value,
        onValueChange = { vm.setField(key, it) },
        singleLine = true,
        textStyle = LocalTextStyle.current.merge(TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)),
        keyboardOptions = KeyboardOptions(keyboardType = type),
        cursorBrush = SolidColor(ACCENT),
        interactionSource = interaction,
        modifier = modifier,
        decorationBox = { inner ->
            // same look as OutlinedTextField, with tighter padding (the stock field is 56 dp tall)
            OutlinedTextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = inner,
                enabled = true,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                interactionSource = interaction,
                label = { Text(label, maxLines = 1, fontSize = 10.sp) },
                colors = colors,
                contentPadding = OutlinedTextFieldDefaults.contentPadding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 5.dp),
                container = {
                    OutlinedTextFieldDefaults.Container(
                        enabled = true,
                        isError = false,
                        interactionSource = interaction,
                        colors = colors,
                        shape = RoundedCornerShape(2.dp),
                    )
                },
            )
        },
    )
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun Spark(h: List<Float>, modifier: Modifier, line: Color, grid: Color) {
    Canvas(modifier) {
        // range-ring style grid, drawn even before there is data
        for (i in 0..4) {
            val y = size.height * i / 4f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        if (h.size < 2) return@Canvas
        var lo = h.min()
        var hi = h.max()
        if (hi - lo < 10f) {
            val m = (hi + lo) / 2
            lo = m - 5
            hi = m + 5
        }
        val path = Path()
        h.forEachIndexed { i, v ->
            val x = i.toFloat() / (h.size - 1) * size.width
            val y = size.height - (v - lo) / (hi - lo) * (size.height - 6f) - 3f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, line, style = Stroke(width = 4f))
    }
}

/**
 * Polar plot of signal strength by true heading (north up): the filled shape is the power the app has
 * seen while the antenna was swept, the amber needle is where the antenna points now, the green line is the peak.
 */
/** Rolling spectrum around the tuned channel (±128 kHz, newest row at the bottom): shows drift and neighbours. */
@Composable
fun Waterfall(spec: FloatArray?, modifier: Modifier) {
    val bins = Tracker.SPEC_BINS
    val rowsMax = 40
    val rows = remember { ArrayDeque<FloatArray>() }
    val bmp = remember { Bitmap.createBitmap(bins, rowsMax, Bitmap.Config.ARGB_8888) }
    var tick by remember { mutableIntStateOf(0) }
    val night = LocalTac.current.night
    if (spec != null && (rows.isEmpty() || rows.last() !== spec)) {
        rows.addLast(spec)
        while (rows.size > rowsMax) rows.removeFirst()
        val all = rows.flatMap { it.asIterable() }.sorted()
        val lo = all[all.size / 10]
        val hi = max(all[all.size - 1 - all.size / 200], lo + 15f)
        val px = IntArray(bins * rowsMax)
        val off = rowsMax - rows.size
        rows.forEachIndexed { r, row ->
            for (x in 0 until bins) {
                val v = ((row[x] - lo) / (hi - lo)).coerceIn(0f, 1f)
                val c = if (night) (v * 255).toInt().let { (0xFF shl 24) or (it shl 16) or ((it * 0.1f).toInt() shl 8) }
                else {
                    // black -> olive -> amber -> white
                    val rr = (255 * minOf(1f, v * 1.6f)).toInt()
                    val gg = (255 * (v * v * 1.2f).coerceIn(0f, 1f) * 0.85f + 20 * v).toInt().coerceIn(0, 255)
                    val bb = (255 * ((v - 0.75f) * 4f).coerceIn(0f, 1f)).toInt()
                    (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                }
                px[(off + r) * bins + x] = c
            }
        }
        bmp.setPixels(px, 0, bins, 0, 0, bins, rowsMax)
        tick++
    }
    val line = MaterialTheme.colorScheme.outline
    Canvas(modifier.border(1.dp, MaterialTheme.colorScheme.outline)) {
        @Suppress("UNUSED_EXPRESSION") tick
        drawImage(
            bmp.asImageBitmap(), dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.None,
        )
        drawLine(line, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = 1f)
    }
}

@Composable
fun PolarPlot(peak: PeakInfo?, headingDeg: Double?, modifier: Modifier) {
    val ring = MaterialTheme.colorScheme.outlineVariant
    val north = BAD
    val fill = OK
    val needle = ACCENT
    val peakColor = if (peak?.reliable == true) OK else WARN
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2f - 6f
        drawCircle(ring, r, c, style = Stroke(width = 1.5f))
        drawCircle(ring, r * 0.5f, c, style = Stroke(width = 1f))
        drawLine(ring, Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = 1f)
        drawLine(ring, Offset(c.x, c.y - r), Offset(c.x, c.y + r), strokeWidth = 1f)
        drawLine(north, Offset(c.x, c.y - r), Offset(c.x, c.y - r + 12f), strokeWidth = 4f)
        val prof = peak?.profile
        if (prof != null && prof.isNotEmpty()) {
            val vals = prof.filter { !it.isNaN() }
            if (vals.size >= 3) {
                val lo = vals.min()
                val hi = max(vals.max(), lo + 6.0) // at least 6 dB of range so noise does not look like a lobe
                val path = Path()
                var first = true
                val bins = prof.size
                val step = 360.0 / bins
                for (i in 0 until bins) {
                    val v = prof[i]
                    if (v.isNaN()) continue
                    val rad = Math.toRadians((i + 0.5) * step)
                    val rr = r * (0.12f + 0.88f * ((v - lo) / (hi - lo)).toFloat())
                    val pt = Offset(c.x + sin(rad).toFloat() * rr, c.y - cos(rad).toFloat() * rr)
                    if (first) { path.moveTo(pt.x, pt.y); first = false } else path.lineTo(pt.x, pt.y)
                }
                path.close()
                drawPath(path, fill.copy(alpha = 0.25f))
                drawPath(path, fill, style = Stroke(width = 3f))
            }
        }
        peak?.let {
            val rad = Math.toRadians(it.bearing)
            drawLine(
                peakColor, c,
                Offset(c.x + sin(rad).toFloat() * r, c.y - cos(rad).toFloat() * r), strokeWidth = 4f,
            )
        }
        headingDeg?.let {
            val rad = Math.toRadians(it)
            val tip = Offset(c.x + sin(rad).toFloat() * r, c.y - cos(rad).toFloat() * r)
            drawLine(needle, c, tip, strokeWidth = 6f)
            drawCircle(needle, 7f, tip)
        }
        drawCircle(needle, 4f, c)
    }
}

/** Compass rose: fixed lubber line on top, the rose turns so the current bearing is at the top. */
@Composable
fun CompassDial(deg: Double?, modifier: Modifier) {
    val ring = MaterialTheme.colorScheme.outline
    val tick = MUTED
    val north = BAD
    val accent = ACCENT
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2f - 4f
        drawCircle(ring, r, c, style = Stroke(width = 2f))
        rotate(-(deg ?: 0.0).toFloat(), c) {
            for (a in 0 until 360 step 30) {
                val cardinal = a % 90 == 0
                val len = if (cardinal) 12f else 6f
                val rad = Math.toRadians(a.toDouble())
                val dx = sin(rad).toFloat()
                val dy = -cos(rad).toFloat()
                drawLine(
                    if (a == 0) north else tick,
                    Offset(c.x + dx * (r - len), c.y + dy * (r - len)),
                    Offset(c.x + dx * r, c.y + dy * r),
                    strokeWidth = if (cardinal) 3f else 2f,
                )
            }
            // north marker: filled triangle just inside the ring
            val tri = Path().apply {
                moveTo(c.x, c.y - r + 14f)
                lineTo(c.x - 7f, c.y - r + 30f)
                lineTo(c.x + 7f, c.y - r + 30f)
                close()
            }
            drawPath(tri, north)
        }
        // lubber line (the antenna direction)
        drawLine(accent, Offset(c.x, c.y - r - 2f), Offset(c.x, c.y - r + 18f), strokeWidth = 5f)
        drawCircle(accent, 3f, c)
    }
}

private fun openDriver(ctx: Context, vm: MainViewModel) {
    val i = ctx.packageManager.getLaunchIntentForPackage("marto.rtl_tcp_andro")
    if (i == null) vm.postMessage("Could not find the \"SDR driver\" app") else ctx.startActivity(i)
}

private fun openInMaps(ctx: Context, lat: Double, lon: Double) {
    val uri = String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(Emitter)", lat, lon, lat, lon)
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        // no maps app installed: fine, the coordinates are on screen
    }
}

// ------------------------------------------------------------------ Scan tab
@Composable
fun Preset(vm: MainViewModel, label: String, from: String, to: String, modifier: Modifier) {
    TacOutlinedButton(
        onClick = {
            vm.setField("scan_start", from)
            vm.setField("scan_stop", to)
        },
        modifier = modifier,
    ) { Text(label.uppercase(), fontSize = 10.sp, maxLines = 2, lineHeight = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
}

@Composable
fun ScanTab(vm: MainViewModel, goTrack: () -> Unit) {
    val scan by vm.scan.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Preset(vm, "Yagi\n400–470", "400", "470", Modifier.weight(1f))
        Preset(vm, "PMR\n446", "446", "446.2", Modifier.weight(1f))
        Preset(vm, "70 cm\n430–440", "430", "440", Modifier.weight(1f))
        Preset(vm, "ISM\n433", "433", "434.8", Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        NumField(vm, "scan_start", "From (MHz)", Modifier.weight(1f))
        NumField(vm, "scan_stop", "To (MHz)", Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        NumField(vm, "scan_dur", "Duration (s)", Modifier.weight(1f))
        NumField(vm, "scan_thr", "SNR threshold (dB)", Modifier.weight(1f))
        NumField(vm, "scan_gain", "Gain (dB)", Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TacButton(onClick = { vm.startScan() }, enabled = mode != Mode.Scan, modifier = Modifier.weight(1f)) {
            Text("START SCAN")
        }
        TacOutlinedButton(onClick = { vm.stopAll() }, enabled = mode == Mode.Scan, modifier = Modifier.weight(1f)) {
            Text("STOP")
        }
    }
    LinearProgressIndicator(progress = { scan.progress }, modifier = Modifier.fillMaxWidth())
    Text(
        if (scan.sweeps > 0 || scan.running) {
            "${if (scan.running) "Scanning" else "Finished"} · ${scan.sweeps} sweeps · " +
                "${scan.elapsedS.roundToInt()}/${scan.durationS.roundToInt()} s · ${scan.results.size} emitters"
        } else {
            "No results yet. Connect the SDR in the Track tab and start a scan."
        },
        fontSize = 13.sp,
        color = MUTED,
    )
    Row(Modifier.fillMaxWidth()) {
        Label("MHz", Modifier.weight(1.3f))
        Label("dB", Modifier.weight(0.8f))
        Label("SNR", Modifier.weight(0.8f))
        Label("Activity", Modifier.weight(0.9f))
        Text("", Modifier.weight(1.2f))
    }
    scan.results.forEach { r ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1.3f)) {
                Text(String.format(Locale.US, "%.4f", r.freqHz / 1e6), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ACCENT)
                val nm = vm.foxName(r.freqHz / 1e6)
                if (nm.isNotBlank()) Text(nm, fontSize = 10.sp, color = MUTED, maxLines = 1)
            }
            Text("${r.powerDb.roundToInt()}", Modifier.weight(0.8f), fontSize = 14.sp)
            Text("${r.snrDb.roundToInt()}", Modifier.weight(0.8f), fontSize = 14.sp)
            Text("${(r.activity * 100).roundToInt()}%", Modifier.weight(0.9f), fontSize = 14.sp)
            TextButton(
                onClick = {
                    vm.setField("trk_freq", String.format(Locale.US, "%.4f", r.freqHz / 1e6))
                    goTrack()
                },
                modifier = Modifier.weight(1.2f),
            ) { Text("TRACK", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

// ------------------------------------------------------------------ Hunt screen
/** The screen used while walking: everything that is touched during a hunt, big, with no scrolling needed. */
@Composable
fun HuntTab(vm: MainViewModel, holder: MapHolder) {
    val live by vm.live.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val heading by vm.heading.collectAsStateWithLifecycle()
    val peak by vm.peak.collectAsStateWithLifecycle()
    val connected by vm.sdrConnected.collectAsStateWithLifecycle()
    val sdrInfo by vm.sdrInfo.collectAsStateWithLifecycle()
    val gainNow by vm.gainDb.collectAsStateWithLifecycle()
    val listening by vm.listen.collectAsStateWithLifecycle()
    val atMinGain by vm.atMinGain.collectAsStateWithLifecycle()
    val trend by vm.trend.collectAsStateWithLifecycle()
    val sound by vm.sound.collectAsStateWithLifecycle()
    val data by vm.session.collectAsStateWithLifecycle()
    val useSensor = vm.f("use_sensor") == "true"
    var more by rememberSaveable { mutableStateOf(false) }
    val tracking = mode == Mode.Track

    // one main action: connect -> start -> stop
    val mainLabel = when {
        !connected -> "CONNECT SDR"
        !tracking -> "START TRACK  ${vm.f("trk_freq")} MHz"
        else -> "STOP"
    }
    val mainClick = { when { !connected -> vm.connect(); !tracking -> vm.startTrack(); else -> vm.stopAll() } }
    if (tracking) TacOutlinedButton(onClick = mainClick, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(mainLabel, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
    else TacButton(onClick = mainClick, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(mainLabel, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
    if (!connected) Text(sdrInfo, fontSize = 13.sp, color = MUTED)

    // frequency and trend
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(vm.f("trk_freq").ifBlank { "---" } + " MHz", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = ACCENT)
            val nm = vm.foxName(vm.f("trk_freq").replace(',', '.').toDoubleOrNull())
            if (nm.isNotBlank()) Text(nm, fontSize = 13.sp, color = MUTED, maxLines = 1)
        }
        Text(
            when (trend) { Trend.HOTTER -> "▲ HOTTER"; Trend.COLDER -> "▼ COLDER"; Trend.STEADY -> "● STEADY"; null -> "" },
            fontSize = 24.sp, fontWeight = FontWeight.Bold,
            color = when (trend) { Trend.HOTTER -> OK; Trend.COLDER -> BAD; else -> MUTED },
        )
    }

    // signal
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    (live.smoothDb ?: live.powerDb)?.let { f1(it) } ?: "--",
                    fontSize = 72.sp, fontWeight = FontWeight.Bold, lineHeight = 74.sp,
                    color = if (live.clipping) BAD else ACCENT, modifier = Modifier.weight(1f),
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text("SNR", fontSize = 12.sp, color = MUTED)
                    Text(
                        live.snrDb?.let { "${it.roundToInt()}" } ?: "--", fontSize = 34.sp, fontWeight = FontWeight.Bold,
                        color = if ((live.snrDb ?: 0.0) >= 6.0) OK else MUTED,
                    )
                }
            }
            LevelBar(live.smoothDb ?: live.powerDb, live.peakDb, live.clipping, Modifier.fillMaxWidth().height(26.dp))
            if (live.clipping) {
                Text(
                    if (atMinGain) "CLIPPING: MIN GAIN. ADD ATTENUATOR OR MOVE BACK" else "CLIPPING: PRESS − ATT",
                    color = BAD, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
    }

    // bearings
    Panel(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (useSensor) {
                Column(Modifier.weight(1f)) {
                    Label("Heading true")
                    Text(
                        heading.trueDeg?.let { String.format(Locale.US, "%03d°", it.roundToInt() % 360) } ?: "---",
                        fontSize = 44.sp, fontWeight = FontWeight.Bold, color = ACCENT,
                    )
                    if (heading.accuracy < 2) Text("CALIBRATE COMPASS", fontSize = 12.sp, color = WARN, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f)) {
                    Label("Peak")
                    Text(
                        peak?.bearing?.let { String.format(Locale.US, "%03d°", it.roundToInt() % 360) } ?: "---",
                        fontSize = 44.sp, fontWeight = FontWeight.Bold, color = if (peak?.reliable == true) OK else MUTED,
                    )
                    Text(
                        when {
                            peak == null -> "SWEEP 360°"
                            peak?.multipath == true -> "MULTIPATH?"
                            peak?.reliable == true -> "+${f1(peak!!.prominenceDb)} dB"
                            else -> "SWEEP WIDER"
                        },
                        fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (peak?.multipath == true) WARN else MUTED,
                    )
                }
                PolarPlot(peak, heading.trueDeg, Modifier.size(96.dp))
            } else {
                NumField(vm, "trk_bearing", "Bearing from your compass (° magnetic)", Modifier.weight(1f))
            }
        }
    }

    // gain
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        TacOutlinedButton(onClick = { vm.stepGain(-1) }, modifier = Modifier.weight(1f).height(56.dp)) {
            Text("− ATT", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Panel(Modifier.weight(1.1f).height(56.dp)) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("GAIN", fontSize = 11.sp, color = MUTED)
                Text(gainNow?.let { "${f1(it)} dB" } ?: "AGC", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (live.clipping) BAD else ACCENT)
            }
        }
        TacOutlinedButton(onClick = { vm.stepGain(1) }, modifier = Modifier.weight(1f).height(56.dp)) {
            Text("+ GAIN", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }

    // toggles: filled = on
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val auto = vm.f("auto_att") == "true"
        @Composable fun toggle(on: Boolean, label: String, enabled: Boolean = true, click: () -> Unit) {
            val body: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = { Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
            if (on) TacButton(onClick = click, enabled = enabled, modifier = Modifier.weight(1f).height(52.dp), content = body)
            else TacOutlinedButton(onClick = click, enabled = enabled, modifier = Modifier.weight(1f).height(52.dp), content = body)
        }
        toggle(auto, "AUTO ATT") { vm.setField("auto_att", (!auto).toString()) }
        toggle(listening, if (listening) "LISTENING" else "LISTEN", tracking || listening) { vm.setListen(!listening) }
        toggle(sound, "TONE") { vm.setSound(!sound) }
    }

    // record
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TacButton(onClick = { vm.recordPoint() }, enabled = tracking, modifier = Modifier.weight(1.3f).height(76.dp)) {
            Text("RECORD FIX", fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        TacOutlinedButton(
            onClick = { vm.recordPoint(usePeak = true) },
            enabled = tracking && peak?.reliable == true,
            modifier = Modifier.weight(1f).height(76.dp),
        ) { Text("FIX AT PEAK", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("FIXES ${data.points.size}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MUTED)
            TargetLine(vm, big = false)
        }
        TacOutlinedButton(onClick = { more = !more }, modifier = Modifier.height(48.dp)) {
            Text(if (more) "DETAILS ▴" else "DETAILS ▾", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
    if (more) DetailsTab(vm, holder)
}

/** Horizontal level bar over -60..0 dBFS with a peak-hold tick. */
@Composable
fun LevelBar(db: Double?, peakDb: Double?, clipping: Boolean, modifier: Modifier) {
    val fill = if (clipping) BAD else OK
    val grid = MaterialTheme.colorScheme.outline
    val tick = ACCENT
    Canvas(modifier.border(1.dp, grid)) {
        fun x(v: Double) = (((v + 60.0) / 60.0).coerceIn(0.0, 1.0) * size.width).toFloat()
        if (db != null) drawRect(fill, Offset(0f, 0f), androidx.compose.ui.geometry.Size(x(db), size.height))
        for (i in 1 until 6) drawLine(grid, Offset(size.width * i / 6, 0f), Offset(size.width * i / 6, size.height), strokeWidth = 1f)
        if (peakDb != null) drawLine(tick, Offset(x(peakDb), 0f), Offset(x(peakDb), size.height), strokeWidth = 5f)
    }
}

// ------------------------------------------------------------------ Details (below the Hunt screen)
@Composable
fun DetailsTab(vm: MainViewModel, holder: MapHolder) {
    val ctx = LocalContext.current
    val live by vm.live.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val heading by vm.heading.collectAsStateWithLifecycle()
    val data by vm.session.collectAsStateWithLifecycle()
    val sound by vm.sound.collectAsStateWithLifecycle()
    val connected by vm.sdrConnected.collectAsStateWithLifecycle()
    val sdrInfo by vm.sdrInfo.collectAsStateWithLifecycle()
    val gainNow by vm.gainDb.collectAsStateWithLifecycle()
    val listening by vm.listen.collectAsStateWithLifecycle()
    val atMinGain by vm.atMinGain.collectAsStateWithLifecycle()
    val trend by vm.trend.collectAsStateWithLifecycle()
    val burst by vm.burst.collectAsStateWithLifecycle()
    val drift by vm.driftHz.collectAsStateWithLifecycle()
    val useSensor = vm.f("use_sensor") == "true"

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TacButton(onClick = { if (connected) vm.disconnect() else vm.connect() }, modifier = Modifier.weight(1f)) {
            Text(if (connected) "DISCONNECT SDR" else "CONNECT SDR", fontSize = 13.sp)
        }
        TacOutlinedButton(onClick = { openDriver(ctx, vm) }, modifier = Modifier.weight(1f)) {
            Text("OPEN SDR DRIVER", fontSize = 13.sp)
        }
    }
    Text(if (connected) sdrInfo else "Disconnected", fontSize = 12.sp, color = MUTED)

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        NumField(vm, "trk_freq", "Frequency (MHz)", Modifier.weight(1.4f))
        NumField(vm, "trk_bw", "Channel (kHz)", Modifier.weight(1f))
        NumField(vm, "trk_gain", "Gain (dB)", Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TacButton(onClick = { vm.startTrack() }, enabled = mode != Mode.Track, modifier = Modifier.weight(1f)) {
            Text("START TRACK", fontSize = 13.sp)
        }
        TacOutlinedButton(onClick = { vm.stopAll() }, enabled = mode == Mode.Track, modifier = Modifier.weight(1f)) {
            Text("STOP", fontSize = 13.sp)
        }
        TacOutlinedButton(onClick = { vm.setSound(!sound) }, modifier = Modifier.weight(1f)) {
            Text(if (sound) "TONE ON" else "TONE OFF", fontSize = 13.sp)
        }
    }

    val trkMhz = vm.f("trk_freq").replace(',', '.').toDoubleOrNull()
    if (trkMhz != null) NumField(vm, vm.foxKey(trkMhz), "Fox name / notes (saved for this frequency)", Modifier.fillMaxWidth(), KeyboardType.Text)

    val peak by vm.peak.collectAsStateWithLifecycle()
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Label("Signal history · spectrum ±128 kHz")
            Spark(live.history, Modifier.fillMaxWidth().height(40.dp), OK, MaterialTheme.colorScheme.outlineVariant)
            Waterfall(live.spectrum, Modifier.fillMaxWidth().height(56.dp))
        }
    }

    // Fox status: hotter/colder, burst timing, carrier drift
    if (mode == Mode.Track) {
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (trend) {
                            Trend.HOTTER -> "▲ HOTTER"
                            Trend.COLDER -> "▼ COLDER"
                            Trend.STEADY -> "● STEADY"
                            null -> "· · ·"
                        },
                        Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = when (trend) { Trend.HOTTER -> OK; Trend.COLDER -> BAD; else -> MUTED },
                    )
                    val coach = vm.f("coach") == "true"
                    if (coach) TacButton(onClick = { vm.setCoach(false) }) { Text("VOICE ON", fontSize = 12.sp) }
                    else TacOutlinedButton(onClick = { vm.setCoach(true) }) { Text("VOICE OFF", fontSize = 12.sp) }
                }
                burst?.let { b ->
                    Text(
                        "TIMED FOX · every ${b.periodS.roundToInt()} s" + (if (b.onS > 0) ", on ~${b.onS.roundToInt()} s" else "") +
                            " · " + (if (b.onAir) "ON AIR" else "next in ${b.nextInS?.roundToInt() ?: 0} s"),
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (b.onAir) OK else WARN,
                    )
                }
                drift?.let { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            String.format(Locale.US, "CARRIER %+.1f kHz from the tuned frequency", d / 1000.0),
                            Modifier.weight(1f), fontSize = 12.sp, color = if (kotlin.math.abs(d) > 1500) WARN else MUTED,
                        )
                        if (kotlin.math.abs(d) > 1500) TacOutlinedButton(onClick = { vm.retuneToDrift() }) { Text("RETUNE", fontSize = 12.sp) }
                    }
                }
            }
        }
    }

    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Label("Antenna bearing · true")
            if (useSensor) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        heading.trueDeg?.let { String.format(Locale.US, "%03d°", it.roundToInt() % 360) }
                            ?: if (heading.hasSensor) "-- (too vertical)" else "no sensor",
                        Modifier.weight(1f),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = ACCENT,
                    )
                    CompassDial(heading.trueDeg, Modifier.size(62.dp))
                }
                val acc = when (heading.accuracy) {
                    3 -> "high"; 2 -> "medium"; 1 -> "low"; else -> "unreliable"
                }
                Text(
                    "magnetic ${heading.magnetic?.let { "${it.roundToInt()}°" } ?: "--"} · " +
                        "declination ${f1(heading.declination)}° · calibration $acc",
                    fontSize = 12.sp,
                    color = MUTED,
                )
                if (heading.accuracy < 2) {
                    Text("Calibrate the compass by moving the phone in a figure 8", color = WARN, fontSize = 12.sp)
                }
            } else {
                NumField(vm, "trk_bearing", "Bearing from your compass (° magnetic)", Modifier.fillMaxWidth())
            }
        }
    }
    SwitchRow("Use the phone compass", useSensor) { vm.setField("use_sensor", it.toString()) }

    if (useSensor) {
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val pk = peak
                Text("SWEEP ASSIST", fontSize = 12.sp, color = MUTED, letterSpacing = 1.sp)
                if (pk == null) {
                    Text("Sweep the antenna slowly through a full turn to find the strongest direction.", fontSize = 13.sp, color = MUTED)
                } else {
                    Text(
                        "PEAK ${pk.bearing.roundToInt()}° true · +${f1(pk.prominenceDb)} dB over average",
                        fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (pk.reliable) OK else WARN,
                    )
                    if (pk.multipath) {
                        Text(
                            "⚠ MULTIPATH? A second lobe at ${pk.secondBearing?.roundToInt()}° is within 3 dB: reflections likely. Take fixes from other places and trust the crossing.",
                            fontSize = 12.sp, color = WARN, fontWeight = FontWeight.Bold,
                        )
                    } else if (pk.lobeWidthDeg > 120) {
                        Text("Wide lobe (${pk.lobeWidthDeg.roundToInt()}°): lower the gain to sharpen the peak.", fontSize = 12.sp, color = WARN)
                    }
                    Text(
                        if (pk.reliable) "Point the Yagi at ${pk.bearing.roundToInt()}° and record, or record at the peak directly."
                        else "Not reliable yet (${pk.coverageDeg.roundToInt()}° covered): sweep a wider arc past the lobe.",
                        fontSize = 12.sp, color = MUTED,
                    )
                }
            }
        }
    }

    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val sqlDb = vm.f("listen_sql").replace(',', '.').toDoubleOrNull() ?: 6.0
            val rx = (live.snrDb ?: -99.0) > sqlDb
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("Receiver · ${vm.f("trk_freq")} MHz", Modifier.weight(1f))
                if (listening) Text(if (rx) "● RX" else "○ SQUELCH", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (rx) OK else MUTED)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TacButton(
                    onClick = { vm.setListen(!listening) },
                    enabled = mode == Mode.Track || listening,
                    modifier = Modifier.weight(1.4f),
                ) { Text(if (listening) "■ STOP LISTENING" else "▶ LISTEN", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                TacOutlinedButton(
                    onClick = {
                        val order = listOf("NFM", "AM", "USB", "LSB", "CW")
                        vm.setField("listen_mode", order[(order.indexOf(vm.f("listen_mode")) + 1) % order.size])
                    },
                    modifier = Modifier.weight(0.6f),
                ) { Text(vm.f("listen_mode"), fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                NumField(vm, "listen_sql", "Squelch (SNR dB)", Modifier.weight(1f))
                NumField(vm, "listen_vol", "Volume (%)", Modifier.weight(1f))
            }
        }
    }

    EstimateCard(vm, data, holder)
    PointsList(vm, data)
}

@Composable
fun EstimateCard(vm: MainViewModel, data: SessionData, holder: MapHolder) {
    if (data.points.isEmpty()) return
    val ctx = LocalContext.current
    val fix by vm.gps.fix.collectAsStateWithLifecycle()
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val e = data.estimate
            if (e == null) {
                Text("Estimate: ${data.estimateError ?: ""}", fontSize = 14.sp)
            } else {
                Label("Estimated emitter")
                Text(String.format(Locale.US, "%.6f, %.6f", e.lat, e.lon), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = ACCENT)
                Text(
                    String.format(
                        Locale.US, "%d rays · residual %.0f m · max angle %.0f° · baseline %.0f m",
                        e.n, e.rmsM, e.bestAngleDeg, e.baselineM,
                    ),
                    fontSize = 13.sp,
                    color = MUTED,
                )
                if (e.ellipseMajorM > 0) {
                    Text(
                        String.format(Locale.US, "95%% confidence: %.0f × %.0f m (ellipse on the map)", e.ellipseMajorM, e.ellipseMinorM),
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (e.ellipseMajorM < 100) OK else WARN,
                    )
                }
                e.warnings.forEach { Text("⚠ $it", color = WARN, fontSize = 13.sp) }
                fix?.let { me ->
                    val (d, b) = FieldGuide.distBearing(me.lat, me.lon, e.lat, e.lon)
                    Text(
                        String.format(Locale.US, "TARGET %s at %.0f° %s", if (d >= 1000) "%.2f km".format(Locale.US, d / 1000) else "%.0f m".format(Locale.US, d), b, FieldGuide.compass8(b)),
                        fontSize = 16.sp, fontWeight = FontWeight.Bold, color = OK,
                    )
                    val next = FieldGuide.nextFix(data.points.map { Geo.Ray(it.lat, it.lon, it.bearingTrue) }, e.lat, e.lon, me.lat, me.lon)
                    if (next != null && d > 40) {
                        Text(
                            String.format(
                                Locale.US, "Next fix: %.0f m at %.0f° %s (crosses the earlier views at %.0f°)",
                                next.distFromMeM, next.bearingFromMe, FieldGuide.compass8(next.bearingFromMe), next.angleDeg,
                            ),
                            fontSize = 12.sp, color = MUTED,
                        )
                        TacOutlinedButton(onClick = { openInMaps(ctx, next.lat, next.lon) }, modifier = Modifier.fillMaxWidth()) {
                            Text("OPEN NEXT FIX IN MAPS", fontSize = 12.sp)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TacOutlinedButton(
                        onClick = {
                            holder.map?.controller?.setZoom(16.0)
                            holder.map?.controller?.animateTo(GeoPoint(e.lat, e.lon))
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("CENTER ON MAP", fontSize = 12.sp) }
                    TacOutlinedButton(onClick = { openInMaps(ctx, e.lat, e.lon) }, modifier = Modifier.weight(1f)) {
                        Text("OPEN IN MAPS", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun PointsList(vm: MainViewModel, data: SessionData) {
    var toDelete by remember { mutableStateOf<Long?>(null) }
    if (data.points.isNotEmpty()) Label("Fixes in this session")
    data.points.forEachIndexed { i, p ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "#${i + 1} · ${p.bearingTrue.roundToInt()}° · ${f1(p.powerDb)} dB · SNR ${p.snrDb?.roundToInt() ?: "?"} · Q ${FixQuality.percent(p.snrDb, p.accM)}%" +
                        if (p.tsSrc == "import") " · imported" else "",
                    fontSize = 14.sp,
                )
                Text(
                    String.format(Locale.US, "%.5f, %.5f · %s UTC", p.lat, p.lon, p.ts.substringAfter('T').take(8)),
                    fontSize = 11.sp,
                    color = MUTED,
                )
            }
            TextButton(onClick = { toDelete = p.id }) { Text("✕") }
        }
    }
    val id = toDelete
    if (id != null) {
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete this point?") },
            confirmButton = {
                TextButton(onClick = { vm.deletePoint(id); toDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

// ------------------------------------------------------------------ Sessions tab
@Composable
fun SessionsTab(vm: MainViewModel) {
    val ctx = LocalContext.current
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val data by vm.session.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val cur = data.session

    Text("Each session stores, per point: GPS position, time, frequency, power, SNR, gain and bearing.", fontSize = 12.sp, color = MUTED)
    Box {
        TacOutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (cur != null) "#${cur.id} · ${String.format(Locale.US, "%.4f", cur.freqHz / 1e6)} MHz · ${data.points.size} pts"
                else "No sessions",
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            sessions.forEach { s ->
                DropdownMenuItem(
                    text = {
                        Text("#${s.id} · ${String.format(Locale.US, "%.4f", s.freqHz / 1e6)} MHz · ${s.n} pts · ${s.created.take(16).replace('T', ' ')}")
                    },
                    onClick = {
                        vm.selectSession(s.id)
                        open = false
                    },
                )
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TacOutlinedButton(onClick = { vm.newSession() }, modifier = Modifier.weight(1f)) {
            Text("NEW HUNT (TRACK FREQ.)", fontSize = 12.sp)
        }
        TacOutlinedButton(onClick = { confirm = true }, enabled = cur != null, modifier = Modifier.weight(1f)) {
            Text("DELETE SESSION", fontSize = 12.sp)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TacButton(onClick = { vm.export(ctx, MainViewModel.Format.CSV) }, enabled = cur != null, modifier = Modifier.weight(1f)) { Text("CSV", fontSize = 12.sp) }
        TacButton(onClick = { vm.export(ctx, MainViewModel.Format.GEOJSON) }, enabled = cur != null, modifier = Modifier.weight(1f)) { Text("GEOJSON", fontSize = 12.sp) }
        TacButton(onClick = { vm.export(ctx, MainViewModel.Format.KML) }, enabled = cur != null, modifier = Modifier.weight(1f)) { Text("KML", fontSize = 12.sp) }
        TacButton(onClick = { vm.export(ctx, MainViewModel.Format.GPX) }, enabled = cur != null, modifier = Modifier.weight(1f)) { Text("GPX", fontSize = 12.sp) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importFixes(ctx, uri) }
    TacOutlinedButton(onClick = { picker.launch(arrayOf("text/*", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) {
        Text("IMPORT FIXES FROM ANOTHER OPERATOR (CSV)", fontSize = 12.sp)
    }
    if (confirm && cur != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete session #${cur.id} and all its points?") },
            confirmButton = {
                TextButton(onClick = { vm.deleteSession(cur.id); confirm = false }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

// ------------------------------------------------------------------ Settings tab
@Composable
fun SettingsTab(vm: MainViewModel, holder: MapHolder) {
    val ctx = LocalContext.current

    Text("SDR driver connection (rtl_tcp)", fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        NumField(vm, "sdr_host", "Host", Modifier.weight(1.5f), KeyboardType.Uri)
        NumField(vm, "sdr_port", "Port", Modifier.weight(1f), KeyboardType.Number)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        NumField(vm, "sdr_ppm", "Correction (ppm)", Modifier.weight(1f))
        NumField(vm, "sdr_settle", "Settle time (ms)", Modifier.weight(1f))
    }
    Text(
        "Settle time: how long data is discarded after retuning. If the scan shows ghost " +
            "emitters from the previous hop, raise it; for faster sweeps, lower it.",
        fontSize = 12.sp,
        color = MUTED,
    )

    Text("Phone mounting on the Yagi", fontWeight = FontWeight.Bold)
    MountAxis.entries.forEach { a ->
        Row(
            Modifier.fillMaxWidth().clickable { vm.setField("mount_axis", a.name) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = vm.f("mount_axis") == a.name, onClick = { vm.setField("mount_axis", a.name) })
            Text(a.label, fontSize = 13.sp)
        }
    }
    NumField(vm, "heading_offset", "Bearing offset (°, + clockwise)", Modifier.fillMaxWidth())
    Text(
        "If the phone is not perfectly aligned with the boom, point the Yagi at something with a known " +
            "bearing and enter the difference here (true bearing − displayed bearing).",
        fontSize = 12.sp,
        color = MUTED,
    )
    SwitchRow("Volume keys = record point", vm.f("use_volume") == "true") { vm.setField("use_volume", it.toString()) }
    SwitchRow("Offline-only map (no tile downloads)", vm.f("map_offline") == "true") { vm.setField("map_offline", it.toString()) }

    Text("Map style", fontWeight = FontWeight.Bold)
    MapStyle.entries.forEach { st ->
        Row(
            Modifier.fillMaxWidth().clickable { vm.setField("map_style", st.name) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = vm.f("map_style") == st.name, onClick = { vm.setField("map_style", st.name) })
            Text(st.label, fontSize = 13.sp)
        }
    }
    Text(
        "Tiles are cached per style: download the offline area again after changing it.",
        fontSize = 12.sp,
        color = MUTED,
    )
    SwitchRow("Night mode (red display, keeps night vision)", vm.f("night_mode") == "true") { vm.setField("night_mode", it.toString()) }

    Text("Power display", fontWeight = FontWeight.Bold)
    SwitchRow("Gain-compensated power (compare readings at different gains)", vm.f("pwr_norm") == "true") { vm.setField("pwr_norm", it.toString()) }
    NumField(vm, "pwr_offset", "Calibration offset (dB, e.g. from a known source)", Modifier.fillMaxWidth())
    Text(
        "Shows power as if the tuner gain were 30 dB. It assumes the R820T gain steps are accurate, so treat it as approximate.",
        fontSize = 12.sp,
        color = MUTED,
    )

    Text("Offline map", fontWeight = FontWeight.Bold)
    Text(
        "Frame your work area on the map and tap download. Tiles are stored on the phone. " +
            "Cap: 2000 tiles (OpenStreetMap usage policy).",
        fontSize = 12.sp,
        color = MUTED,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        NumField(vm, "dl_zmin", "Min zoom", Modifier.weight(1f), KeyboardType.Number)
        NumField(vm, "dl_zmax", "Max zoom", Modifier.weight(1f), KeyboardType.Number)
    }
    val dl by vm.download.collectAsStateWithLifecycle()
    dl?.let { (done, total) ->
        Text("Downloading $done / $total tiles", fontSize = 12.sp, color = ACCENT)
        LinearProgressIndicator(progress = { done.toFloat() / total }, Modifier.fillMaxWidth(), color = ACCENT)
    }
    val fix by vm.gps.fix.collectAsStateWithLifecycle()
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TacButton(onClick = { downloadArea(ctx, holder, vm, null) }, modifier = Modifier.weight(1f)) {
            Text("VISIBLE AREA", fontSize = 12.sp)
        }
        TacButton(onClick = { downloadArea(ctx, holder, vm, fix) }, enabled = fix != null, modifier = Modifier.weight(1f)) {
            Text("AROUND ME (3 km)", fontSize = 12.sp)
        }
    }
}

private fun downloadArea(ctx: Context, holder: MapHolder, vm: MainViewModel, around: GpsFix?) {
    val map = holder.map
    if (map == null) {
        vm.postMessage("The map is not ready yet")
        return
    }
    val zmin = vm.f("dl_zmin").toIntOrNull()
    val zmax = vm.f("dl_zmax").toIntOrNull()
    val maxZ = map.tileProvider.tileSource.maximumZoomLevel
    if (zmin == null || zmax == null || zmin < 1 || zmax > maxZ || zmin > zmax) {
        vm.postMessage("Invalid zoom (1–$maxZ for this map style, min ≤ max)")
        return
    }
    val box = if (around == null) map.boundingBox else {
        val dLat = 1500.0 / 111_320.0
        val dLon = dLat / kotlin.math.cos(Math.toRadians(around.lat))
        org.osmdroid.util.BoundingBox(around.lat + dLat, around.lon + dLon, around.lat - dLat, around.lon - dLon)
    }
    val cm = CacheManager(map)
    var zTop = zmax
    // around the user's position the area is fixed, so lower the top zoom until it fits the tile cap
    while (around != null && zTop > zmin && cm.possibleTilesInArea(box, zmin, zTop) > 2000) zTop--
    val total = cm.possibleTilesInArea(box, zmin, zTop)
    if (total > 2000) {
        vm.postMessage("Too many tiles ($total > 2000): zoom the map in or lower the max zoom")
        return
    }
    vm.postMessage("Downloading $total tiles…")
    cm.downloadAreaAsync(
        ctx, box, zmin, zTop,
        object : CacheManager.CacheManagerCallback {
            override fun onTaskComplete() { vm.clearDownload(); vm.postMessage("Map downloaded") }
            override fun onTaskFailed(errors: Int) { vm.clearDownload(); vm.postMessage("Download finished with $errors errors") }
            override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) = vm.setDownload(progress, total)
            override fun downloadStarted() = vm.setDownload(0, total)
            override fun setPossibleTilesInArea(total: Int) {}
        },
    )
}
