package io.windsor.telematics.internal

/**
 * MSB-first bit writer matching the reference client's PackedBitWriter
 * (and asn1tools' unaligned PER output). Useful for hand-assembled frames.
 */
internal class BitWriter {
    private val out = ArrayList<Byte>()
    private var buf = 0
    private var count = 0
    private var totalBits = 0

    fun write(value: Int, nBits: Int) {
        var v = value
        var n = nBits
        while (n > 0) {
            val take = minOf(n, 8 - count)
            val mask = (1 shl take) - 1
            buf = (buf shl take) or (((v ushr (n - take)) and mask))
            count += take
            n -= take
            totalBits += take
            if (count == 8) {
                out.add((buf and 0xFF).toByte())
                buf = 0
                count = 0
            }
        }
    }

    fun writeBits(value: Int, nBits: Int): Unit = write(value, nBits)

    fun writeFixedString7(s: String) {
        for (c in s) write(c.code and 0x7F, 7)
    }

    fun writeNumericString(s: String) {
        for (c in s) write(c.code - '0'.code, 4)
    }

    /** IA5String with a size constraint; no length prefix when min == max. */
    fun writeString(s: String, minLen: Int, maxLen: Int) {
        if (minLen != maxLen) write(s.length - minLen, integerBits(maxLen - minLen))
        writeFixedString7(s)
    }

    fun writeOctets(b: ByteArray, minLen: Int, maxLen: Int) {
        if (minLen != maxLen) write(b.size - minLen, integerBits(maxLen - minLen))
        for (byte in b) write(byte.toInt() and 0xFF, 8)
    }

    fun bits(): Int = totalBits

    fun toByteArray(): ByteArray {
        if (count > 0) out.add((buf shl (8 - count) and 0xFF).toByte())
        return out.toByteArray()
    }
}

/** MSB-first bit reader over a byte array, matching PackedBitReader. */
internal class BitReader(bytes: ByteArray, offsetBits: Int = 0) {
    private val data = bytes
    private var pos = offsetBits

    fun read(nBits: Int): Int {
        var out = 0
        var n = nBits
        while (n > 0) {
            val byteBits = 8 - (pos and 7)
            val take = minOf(n, byteBits)
            val shift = byteBits - take
            out = (out shl take) or (((data[pos ushr 3].toInt() ushr shift) and ((1 shl take) - 1)))
            pos += take
            n -= take
        }
        return out
    }

    fun readLong(nBits: Int): Long {
        var out = 0L
        var n = nBits
        while (n > 0) {
            val byteBits = 8 - (pos and 7)
            val take = minOf(n, byteBits)
            val shift = byteBits - take
            out = (out shl take) or (((data[pos ushr 3].toLong() ushr shift) and ((1L shl take) - 1)))
            pos += take
            n -= take
        }
        return out
    }

    fun readFixedString7(len: Int): String =
        buildString { repeat(len) { append((read(7) and 0x7F).toChar()) } }

    fun readString(minLen: Int, maxLen: Int): String {
        val len = if (minLen == maxLen) minLen else minLen + read(integerBits(maxLen - minLen))
        return readFixedString7(len)
    }

    fun readNumericString(len: Int): String =
        buildString { repeat(len) { append((read(4) + '0'.code).toChar()) } }

    /** OCTET STRING with a size constraint; no length prefix when min == max. */
    fun readOctets(minLen: Int, maxLen: Int): ByteArray {
        val len = if (minLen == maxLen) minLen else minLen + read(integerBits(maxLen - minLen))
        return ByteArray(len) { read(8).toByte() }
    }

    fun position(): Int = pos
}

/** Bits required to encode any value in 0..span bounds (asn1tools integer_as_number_of_bits). */
internal fun integerBits(span: Long): Int {
    var count = 0
    var v = span
    while (v > 0) {
        count += 1
        v = v ushr 1
    }
    return count
}

internal fun integerBits(span: Int): Int = integerBits(span.toLong())

/** Read a constrained INTEGER, returning the raw value (not span-scaled). */
internal fun BitReader.readConstrained(min: Long, max: Long): Long =
    min + readLong(integerBits(max - min))

/** Append a constrained INTEGER at its minimum-relative encoding. */
internal fun BitWriter.writeConstrained(value: Long, min: Long, max: Long) {
    write((value - min).toInt(), integerBits(max - min))
}

// ---------------------------------------------------------------------------
// Byte-level helpers used by the hand-assembled login dispatcher.
// ---------------------------------------------------------------------------

internal fun setMsbBits(buf: ByteArray, offset: Int, count: Int, value: Int) {
    for (idx in 0 until count) {
        val absolute = offset + idx
        val byteIdx = absolute ushr 3
        val mask = 1 shl (7 - (absolute and 7))
        val bit = (value ushr (count - 1 - idx)) and 1
        if (bit == 1) buf[byteIdx] = (buf[byteIdx].toInt() or mask).toByte()
        else buf[byteIdx] = (buf[byteIdx].toInt() and mask.inv()).toByte()
    }
}

internal fun setFixed7bit(buf: ByteArray, offset: Int, value: String) {
    for ((idx, c) in value.withIndex()) setMsbBits(buf, offset + idx * 7, 7, c.code)
}

internal fun readFixed7bit(buf: ByteArray, offset: Int, count: Int): String {
    fun readMsbBits(off: Int, n: Int): Int {
        var value = 0
        for (idx in 0 until n) {
            val absolute = off + idx
            value = (value shl 1) or ((buf[absolute ushr 3].toInt() ushr (7 - (absolute and 7))) and 1)
        }
        return value
    }
    return buildString { repeat(count) { append(readMsbBits(offset + it * 7, 7).toChar()) } }
}

// ---------------------------------------------------------------------------
// Hex helpers.
// ---------------------------------------------------------------------------

private val HEX_CHARS = "0123456789ABCDEF".toCharArray()

internal fun ByteArray.toHexUpper(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(HEX_CHARS[v ushr 4]).append(HEX_CHARS[v and 0x0F])
    }
    return sb.toString()
}

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex string must have even length" }
    return ByteArray(length / 2) { i -> (charToNibble(this[i * 2]) * 16 + charToNibble(this[i * 2 + 1])).toByte() }
}

internal fun charToNibble(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> throw IllegalArgumentException("invalid hex char: $c")
}