package space.boscon.kapex.drivers

import space.boscon.kapex.hal.I2cBus

/**
 * Bosch BMP280 / BME280 (pressure + temperature; humidity ignored).
 * Bench sensor only — it is borrowed and its rated range is 300–1100 hPa
 * (datasheet; the MS5611 is the flight barometer). Compensation is the
 * datasheet's 32-bit integer algorithm (BMP280 DS §3.11.3), ported 1:1 so it
 * can be checked against the reference numbers.
 */
class Bmp280(private val bus: I2cBus, private val address: Int = 0x76) {
    data class Calib(
        val t1: Int, val t2: Int, val t3: Int,
        val p1: Int, val p2: Int, val p3: Int, val p4: Int, val p5: Int,
        val p6: Int, val p7: Int, val p8: Int, val p9: Int,
    )
    data class Reading(val temperatureC: Double, val pressurePa: Double, val rawT: Int, val rawP: Int)

    companion object {
        const val REG_ID = 0xD0; const val REG_RESET = 0xE0; const val REG_CTRL = 0xF4
        const val REG_CONFIG = 0xF5; const val REG_DATA = 0xF7; const val REG_CALIB = 0x88
        const val ID_BMP280 = 0x58; const val ID_BME280 = 0x60

        /** Pure function: raw ADC values + calibration → °C and Pa. Datasheet integer path. */
        fun compensate(c: Calib, adcT: Int, adcP: Int): Pair<Double, Double> {
            // temperature
            val var1 = (((adcT shr 3) - (c.t1 shl 1)) * c.t2) shr 11
            val var2 = (((((adcT shr 4) - c.t1) * ((adcT shr 4) - c.t1)) shr 12) * c.t3) shr 14
            val tFine = var1 + var2
            val tempC = ((tFine * 5 + 128) shr 8) / 100.0
            // pressure (64-bit variant, DS §3.11.3 "Pressure compensation in 64 bit")
            var v1 = tFine.toLong() - 128000L
            var v2 = v1 * v1 * c.p6.toLong()
            v2 += (v1 * c.p5.toLong()) shl 17
            v2 += c.p4.toLong() shl 35
            v1 = ((v1 * v1 * c.p3.toLong()) shr 8) + ((v1 * c.p2.toLong()) shl 12)
            v1 = (((1L shl 47) + v1) * c.p1.toLong()) shr 33
            if (v1 == 0L) return tempC to 0.0
            var p = 1048576L - adcP.toLong()
            p = (((p shl 31) - v2) * 3125L) / v1
            v1 = (c.p9.toLong() * (p shr 13) * (p shr 13)) shr 25
            v2 = (c.p8.toLong() * p) shr 19
            p = ((p + v1 + v2) shr 8) + (c.p7.toLong() shl 4)
            return tempC to p / 256.0
        }

        fun parseCalib(b: ByteArray): Calib {
            fun u16(i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
            fun s16(i: Int) = u16(i).toShort().toInt()
            return Calib(
                t1 = u16(0), t2 = s16(2), t3 = s16(4),
                p1 = u16(6), p2 = s16(8), p3 = s16(10), p4 = s16(12), p5 = s16(14),
                p6 = s16(16), p7 = s16(18), p8 = s16(20), p9 = s16(22),
            )
        }
    }

    val chipId: Int get() = bus.readRegister(address, REG_ID)
    private lateinit var calib: Calib

    /** Normal mode, 16× pressure / 2× temperature oversampling, IIR 16, 62.5 ms standby. */
    fun begin() {
        calib = parseCalib(bus.readRegister(address, REG_CALIB, 24))
        bus.writeRegister(address, REG_CONFIG, (0b001 shl 5) or (0b100 shl 2))   // t_sb=62.5ms, filter=16
        bus.writeRegister(address, REG_CTRL, (0b010 shl 5) or (0b101 shl 2) or 0b11) // osrs_t=2, osrs_p=16, normal
    }

    fun read(): Reading {
        val d = bus.readRegister(address, REG_DATA, 6)
        val adcP = ((d[0].toInt() and 0xFF) shl 12) or ((d[1].toInt() and 0xFF) shl 4) or ((d[2].toInt() and 0xFF) shr 4)
        val adcT = ((d[3].toInt() and 0xFF) shl 12) or ((d[4].toInt() and 0xFF) shl 4) or ((d[5].toInt() and 0xFF) shr 4)
        val (t, p) = compensate(calib, adcT, adcP)
        return Reading(t, p, adcT, adcP)
    }
}
