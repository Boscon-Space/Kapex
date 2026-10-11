package space.boscon.hab.drivers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class NmeaTest {
    private fun withChecksum(body: String): String {
        var cs = 0; for (c in body) cs = cs xor c.code
        return "$" + body + "*" + cs.toString(16).uppercase().padStart(2, '0') + "\r\n"
    }

    @Test fun ggaFromACloneTalker() {
        val p = NmeaParser()
        p.feed(withChecksum("BDGGA,123519,1831.224,N,07351.402,E,1,08,0.9,560.4,M,46.9,M,,").encodeToByteArray())
        assertTrue(p.hasFix)
        assertEquals(18.5204, p.lat!!, 1e-4)
        assertEquals(73.8567, p.lon!!, 1e-4)
        assertEquals(8, p.satellites)
        assertEquals(560.4, p.altitudeM!!, 1e-9)
    }

    @Test fun badChecksumIsCounted() {
        val p = NmeaParser()
        p.feed("\$GPGGA,123519,1831.224,N,07351.402,E,1,08,0.9,560.4,M,46.9,M,,*00\r\n".encodeToByteArray())
        assertFalse(p.hasFix); assertEquals(1, p.badChecksums)
    }

    @Test fun noFixGgaKeepsNoPosition() {
        val p = NmeaParser()
        p.feed(withChecksum("GNGGA,123519,,,,,0,00,,,M,,M,,").encodeToByteArray())
        assertFalse(p.hasFix)
    }
}
