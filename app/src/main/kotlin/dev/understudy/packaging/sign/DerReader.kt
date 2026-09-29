package dev.understudy.packaging.sign

/**
 * Read-side counterpart to [DerWriter], for the handful of fields we need back out of a
 * certificate we generated ourselves.
 *
 * Scope is deliberately tiny: TLV walking only, no schema knowledge. Anything richer is
 * delegated to `java.security.cert.X509Certificate`, which the platform provides. Keeping
 * this to ~60 lines is the reason we do not need an ASN.1 library.
 *
 * @param bytes the buffer to read from
 */
internal class DerReader(private val bytes: ByteArray) {

    /** A decoded tag-length-value: [tag], and [content] excluding the header. */
    class Tlv(val tag: Int, val content: ByteArray, val contentOffset: Int, val totalLength: Int)

    private var position = 0

    val hasRemaining: Boolean get() = position < bytes.size

    /** Reads the next TLV at the current position and advances past it. */
    fun next(): Tlv {
        val tlv = peek(position)
        position += tlv.totalLength
        return tlv
    }

    /** Reads the TLV at [offset] without moving the cursor. */
    fun peek(offset: Int): Tlv {
        require(offset + 2 <= bytes.size) { "TLV header truncated at $offset (len=${bytes.size})" }
        val tag = bytes[offset].toInt() and 0xFF
        val first = bytes[offset + 1].toInt() and 0xFF

        val headerLength: Int
        val length: Int
        if (first < 0x80) {
            headerLength = 2
            length = first
        } else {
            val count = first and 0x7F
            require(count in 1..4) { "unsupported DER length width $count at $offset" }
            require(offset + 2 + count <= bytes.size) { "DER length truncated at $offset" }
            var value = 0
            for (i in 0 until count) {
                value = (value shl 8) or (bytes[offset + 2 + i].toInt() and 0xFF)
            }
            headerLength = 2 + count
            length = value
        }

        val contentOffset = offset + headerLength
        require(contentOffset + length <= bytes.size) {
            "TLV at $offset declares $length content bytes but only " +
                "${bytes.size - contentOffset} remain"
        }
        return Tlv(
            tag = tag,
            content = bytes.copyOfRange(contentOffset, contentOffset + length),
            contentOffset = contentOffset,
            totalLength = headerLength + length,
        )
    }

    /** Seeks to [offset]. */
    fun seek(offset: Int) {
        require(offset in 0..bytes.size) { "seek out of range: $offset" }
        position = offset
    }

    /** Reads the whole buffer as a single TLV — the usual entry point for a certificate. */
    fun root(): Tlv = peek(0)

    companion object {
        const val TAG_SEQUENCE = 0x30
    }
}
