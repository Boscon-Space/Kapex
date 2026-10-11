package space.boscon.hab.hal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.errno
import platform.posix.strerror

@OptIn(ExperimentalForeignApi::class)
internal fun fail(call: String): Nothing {
    val e = errno
    throw HalException(call, e, strerror(e)?.toKString() ?: "?")
}

/** Run [block]; if it returns < 0, throw with errno. */
internal inline fun check(call: String, block: () -> Int): Int {
    val r = block()
    if (r < 0) fail(call)
    return r
}
