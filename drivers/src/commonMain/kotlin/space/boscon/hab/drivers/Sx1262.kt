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

    /** Bytes the Heltec's GPS UART produced since the last call (bridge 'N'). */
    fun drainGps(): ByteArray {
        port.write(byteArrayOf('N'.code.toByte())); expect('n', "gps drain")
        val len = port.readExact(1, 500)[0].toInt() and 0xFF
        return if (len == 0) ByteArray(0) else port.readExact(len, 500)
    }

    override fun close() { port.close() }
}

/**
 * Semtech SX1262 driver: GFSK as RadioLib 7.7.1's SX1262::beginFSK configures
 * it, so a packet sent from here is received by firmware/09_telemetry_rx
 * unchanged. Every command below is the SPI stream RadioLib issues, in the
 * same order, with the same values (SX1261/2 datasheet §13; RadioLib 7.7.1
 * sources read for this port — see payload/docs/04-worklog.md).
 */
class Sx1262(private val bus: Sx1262Bus) {
    companion object {
        const val OP_SET_STANDBY = 0x80; const val OP_SET_TX = 0x83; const val OP_SET_RF_FREQUENCY = 0x86
        const val OP_SET_PACKET_TYPE = 0x8A; const val OP_SET_MODULATION_PARAMS = 0x8B; const val OP_SET_PACKET_PARAMS = 0x8C
        const val OP_SET_TX_PARAMS = 0x8E; const val OP_SET_BUFFER_BASE_ADDRESS = 0x8F; const val OP_SET_PA_CONFIG = 0x95
        const val OP_SET_REGULATOR_MODE = 0x96; const val OP_SET_DIO3_AS_TCXO_CTRL = 0x97; const val OP_CALIBRATE_IMAGE = 0x98
        const val OP_SET_DIO2_AS_RF_SWITCH_CTRL = 0x9D; const val OP_SET_RX_TX_FALLBACK_MODE = 0x93; const val OP_CALIBRATE = 0x89
        const val OP_SET_DIO_IRQ_PARAMS = 0x08; const val OP_GET_IRQ_STATUS = 0x12; const val OP_CLEAR_IRQ_STATUS = 0x02
        const val OP_GET_DEVICE_ERRORS = 0x17; const val OP_CLEAR_DEVICE_ERRORS = 0x07; const val OP_GET_STATUS = 0xC0
        const val OP_GET_PACKET_TYPE = 0x11; const val OP_READ_REGISTER = 0x1D; const val OP_WRITE_REGISTER = 0x0D
        const val OP_WRITE_BUFFER = 0x0E; const val OP_NOP = 0x00

        const val REG_VERSION_STRING = 0x0320
        const val REG_CRC_INITIAL_MSB = 0x06BC; const val REG_CRC_POLYNOMIAL_MSB = 0x06BE; const val REG_SYNC_WORD_0 = 0x06C0
        const val REG_OCP_CONFIGURATION = 0x08E7; const val REG_TX_CLAMP_CONFIG = 0x08D8
        const val REG_GFSK_FIX_1 = 0x06D1; const val REG_GFSK_FIX_3 = 0x08B8; const val REG_GFSK_FIX_4 = 0x06AC; const val REG_RSSI_AVG_WINDOW = 0x089B

        const val IRQ_TX_DONE = 0x0001; const val IRQ_TIMEOUT = 0x0200; const val IRQ_ALL = 0x43FF
        const val CRYSTAL_HZ = 32_000_000.0f

        /** Pure: RadioLib setBitRate raw value (32e6 * 32 / br). 38.4 kb/s -> 26666. */
        fun bitRateRaw(kbps: Float): Int = (CRYSTAL_HZ * 32.0f / (kbps * 1000.0f)).toInt()
        /** Pure: RadioLib setFrequencyDeviation raw value. 40 kHz -> 41943. */
        fun freqDevRaw(kHz: Float): Int = ((kHz * 1000.0f) * (1 shl 25).toFloat() / CRYSTAL_HZ).toInt()
        /** Pure: RadioLib setFrequencyRaw frf (float32 maths like the C++). */
        fun frfRaw(mhz: Float): Long = (mhz * (1 shl 25).toFloat() / 32.0f).toLong()
        /** RadioLib SX1262 paOptTable, index = power + 9: (paDutyCycle, hpMax, paVal). */
        val PA_OPT = listOf(
            Triple(2,2,-5), Triple(2,1,0), Triple(1,1,3), Triple(1,2,0), Triple(1,1,6), Triple(1,2,3), Triple(2,2,2), Triple(4,1,6), Triple(1,1,11), Triple(2,1,11),
            Triple(1,1,14), Triple(2,1,14), Triple(1,1,20), Triple(1,1,22), Triple(2,2,11), Triple(3,1,21), Triple(1,2,17), Triple(4,2,13), Triple(1,2,20), Triple(1,2,22),
            Triple(2,2,21), Triple(3,2,21), Triple(1,4,19), Triple(1,4,20), Triple(3,3,20), Triple(2,5,19), Triple(1,6,22), Triple(2,5,22), Triple(3,5,22), Triple(3,6,22),
            Triple(4,6,22), Triple(4,7,22),
        )
    }

    /** The link firmware/08 and 09 use: GFSK 38.4 kb/s, ±40 kHz, RX BW 156.2 kHz, 866.1 MHz, sync 2D D4, CRC-16 CCITT inverted. */
    data class GfskConfig(
        val freqMHz: Float = 866.1f,
        val bitRateKbps: Float = 38.4f,
        val freqDevKHz: Float = 40.0f,
        val rxBandwidthCode: Int = 0x1A,          // 156.2 kHz
        val preambleBits: Int = 16,
        val syncWord: ByteArray = byteArrayOf(0x2D, 0xD4.toByte()),
        val crcInitial: Int = 0x1D0F, val crcPolynomial: Int = 0x1021,
        val powerDbm: Int = 22,
        val tcxoVolts: Float = 1.6f,
    )

    // ---- transport helpers --------------------------------------------------
    private fun busy(call: String) { if (!bus.waitBusy(100)) throw HalException(call, 110, "BUSY stayed high") }
    /**
     * Until the TCXO is configured the chip's oscillator has not started and
     * every command reports "failure to execute" in its status byte; RadioLib
     * ignores status up to that point (findChip / reset / the standby before
     * setTCXO) and so do we: [checkStatus] is false until beginGfsk has sent
     * SetDIO3AsTCXOCtrl.
     */
    private var checkStatus = false
    private fun cmd(op: Int, vararg data: Int): ByteArray {
        busy("cmd 0x${op.toString(16)}")
        val tx = ByteArray(1 + data.size); tx[0] = op.toByte(); data.forEachIndexed { i, v -> tx[i + 1] = v.toByte() }
        val rx = bus.transfer(tx)
        val st = (rx[0].toInt() and 0x0E)
        if (checkStatus && (st == 0x06 || st == 0x08 || st == 0x0A))   // command timeout / invalid / failed
            throw HalException("cmd 0x${op.toString(16)}", 71, "status 0x${(rx[0].toInt() and 0xFF).toString(16)}, device errors 0x${deviceErrors().toString(16)}")
        return rx
    }
    private fun cmdRead(op: Int, count: Int): ByteArray {
        busy("cmd 0x${op.toString(16)}")
        val tx = ByteArray(2 + count); tx[0] = op.toByte()
        return bus.transfer(tx).copyOfRange(2, 2 + count)
    }

    fun reset() { bus.reset(); busy("reset") }
    fun status(): Int { busy("GetStatus"); return bus.transfer(byteArrayOf(OP_GET_STATUS.toByte(), 0))[1].toInt() and 0xFF }
    fun chipModeName(status: Int) = when ((status shr 4) and 0x7) { 2 -> "STDBY_RC"; 3 -> "STDBY_XOSC"; 4 -> "FS"; 5 -> "RX"; 6 -> "TX"; else -> "?" }

    fun readRegister(address: Int, count: Int): ByteArray {
        busy("ReadRegister")
        val tx = ByteArray(4 + count); tx[0] = OP_READ_REGISTER.toByte(); tx[1] = (address shr 8).toByte(); tx[2] = address.toByte()
        return bus.transfer(tx).copyOfRange(4, 4 + count)
    }
    fun writeRegister(address: Int, data: ByteArray) {
        busy("WriteRegister")
        bus.transfer(byteArrayOf(OP_WRITE_REGISTER.toByte(), (address shr 8).toByte(), address.toByte()) + data)
    }
    /** RadioLib Module::SPIsetRegValue: set bits [msb:lsb] of a register, read-modify-write. */
    private fun setRegBits(address: Int, value: Int, msb: Int, lsb: Int) {
        val cur = readRegister(address, 1)[0].toInt() and 0xFF
        val mask = ((0xFF shl (msb + 1)) or (0xFF shr (8 - lsb))).inv() and 0xFF
        val new = (cur and mask.inv()) or (value and mask)
        if (new != cur) writeRegister(address, byteArrayOf(new.toByte()))
    }
    fun versionString(): String = readRegister(REG_VERSION_STRING, 16).takeWhile { it != 0.toByte() }.toByteArray().decodeToString()
    fun deviceErrors(): Int { val d = cmdRead(OP_GET_DEVICE_ERRORS, 2); return ((d[0].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF) }
    fun irqFlags(): Int { val d = cmdRead(OP_GET_IRQ_STATUS, 2); return ((d[0].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF) }

    /** RadioLib: an unchecked NOP to wake the chip (its status byte carries the *previous* command's flags), then STDBY_RC. */
    fun standby() { busy("nop"); bus.transfer(byteArrayOf(OP_NOP.toByte())); cmd(OP_SET_STANDBY, 0x00) }
    fun clearIrq(mask: Int = IRQ_ALL) { cmd(OP_CLEAR_IRQ_STATUS, mask shr 8, mask and 0xFF) }
    fun setDioIrqParams(irq: Int, dio1: Int) { cmd(OP_SET_DIO_IRQ_PARAMS, irq shr 8, irq and 0xFF, dio1 shr 8, dio1 and 0xFF, 0, 0, 0, 0) }

    // ---- configuration, in RadioLib's order ----------------------------------
    private var cfg = GfskConfig()
    private var bitRate = 0; private var freqDev = 0; private var pulseShape = 0x09; private var preambleDet = 0x05
    private var syncWordBits = 16; private var crcType = 0x06; private var whitening = 0x00

    private fun setModulationParams() = cmd(OP_SET_MODULATION_PARAMS,
        bitRate shr 16, bitRate shr 8, bitRate, pulseShape, cfg.rxBandwidthCode, freqDev shr 16, freqDev shr 8, freqDev)
    private fun setPacketParams(payloadLen: Int) = cmd(OP_SET_PACKET_PARAMS,
        cfg.preambleBits shr 8, cfg.preambleBits and 0xFF, preambleDet, syncWordBits, 0x00 /* addr filt off */, 0x01 /* variable */, payloadLen, crcType, whitening)
    private fun preambleDetFor(bits: Int) = when { bits >= 32 -> 0x07; bits >= 24 -> 0x06; bits >= 16 -> 0x05; bits > 0 -> 0x04; else -> 0x00 }

    /** Mirrors SX1262::beginFSK(866.1, 38.4, 40, 156.2, 22) + setSyncWord(2D D4) + setCRC(2) + setOutputPower, as 08_telemetry_tx does. */
    fun beginGfsk(c: GfskConfig = GfskConfig()) {
        cfg = c
        reset(); standby()                                                   // findChip
        val v = versionString(); require(v.startsWith("SX126")) { "no SX126x: version '$v'" }
        standby(); if (deviceErrors() and 0x20 != 0) cmd(OP_CLEAR_DEVICE_ERRORS, 0, 0)   // setTCXO: XOSC error clear
        val dio3 = when (c.tcxoVolts) { 1.6f -> 0x00; 1.7f -> 0x01; 1.8f -> 0x02; 2.2f -> 0x03; 2.4f -> 0x04; 2.7f -> 0x05; 3.0f -> 0x06; 3.3f -> 0x07; else -> error("tcxo") }
        checkStatus = false
        cmd(OP_SET_DIO3_AS_TCXO_CTRL, dio3, 0x00, 0x01, 0x40)                 // 5000 us / 15.625 = 320
        checkStatus = true                                                    // from here RadioLib asserts every command
        cmd(OP_SET_BUFFER_BASE_ADDRESS, 0, 0)                                 // config(GFSK)
        cmd(OP_SET_PACKET_TYPE, 0x00)
        cmd(OP_SET_RX_TX_FALLBACK_MODE, 0x20)
        clearIrq(); setDioIrqParams(0, 0)
        cmd(OP_CALIBRATE, 0x7F); busy("calibrate")
        val err = deviceErrors(); if (err != 0) throw HalException("calibrate", 71, "device errors 0x${err.toString(16)}")
        cmd(OP_SET_REGULATOR_MODE, 0x01)                                      // DC-DC
        bitRate = bitRateRaw(c.bitRateKbps); freqDev = 52428; pulseShape = 0x09; setModulationParams()   // setBitRate
        fixGfsk()
        freqDev = freqDevRaw(c.freqDevKHz); setModulationParams()             // setFrequencyDeviation
        setModulationParams()                                                 // setRxBandwidth
        writeRegister(REG_OCP_CONFIGURATION, byteArrayOf(0x18))               // setCurrentLimit(60 mA)
        preambleDet = preambleDetFor(minOf(syncWordBits, c.preambleBits)); setPacketParams(255)   // setPreambleLength(16)
        setSyncWord(byteArrayOf(0x12, 0xAD.toByte()))                          // beginFSK default sync, replaced below
        pulseShape = 0x00; setModulationParams()                                // setDataShaping(NONE) — 7.7.1 default
        whitening = 0x00; setPacketParams(255)                                  // setEncoding(NRZ): whitening off
        setPacketParams(255)                                                    // variablePacketLengthMode(255)
        setCrc16(c.crcInitial, c.crcPolynomial)                                 // setCRC(2)
        cmd(OP_SET_DIO2_AS_RF_SWITCH_CTRL, 0x01)                                // setDio2AsRfSwitch(true)
        cmd(OP_CALIBRATE_IMAGE, 0xD7, 0xDB)                                     // setFrequency: 863–870 MHz image cal
        val frf = frfRaw(c.freqMHz)
        cmd(OP_SET_RF_FREQUENCY, (frf shr 24).toInt(), (frf shr 16).toInt(), (frf shr 8).toInt(), frf.toInt())
        val clamp = readRegister(REG_TX_CLAMP_CONFIG, 1)[0].toInt() and 0xFF   // fixPaClamping
        writeRegister(REG_TX_CLAMP_CONFIG, byteArrayOf((clamp or 0x1E).toByte()))
        setOutputPower(c.powerDbm)
        setSyncWord(c.syncWord)                                                 // what 08 adds after begin
        setCrc16(c.crcInitial, c.crcPolynomial)
        setOutputPower(c.powerDbm)
    }

    private fun fixGfsk() {   // RadioLib fixGFSK for bit rates other than 600/1200
        setRegBits(REG_GFSK_FIX_1, 0x08, 4, 3)
        setRegBits(REG_RSSI_AVG_WINDOW, 0x00, 4, 2)
        setRegBits(REG_GFSK_FIX_3, 0x10, 4, 4)
        setRegBits(REG_GFSK_FIX_4, 0x00, 6, 4)
    }

    fun setSyncWord(sync: ByteArray) {
        writeRegister(REG_SYNC_WORD_0, sync)
        syncWordBits = sync.size * 8
        preambleDet = preambleDetFor(minOf(syncWordBits, cfg.preambleBits))
        setPacketParams(255)
    }

    fun setCrc16(initial: Int, polynomial: Int) {
        crcType = 0x06; setPacketParams(255)
        writeRegister(REG_CRC_INITIAL_MSB, byteArrayOf((initial shr 8).toByte(), initial.toByte()))
        writeRegister(REG_CRC_POLYNOMIAL_MSB, byteArrayOf((polynomial shr 8).toByte(), polynomial.toByte()))
    }

    /** SX1262::setOutputPower(power, optimize=true). */
    fun setOutputPower(dbm: Int) {
        require(dbm in -9..22) { "power $dbm dBm out of range" }
        val (duty, hpMax, paVal) = PA_OPT[dbm + 9]
        val ocp = readRegister(REG_OCP_CONFIGURATION, 1)
        cmd(OP_SET_PA_CONFIG, duty, hpMax, 0x00 /* SX1262 */, 0x01)
        cmd(OP_SET_TX_PARAMS, paVal, 0x04 /* ramp 200 us */)
        writeRegister(REG_OCP_CONFIGURATION, ocp)
    }

    // ---- transmit -----------------------------------------------------------
    /** RadioLib transmit(): stageMode(TX) + launchMode + wait DIO1 + finishTransmit. Returns DIO1 polls waited. */
    fun transmit(data: ByteArray, timeoutMs: Int = 300): Int {
        require(data.size in 1..255)
        standby()
        setPacketParams(data.size)
        setDioIrqParams(IRQ_TX_DONE or IRQ_TIMEOUT, IRQ_TX_DONE)
        cmd(OP_SET_BUFFER_BASE_ADDRESS, 0, 0)
        busy("WriteBuffer"); bus.transfer(byteArrayOf(OP_WRITE_BUFFER.toByte(), 0) + data)
        clearIrq()
        cmd(OP_SET_TX, 0, 0, 0)                                                 // no timeout
        busy("SetTx")
        var polls = 0
        while (!bus.dio1()) { if (++polls > timeoutMs) { clearIrq(); standby(); throw HalException("transmit", 110, "no TX_DONE within ~$timeoutMs polls") } }
        val flags = irqFlags()
        clearIrq(); standby()
        if (flags and IRQ_TX_DONE == 0) throw HalException("transmit", 71, "DIO1 without TX_DONE, irq=0x${flags.toString(16)}")
        return polls
    }
}
