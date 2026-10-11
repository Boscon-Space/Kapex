package space.boscon.hab.drivers

/**
 * Minimal NMEA-0183 parser for GGA and RMC from any talker ($GP, $GN, $BD,
 * $GB …) — the bench GPS is a clone that emits $BD/$GN. Mirrors what
 * TinyGPSPlus gives 08_telemetry_tx: location, altitude, satellites.
 */
class NmeaParser {
    var lat: Double? = null; private set
    var lon: Double? = null; private set
    var altitudeM: Double? = null; private set
    var satellites: Int? = null; private set
    var fixQuality: Int = 0; private set
    var sentences = 0; private set
    var badChecksums = 0; private set
    private val line = StringBuilder()

    val hasFix: Boolean get() = fixQuality > 0 && lat != null && lon != null

    fun feed(bytes: ByteArray) { for (b in bytes) feed(b.toInt().toChar()) }

    fun feed(c: Char) {
        when (c) {
            '$' -> { line.clear(); line.append(c) }
            '\n', '\r' -> { if (line.isNotEmpty()) { parse(line.toString()); line.clear() } }
            else -> if (line.isNotEmpty() && line.length < 120) line.append(c)
        }
    }

    internal fun parse(s: String) {
        if (!s.startsWith('$')) return
        val star = s.indexOf('*')
        if (star < 0 || star + 3 > s.length) return
        var cs = 0; for (i in 1 until star) cs = cs xor s[i].code
        val want = s.substring(star + 1, star + 3).toIntOrNull(16) ?: return
        if (cs != want) { badChecksums++; return }
        val f = s.substring(1, star).split(',')
        val type = f[0].drop(2)   // talker-independent
        sentences++
        when (type) {
            "GGA" -> {
                fixQuality = f.getOrNull(6)?.toIntOrNull() ?: 0
                if (fixQuality > 0) {
                    lat = coord(f.getOrNull(2), f.getOrNull(3)); lon = coord(f.getOrNull(4), f.getOrNull(5))
                    satellites = f.getOrNull(7)?.toIntOrNull()
                    altitudeM = f.getOrNull(9)?.toDoubleOrNull()
                }
            }
            "RMC" -> if (f.getOrNull(2) == "A") {
                lat = coord(f.getOrNull(3), f.getOrNull(4)); lon = coord(f.getOrNull(5), f.getOrNull(6))
                if (fixQuality == 0) fixQuality = 1
            }
        }
    }

    private fun coord(v: String?, hemi: String?): Double? {
        if (v.isNullOrEmpty() || hemi.isNullOrEmpty()) return null
        val dot = v.indexOf('.'); if (dot < 3) return null
        val deg = v.substring(0, dot - 2).toDoubleOrNull() ?: return null
        val min = v.substring(dot - 2).toDoubleOrNull() ?: return null
        val d = deg + min / 60.0
        return if (hemi == "S" || hemi == "W") -d else d
    }
}
