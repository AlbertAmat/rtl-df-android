package app.rdf.finder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay
import kotlin.math.max

/** Map drawing: numbered points, rays, estimated emitter and own position. */
object MapOverlays {
    private val RAY_DAY = Color.rgb(255, 179, 0)
    private val FRIENDLY_DAY = Color.rgb(63, 167, 255)
    private val HOSTILE_DAY = Color.rgb(229, 72, 58)
    private val NEXT_DAY = Color.rgb(76, 175, 80)
    private val RED_NIGHT = Color.rgb(255, 68, 51)

    fun render(map: MapView, ctx: Context, data: SessionData, fix: GpsFix?, night: Boolean, next: NextSpot.Spot? = null) {
        // blue = own position, amber = bearing lines, red = estimated emitter; all red in night mode
        val rayColor = if (night) RED_NIGHT else RAY_DAY
        val ownColor = if (night) RED_NIGHT else FRIENDLY_DAY
        val emitterColor = if (night) RED_NIGHT else HOSTILE_DAY
        map.overlays.clear()
        val est = data.estimate

        data.points.forEachIndexed { i, p ->
            val len = if (est != null && i < est.rays.size) max(1500.0, est.rays[i].rangeM * 1.3) else 3000.0
            val (la, lo) = Geo.destination(p.lat, p.lon, p.bearingTrue, len)
            val dropped = est != null && i in est.excluded
            map.overlays.add(
                Polyline().apply {
                    setPoints(listOf(GeoPoint(p.lat, p.lon), GeoPoint(la, lo)))
                    outlinePaint.color = if (dropped) Color.GRAY else rayColor
                    outlinePaint.strokeWidth = if (dropped) 3f else 6f
                    if (dropped) outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 10f), 0f)
                },
            )
            map.overlays.add(
                Marker(map).apply {
                    position = GeoPoint(p.lat, p.lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = dot(ctx, rayColor, 28, "${i + 1}")
                    title = "#${i + 1} · bearing ${"%.0f".format(p.bearingTrue)}° · ${"%.1f".format(p.powerDb)} dB"
                },
            )
        }

        if (est != null && est.ellipseMajorM > 0) {
            // 95% confidence ellipse around the estimate
            val pts = ArrayList<GeoPoint>()
            val rot = Math.toRadians(est.ellipseAngleDeg)
            for (k in 0..48) {
                val t = 2 * Math.PI * k / 48
                val u = est.ellipseMajorM * Math.cos(t)
                val v = est.ellipseMinorM * Math.sin(t)
                // u along the major axis (bearing rot), v perpendicular; east/north components
                val east = u * Math.sin(rot) + v * Math.cos(rot)
                val north = u * Math.cos(rot) - v * Math.sin(rot)
                val (la, lo) = Geo.destination(est.lat, est.lon, Math.toDegrees(Math.atan2(east, north)), Math.hypot(east, north))
                pts.add(GeoPoint(la, lo))
            }
            map.overlays.add(
                Polyline().apply {
                    setPoints(pts)
                    outlinePaint.color = emitterColor
                    outlinePaint.strokeWidth = 3f
                },
            )
        }
        if (est != null) {
            map.overlays.add(
                Marker(map).apply {
                    position = GeoPoint(est.lat, est.lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = dot(ctx, emitterColor, 32, null)
                    title = "Estimated emitter"
                },
            )
        }

        if (next != null) {
            map.overlays.add(
                Marker(map).apply {
                    position = GeoPoint(next.lat, next.lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = dot(ctx, if (night) RED_NIGHT else NEXT_DAY, 26, "→")
                    title = "Next spot · ${"%.0f".format(next.distM)} m · ${"%.0f".format(next.expectedSepDeg)}° expected angle"
                },
            )
        }

        if (fix != null) {
            map.overlays.add(
                Marker(map).apply {
                    position = GeoPoint(fix.lat, fix.lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = dot(ctx, ownColor, 22, null)
                    title = "You"
                },
            )
        }
        map.overlays.add(ScaleBarOverlay(map))
        map.invalidate()
    }

    private val dotCache = HashMap<Triple<Int, Int, String?>, Bitmap>()

    /** Markers are redrawn at every GPS fix: reuse the bitmaps instead of painting new ones. */
    private fun dot(ctx: Context, color: Int, sizeDp: Int, label: String?): BitmapDrawable =
        BitmapDrawable(ctx.resources, dotCache.getOrPut(Triple(color, sizeDp, label)) { paintDot(ctx, color, sizeDp, label) })

    private fun paintDot(ctx: Context, color: Int, sizeDp: Int, label: String?): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val s = (sizeDp * d).toInt()
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE
        c.drawCircle(s / 2f, s / 2f, s / 2f, p)
        p.color = color
        c.drawCircle(s / 2f, s / 2f, s / 2f - 2 * d, p)
        if (label != null) {
            p.color = Color.BLACK
            p.textAlign = Paint.Align.CENTER
            p.textSize = s * 0.5f
            p.isFakeBoldText = true
            c.drawText(label, s / 2f, s / 2f - (p.descent() + p.ascent()) / 2f, p)
        }
        return bmp
    }
}
