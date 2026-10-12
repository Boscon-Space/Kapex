package space.boscon.kapex.flightd

import kotlinx.cinterop.*
import platform.posix.*
import space.boscon.kapex.hal.HalException

/**
 * NDJSON flight log on the synced /log partition: one record per line,
 * O_SYNC so a brownout loses at most the record being written, a new file
 * every [rotateS] seconds so a torn file costs minutes, not the flight.
 */
@OptIn(ExperimentalForeignApi::class)
class FlightLog(private val dir: String = "/log/flight", private val rotateS: Long = 600) : AutoCloseable {
    private var fd = -1; private var openedAt = 0L; var currentPath = ""; private set
    var records = 0L; private set

    init { mkdir(dir, 0b111_101_101u) }   // 0755, exists is fine

    private fun now(): Long = memScoped { val ts = alloc<timespec>(); clock_gettime(CLOCK_REALTIME, ts.ptr); ts.tv_sec }
    private fun nowMs(): Long = memScoped { val ts = alloc<timespec>(); clock_gettime(CLOCK_REALTIME, ts.ptr); ts.tv_sec * 1000 + ts.tv_nsec / 1_000_000 }

    private fun rotateIfDue() {
        val t = now()
        if (fd >= 0 && t - openedAt < rotateS) return
        if (fd >= 0) close(fd)
        currentPath = "$dir/flight-$t.ndjson"
        fd = open(currentPath, O_WRONLY or O_CREAT or O_APPEND or O_SYNC or O_CLOEXEC, 0b110_100_100u)
        if (fd < 0) throw HalException("open($currentPath)", errno, strerror(errno)?.toKString() ?: "?")
        openedAt = t
    }

    /** [fields] is already JSON-ish: key to raw value text (numbers unquoted, strings quoted by the caller). */
    fun write(kind: String, fields: Map<String, String>) {
        rotateIfDue()
        val line = buildString {
            append("{\"t\":").append(nowMs()).append(",\"kind\":\"").append(kind).append('"')
            for ((k, v) in fields) append(",\"").append(k).append("\":").append(v)
            append("}\n")
        }
        val bytes = line.encodeToByteArray()
        bytes.usePinned { var off = 0; while (off < bytes.size) { val n = write(fd, it.addressOf(off), (bytes.size - off).convert()).toInt(); if (n < 0) throw HalException("write(log)", errno, strerror(errno)?.toKString() ?: "?"); off += n } }
        records++
    }
    override fun close() { if (fd >= 0) close(fd); fd = -1 }
}

fun jsonStr(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
