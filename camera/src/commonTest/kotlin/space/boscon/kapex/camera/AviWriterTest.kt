package space.boscon.kapex.camera

import kotlin.test.*

/** Parses the written file with its own tiny RIFF reader, independent of the writer's bookkeeping. */
class AviWriterTest {
    private fun u32(b: ByteArray, at: Int): Long = (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)
    private fun cc(b: ByteArray, at: Int) = b.copyOfRange(at, at + 4).decodeToString()
    private fun frame(n: Int, size: Int) = ByteArray(size) { ((it + n) and 0xFF).toByte() }

    private fun parse(b: ByteArray): Map<String, Any> {
        assertEquals("RIFF", cc(b, 0)); assertEquals(b.size - 8L, u32(b, 4)); assertEquals("AVI ", cc(b, 8))
        assertEquals("LIST", cc(b, 12)); assertEquals("hdrl", cc(b, 20))
        assertEquals("avih", cc(b, 24)); val totalFrames = u32(b, 24 + 8 + 16)
        val strlAt = 24 + 8 + 56; assertEquals("LIST", cc(b, strlAt)); assertEquals("strl", cc(b, strlAt + 8))
        assertEquals("strh", cc(b, strlAt + 12)); assertEquals("vids", cc(b, strlAt + 20)); assertEquals("MJPG", cc(b, strlAt + 24))
        val strhLength = u32(b, strlAt + 20 + 32)
        val strfAt = strlAt + 12 + 64; assertEquals("strf", cc(b, strfAt)); assertEquals("MJPG", cc(b, strfAt + 8 + 16))
        val moviAt = AviWriter.MOVI_LIST_AT.toInt(); assertEquals("LIST", cc(b, moviAt)); assertEquals("movi", cc(b, moviAt + 8))
        val moviSize = u32(b, moviAt + 4)
        val frames = ArrayList<ByteArray>(); val offsets = ArrayList<Long>()
        var p = moviAt + 12; val moviEnd = moviAt + 8 + moviSize.toInt()
        while (p < moviEnd) { assertEquals("00dc", cc(b, p)); val n = u32(b, p + 4).toInt(); offsets.add((p - (moviAt + 8)).toLong()); frames.add(b.copyOfRange(p + 8, p + 8 + n)); p += 8 + n + (n and 1) }
        assertEquals(moviEnd, p, "movi size covers whole chunks")
        var idx: List<Pair<Long, Long>>? = null
        if (p < b.size) { assertEquals("idx1", cc(b, p)); val n = u32(b, p + 4).toInt() / 16; idx = (0 until n).map { i -> val e = p + 8 + 16 * i; assertEquals("00dc", cc(b, e)); u32(b, e + 8) to u32(b, e + 12) } }
        return mapOf("totalFrames" to totalFrames, "strhLength" to strhLength, "frames" to frames, "offsets" to offsets, "idx" to (idx ?: emptyList<Pair<Long, Long>>()), "hasIdx" to (idx != null))
    }

    @Test fun threeFramesRoundTripWithIndex() {
        val sink = MemorySink(); val w = AviWriter(sink, 1280, 720, 15)
        val inputs = listOf(frame(1, 1001), frame(2, 500), frame(3, 77))   // odd sizes exercise padding
        inputs.forEach { assertTrue(w.addFrame(it)) }
        w.finish()
        val r = parse(sink.bytes())
        assertEquals(3L, r["totalFrames"]); assertEquals(3L, r["strhLength"])
        @Suppress("UNCHECKED_CAST") val frames = r["frames"] as List<ByteArray>
        assertEquals(3, frames.size); inputs.zip(frames).forEach { (a, b) -> assertContentEquals(a, b) }
        assertTrue(r["hasIdx"] as Boolean)
        @Suppress("UNCHECKED_CAST") val idx = r["idx"] as List<Pair<Long, Long>>
        @Suppress("UNCHECKED_CAST") val offsets = r["offsets"] as List<Long>
        assertEquals(offsets, idx.map { it.first }); assertEquals(inputs.map { it.size.toLong() }, idx.map { it.second })
    }

    @Test fun checkpointLeavesAPlayableTruncatedFile() {
        val sink = MemorySink(); val w = AviWriter(sink, 640, 480, 30)
        repeat(5) { assertTrue(w.addFrame(frame(it, 300))) }
        w.checkpoint()
        // simulate power loss: a sixth frame half-written after the checkpoint
        sink.write(byteArrayOf(0x30, 0x30, 0x64, 0x63, 1, 0, 0, 0, 9))
        val cut = sink.bytes().copyOf((sink.position - 9).toInt())   // what the checkpointed part declares
        val r = parse(cut)
        assertEquals(5L, r["totalFrames"]); assertFalse(r["hasIdx"] as Boolean)
        @Suppress("UNCHECKED_CAST") assertEquals(5, (r["frames"] as List<ByteArray>).size)
    }

    @Test fun refusesToPassOneGib() {
        val sink = object : RandomAccessSink {   // counts only; never stores
            override var position = 0L
            override fun write(b: ByteArray, off: Int, len: Int) { position += len }
            override fun writeAt(pos: Long, b: ByteArray) {}
            override fun sync() {}; override fun close() {}
        }
        val w = AviWriter(sink, 1280, 720, 15)
        val big = ByteArray(16 shl 20)
        var n = 0; while (w.addFrame(big)) n++
        assertTrue(n in 60..63, "accepted $n frames of 16 MiB")
        assertTrue(sink.position < AviWriter.MAX_BYTES)
    }
}
