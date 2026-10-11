package space.boscon.hab.drivers

import kotlin.test.Test
import kotlin.test.assertEquals

class Bmp280Test {
    // Calibration + raw values from the Bosch BMP280 driver's documented
    // example (bmp280_test / datasheet-derived): these give 25.08 °C and
    // 1006.52 hPa with the reference integer algorithm. If this test fails
    // after a refactor, the port drifted from the datasheet.
    private val calib = Bmp280.Calib(
        t1 = 27504, t2 = 26435, t3 = -1000,
        p1 = 36477, p2 = -10685, p3 = 3024, p4 = 2855, p5 = 140,
        p6 = -7, p7 = 15500, p8 = -14600, p9 = 6000,
    )

    @Test fun datasheetExample() {
        val (t, p) = Bmp280.compensate(calib, adcT = 519888, adcP = 415148)
        assertEquals(25.08, t, 0.005)
        assertEquals(100653.0, p, 2.0)   // datasheet float path gives 100653.27 Pa
    }

    @Test fun calibParsingIsLittleEndianSigned() {
        val raw = byteArrayOf(0x70, 0x6B, 0x43, 0x67, 0x18, 0xFC.toByte(), 0x7D, 0x8E.toByte(),
            0x43, 0xD6.toByte(), 0xD0.toByte(), 0x0B, 0x27, 0x0B, 0x8C.toByte(), 0x00,
            0xF9.toByte(), 0xFF.toByte(), 0x8C.toByte(), 0x3C, 0xF8.toByte(), 0xC6.toByte(), 0x70, 0x17)
        assertEquals(calib, Bmp280.parseCalib(raw))
    }
}
