@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package space.boscon.kapex.camera

import kotlinx.cinterop.*
import platform.posix.*

class SinkException(call: String, val errno: Int) : RuntimeException("$call failed: ${strerror(errno)?.toKString() ?: "?"} (errno $errno)")

/** A file opened for writing; [sync] is fsync. [syncDirectory] makes the name durable too. */
@OptIn(ExperimentalForeignApi::class)
class PosixFileSink(val path: String) : RandomAccessSink {
    private val fd = open(path, O_WRONLY or O_CREAT or O_TRUNC or O_CLOEXEC, 0b110_100_100u).also { if (it < 0) throw SinkException("open($path)", errno) }
    override var position = 0L; private set

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len == 0) return
        b.usePinned { p -> var done = 0; while (done < len) { val n = write(fd, p.addressOf(off + done), (len - done).convert()).toInt(); if (n < 0) { if (errno == EINTR) continue; throw SinkException("write($path)", errno) }; done += n } }
        position += len
    }
    override fun writeAt(pos: Long, b: ByteArray) {
        b.usePinned { p -> var done = 0; while (done < b.size) { val n = pwrite(fd, p.addressOf(done), (b.size - done).convert(), pos + done).toInt(); if (n < 0) { if (errno == EINTR) continue; throw SinkException("pwrite($path)", errno) }; done += n } }
    }
    override fun sync() { if (fsync(fd) < 0) throw SinkException("fsync($path)", errno) }
    override fun close() { close(fd) }

    companion object {
        fun syncDirectory(dir: String) { val d = open(dir, O_RDONLY or O_DIRECTORY); if (d >= 0) { fsync(d); close(d) } }
    }
}
