package space.boscon.kapex.camera

import javax.imageio.ImageIO
import kotlin.test.*

/**
 * A real 1280x720 MJPEG frame captured from the Logitech C270 on the Pi
 * (2026-10-20, v4l2-ctl --stream-to). ImageIO is a strict baseline decoder:
 * it fails on the raw frame and must succeed once the tables are inserted.
 */
class C270FrameTest {
    private val raw = javaClass.getResourceAsStream("/c270-frame0.jpg")!!.readBytes()

    @Test fun c270FrameHasNoHuffmanTables() {
        assertTrue(Mjpeg.isJpeg(raw)); assertFalse(Mjpeg.hasHuffmanTables(raw))
        assertFailsWith<Exception> { ImageIO.read(raw.inputStream()) ?: throw IllegalStateException("no decoder") }
    }

    @Test fun frameDecodesAt1280x720AfterInsertingTables() {
        val fixed = Mjpeg.withHuffmanTables(raw)
        assertEquals(raw.size + 420, fixed.size)
        val img = ImageIO.read(fixed.inputStream())
        assertNotNull(img); assertEquals(1280, img.width); assertEquals(720, img.height)
    }

    @Test fun aviOfRealFramesParsesBack() {
        val sink = MemorySink(); val w = AviWriter(sink, 1280, 720, 15)
        repeat(3) { w.addFrame(Mjpeg.withHuffmanTables(raw)) }
        w.finish()
        val b = sink.bytes()
        assertEquals("RIFF", b.copyOfRange(0, 4).decodeToString())
        assertEquals(b.size - 8L, (b[4].toLong() and 0xFF) or ((b[5].toLong() and 0xFF) shl 8) or ((b[6].toLong() and 0xFF) shl 16) or ((b[7].toLong() and 0xFF) shl 24))
    }
}
