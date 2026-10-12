package space.boscon.kapex.flightd

import kotlinx.cinterop.*
import platform.posix.*
import space.boscon.kapex.hal.linuxio.hab_sockaddr_un

/** sd_notify without libsystemd: a datagram to $NOTIFY_SOCKET. READY=1 once, WATCHDOG=1 on every pet. */
@OptIn(ExperimentalForeignApi::class)
object Systemd {
    private val path: String? = getenv("NOTIFY_SOCKET")?.toKString()
    val enabled: Boolean get() = path != null
    /** Watchdog interval from WATCHDOG_USEC, in ms; 0 when systemd did not ask for a watchdog. */
    val watchdogMs: Long = getenv("WATCHDOG_USEC")?.toKString()?.toLongOrNull()?.div(1000) ?: 0L

    fun notify(state: String) {
        val p = path ?: return
        memScoped {
            val fd = socket(AF_UNIX, SOCK_DGRAM or SOCK_CLOEXEC, 0); if (fd < 0) return
            val addr = alloc<hab_sockaddr_un>(); memset(addr.ptr, 0, sizeOf<hab_sockaddr_un>().convert()); addr.sun_family = AF_UNIX.convert()
            val bytes = p.encodeToByteArray(); bytes.forEachIndexed { i, b -> addr.sun_path[i] = b }
            if (p.startsWith('@')) addr.sun_path[0] = 0   // abstract namespace
            val msg = state.encodeToByteArray()
            msg.usePinned { sendto(fd, it.addressOf(0), msg.size.convert(), 0, addr.ptr.reinterpret(), (sizeOf<hab_sockaddr_un>()).convert()) }
            close(fd)
        }
    }
    fun ready() = notify("READY=1")
    fun pet() = notify("WATCHDOG=1")
    fun status(s: String) = notify("STATUS=$s")
}
