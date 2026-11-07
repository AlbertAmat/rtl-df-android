package app.rdf.finder

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant

data class SessionRow(val id: Long, val freqHz: Double, val created: String, val n: Int)

data class PointRow(
    val id: Long,
    val sessionId: Long,
    val ts: String,          // UTC time (from GPS if available)
    val tsSrc: String,       // "gps" or "system"
    val lat: Double,
    val lon: Double,
    val alt: Double?,
    val accM: Double?,
    val freqHz: Double,
    val powerDb: Double,     // relative dBFS (uncalibrated)
    val snrDb: Double?,
    val gain: Double?,
    val bearingMag: Double,  // measured magnetic bearing (sensor or typed)
    val declination: Double,
    val offset: Double,      // mounting offset
    val bearingTrue: Double, // = bearingMag + declination + offset
)

/** Persistence: tracking sessions (one per frequency) and measured points. */
class RdfDb(ctx: Context) : SQLiteOpenHelper(ctx, "rdf.db", null, 1) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE sessions(id INTEGER PRIMARY KEY AUTOINCREMENT, freq_hz REAL NOT NULL, created TEXT NOT NULL)",
        )
        db.execSQL(
            """CREATE TABLE points(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                ts TEXT NOT NULL, ts_src TEXT NOT NULL,
                lat REAL NOT NULL, lon REAL NOT NULL, alt REAL, acc_m REAL,
                freq_hz REAL NOT NULL, power_db REAL NOT NULL, snr_db REAL, gain REAL,
                bearing_mag REAL NOT NULL, declination REAL NOT NULL, offset_deg REAL NOT NULL,
                bearing_true REAL NOT NULL)""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun createSession(freqHz: Double): Long {
        val v = ContentValues().apply {
            put("freq_hz", freqHz)
            put("created", Instant.now().toString())
        }
        return writableDatabase.insertOrThrow("sessions", null, v)
    }

    fun sessions(): List<SessionRow> {
        val out = ArrayList<SessionRow>()
        readableDatabase.rawQuery(
            "SELECT s.id, s.freq_hz, s.created, (SELECT COUNT(*) FROM points p WHERE p.session_id = s.id) " +
                "FROM sessions s ORDER BY s.id DESC",
            null,
        ).use { c -> while (c.moveToNext()) out.add(SessionRow(c.getLong(0), c.getDouble(1), c.getString(2), c.getInt(3))) }
        return out
    }

    fun getSession(id: Long): SessionRow? = sessions().firstOrNull { it.id == id }

    fun lastSessionId(): Long? =
        readableDatabase.rawQuery("SELECT MAX(id) FROM sessions", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }

    fun deleteSession(id: Long) {
        writableDatabase.delete("sessions", "id=?", arrayOf(id.toString()))
    }

    fun addPoint(p: PointRow): Long {
        val v = ContentValues().apply {
            put("session_id", p.sessionId); put("ts", p.ts); put("ts_src", p.tsSrc)
            put("lat", p.lat); put("lon", p.lon); put("alt", p.alt); put("acc_m", p.accM)
            put("freq_hz", p.freqHz); put("power_db", p.powerDb); put("snr_db", p.snrDb); put("gain", p.gain)
            put("bearing_mag", p.bearingMag); put("declination", p.declination)
            put("offset_deg", p.offset); put("bearing_true", p.bearingTrue)
        }
        return writableDatabase.insertOrThrow("points", null, v)
    }

    fun points(sessionId: Long): List<PointRow> {
        val out = ArrayList<PointRow>()
        readableDatabase.rawQuery(
            "SELECT id, session_id, ts, ts_src, lat, lon, alt, acc_m, freq_hz, power_db, snr_db, gain, " +
                "bearing_mag, declination, offset_deg, bearing_true FROM points WHERE session_id=? ORDER BY id",
            arrayOf(sessionId.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    PointRow(
                        id = c.getLong(0), sessionId = c.getLong(1), ts = c.getString(2), tsSrc = c.getString(3),
                        lat = c.getDouble(4), lon = c.getDouble(5), alt = c.dbl(6), accM = c.dbl(7),
                        freqHz = c.getDouble(8), powerDb = c.getDouble(9), snrDb = c.dbl(10), gain = c.dbl(11),
                        bearingMag = c.getDouble(12), declination = c.getDouble(13), offset = c.getDouble(14),
                        bearingTrue = c.getDouble(15),
                    ),
                )
            }
        }
        return out
    }

    /** Deletes a point and returns its session id (or null if it did not exist). */
    fun deletePoint(id: Long): Long? {
        val sid = readableDatabase.rawQuery("SELECT session_id FROM points WHERE id=?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }
        if (sid != null) writableDatabase.delete("points", "id=?", arrayOf(id.toString()))
        return sid
    }

    private fun Cursor.dbl(i: Int): Double? = if (isNull(i)) null else getDouble(i)
}
