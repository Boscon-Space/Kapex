package space.boscon.kapex.camera

import kotlin.test.*

class MjpegTest {
    // A minimal JPEG skeleton: SOI, APP0 (empty), DQT (empty), SOF0 (empty), SOS, EOI. Not decodable; only structure matters here.
    private fun skeleton(withDht: Boolean): ByteArray {
        val seg = { m: Int -> byteArrayOf(0xFF.toByte(), m.toByte(), 0, 2) }
        var f = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + seg(0xE0) + seg(0xDB)
        if (withDht) f += seg(0xC4)
        return f + seg(0xC0) + seg(0xDA) + byteArrayOf(1, 2, 3) + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }

    @Test fun standardDhtIs420BytesWithCorrectLengthField() {
        val d = Mjpeg.STANDARD_DHT
        assertEquals(420, d.size)
        assertEquals(0xFF, d[0].toInt() and 0xFF); assertEquals(0xC4, d[1].toInt() and 0xFF)
        assertEquals(418, ((d[2].toInt() and 0xFF) shl 8) or (d[3].toInt() and 0xFF))
    }

    @Test fun insertsBeforeSof0WhenMissing() {
        val f = skeleton(false)
        assertFalse(Mjpeg.hasHuffmanTables(f))
        val g = Mjpeg.withHuffmanTables(f)
        assertEquals(f.size + 420, g.size)
        assertTrue(Mjpeg.hasHuffmanTables(g))
        val sofAt = 2 + 4 + 4   // after APP0 and DQT in the skeleton
        assertEquals(0xC4, g[sofAt + 1].toInt() and 0xFF, "DHT sits where SOF0 was")
        assertEquals(0xC0, g[sofAt + 420 + 1].toInt() and 0xFF, "SOF0 follows the DHT")
        assertContentEquals(g, Mjpeg.withHuffmanTables(g), "idempotent")
    }

    @Test fun leavesFramesWithTablesAndNonJpegsAlone() {
        val f = skeleton(true)
        assertTrue(Mjpeg.hasHuffmanTables(f))
        assertSame(f, Mjpeg.withHuffmanTables(f))
        val junk = byteArrayOf(1, 2, 3, 4, 5)
        assertFalse(Mjpeg.isJpeg(junk)); assertSame(junk, Mjpeg.withHuffmanTables(junk))
    }
}
