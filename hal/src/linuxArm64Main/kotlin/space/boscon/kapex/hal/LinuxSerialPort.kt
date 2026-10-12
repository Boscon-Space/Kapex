package space.boscon.kapex.hal

import kotlinx.cinterop.*
import platform.posix.*

/** Raw 8N1 serial through termios. Used for the GPS UART and the Heltec USB-CDC bridge. */
@OptIn(ExperimentalForeignApi::class)
class LinuxSerialPort(private val path: String, baud: Int) : SerialPort {
    private val fd: Int = check("open($path)") { open(path, O_RDWR or O_NOCTTY or O_CLOEXEC) }

    init {
        memScoped {
            val t = alloc<termios>()
            check("tcgetattr") { tcgetattr(fd, t.ptr) }
            cfmakeraw(t.ptr)
            val speed = when (baud) {
                9600 -> B9600; 19200 -> B19200; 38400 -> B38400; 57600 -> B57600
                115200 -> B115200; 230400 -> B230400; 460800 -> B460800; 921600 -> B921600
                else -> throw HalException("baud", 22, "unsupported baud $baud")
            }
            cfsetispeed(t.ptr, speed.toUInt()); cfsetospeed(t.ptr, speed.toUInt())
            t.c_cflag = t.c_cflag or CLOCAL.toUInt() or CREAD.toUInt()
            t.c_cflag = t.c_cflag and CRTSCTS.toUInt().inv()
            t.c_cc[VMIN] = 0u; t.c_cc[VTIME] = 0u   // non-blocking reads; we poll()
            check("tcsetattr") { tcsetattr(fd, TCSANOW, t.ptr) }
            tcflush(fd, TCIOFLUSH)
        }
    }

    override fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        bytes.usePinned { p ->
            var off = 0
            while (off < bytes.size) {
                val n = write(fd, p.addressOf(off), (bytes.size - off).convert()).toInt()
                if (n < 0) fail("write($path)")
                off += n
            }
        }
    }

    override fun read(max: Int, timeoutMs: Int): ByteArray = memScoped {
        val pfd = alloc<pollfd>(); pfd.fd = fd; pfd.events = POLLIN.toShort()
        val r = poll(pfd.ptr, 1u, timeoutMs)
        if (r < 0) fail("poll($path)")
        if (r == 0) return ByteArray(0)
        val buf = ByteArray(max)
        val n = buf.usePinned { p -> read(fd, p.addressOf(0), max.convert()).toInt() }
        if (n < 0) fail("read($path)")
        buf.copyOf(n)
    }

    override fun close() { close(fd) }
}
