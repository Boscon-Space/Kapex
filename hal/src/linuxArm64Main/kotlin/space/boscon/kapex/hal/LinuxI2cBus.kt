package space.boscon.kapex.hal

import kotlinx.cinterop.*
import platform.posix.*
import space.boscon.kapex.hal.linuxio.*

/** I²C through /dev/i2c-N and the I2C_RDWR ioctl (combined transactions). */
@OptIn(ExperimentalForeignApi::class)
class LinuxI2cBus(private val path: String = "/dev/i2c-1") : I2cBus {
    private val fd: Int = check("open($path)") { open(path, O_RDWR or O_CLOEXEC) }

    private fun transfer(address: Int, write: ByteArray?, readCount: Int, call: String): ByteArray = memScoped {
        val nmsgs = (if (write != null) 1 else 0) + (if (readCount > 0) 1 else 0)
        val msgs = allocArray<i2c_msg>(nmsgs)
        var i = 0
        val wbuf = write?.let { w -> allocArray<UByteVar>(w.size.coerceAtLeast(1)).also { b -> w.forEachIndexed { k, v -> b[k] = v.toUByte() } } }
        if (write != null) {
            msgs[i].addr = address.toUShort(); msgs[i].flags = 0u
            msgs[i].len = write.size.toUShort(); msgs[i].buf = wbuf; i++
        }
        val rbuf = if (readCount > 0) allocArray<UByteVar>(readCount) else null
        if (readCount > 0) {
            msgs[i].addr = address.toUShort(); msgs[i].flags = I2C_M_RD.toUShort()
            msgs[i].len = readCount.toUShort(); msgs[i].buf = rbuf; i++
        }
        val data = alloc<i2c_rdwr_ioctl_data>()
        data.msgs = msgs; data.nmsgs = nmsgs.toUInt()
        check(call) { ioctl(fd, hab_ioc_i2c_rdwr(), data.ptr) }
        if (rbuf != null) ByteArray(readCount) { rbuf[it].toByte() } else ByteArray(0)
    }

    override fun write(address: Int, bytes: ByteArray) {
        transfer(address, bytes, 0, "I2C_RDWR write 0x${address.toString(16)}")
    }

    override fun readRegister(address: Int, reg: Int, count: Int): ByteArray =
        transfer(address, byteArrayOf(reg.toByte()), count, "I2C_RDWR read 0x${address.toString(16)}/0x${reg.toString(16)}")

    override fun scan(): List<Int> = (0x03..0x77).filter { addr ->
        try { transfer(addr, ByteArray(0), 0, "probe"); true } catch (e: HalException) { false }
    }

    override fun close() { close(fd) }
}
