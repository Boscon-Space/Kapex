package space.boscon.kapex.camrec

import kotlinx.cinterop.*
import platform.posix.*
import space.boscon.kapex.camera.*
import space.boscon.kapex.hal.HalException
import space.boscon.kapex.hal.LinuxV4l2Camera
import space.boscon.kapex.hal.linuxio.hab_free_bytes
import kotlin.concurrent.AtomicInt

/**
 * camrec: records one camera into AVI segments.
 *   camrec [device] [dir] [segment_s] [WxH] [fps] [min_free_mb] [max_segments]
 * defaults: /dev/video0 /home/hab/footage 60 1280x720 15 500 0 (0 = record until stopped)
 *
 * Each segment is a self-contained MJPEG AVI named cam0-<UTC stamp>-<n>.avi.
 * Every 2 s the header is patched and the file fsynced, so a power loss costs
 * at most the last 2 s plus the index of the open file. Below [min_free_mb]
 * free on the folder's filesystem, recording pauses and says so. A camera that
 * vanishes is retried every 5 s. One NDJSON line per segment goes to stdout
 * (the journal). SIGTERM finishes the current segment and exits.
 */
private val stopFlag = AtomicInt(0)

@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    val dev = args.getOrNull(0) ?: "/dev/video0"
    val dir = args.getOrNull(1) ?: "/home/hab/footage"
    val segmentS = args.getOrNull(2)?.toIntOrNull() ?: 60
    val (w, h) = (args.getOrNull(3) ?: "1280x720").split('x').map { it.toInt() }
    val fps = args.getOrNull(4)?.toIntOrNull() ?: 15
    val minFreeMb = args.getOrNull(5)?.toLongOrNull() ?: 500
    val maxSegments = args.getOrNull(6)?.toIntOrNull() ?: 0
    setvbuf(stdout, null, _IOLBF, 0u)
    signal(SIGTERM, staticCFunction<Int, Unit> { stopFlag.value = 1 })
    signal(SIGINT, staticCFunction<Int, Unit> { stopFlag.value = 1 })
    mkdir(dir, 0b111_101_101u)
    log("start", "\"dev\":${q(dev)},\"dir\":${q(dir)},\"segment_s\":$segmentS,\"size\":\"${w}x$h\",\"fps\":$fps,\"min_free_mb\":$minFreeMb,\"max_segments\":$maxSegments")

    var segment = 0
    while (stopFlag.value == 0) {
        val free = freeMb(dir)
        if (free < minFreeMb) { log("paused", "\"reason\":\"disk\",\"free_mb\":$free,\"min_free_mb\":$minFreeMb"); sleep(30u); continue }
        val cam = try { LinuxV4l2Camera(dev, w, h, fps).also { it.start() } } catch (e: Exception) {
            log("camera_absent", "\"error\":${q(e.message ?: "?")}"); sleep(5u); continue
        }
        val i = cam.info
        log("camera", "\"card\":${q(i.card)},\"driver\":${q(i.driver)},\"bus\":${q(i.bus)},\"size\":\"${i.width}x${i.height}\",\"fourcc\":${q(i.fourcc)},\"fps\":${i.fps}")
        if (i.width != w || i.height != h || i.fps != fps) log("warn", "\"msg\":\"camera granted ${i.width}x${i.height}@${i.fps}, asked ${w}x$h@$fps\"")
        try {
            while (stopFlag.value == 0) {
                if (freeMb(dir) < minFreeMb) break
                segment++
                val path = "$dir/cam0-${utcStamp()}-${pad(segment, 4)}.avi"
                val sink = PosixFileSink(path)
                val avi = AviWriter(sink, i.width, i.height, i.fps)
                val t0 = monoMs(); var lastCheckpoint = t0; var lastSeq = -1; var dropped = 0; var timeouts = 0; var skipped = 0
                var full = false
                while (stopFlag.value == 0 && monoMs() - t0 < segmentS * 1000L) {
                    val f = cam.nextFrame(2000)
                    if (f == null) { if (++timeouts >= 5) throw HalException("nextFrame", ETIMEDOUT, "no frame for 10 s"); continue }
                    timeouts = 0
                    if (lastSeq >= 0 && f.sequence > lastSeq + 1) dropped += f.sequence - lastSeq - 1
                    lastSeq = f.sequence
                    val jpeg = f.bytes
                    if (!Mjpeg.isJpeg(jpeg)) { skipped++; continue }
                    if (!avi.addFrame(Mjpeg.withHuffmanTables(jpeg))) { full = true; break }
                    if (monoMs() - lastCheckpoint >= 2000) { avi.checkpoint(); lastCheckpoint = monoMs() }
                }
                avi.finish(); sink.close(); PosixFileSink.syncDirectory(dir)
                val secs = (monoMs() - t0) / 1000.0
                if (maxSegments > 0 && segment >= maxSegments) stopFlag.value = 1
                log("segment", "\"path\":${q(path)},\"frames\":${avi.frames},\"dropped\":$dropped,\"skipped\":$skipped,\"bytes\":${sink.position},\"seconds\":$secs,\"expected_frames\":${(secs * i.fps).toInt()},\"full\":$full,\"free_mb\":${freeMb(dir)}")
            }
        } catch (e: Exception) {
            log("camera_lost", "\"error\":${q(e.message ?: "?")}")
            sleep(5u)
        } finally { runCatching { cam.close() } }
    }
    log("stop", "\"segments\":$segment")
}

private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
private fun pad(n: Int, w: Int) = n.toString().padStart(w, '0')
private fun log(kind: String, fields: String) = println("{\"t\":${wallMs()},\"kind\":\"$kind\",$fields}")

@OptIn(ExperimentalForeignApi::class)
private fun monoMs(): Long = memScoped { val ts = alloc<timespec>(); clock_gettime(CLOCK_MONOTONIC, ts.ptr); ts.tv_sec * 1000 + ts.tv_nsec / 1_000_000 }
@OptIn(ExperimentalForeignApi::class)
private fun wallMs(): Long = memScoped { val ts = alloc<timespec>(); clock_gettime(CLOCK_REALTIME, ts.ptr); ts.tv_sec * 1000 + ts.tv_nsec / 1_000_000 }
@OptIn(ExperimentalForeignApi::class)
private fun utcStamp(): String = memScoped {
    val t = alloc<time_tVar>(); time(t.ptr); val tm = alloc<tm>(); gmtime_r(t.ptr, tm.ptr)
    "${tm.tm_year + 1900}${pad(tm.tm_mon + 1, 2)}${pad(tm.tm_mday, 2)}T${pad(tm.tm_hour, 2)}${pad(tm.tm_min, 2)}${pad(tm.tm_sec, 2)}Z"
}
@OptIn(ExperimentalForeignApi::class)
private fun freeMb(dir: String): Long { val b = hab_free_bytes(dir); return if (b < 0) 0 else b / (1024 * 1024) }
