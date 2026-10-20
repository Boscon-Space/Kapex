package space.boscon.kapex.camera

/** Where an AVI goes: sequential writes plus patching earlier positions. */
interface RandomAccessSink : AutoCloseable {
    fun write(b: ByteArray, off: Int = 0, len: Int = b.size - off)
    fun writeAt(pos: Long, b: ByteArray)
    val position: Long
    /** Push everything to the medium (fsync on a file). */
    fun sync()
}

/** In-memory sink for tests. */
class MemorySink : RandomAccessSink {
    private var buf = ByteArray(1 shl 16); private var size = 0
    override val position: Long get() = size.toLong()
    private fun ensure(n: Int) { if (n > buf.size) buf = buf.copyOf(maxOf(n, buf.size * 2)) }
    override fun write(b: ByteArray, off: Int, len: Int) { ensure(size + len); b.copyInto(buf, size, off, off + len); size += len }
    override fun writeAt(pos: Long, b: ByteArray) { ensure(pos.toInt() + b.size); b.copyInto(buf, pos.toInt()) }
    override fun sync() {}
    override fun close() {}
    fun bytes(): ByteArray = buf.copyOf(size)
}

/**
 * Writes one video stream of MJPEG frames as a plain AVI 1.0 file
 * ('vids' / 'MJPG', 24 bit). The header is written first with zero counts
 * and patched by [checkpoint] and [finish], so a file cut by a power loss
 * still declares the frames written up to the last checkpoint; only the
 * idx1 index at the end is missing in that case (ffmpeg rebuilds it).
 * One file must stay under 1 GiB: RIFF sizes are 32-bit and this writer
 * does not do OpenDML.
 */
class AviWriter(private val sink: RandomAccessSink, val width: Int, val height: Int, val fps: Int) {
    companion object {
        const val MAX_BYTES = 1L shl 30
        private const val AVIF_HASINDEX = 0x10
        private const val AVIF_TRUSTCKTYPE = 0x800
        private const val AVIIF_KEYFRAME = 0x10
        private const val HDRL_BYTES = 4 + 64 + (8 + 4 + 64 + 48)   // 'hdrl' + avih chunk + strl list
        /** Byte offset of the 'movi' LIST header from the file start. */
        const val MOVI_LIST_AT = 12L + 8 + HDRL_BYTES
    }
    private val index = ArrayList<Long>()      // (offset shl 32) or size, per frame, offset relative to the 'movi' fourcc
    var frames = 0; private set
    var bytes = 0L; private set
    var maxFrame = 0; private set
    private var finished = false
    private var riffSizeAt = 4L; private var totalFramesAt = 0L; private var strhLengthAt = 0L; private var suggestedAt = 0L; private var strhSuggestedAt = 0L; private var moviSizeAt = 0L

    init { writeHeader() }

    private fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun fourcc(s: String) = s.encodeToByteArray().also { require(it.size == 4) }
    private fun put(vararg parts: ByteArray) { for (p in parts) sink.write(p) }

    private fun writeHeader() {
        put(fourcc("RIFF"), le32(0), fourcc("AVI "))
        put(fourcc("LIST"), le32(HDRL_BYTES.toLong()), fourcc("hdrl"))
        // avih, 56 bytes
        put(fourcc("avih"), le32(56))
        put(le32(1_000_000L / fps), le32(0), le32(0), le32((AVIF_HASINDEX or AVIF_TRUSTCKTYPE).toLong()))
        totalFramesAt = sink.position; put(le32(0))
        put(le32(0), le32(1)); suggestedAt = sink.position; put(le32(0))
        put(le32(width.toLong()), le32(height.toLong()), le32(0), le32(0), le32(0), le32(0))
        // strl: strh (56) + strf (40)
        put(fourcc("LIST"), le32((4 + 64 + 48).toLong()), fourcc("strl"))
        put(fourcc("strh"), le32(56))
        put(fourcc("vids"), fourcc("MJPG"), le32(0), le16(0), le16(0), le32(0), le32(1), le32(fps.toLong()), le32(0))
        strhLengthAt = sink.position; put(le32(0))
        strhSuggestedAt = sink.position; put(le32(0))
        put(le32(0xFFFFFFFFL), le32(0), le16(0), le16(0), le16(width), le16(height))
        put(fourcc("strf"), le32(40))
        put(le32(40), le32(width.toLong()), le32(height.toLong()), le16(1), le16(24), fourcc("MJPG"),
            le32(width.toLong() * height * 3), le32(0), le32(0), le32(0), le32(0))
        check(sink.position == MOVI_LIST_AT) { "header layout: ${sink.position} != $MOVI_LIST_AT" }
        put(fourcc("LIST")); moviSizeAt = sink.position; put(le32(4), fourcc("movi"))
    }

    /** Append one JPEG frame. Returns false (and writes nothing) if the file would pass [MAX_BYTES]. */
    fun addFrame(jpeg: ByteArray): Boolean {
        check(!finished)
        val padded = jpeg.size + (jpeg.size and 1)
        if (sink.position + 8 + padded + 8 + 16L * (frames + 1) > MAX_BYTES) return false
        val offset = sink.position - (MOVI_LIST_AT + 8)   // relative to the 'movi' fourcc
        put(fourcc("00dc"), le32(jpeg.size.toLong()))
        sink.write(jpeg)
        if (padded != jpeg.size) sink.write(byteArrayOf(0))
        index.add((offset shl 32) or jpeg.size.toLong())
        frames++; bytes += jpeg.size; if (jpeg.size > maxFrame) maxFrame = jpeg.size
        return true
    }

    private fun patchCounts(fileEnd: Long) {
        sink.writeAt(riffSizeAt, le32(fileEnd - 8))
        sink.writeAt(totalFramesAt, le32(frames.toLong()))
        sink.writeAt(strhLengthAt, le32(frames.toLong()))
        sink.writeAt(suggestedAt, le32(maxFrame.toLong())); sink.writeAt(strhSuggestedAt, le32(maxFrame.toLong()))
        sink.writeAt(moviSizeAt, le32(4 + (moviEnd - (MOVI_LIST_AT + 12))))   // 'movi' fourcc + chunks
    }
    private var moviEnd: Long = MOVI_LIST_AT + 12

    /** Make the file consistent as written so far (no index yet) and push it to the medium. */
    fun checkpoint() {
        check(!finished)
        moviEnd = sink.position
        patchCounts(sink.position)
        sink.sync()
    }

    /** Write the index, patch the header, sync. The writer is unusable afterwards. */
    fun finish() {
        if (finished) return
        moviEnd = sink.position
        put(fourcc("idx1"), le32(16L * frames))
        for (e in index) put(fourcc("00dc"), le32(AVIIF_KEYFRAME.toLong()), le32(e ushr 32), le32(e and 0xFFFFFFFFL))
        patchCounts(sink.position)
        sink.sync()
        finished = true
    }
}
