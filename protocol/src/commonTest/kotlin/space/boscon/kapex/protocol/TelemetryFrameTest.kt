package space.boscon.kapex.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TelemetryFrameTest {
    private val f = TelemetryFrame(seq = 7, lat = 18.5204f, lon = 73.8567f, altGps = 560.0f, pressPa = 93771.0f,
        tempC100 = 3147, altBaro = 650.5f, sats = 9, flags = 0x07, vbatMv = 4123)

    @Test fun sizeAndMagic() {
        val b = f.encode()
        assertEquals(28, b.size)
        assertEquals('T'.code, b[0].toInt() and 0xFF)
        assertEquals(7, b[1].toInt() and 0xFF)
    }

    @Test fun fieldOffsetsMatchTheCStruct() {
        // offsets from the packed C struct: magic 0, seq 1, lat 2, lon 6, altGps 10,
        // pressPa 14, tempC100 18, altBaro 20, sats 24, flags 25, vbatMv 26
        val b = f.encode()
        assertEquals(3147, (b[18].toInt() and 0xFF) or ((b[19].toInt() and 0xFF) shl 8))
        assertEquals(9, b[24].toInt() and 0xFF)
        assertEquals(0x07, b[25].toInt() and 0xFF)
        assertEquals(4123, (b[26].toInt() and 0xFF) or ((b[27].toInt() and 0xFF) shl 8))
        assertEquals(18.5204f.toRawBits(), (b[2].toInt() and 0xFF) or ((b[3].toInt() and 0xFF) shl 8) or ((b[4].toInt() and 0xFF) shl 16) or ((b[5].toInt() and 0xFF) shl 24))
    }

    @Test fun roundTrip() { assertEquals(f, TelemetryFrame.decode(f.encode())) }

    @Test fun rejectsWrongMagicOrSize() {
        assertNull(TelemetryFrame.decode(ByteArray(28)))
        assertNull(TelemetryFrame.decode(f.encode().copyOf(27)))
    }

    @Test fun baroAltitudeLowAltitude() {
        // 1013.25 hPa -> 0 m; 937.71 hPa -> 648.76 m geometric (US Std Atm 1976, computed in Python)
        assertEquals(0.0f, TelemetryFrame.baroAltitudeM(101325.0f), 0.01f)
        assertEquals(648.76f, TelemetryFrame.baroAltitudeM(93771.0f), 0.5f)
    }

    // US Standard Atmosphere 1976 table, geometric altitude: 20 km = 5529.3 Pa, 30 km = 1197.0 Pa.
    // The single-layer formula reads 18 842 m and 25 281 m here.
    @Test fun baroAltitudeAt20km() = assertEquals(20000.0f, TelemetryFrame.baroAltitudeM(5529.3f), 2.0f)

    @Test fun baroAltitudeAt30km() = assertEquals(30000.0f, TelemetryFrame.baroAltitudeM(1197.0f), 2.0f)
}
