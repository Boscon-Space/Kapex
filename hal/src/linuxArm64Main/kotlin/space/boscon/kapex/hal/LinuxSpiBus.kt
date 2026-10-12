package space.boscon.kapex.hal

import kotlinx.cinterop.*
import platform.posix.*
import space.boscon.kapex.hal.linuxio.*

/**
 * SPI through /dev/spidevB.C and SPI_IOC_MESSAGE(1). Mode 0, MSB first,
 * 8-bit words; the kernel toggles CE. SX1262 wants mode 0 and ≤ 16 MHz.
 */
@OptIn(ExperimentalForeignApi::class)
class LinuxSpiBus(private val path: String = "/dev/spidev0.0", override val speedHz: Int = 2_000_000, override val mode: Int = 0) : SpiBus {
    private val fd: Int = check("open($path)") { open(path, O_RDWR or O_CLOEXEC) }

    init {
        memScoped {
            val m = alloc<UByteVar>(); m.value = mode.toUByte()
            check("SPI_IOC_WR_MODE") { ioctl(fd, hab_ioc_spi_wr_mode(), m.ptr) }
            val bits = alloc<UByteVar>(); bits.value = 8u
            check("SPI_IOC_WR_BITS_PER_WORD") { ioctl(fd, hab_ioc_spi_wr_bits(), bits.ptr) }
            val sp = alloc<UIntVar>(); sp.value = speedHz.toUInt()
            check("SPI_IOC_WR_MAX_SPEED_HZ") { ioctl(fd, hab_ioc_spi_wr_max_speed(), sp.ptr) }
        }
    }

    override fun transfer(tx: ByteArray): ByteArray = memScoped {
        val n = tx.size
        val txBuf = allocArray<UByteVar>(n.coerceAtLeast(1)); tx.forEachIndexed { i, b -> txBuf[i] = b.toUByte() }
        val rxBuf = allocArray<UByteVar>(n.coerceAtLeast(1))
        val tr = alloc<spi_ioc_transfer>()
        memset(tr.ptr, 0, sizeOf<spi_ioc_transfer>().convert())
        tr.tx_buf = txBuf.rawValue.toLong().toULong()
        tr.rx_buf = rxBuf.rawValue.toLong().toULong()
        tr.len = n.toUInt()
        tr.speed_hz = speedHz.toUInt()
        tr.bits_per_word = 8u
        check("SPI_IOC_MESSAGE(1) len=$n") { ioctl(fd, hab_ioc_spi_message(1), tr.ptr) }
        ByteArray(n) { rxBuf[it].toByte() }
    }

    override fun close() { close(fd) }
}
