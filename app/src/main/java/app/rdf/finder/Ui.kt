package app.rdf.finder

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
import kotlin.math.roundToInt

private val OK = Color(0xFF1B4D2A)
private val BAD = Color(0xFF5C1F1D)
private val NEUTRAL = Color(0xFF263241)
private val WARN = Color(0xFFD29922)

/** Shared reference to the MapView (used to recenter and download areas). */
class MapHolder {
    var map: MapView? = null
    var centered = false
}

private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)

@Composable
fun RdfApp(vm: MainViewModel) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val holder = remember { MapHolder() }
            val snackbar = remember { SnackbarHostState() }
            LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                containerColor = MaterialTheme.colorScheme.background,
            ) { pad ->
                var tab by rememberSaveable { mutableIntStateOf(0) }
                Column(Modifier.padding(pad).fillMaxSize()) {
                    Header(vm)
                    Box(Modifier.weight(0.36f).fillMaxWidth()) { MapPane(vm, holder) }
                    TabRow(selectedTabIndex = tab) {
                        listOf("Scan", "Track", "Sessions", "Settings").forEachIndexed { i, t ->
                            Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontSize = 13.sp) })
                        }
                    }
                    Column(
                        Modifier.weight(0.64f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        when (tab) {
                            0 -> ScanTab(vm) { tab = 1 }
                            1 -> TrackTab(vm, holder)
                            2 -> SessionsTab(vm)
                            else -> SettingsTab(vm, holder)
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ header and map
@Composable
fun Chip(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(10.dp), color = color) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), fontSize = 12.sp)
    }
}

@Composable
fun Header(vm: MainViewModel) {
    val gpsValid by vm.gpsValid.collectAsStateWithLifecycle()
    val fix by vm.gps.fix.collectAsStateWithLifecycle()
    val connected by vm.sdrConnected.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val acc = fix?.accM?.roundToInt()?.toString() ?: "?"
        val sats = fix?.sats?.toString() ?: "?"
        Chip(if (gpsValid) "GPS ±$acc m · $sats sat" else "GPS: no fix", if (gpsValid) OK else BAD)
        Chip(if (connected) "SDR connected" else "SDR disconnected", if (connected) OK else BAD)
        Chip(
            when (mode) {
                Mode.Idle -> "idle"
                Mode.Scan -> "scanning"
                Mode.Track -> "tracking"
            },
            NEUTRAL,
        )
    }
}

@Composable
fun MapPane(vm: MainViewModel, holder: MapHolder) {
    val ctx = LocalContext.current
    val data by vm.session.collectAsStateWithLifecycle()
    val fix by vm.gps.fix.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val offlineOnly = vm.f("map_offline") == "true"

    val mapView = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
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
                val fx = fix
                if (!holder.centered && fx != null) {
                    m.controller.setZoom(15.0)
                    m.controller.setCenter(GeoPoint(fx.lat, fx.lon))
                    holder.centered = true
                }
                MapOverlays.render(m, ctx, data, fx)
            },
        )
        Text(
            "© OpenStreetMap contributors",
            Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(2.dp),
            fontSize = 10.sp,
        )
        Button(
            onClick = {
                val fx = fix
                if (fx != null) mapView.controller.animateTo(GeoPoint(fx.lat, fx.lon)) else vm.postMessage("No GPS fix")
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
        ) { Text("◎") }
    }
}

// ------------------------------------------------------------------ components
@Composable
fun NumField(
    vm: MainViewModel,
    key: String,
    label: String,
    modifier: Modifier = Modifier,
    type: KeyboardType = KeyboardType.Decimal,
) {
    OutlinedTextField(
        value = vm.f(key),
        onValueChange = { vm.setField(key, it) },
        label = { Text(label, maxLines = 1, fontSize = 11.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        modifier = modifier,
    )
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun Spark(h: List<Float>, modifier: Modifier) {
    Canvas(modifier) {
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
        drawPath(path, Color(0xFF4CAF50), style = Stroke(width = 4f))
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
    OutlinedButton(
        onClick = {
            vm.setField("scan_start", from)
            vm.setField("scan_stop", to)
        },
        modifier = modifier,
    ) { Text(label, fontSize = 12.sp, maxLines = 1) }
}

@Composable
fun ScanTab(vm: MainViewModel, goTrack: () -> Unit) {
    val scan by vm.scan.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Preset(vm, "Yagi 400–470", "400", "470", Modifier.weight(1f))
        Preset(vm, "PMR 446", "446", "446.2", Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Preset(vm, "70 cm 430–440", "430", "440", Modifier.weight(1f))
        Preset(vm, "ISM 433", "433", "434.8", Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NumField(vm, "scan_start", "From (MHz)", Modifier.weight(1f))
        NumField(vm, "scan_stop", "To (MHz)", Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NumField(vm, "scan_dur", "Duration (s)", Modifier.weight(1f))
        NumField(vm, "scan_thr", "SNR threshold (dB)", Modifier.weight(1f))
        NumField(vm, "scan_gain", "Gain (dB)", Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = { vm.startScan() }, enabled = mode != Mode.Scan, modifier = Modifier.weight(1f)) {
            Text("Start scan")
        }
        OutlinedButton(onClick = { vm.stopAll() }, enabled = mode == Mode.Scan, modifier = Modifier.weight(1f)) {
            Text("Stop")
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
        color = Color.Gray,
    )
    Row(Modifier.fillMaxWidth()) {
        Text("MHz", Modifier.weight(1.3f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("dB", Modifier.weight(0.8f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("SNR", Modifier.weight(0.8f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("Activity", Modifier.weight(0.9f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("", Modifier.weight(1.2f))
    }
    scan.results.forEach { r ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(String.format(Locale.US, "%.4f", r.freqHz / 1e6), Modifier.weight(1.3f), fontSize = 14.sp)
            Text("${r.powerDb.roundToInt()}", Modifier.weight(0.8f), fontSize = 14.sp)
            Text("${r.snrDb.roundToInt()}", Modifier.weight(0.8f), fontSize = 14.sp)
            Text("${(r.activity * 100).roundToInt()}%", Modifier.weight(0.9f), fontSize = 14.sp)
            TextButton(
                onClick = {
                    vm.setField("trk_freq", String.format(Locale.US, "%.4f", r.freqHz / 1e6))
                    goTrack()
                },
                modifier = Modifier.weight(1.2f),
            ) { Text("Track", fontSize = 12.sp) }
        }
    }
}

// ------------------------------------------------------------------ Track tab
@Composable
fun TrackTab(vm: MainViewModel, holder: MapHolder) {
    val ctx = LocalContext.current
    val live by vm.live.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val heading by vm.heading.collectAsStateWithLifecycle()
    val data by vm.session.collectAsStateWithLifecycle()
    val sound by vm.sound.collectAsStateWithLifecycle()
    val connected by vm.sdrConnected.collectAsStateWithLifecycle()
    val sdrInfo by vm.sdrInfo.collectAsStateWithLifecycle()
    val useSensor = vm.f("use_sensor") == "true"

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = { if (connected) vm.disconnect() else vm.connect() }, modifier = Modifier.weight(1f)) {
            Text(if (connected) "Disconnect SDR" else "Connect SDR", fontSize = 13.sp)
        }
        OutlinedButton(onClick = { openDriver(ctx, vm) }, modifier = Modifier.weight(1f)) {
            Text("Open SDR driver", fontSize = 13.sp)
        }
    }
    Text(if (connected) sdrInfo else "Disconnected", fontSize = 12.sp, color = Color.Gray)

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NumField(vm, "trk_freq", "Frequency (MHz)", Modifier.weight(1.4f))
        NumField(vm, "trk_bw", "Channel (kHz)", Modifier.weight(1f))
        NumField(vm, "trk_gain", "Gain (dB)", Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = { vm.startTrack() }, enabled = mode != Mode.Track, modifier = Modifier.weight(1f)) {
            Text("Start tracking", fontSize = 13.sp)
        }
        OutlinedButton(onClick = { vm.stopAll() }, enabled = mode == Mode.Track, modifier = Modifier.weight(1f)) {
            Text("Stop", fontSize = 13.sp)
        }
        OutlinedButton(onClick = { vm.setSound(!sound) }, modifier = Modifier.weight(1f)) {
            Text(if (sound) "🔊 Sound" else "🔇 Sound", fontSize = 13.sp)
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    (live.smoothDb ?: live.powerDb)?.let { f1(it) } ?: "--",
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(" dB", Modifier.padding(bottom = 8.dp), color = Color.Gray)
            }
            Text(
                "SNR ${live.snrDb?.let { f1(it) } ?: "--"} dB · 10 s peak ${live.peakDb?.let { f1(it) } ?: "--"} dB",
                fontSize = 13.sp,
                color = Color.Gray,
            )
            if (live.clipping) Text("⚠ Signal clipping: lower the gain", color = WARN, fontSize = 13.sp)
            Spark(live.history, Modifier.fillMaxWidth().height(70.dp))
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Antenna bearing", fontSize = 12.sp, color = Color.Gray)
            if (useSensor) {
                Text(
                    heading.trueDeg?.let { "${it.roundToInt()}°" } ?: if (heading.hasSensor) "-- (too vertical)" else "no sensor",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                )
                val acc = when (heading.accuracy) {
                    3 -> "high"; 2 -> "medium"; 1 -> "low"; else -> "unreliable"
                }
                Text(
                    "magnetic ${heading.magnetic?.let { "${it.roundToInt()}°" } ?: "--"} · " +
                        "declination ${f1(heading.declination)}° · calibration $acc",
                    fontSize = 12.sp,
                    color = Color.Gray,
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

    Button(
        onClick = { vm.recordPoint() },
        enabled = mode == Mode.Track,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) { Text("📍 Record point here  (or volume key)", fontSize = 15.sp) }

    EstimateCard(data, holder)
    PointsList(vm, data)
}

@Composable
fun EstimateCard(data: SessionData, holder: MapHolder) {
    if (data.points.isEmpty()) return
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val e = data.estimate
            if (e == null) {
                Text("Estimate: ${data.estimateError ?: ""}", fontSize = 14.sp)
            } else {
                Text("Estimated emitter", fontWeight = FontWeight.Bold)
                Text(String.format(Locale.US, "%.6f, %.6f", e.lat, e.lon), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    String.format(
                        Locale.US, "%d rays · residual %.0f m · max angle %.0f° · baseline %.0f m",
                        e.n, e.rmsM, e.bestAngleDeg, e.baselineM,
                    ),
                    fontSize = 13.sp,
                    color = Color.Gray,
                )
                e.warnings.forEach { Text("⚠ $it", color = WARN, fontSize = 13.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = {
                            holder.map?.controller?.setZoom(16.0)
                            holder.map?.controller?.animateTo(GeoPoint(e.lat, e.lon))
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Center on map", fontSize = 12.sp) }
                    OutlinedButton(onClick = { openInMaps(ctx, e.lat, e.lon) }, modifier = Modifier.weight(1f)) {
                        Text("Open in maps", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun PointsList(vm: MainViewModel, data: SessionData) {
    var toDelete by remember { mutableStateOf<Long?>(null) }
    if (data.points.isNotEmpty()) Text("Points in this session", fontSize = 12.sp, color = Color.Gray)
    data.points.forEachIndexed { i, p ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "#${i + 1} · bearing ${p.bearingTrue.roundToInt()}° · ${f1(p.powerDb)} dB · SNR ${p.snrDb?.roundToInt() ?: "?"}",
                    fontSize = 14.sp,
                )
                Text(
                    String.format(Locale.US, "%.5f, %.5f · %s", p.lat, p.lon, p.ts.takeLast(9).dropLast(1)),
                    fontSize = 11.sp,
                    color = Color.Gray,
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

    Text("Each session stores, per point: GPS position, time, frequency, power, SNR, gain and bearing.", fontSize = 12.sp, color = Color.Gray)
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
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
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { vm.newSession() }, modifier = Modifier.weight(1f)) {
            Text("New hunt (uses the Track frequency)", fontSize = 12.sp)
        }
        OutlinedButton(onClick = { confirm = true }, enabled = cur != null, modifier = Modifier.weight(1f)) {
            Text("Delete session", fontSize = 12.sp)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = { vm.export(ctx, true) }, enabled = cur != null, modifier = Modifier.weight(1f)) { Text("Share CSV") }
        Button(onClick = { vm.export(ctx, false) }, enabled = cur != null, modifier = Modifier.weight(1f)) { Text("Share GeoJSON") }
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
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NumField(vm, "sdr_host", "Host", Modifier.weight(1.5f), KeyboardType.Uri)
        NumField(vm, "sdr_port", "Port", Modifier.weight(1f), KeyboardType.Number)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NumField(vm, "sdr_ppm", "Correction (ppm)", Modifier.weight(1f))
        NumField(vm, "sdr_settle", "Settle time (ms)", Modifier.weight(1f))
    }
    Text(
        "Settle time: how long data is discarded after retuning. If the scan shows ghost " +
            "emitters from the previous hop, raise it; for faster sweeps, lower it.",
        fontSize = 12.sp,
        color = Color.Gray,
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
        color = Color.Gray,
    )
    SwitchRow("Volume keys = record point", vm.f("use_volume") == "true") { vm.setField("use_volume", it.toString()) }
    SwitchRow("Offline-only map (no tile downloads)", vm.f("map_offline") == "true") { vm.setField("map_offline", it.toString()) }

    Text("Offline map", fontWeight = FontWeight.Bold)
    Text(
        "Frame your work area on the map and tap download. Tiles are stored on the phone. " +
            "Cap: 2000 tiles (OpenStreetMap usage policy).",
        fontSize = 12.sp,
        color = Color.Gray,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NumField(vm, "dl_zmin", "Min zoom", Modifier.weight(1f), KeyboardType.Number)
        NumField(vm, "dl_zmax", "Max zoom", Modifier.weight(1f), KeyboardType.Number)
    }
    Button(onClick = { downloadArea(ctx, holder, vm) }, modifier = Modifier.fillMaxWidth()) {
        Text("Download the visible map area")
    }
}

private fun downloadArea(ctx: Context, holder: MapHolder, vm: MainViewModel) {
    val map = holder.map
    if (map == null) {
        vm.postMessage("The map is not ready yet")
        return
    }
    val zmin = vm.f("dl_zmin").toIntOrNull()
    val zmax = vm.f("dl_zmax").toIntOrNull()
    if (zmin == null || zmax == null || zmin < 1 || zmax > 18 || zmin > zmax) {
        vm.postMessage("Invalid zoom (1–18, min ≤ max)")
        return
    }
    val box = map.boundingBox
    val cm = CacheManager(map)
    val total = cm.possibleTilesInArea(box, zmin, zmax)
    if (total > 2000) {
        vm.postMessage("Too many tiles ($total > 2000): zoom the map in or lower the max zoom")
        return
    }
    vm.postMessage("Downloading $total tiles…")
    cm.downloadAreaAsync(
        ctx, box, zmin, zmax,
        object : CacheManager.CacheManagerCallback {
            override fun onTaskComplete() = vm.postMessage("Map downloaded")
            override fun onTaskFailed(errors: Int) = vm.postMessage("Download finished with $errors errors")
            override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {}
            override fun downloadStarted() {}
            override fun setPossibleTilesInArea(total: Int) {}
        },
    )
}
