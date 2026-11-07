package app.rdf.finder

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Exports a session to CSV / GeoJSON and shares it with other apps. */
object Export {
    private fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "exports").apply { mkdirs() }

    fun csv(ctx: Context, session: SessionRow, pts: List<PointRow>): File {
        val f = File(dir(ctx), "rdf_session_${session.id}.csv")
        val sb = StringBuilder()
        sb.append("id,ts,ts_src,lat,lon,alt,acc_m,freq_hz,power_db,snr_db,gain,bearing_mag,declination,offset,bearing_true\n")
        for (p in pts) {
            sb.append(
                listOf(
                    p.id, p.ts, p.tsSrc, p.lat, p.lon, p.alt ?: "", p.accM ?: "", p.freqHz,
                    String.format(Locale.US, "%.2f", p.powerDb),
                    p.snrDb?.let { String.format(Locale.US, "%.2f", it) } ?: "",
                    p.gain ?: "", p.bearingMag, p.declination, p.offset, p.bearingTrue,
                ).joinToString(","),
            ).append('\n')
        }
        f.writeText(sb.toString())
        return f
    }

    fun geojson(ctx: Context, session: SessionRow, pts: List<PointRow>, est: Geo.Estimate?): File {
        val feats = JSONArray()
        for (p in pts) {
            feats.put(
                JSONObject()
                    .put("type", "Feature")
                    .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(p.lon).put(p.lat)))
                    .put(
                        "properties",
                        JSONObject().put("ts", p.ts).put("freq_hz", p.freqHz).put("power_db", p.powerDb)
                            .put("bearing_true", p.bearingTrue),
                    ),
            )
        }
        if (est != null) {
            feats.put(
                JSONObject()
                    .put("type", "Feature")
                    .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(est.lon).put(est.lat)))
                    .put("properties", JSONObject().put("estimate", true).put("rms_m", est.rmsM)),
            )
        }
        val f = File(dir(ctx), "rdf_session_${session.id}.geojson")
        f.writeText(JSONObject().put("type", "FeatureCollection").put("features", feats).toString())
        return f
    }

    fun share(ctx: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
