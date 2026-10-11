package space.boscon.hab.drivers

import space.boscon.hab.hal.HalException
import space.boscon.hab.hal.SerialPort

/**
 * What the SX1262 driver needs from the hardware below it. Two
 * implementations: [BridgeBus] (bench, Heltec as a dumb adapter over USB
 * serial, firmware/23_spi_bridge) and later SpidevBus (flight, E22 module on
 * the Pi's own SPI + GPIO). The driver above is identical.
 */
interface Sx1262Bus : AutoCloseable {
    /** Full-duplex SPI transfer with NSS asserted around the whole frame. */
    fun transfer(tx: ByteArray): ByteArray
    fun reset()
    /** Block while BUSY is high; false on timeout. */
    fun waitBusy(timeoutMs: Int = 100): Boolean
    fun dio1(): Boolean
}

/** Sx1262Bus over the 23_spi_bridge wire protocol. */
class BridgeBus(private val port: SerialPort) : Sx1262Bus {
    private fun expect(tag: Char, call: String) {
        // skip unsolicited DIO1 markers ('!') that may precede a reply
        while (true) {
            val b = port.readExact(1, 500)[0].toInt().toChar()
            if (b == '!') { pendingDio1Edges++; continue }
            if (b != tag) throw HalException(call, 71, "bridge replied '$b', expected '$tag'")
            return
        }
    }
    var pendingDio1Edges = 0; private set

    /**
     * Opening the USB-serial port toggles DTR/RTS, which resets the ESP32;
     * it needs ~2 s to boot. Drain anything it printed, then ping, retrying
     * for up to [bootWaitMs].
     */
    fun ping(bootWaitMs: Int = 4000): Boolean {
        var waited = 0
        while (waited <= bootWaitMs) {
            while (port.read(64, 50).isNotEmpty()) { /* drain boot chatter */ }
            port.write(byteArrayOf('P'.code.toByte()))
            // the USB-serial adapter may deliver the 5 bytes in pieces: accumulate
            val reply = try { port.readExact(5, 300) } catch (e: HalException) { ByteArray(0) }
            if (reply.decodeToString() == "pHAB1") return true
            waited += 350
        }
        return false
    }

    override fun transfer(tx: ByteArray): ByteArray {
        require(tx.size in 1..255) { "bridge transfer length ${tx.size}" }
        port.write(byteArrayOf('X'.code.toByte(), tx.size.toByte()) + tx)
        expect('x', "spi transfer")
        val len = port.readExact(1, 500)[0].toInt() and 0xFF
        if (len != tx.size) throw HalException("spi transfer", 71, "bridge returned $len bytes for ${tx.size}")
        return port.readExact(len, 500)
    }

    override fun reset() {
        port.write(byteArrayOf('R'.code.toByte(), 0)); expect('k', "reset low")
        port.write(byteArrayOf('R'.code.toByte(), 1)); expect('k', "reset high")
    }

    override fun waitBusy(timeoutMs: Int): Boolean {
        port.write(byteArrayOf('W'.code.toByte(), timeoutMs.coerceIn(1, 255).toByte()))
        expect('w', "wait busy")
        return port.readExact(1, timeoutMs + 500)[0].toInt() == 0
    }

    override fun dio1(): Boolean {
        port.write(byteArrayOf('D'.code.toByte())); expect('d', "dio1")
        return port.readExact(1, 500)[0].toInt() != 0
    }

    override fun close() { port.close() }
}

/**
 * Semtech SX1262 — minimal core: reset, status, register access, version.
 * Opcodes from the SX1261/2 datasheet (DS.SX1261-2.W.APP) §13. Modulation
 * and packet setup for GFSK 38.4 kb/s follow in the next milestone; the
 * values will be the ones in firmware/03_prompt_tx and docs/15.
 */
class Sx1262(private val bus: Sx1262Bus) {
    companion object {
        const val OP_GET_STATUS = 0xC0
        const val OP_READ_REGISTER = 0x1D
        const val OP_WRITE_REGISTER = 0x0D
        const val REG_VERSION_STRING = 0x0320   // 16 ASCII bytes, e.g. "SX1262 V2D 2D02"
    }

    private fun busy(call: String) { if (!bus.waitBusy(100)) throw HalException(call, 110, "BUSY stayed high") }

    fun reset() { bus.reset(); busy("reset") }

    /** Status byte: bits 6:4 chip mode (2=STDBY_RC, 3=STDBY_XOSC, 4=FS, 5=RX, 6=TX), bits 3:1 command status. */
    fun status(): Int {
        busy("GetStatus")
        return bus.transfer(byteArrayOf(OP_GET_STATUS.toByte(), 0))[1].toInt() and 0xFF
    }

    fun readRegister(address: Int, count: Int): ByteArray {
        busy("ReadRegister")
        val tx = ByteArray(4 + count)
        tx[0] = OP_READ_REGISTER.toByte(); tx[1] = (address shr 8).toByte(); tx[2] = address.toByte(); tx[3] = 0
        return bus.transfer(tx).copyOfRange(4, 4 + count)
    }

    fun writeRegister(address: Int, data: ByteArray) {
        busy("WriteRegister")
        bus.transfer(byteArrayOf(OP_WRITE_REGISTER.toByte(), (address shr 8).toByte(), address.toByte()) + data)
    }

    fun versionString(): String = readRegister(REG_VERSION_STRING, 16).takeWhile { it != 0.toByte() }.toByteArray().decodeToString()

    fun chipModeName(status: Int) = when ((status shr 4) and 0x7) {
        2 -> "STDBY_RC"; 3 -> "STDBY_XOSC"; 4 -> "FS"; 5 -> "RX"; 6 -> "TX"; else -> "?"
    }
}
