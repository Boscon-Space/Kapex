package space.boscon.hab.hal

/** Thrown for any failed kernel call; carries errno and the call that failed. */
class HalException(val call: String, val errno: Int, val detail: String) :
    RuntimeException("$call failed: $detail (errno $errno)")

enum class Bias { NONE, PULL_UP, PULL_DOWN }
enum class Edge { NONE, RISING, FALLING, BOTH }

interface GpioOutput : AutoCloseable {
    val offset: Int
    fun set(high: Boolean)
    fun high() = set(true)
    fun low() = set(false)
}

interface GpioInput : AutoCloseable {
    val offset: Int
    fun get(): Boolean
}

data class EdgeEvent(val offset: Int, val rising: Boolean, val timestampNs: Long, val seqno: Int)

/** An input requested with an edge: [readEvent] blocks until the next edge. */
interface GpioEventInput : GpioInput {
    fun readEvent(): EdgeEvent
}

data class GpioChipInfo(val name: String, val label: String, val lines: Int)

interface GpioChip : AutoCloseable {
    fun info(): GpioChipInfo
    fun requestOutput(offset: Int, initialHigh: Boolean = false, consumer: String = "hab1"): GpioOutput
    fun requestInput(offset: Int, bias: Bias = Bias.NONE, consumer: String = "hab1"): GpioInput
    fun requestEvents(offset: Int, edge: Edge, bias: Bias = Bias.NONE, consumer: String = "hab1"): GpioEventInput
}

interface I2cBus : AutoCloseable {
    /** Write [bytes] to [address] (7-bit). */
    fun write(address: Int, bytes: ByteArray)
    /** Write [reg] then read [count] bytes in one transaction (repeated start). */
    fun readRegister(address: Int, reg: Int, count: Int): ByteArray
    fun readRegister(address: Int, reg: Int): Int = readRegister(address, reg, 1)[0].toInt() and 0xFF
    fun writeRegister(address: Int, reg: Int, value: Int) = write(address, byteArrayOf(reg.toByte(), value.toByte()))
    /** Addresses that ACK a zero-length write, like `i2cdetect -q`. */
    fun scan(): List<Int>
}

interface SpiBus : AutoCloseable {
    /** Full-duplex transfer: sends [tx], returns the same number of bytes received. Chip select is driven by the kernel. */
    fun transfer(tx: ByteArray): ByteArray
    val speedHz: Int
    val mode: Int
}

interface SerialPort : AutoCloseable {
    fun write(bytes: ByteArray)
    /** Read up to [max] bytes, blocking up to [timeoutMs]; returns an empty array on timeout. */
    fun read(max: Int, timeoutMs: Int): ByteArray
    /** Read exactly [n] bytes or throw after [timeoutMs] total. */
    fun readExact(n: Int, timeoutMs: Int): ByteArray {
        val out = ByteArray(n); var got = 0; var left = timeoutMs
        while (got < n) {
            val chunk = read(n - got, left.coerceAtLeast(1))
            if (chunk.isEmpty()) throw HalException("serial read", 110, "timeout after $timeoutMs ms with $got/$n bytes")
            chunk.copyInto(out, got); got += chunk.size; left -= 1
        }
        return out
    }
    fun readLine(timeoutMs: Int, maxLen: Int = 512): String? {
        val sb = StringBuilder()
        while (sb.length < maxLen) {
            val b = read(1, timeoutMs)
            if (b.isEmpty()) return if (sb.isEmpty()) null else sb.toString()
            val c = b[0].toInt().toChar()
            if (c == '\n') return sb.toString().trimEnd('\r')
            sb.append(c)
        }
        return sb.toString()
    }
}
