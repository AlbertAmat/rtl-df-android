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

/** Referencia compartida al MapView (para centrar y descargar zonas). */
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
                        listOf("Escanear", "Rastrear", "Sesiones", "Ajustes").forEachIndexed { i, t ->
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

// ------------------------------------------------------------------ cabecera y mapa
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
        Chip(if (gpsValid) "GPS ±$acc m · $sats sat" else "GPS: sin fix", if (gpsValid) OK else BAD)
        Chip(if (connected) "SDR conectado" else "SDR desconectado", if (connected) OK else BAD)
        Chip(
            when (mode) {
                Mode.Idle -> "inactivo"
                Mode.Scan -> "escaneando"
                Mode.Track -> "rastreando"
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
                if (fx != null) mapView.controller.animateTo(GeoPoint(fx.lat, fx.lon)) else vm.postMessage("Sin fix GPS")
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
        ) { Text("◎") }
    }
}

// ------------------------------------------------------------------ componentes
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
    if (i == null) vm.postMessage("No encuentro la app «SDR driver»") else ctx.startActivity(i)
}

private fun openInMaps(ctx: Context, lat: Double, lon: Double) {
    val uri = String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(Emisor)", lat, lon, lat, lon)
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        // sin app de mapas: no pasa nada, las coordenadas están en pantalla
    }
}

// ------------------------------------------------------------------ pestaña Escanear
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
        NumField(vm, "scan_start", "Desde (MHz)", Modifier.weight(1f))
        NumField(vm, "scan_stop", "Hasta (MHz)", Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NumField(vm, "scan_dur", "Duración (s)", Modifier.weight(1f))
        NumField(vm, "scan_thr", "Umbral SNR (dB)", Modifier.weight(1f))
        NumField(vm, "scan_gain", "Ganancia (dB)", Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = { vm.startScan() }, enabled = mode != Mode.Scan, modifier = Modifier.weight(1f)) {
            Text("Iniciar escaneo")
        }
        OutlinedButton(onClick = { vm.stopAll() }, enabled = mode == Mode.Scan, modifier = Modifier.weight(1f)) {
            Text("Parar")
        }
    }
    LinearProgressIndicator(progress = { scan.progress }, modifier = Modifier.fillMaxWidth())
    Text(
        if (scan.sweeps > 0 || scan.running) {
            "${if (scan.running) "Escaneando" else "Terminado"} · ${scan.sweeps} barridos · " +
                "${scan.elapsedS.roundToInt()}/${scan.durationS.roundToInt()} s · ${scan.results.size} emisoras"
        } else {
            "Sin resultados. Conecta el SDR en la pestaña Rastrear e inicia el escaneo."
        },
        fontSize = 13.sp,
        color = Color.Gray,
    )
    Row(Modifier.fillMaxWidth()) {
        Text("MHz", Modifier.weight(1.3f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("dB", Modifier.weight(0.8f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("SNR", Modifier.weight(0.8f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("Activ.", Modifier.weight(0.9f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
            ) { Text("Rastrear", fontSize = 12.sp) }
        }
    }
}

// ------------------------------------------------------------------ pestaña Rastrear
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
            Text(if (connected) "Desconectar SDR" else "Conectar SDR", fontSize = 13.sp)
        }
        OutlinedButton(onClick = { openDriver(ctx, vm) }, modifier = Modifier.weight(1f)) {
            Text("Abrir SDR driver", fontSize = 13.sp)
        }
    }
    Text(if (connected) sdrInfo else "Desconectado", fontSize = 12.sp, color = Color.Gray)

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NumField(vm, "trk_freq", "Frecuencia (MHz)", Modifier.weight(1.4f))
        NumField(vm, "trk_bw", "Canal (kHz)", Modifier.weight(1f))
        NumField(vm, "trk_gain", "Gan. (dB)", Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = { vm.startTrack() }, enabled = mode != Mode.Track, modifier = Modifier.weight(1f)) {
            Text("Iniciar rastreo", fontSize = 13.sp)
        }
        OutlinedButton(onClick = { vm.stopAll() }, enabled = mode == Mode.Track, modifier = Modifier.weight(1f)) {
            Text("Parar", fontSize = 13.sp)
        }
        OutlinedButton(onClick = { vm.setSound(!sound) }, modifier = Modifier.weight(1f)) {
            Text(if (sound) "🔊 Sonido" else "🔇 Sonido", fontSize = 13.sp)
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
                "SNR ${live.snrDb?.let { f1(it) } ?: "--"} dB · pico 10 s ${live.peakDb?.let { f1(it) } ?: "--"} dB",
                fontSize = 13.sp,
                color = Color.Gray,
            )
            if (live.clipping) Text("⚠ Señal saturada: baja la ganancia", color = WARN, fontSize = 13.sp)
            Spark(live.history, Modifier.fillMaxWidth().height(70.dp))
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Rumbo de la antena", fontSize = 12.sp, color = Color.Gray)
            if (useSensor) {
                Text(
                    heading.trueDeg?.let { "${it.roundToInt()}°" } ?: if (heading.hasSensor) "-- (muy vertical)" else "sin sensor",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                )
                val acc = when (heading.accuracy) {
                    3 -> "alta"; 2 -> "media"; 1 -> "baja"; else -> "no fiable"
                }
                Text(
                    "magnético ${heading.magnetic?.let { "${it.roundToInt()}°" } ?: "--"} · " +
                        "declinación ${f1(heading.declination)}° · calibración $acc",
                    fontSize = 12.sp,
                    color = Color.Gray,
                )
                if (heading.accuracy < 2) {
                    Text("Calibra la brújula moviendo el móvil en forma de 8", color = WARN, fontSize = 12.sp)
                }
            } else {
                NumField(vm, "trk_bearing", "Rumbo de tu brújula (° magnético)", Modifier.fillMaxWidth())
            }
        }
    }
    SwitchRow("Usar la brújula del móvil", useSensor) { vm.setField("use_sensor", it.toString()) }

    Button(
        onClick = { vm.recordPoint() },
        enabled = mode == Mode.Track,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) { Text("📍 Registrar punto aquí  (o tecla de volumen)", fontSize = 15.sp) }

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
                Text("Estimación: ${data.estimateError ?: ""}", fontSize = 14.sp)
            } else {
                Text("Emisor estimado", fontWeight = FontWeight.Bold)
                Text(String.format(Locale.US, "%.6f, %.6f", e.lat, e.lon), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    String.format(
                        Locale.US, "%d rayos · residuo %.0f m · ángulo máx. %.0f° · base %.0f m",
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
                    ) { Text("Centrar en el mapa", fontSize = 12.sp) }
                    OutlinedButton(onClick = { openInMaps(ctx, e.lat, e.lon) }, modifier = Modifier.weight(1f)) {
                        Text("Abrir en mapas", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun PointsList(vm: MainViewModel, data: SessionData) {
    var toDelete by remember { mutableStateOf<Long?>(null) }
    if (data.points.isNotEmpty()) Text("Puntos de esta sesión", fontSize = 12.sp, color = Color.Gray)
    data.points.forEachIndexed { i, p ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "#${i + 1} · rumbo ${p.bearingTrue.roundToInt()}° · ${f1(p.powerDb)} dB · SNR ${p.snrDb?.roundToInt() ?: "?"}",
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
            title = { Text("¿Borrar este punto?") },
            confirmButton = {
                TextButton(onClick = { vm.deletePoint(id); toDelete = null }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancelar") } },
        )
    }
}

// ------------------------------------------------------------------ pestaña Sesiones
@Composable
fun SessionsTab(vm: MainViewModel) {
    val ctx = LocalContext.current
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val data by vm.session.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val cur = data.session

    Text("Cada sesión guarda por punto: posición GPS, hora, frecuencia, potencia, SNR, ganancia y rumbo.", fontSize = 12.sp, color = Color.Gray)
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (cur != null) "#${cur.id} · ${String.format(Locale.US, "%.4f", cur.freqHz / 1e6)} MHz · ${data.points.size} pts"
                else "Sin sesiones",
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
            Text("Nueva caza (frecuencia de Rastrear)", fontSize = 12.sp)
        }
        OutlinedButton(onClick = { confirm = true }, enabled = cur != null, modifier = Modifier.weight(1f)) {
            Text("Borrar sesión", fontSize = 12.sp)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = { vm.export(ctx, true) }, enabled = cur != null, modifier = Modifier.weight(1f)) { Text("Compartir CSV") }
        Button(onClick = { vm.export(ctx, false) }, enabled = cur != null, modifier = Modifier.weight(1f)) { Text("Compartir GeoJSON") }
    }
    if (confirm && cur != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("¿Borrar la sesión #${cur.id} y todos sus puntos?") },
            confirmButton = {
                TextButton(onClick = { vm.deleteSession(cur.id); confirm = false }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } },
        )
    }
}

// ------------------------------------------------------------------ pestaña Ajustes
@Composable
fun SettingsTab(vm: MainViewModel, holder: MapHolder) {
    val ctx = LocalContext.current

    Text("Conexión con SDR driver (rtl_tcp)", fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NumField(vm, "sdr_host", "Dirección", Modifier.weight(1.5f), KeyboardType.Uri)
        NumField(vm, "sdr_port", "Puerto", Modifier.weight(1f), KeyboardType.Number)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NumField(vm, "sdr_ppm", "Corrección (ppm)", Modifier.weight(1f))
        NumField(vm, "sdr_settle", "Asentamiento (ms)", Modifier.weight(1f))
    }
    Text(
        "Asentamiento: tiempo que se descarta tras cambiar de frecuencia. Si en el escaneo aparecen " +
            "emisoras fantasma del salto anterior, súbelo; si quieres barridos más rápidos, bájalo.",
        fontSize = 12.sp,
        color = Color.Gray,
    )

    Text("Montaje del móvil en la Yagi", fontWeight = FontWeight.Bold)
    MountAxis.entries.forEach { a ->
        Row(
            Modifier.fillMaxWidth().clickable { vm.setField("mount_axis", a.name) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = vm.f("mount_axis") == a.name, onClick = { vm.setField("mount_axis", a.name) })
            Text(a.label, fontSize = 13.sp)
        }
    }
    NumField(vm, "heading_offset", "Corrección de rumbo (°, + horario)", Modifier.fillMaxWidth())
    Text(
        "Si el móvil no queda exactamente alineado con el boom, apunta la Yagi a algo con rumbo conocido " +
            "y pon aquí la diferencia (rumbo real − rumbo mostrado).",
        fontSize = 12.sp,
        color = Color.Gray,
    )
    SwitchRow("Teclas de volumen = registrar punto", vm.f("use_volume") == "true") { vm.setField("use_volume", it.toString()) }
    SwitchRow("Mapa solo offline (sin descargar teselas)", vm.f("map_offline") == "true") { vm.setField("map_offline", it.toString()) }

    Text("Mapa offline", fontWeight = FontWeight.Bold)
    Text(
        "Encuadra en el mapa la zona de trabajo y pulsa descargar. Las teselas quedan guardadas en el móvil. " +
            "Tope: 2000 teselas (política de uso de OpenStreetMap).",
        fontSize = 12.sp,
        color = Color.Gray,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NumField(vm, "dl_zmin", "Zoom mín.", Modifier.weight(1f), KeyboardType.Number)
        NumField(vm, "dl_zmax", "Zoom máx.", Modifier.weight(1f), KeyboardType.Number)
    }
    Button(onClick = { downloadArea(ctx, holder, vm) }, modifier = Modifier.fillMaxWidth()) {
        Text("Descargar la zona visible del mapa")
    }
}

private fun downloadArea(ctx: Context, holder: MapHolder, vm: MainViewModel) {
    val map = holder.map
    if (map == null) {
        vm.postMessage("El mapa aún no está listo")
        return
    }
    val zmin = vm.f("dl_zmin").toIntOrNull()
    val zmax = vm.f("dl_zmax").toIntOrNull()
    if (zmin == null || zmax == null || zmin < 1 || zmax > 18 || zmin > zmax) {
        vm.postMessage("Zoom no válido (1–18, mínimo ≤ máximo)")
        return
    }
    val box = map.boundingBox
    val cm = CacheManager(map)
    val total = cm.possibleTilesInArea(box, zmin, zmax)
    if (total > 2000) {
        vm.postMessage("Demasiadas teselas ($total > 2000): acerca el mapa o baja el zoom máximo")
        return
    }
    vm.postMessage("Descargando $total teselas…")
    cm.downloadAreaAsync(
        ctx, box, zmin, zmax,
        object : CacheManager.CacheManagerCallback {
            override fun onTaskComplete() = vm.postMessage("Mapa descargado")
            override fun onTaskFailed(errors: Int) = vm.postMessage("Descarga terminada con $errors errores")
            override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {}
            override fun downloadStarted() {}
            override fun setPossibleTilesInArea(total: Int) {}
        },
    )
}
