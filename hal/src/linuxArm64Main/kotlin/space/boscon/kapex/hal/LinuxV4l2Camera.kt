package space.boscon.kapex.hal

import kotlinx.cinterop.*
import platform.posix.*
import space.boscon.kapex.hal.linuxio.*

/** One compressed frame copied out of the kernel buffer. [sequence] is the driver's frame counter: a gap means a dropped frame. */
class CameraFrame(val bytes: ByteArray, val sequence: Int, val timestampUs: Long)

data class CameraInfo(val driver: String, val card: String, val bus: String, val width: Int, val height: Int, val fourcc: String, val fps: Int)

/**
 * A UVC camera through V4L2 (videodev2.h): MJPEG, mmap streaming, no libv4l.
 * The camera compresses; the Pi 5 has no video encoder, so frames are stored
 * as the camera delivers them. Frames are copied out and the buffer requeued
 * at once, so the caller may keep a frame as long as it likes.
 */
@OptIn(ExperimentalForeignApi::class)
class LinuxV4l2Camera(private val path: String, width: Int, height: Int, fps: Int, buffers: Int = 4) : AutoCloseable {
    private val fd: Int = check("open($path)") { open(path, O_RDWR or O_NONBLOCK or O_CLOEXEC) }
    private val type = V4L2_BUF_TYPE_VIDEO_CAPTURE.toUInt()
    private val memoryMmap = V4L2_MEMORY_MMAP.toUInt()
    private var maps = arrayOfNulls<COpaquePointer>(0)
    private var lengths = IntArray(0)
    val info: CameraInfo
    private var streaming = false

    init {
        try {
            info = memScoped {
                val cap = alloc<v4l2_capability>()
                check("VIDIOC_QUERYCAP") { ioctl(fd, hab_ioc_vid_querycap(), cap.ptr) }
                val caps = if (cap.capabilities and V4L2_CAP_DEVICE_CAPS.toUInt() != 0u) cap.device_caps else cap.capabilities
                if (caps and V4L2_CAP_VIDEO_CAPTURE.toUInt() == 0u || caps and V4L2_CAP_STREAMING.toUInt() == 0u)
                    throw HalException("VIDIOC_QUERYCAP", 0, "$path is not a streaming capture device")
                val fmt = alloc<v4l2_format>(); memset(fmt.ptr, 0, sizeOf<v4l2_format>().convert())
                fmt.type = type
                fmt.fmt.pix.width = width.toUInt(); fmt.fmt.pix.height = height.toUInt()
                fmt.fmt.pix.pixelformat = hab_v4l2_fourcc_mjpeg(); fmt.fmt.pix.field = V4L2_FIELD_NONE.toUInt()
                check("VIDIOC_S_FMT") { ioctl(fd, hab_ioc_vid_s_fmt(), fmt.ptr) }
                if (fmt.fmt.pix.pixelformat != hab_v4l2_fourcc_mjpeg()) throw HalException("VIDIOC_S_FMT", 0, "$path cannot deliver MJPEG")
                val parm = alloc<v4l2_streamparm>(); memset(parm.ptr, 0, sizeOf<v4l2_streamparm>().convert())
                parm.type = type
                parm.parm.capture.timeperframe.numerator = 1u; parm.parm.capture.timeperframe.denominator = fps.toUInt()
                check("VIDIOC_S_PARM") { ioctl(fd, hab_ioc_vid_s_parm(), parm.ptr) }
                val tpf = parm.parm.capture.timeperframe
                val gotFps = if (tpf.numerator == 0u) 0 else (tpf.denominator / tpf.numerator).toInt()
                CameraInfo(cap.driver.reinterpret<ByteVar>().toKString(), cap.card.reinterpret<ByteVar>().toKString(), cap.bus_info.reinterpret<ByteVar>().toKString(),
                    fmt.fmt.pix.width.toInt(), fmt.fmt.pix.height.toInt(), fourcc(fmt.fmt.pix.pixelformat), gotFps)
            }
            memScoped {
                val req = alloc<v4l2_requestbuffers>(); memset(req.ptr, 0, sizeOf<v4l2_requestbuffers>().convert())
                req.count = buffers.toUInt(); req.type = type; req.memory = memoryMmap
                check("VIDIOC_REQBUFS") { ioctl(fd, hab_ioc_vid_reqbufs(), req.ptr) }
                val n = req.count.toInt()
                if (n < 2) throw HalException("VIDIOC_REQBUFS", 0, "only $n buffers granted")
                maps = arrayOfNulls(n); lengths = IntArray(n)
                for (i in 0 until n) {
                    val b = alloc<v4l2_buffer>(); memset(b.ptr, 0, sizeOf<v4l2_buffer>().convert())
                    b.type = type; b.memory = memoryMmap; b.index = i.toUInt()
                    check("VIDIOC_QUERYBUF($i)") { ioctl(fd, hab_ioc_vid_querybuf(), b.ptr) }
                    val p = mmap(null, b.length.convert(), PROT_READ, MAP_SHARED, fd, b.m.offset.convert())
                    if (p == MAP_FAILED || p == null) fail("mmap(buffer $i)")
                    maps[i] = p; lengths[i] = b.length.toInt()
                }
            }
        } catch (e: Throwable) { close(fd); throw e }
    }

    private fun queue(i: Int) = memScoped {
        val b = alloc<v4l2_buffer>(); memset(b.ptr, 0, sizeOf<v4l2_buffer>().convert())
        b.type = type; b.memory = memoryMmap; b.index = i.toUInt()
        check("VIDIOC_QBUF($i)") { ioctl(fd, hab_ioc_vid_qbuf(), b.ptr) }
    }

    fun start() {
        if (streaming) return
        for (i in maps.indices) queue(i)
        memScoped { val t = alloc<IntVar>(); t.value = type.toInt(); check("VIDIOC_STREAMON") { ioctl(fd, hab_ioc_vid_streamon(), t.ptr) } }
        streaming = true
    }

    /**
     * The next frame, or null when none arrived within [timeoutMs] or the
     * driver flagged the buffer as errored (it is requeued and skipped).
     * Throws when the device goes away (POLLERR / ENODEV).
     */
    fun nextFrame(timeoutMs: Int): CameraFrame? = memScoped {
        val pfd = alloc<pollfd>(); pfd.fd = fd; pfd.events = POLLIN.toShort()
        val r = poll(pfd.ptr, 1u, timeoutMs)
        if (r < 0) { if (errno == EINTR) return null; fail("poll($path)") }
        if (r == 0) return null
        if (pfd.revents.toInt() and POLLERR != 0) throw HalException("poll($path)", EIO, "POLLERR, device gone?")
        val b = alloc<v4l2_buffer>(); memset(b.ptr, 0, sizeOf<v4l2_buffer>().convert())
        b.type = type; b.memory = memoryMmap
        if (ioctl(fd, hab_ioc_vid_dqbuf(), b.ptr) < 0) { if (errno == EAGAIN) return null; fail("VIDIOC_DQBUF") }
        val i = b.index.toInt(); val n = b.bytesused.toInt()
        val errored = (b.flags and V4L2_BUF_FLAG_ERROR.toUInt()) != 0u
        val bytes = ByteArray(n)
        if (n > 0) bytes.usePinned { memcpy(it.addressOf(0), maps[i], n.convert()) }
        val ts = b.timestamp.tv_sec * 1_000_000L + b.timestamp.tv_usec
        val seq = b.sequence.toInt()
        queue(i)
        if (errored || n < 4) null else CameraFrame(bytes, seq, ts)
    }

    fun stop() {
        if (!streaming) return
        memScoped { val t = alloc<IntVar>(); t.value = type.toInt(); ioctl(fd, hab_ioc_vid_streamoff(), t.ptr) }
        streaming = false
    }

    override fun close() {
        runCatching { stop() }
        for (i in maps.indices) maps[i]?.let { munmap(it, lengths[i].convert()); maps[i] = null }
        close(fd)
    }

    private fun fourcc(v: UInt) = CharArray(4) { ((v shr (8 * it)) and 0xFFu).toInt().toChar() }.concatToString()
}
