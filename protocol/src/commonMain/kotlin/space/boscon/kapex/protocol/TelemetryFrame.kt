package space.boscon.kapex.protocol

import kotlin.math.ln
import kotlin.math.pow

/**
 * The 28-byte telemetry frame, byte-for-byte the packed C struct `Telemetry`
 * in firmware/08_telemetry_tx and 09_telemetry_rx. Little-endian, IEEE-754
 * float32, no padding. FIELDS below is parsed by tools/check_packet_abi.py
 * and compared with the C struct — change both or the check fails.
 */
data class TelemetryFrame(
    val seq: Int,
    val lat: Float,
    val lon: Float,
    val altGps: Float,
    val pressPa: Float,
    val tempC100: Int,
    val altBaro: Float,
    val sats: Int,
    val flags: Int,
    val vbatMv: Int,
) {
    companion object {
        const val MAGIC = 'T'.code
        const val SIZE = 28
        const val FLAG_GPS_FIX = 0x01
        const val FLAG_BMP_OK = 0x02
        const val FLAG_FIX_3D = 0x04
        const val FLAG_AIRBORNE_MODEL = 0x08
        const val FLAG_ON_USB = 0x10
        const val FLAG_POWER_UNKNOWN = 0x20

        /** C types in struct order; the ABI checker reads these lines. */
        val FIELDS = listOf(
            "uint8_t magic", "uint8_t seq", "float lat", "float lon", "float altGps",
            "float pressPa", "int16_t tempC100", "float altBaro", "uint8_t sats",
            "uint8_t flags", "uint16_t vbatMv",
        )

        /**
         * Geometric altitude in m from Pa: US Standard Atmosphere 1976, three layers
         * (0-11 km lapse -6.5 K/km, 11-20 km isothermal, 20-32 km lapse +1.0 K/km),
         * converted from geopotential with the standard's Earth radius, 6356766 m.
         * Same arithmetic as baroAltitudeM() in the transmitter sketches. Above 32 km
         * the third layer is extrapolated: -35 m at 35 km, -136 m at 38 km (computed).
         */
        fun baroAltitudeM(pressPa: Float): Float {
            val h = when {
                pressPa >= 22632.06f -> 44330.77f * (1.0f - (pressPa / 101325.0f).pow(0.1902632f))
                pressPa >= 5474.89f -> 11000.0f + 6341.618f * ln(22632.06f / pressPa)
                else -> 20000.0f + 216650.0f * ((pressPa / 5474.89f).pow(-0.02927126f) - 1.0f)
            }
            return 6356766.0f * h / (6356766.0f - h)
        }

        fun decode(b: ByteArray): TelemetryFrame? {
            if (b.size != SIZE || (b[0].toInt() and 0xFF) != MAGIC) return null
            val r = LeReader(b, 1)
            return TelemetryFrame(
                seq = r.u8(), lat = r.f32(), lon = r.f32(), altGps = r.f32(), pressPa = r.f32(),
                tempC100 = r.i16(), altBaro = r.f32(), sats = r.u8(), flags = r.u8(), vbatMv = r.u16(),
            )
        }
    }

    fun encode(): ByteArray {
        val w = LeWriter(SIZE)
        w.u8(MAGIC); w.u8(seq); w.f32(lat); w.f32(lon); w.f32(altGps); w.f32(pressPa)
        w.i16(tempC100); w.f32(altBaro); w.u8(sats); w.u8(flags); w.u16(vbatMv)
        check(w.pos == SIZE) { "frame is ${w.pos} bytes, expected $SIZE" }
        return w.buf
    }
}

internal class LeWriter(size: Int) {
    val buf = ByteArray(size); var pos = 0
    fun u8(v: Int) { buf[pos++] = v.toByte() }
    fun i16(v: Int) { u8(v); u8(v shr 8) }
    fun u16(v: Int) = i16(v)
    fun u32(v: Int) { i16(v); i16(v shr 16) }
    fun f32(v: Float) = u32(v.toRawBits())
}

internal class LeReader(private val b: ByteArray, var pos: Int) {
    fun u8(): Int = b[pos++].toInt() and 0xFF
    fun u16(): Int = u8() or (u8() shl 8)
    fun i16(): Int = u16().toShort().toInt()
    fun u32(): Int = u16() or (u16() shl 16)
    fun f32(): Float = Float.fromBits(u32())
}
