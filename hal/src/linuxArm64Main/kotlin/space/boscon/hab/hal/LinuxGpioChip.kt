package space.boscon.hab.hal

import kotlinx.cinterop.*
import platform.posix.*
import space.boscon.hab.hal.linuxio.*

/**
 * GPIO through the kernel character device, uAPI v2 (gpio.h, kernel >= 5.10).
 * No libgpiod. One fd per line request; values via ioctl, edges via read().
 */
@OptIn(ExperimentalForeignApi::class)
class LinuxGpioChip(private val path: String = "/dev/gpiochip0") : GpioChip {
    private val fd: Int = check("open($path)") { open(path, O_RDWR or O_CLOEXEC) }

    override fun info(): GpioChipInfo = memScoped {
        val ci = alloc<hab_gpiochip_info>()
        check("GPIO_GET_CHIPINFO") { ioctl(fd, hab_ioc_gpio_get_chipinfo(), ci.ptr) }
        GpioChipInfo(ci.name.toKString(), ci.label.toKString(), ci.lines.toInt())
    }

    /** Returns the line fd for one line with the given flags. */
    private fun requestLine(offset: Int, flags: ULong, consumer: String, outputHigh: Boolean?): Int = memScoped {
        val req = alloc<hab_gpio_v2_line_request>()
        memset(req.ptr, 0, sizeOf<hab_gpio_v2_line_request>().convert())
        req.offsets[0] = offset.toUInt()
        req.num_lines = 1u
        val name = consumer.take(HAB_GPIO_MAX_NAME_SIZE - 1).encodeToByteArray()
        name.forEachIndexed { i, b -> req.consumer[i] = b }
        req.config.flags = flags
        if (outputHigh != null) {
            // one attribute: OUTPUT_VALUES for line 0
            req.config.num_attrs = 1u
            val a = req.config.attrs[0]
            a.attr.id = HAB_GPIO_V2_LINE_ATTR_ID_OUTPUT_VALUES.toUInt()
            a.attr.values = if (outputHigh) 1uL else 0uL
            a.mask = 1uL
        }
        check("GPIO_V2_GET_LINE($offset)") { ioctl(fd, hab_ioc_gpio_v2_get_line(), req.ptr) }
        req.fd
    }

    private fun biasFlag(b: Bias) = when (b) {
        Bias.NONE -> 0uL
        Bias.PULL_UP -> HAB_GPIO_V2_LINE_FLAG_BIAS_PULL_UP
        Bias.PULL_DOWN -> HAB_GPIO_V2_LINE_FLAG_BIAS_PULL_DOWN
    }

    override fun requestOutput(offset: Int, initialHigh: Boolean, consumer: String): GpioOutput {
        val lfd = requestLine(offset, HAB_GPIO_V2_LINE_FLAG_OUTPUT, consumer, initialHigh)
        return Line(lfd, offset)
    }

    override fun requestInput(offset: Int, bias: Bias, consumer: String): GpioInput {
        val lfd = requestLine(offset, HAB_GPIO_V2_LINE_FLAG_INPUT or biasFlag(bias), consumer, null)
        return Line(lfd, offset)
    }

    override fun requestEvents(offset: Int, edge: Edge, bias: Bias, consumer: String): GpioEventInput {
        val edgeFlags = when (edge) {
            Edge.NONE -> 0uL
            Edge.RISING -> HAB_GPIO_V2_LINE_FLAG_EDGE_RISING
            Edge.FALLING -> HAB_GPIO_V2_LINE_FLAG_EDGE_FALLING
            Edge.BOTH -> HAB_GPIO_V2_LINE_FLAG_EDGE_RISING or HAB_GPIO_V2_LINE_FLAG_EDGE_FALLING
        }
        val lfd = requestLine(offset, HAB_GPIO_V2_LINE_FLAG_INPUT or edgeFlags or biasFlag(bias), consumer, null)
        return Line(lfd, offset)
    }

    override fun close() { close(fd) }

    private class Line(private val lfd: Int, override val offset: Int) : GpioOutput, GpioEventInput {
        override fun set(high: Boolean) = memScoped {
            val v = alloc<hab_gpio_v2_line_values>()
            v.mask = 1uL
            v.bits = if (high) 1uL else 0uL
            check("GPIO_V2_LINE_SET_VALUES($offset)") { ioctl(lfd, hab_ioc_gpio_v2_set_values(), v.ptr) }
            Unit
        }

        override fun get(): Boolean = memScoped {
            val v = alloc<hab_gpio_v2_line_values>()
            v.mask = 1uL
            v.bits = 0uL
            check("GPIO_V2_LINE_GET_VALUES($offset)") { ioctl(lfd, hab_ioc_gpio_v2_get_values(), v.ptr) }
            (v.bits and 1uL) != 0uL
        }

        override fun readEvent(): EdgeEvent = memScoped {
            val ev = alloc<hab_gpio_v2_line_event>()
            val n = read(lfd, ev.ptr, sizeOf<hab_gpio_v2_line_event>().convert())
            if (n < 0) fail("read(gpio event $offset)")
            EdgeEvent(
                offset = ev.offset.toInt(),
                rising = ev.id.toInt() == HAB_GPIO_V2_LINE_EVENT_RISING_EDGE,
                timestampNs = ev.timestamp_ns.toLong(),
                seqno = ev.seqno.toInt(),
            )
        }

        override fun close() { close(lfd) }
    }
}
