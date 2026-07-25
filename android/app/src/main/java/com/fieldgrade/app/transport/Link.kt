package com.fieldgrade.app.transport

/** Monotonic time source, injected so transport logic is unit-testable. */
fun interface Clock {
    fun nowMs(): Long
}

/**
 * The swappable byte transport to the controller. Real implementations wrap a
 * USB-serial port or a Bluetooth SPP socket (Tier B, hardware); tests use a fake.
 * Keeping the transport behind this interface means the streaming/supervision
 * logic never depends on the Android framework.
 */
interface ByteLink {
    val isOpen: Boolean
    /** Write bytes to the controller. Implementations should not block long. */
    fun write(bytes: ByteArray)
    /** Return any bytes available now (possibly empty); never blocks. */
    fun read(): ByteArray
    fun close()
}

/** Reassembles a byte stream into newline-delimited frames across arbitrary chunk boundaries. */
class LineFramer {
    private val buf = StringBuilder()

    fun offer(bytes: ByteArray): List<String> {
        if (bytes.isEmpty()) return emptyList()
        buf.append(String(bytes, Charsets.UTF_8))
        val lines = ArrayList<String>()
        var idx = buf.indexOf("\n")
        while (idx >= 0) {
            val line = buf.substring(0, idx).trim()
            if (line.isNotEmpty()) lines.add(line)
            buf.delete(0, idx + 1)
            idx = buf.indexOf("\n")
        }
        return lines
    }
}

enum class LinkState { DISCONNECTED, CONNECTED, STALE, FAULTED }
