package dev.understudy.packaging.sign

/**
 * Minimal DER (ASN.1) writer, just enough to emit a self-signed X.509 certificate.
 *
 * We hand-roll this rather than pulling in BouncyCastle for one reason: APK size and
 * dependency surface. Understudy has to generate a signing identity on first run, and a
 * certificate is the only ASN.1 we ever need to *produce* — we never parse one, because
 * `java.security.cert.CertificateFactory` does that for us.
 *
 * DER length rules implemented here:
 *  - length < 128            -> single byte
 *  - otherwise               -> 0x80 | byteCount, then the length in big-endian bytes
 */
internal class DerWriter {

    private val sink = ArrayList<ByteArray>()

    /** Appends a fully-encoded TLV. */
    fun raw(bytes: ByteArray) {
        sink += bytes
    }

    fun int(value: Int) {
        // Minimal big-endian two's-complement encoding, with a leading 0x00 when the high
        // bit of the first byte is set so the value stays positive.
        var v = value
        val out = ArrayList<Byte>()
        do {
            out.add(0, (v and 0xFF).toByte())
            v = v shr 8
        } while (v != 0)
        if (out[0].toInt() and 0x80 != 0) out.add(0, 0)
        tlv(TAG_INTEGER, out.toByteArray())
    }

    /** Encodes a positive integer of arbitrary size (used for serial numbers). */
    fun bigInt(value: ByteArray) {
        var bytes = value
        // Strip redundant leading zeros but keep one if needed for sign.
        var start = 0
        while (start < bytes.size - 1 && bytes[start] == 0.toByte() &&
            bytes[start + 1].toInt() and 0x80 == 0
        ) {
            start++
        }
        bytes = bytes.copyOfRange(start, bytes.size)
        if (bytes[0].toInt() and 0x80 != 0) bytes = byteArrayOf(0) + bytes
        tlv(TAG_INTEGER, bytes)
    }

    fun boolean(value: Boolean) {
        tlv(TAG_BOOLEAN, byteArrayOf(if (value) 0xFF.toByte() else 0x00))
    }

    fun nullValue() {
        sink += byteArrayOf(TAG_NULL.toByte(), 0x00)
    }

    fun oid(dotted: String) {
        tlv(TAG_OBJECT_IDENTIFIER, encodeOid(dotted))
    }

    fun utf8String(value: String) {
        tlv(TAG_UTF8_STRING, value.toByteArray(Charsets.UTF_8))
    }

    fun printableString(value: String) {
        tlv(TAG_PRINTABLE_STRING, value.toByteArray(Charsets.US_ASCII))
    }

    fun utcTime(value: String) {
        tlv(TAG_UTC_TIME, value.toByteArray(Charsets.US_ASCII))
    }

    /** BIT STRING with zero unused bits, wrapping [content]. */
    fun bitString(content: ByteArray) {
        tlv(TAG_BIT_STRING, byteArrayOf(0x00) + content)
    }

    fun octetString(content: ByteArray) {
        tlv(TAG_OCTET_STRING, content)
    }

    /** Builds a constructed tag whose body is produced by [body]. */
    fun sequence(body: DerWriter.() -> Unit) {
        constructed(TAG_SEQUENCE, body)
    }

    fun set(body: DerWriter.() -> Unit) {
        constructed(TAG_SET, body)
    }

    /** Explicitly-tagged wrapper, e.g. `[0]` for the version field. */
    fun explicitTag(number: Int, body: DerWriter.() -> Unit) {
        constructed((0xA0 or number), body)
    }

    private fun constructed(tag: Int, body: DerWriter.() -> Unit) {
        val inner = DerWriter().apply(body)
        tlv(tag, inner.toByteArray())
    }

    private fun tlv(tag: Int, content: ByteArray) {
        sink += byteArrayOf(tag.toByte())
        sink += encodeLength(content.size)
        sink += content
    }

    fun toByteArray(): ByteArray {
        var size = 0
        for (b in sink) size += b.size
        val out = ByteArray(size)
        var pos = 0
        for (b in sink) {
            b.copyInto(out, pos)
            pos += b.size
        }
        return out
    }

    /** Encodes a TLV and returns it, without appending — handy for nested content. */
    companion object {
        const val TAG_BOOLEAN = 0x01
        const val TAG_INTEGER = 0x02
        const val TAG_BIT_STRING = 0x03
        const val TAG_OCTET_STRING = 0x04
        const val TAG_NULL = 0x05
        const val TAG_OBJECT_IDENTIFIER = 0x06
        const val TAG_UTF8_STRING = 0x0C
        const val TAG_PRINTABLE_STRING = 0x13
        const val TAG_UTC_TIME = 0x17
        const val TAG_SEQUENCE = 0x30
        const val TAG_SET = 0x31

        /** sha256WithRSAEncryption. */
        const val OID_SHA256_WITH_RSA = "1.2.840.113549.1.1.11"

        /** rsaEncryption — the algorithm inside SubjectPublicKeyInfo. */
        const val OID_RSA_ENCRYPTION = "1.2.840.113549.1.1.1"

        /** commonName. */
        const val OID_COMMON_NAME = "2.5.4.3"

        /** organizationName. */
        const val OID_ORGANIZATION = "2.5.4.10"

        fun encodeLength(length: Int): ByteArray = when {
            length < 0x80 -> byteArrayOf(length.toByte())
            length <= 0xFF -> byteArrayOf(0x81.toByte(), length.toByte())
            length <= 0xFFFF -> byteArrayOf(
                0x82.toByte(),
                ((length shr 8) and 0xFF).toByte(),
                (length and 0xFF).toByte(),
            )
            length <= 0xFFFFFF -> byteArrayOf(
                0x83.toByte(),
                ((length shr 16) and 0xFF).toByte(),
                ((length shr 8) and 0xFF).toByte(),
                (length and 0xFF).toByte(),
            )
            else -> byteArrayOf(
                0x84.toByte(),
                ((length shr 24) and 0xFF).toByte(),
                ((length shr 16) and 0xFF).toByte(),
                ((length shr 8) and 0xFF).toByte(),
                (length and 0xFF).toByte(),
            )
        }

        /** Encodes a standalone TLV — used to build the AlgorithmIdentifier inline. */
        fun tlv(tag: Int, content: ByteArray): ByteArray =
            byteArrayOf(tag.toByte()) + encodeLength(content.size) + content

        fun sequenceOf(content: ByteArray): ByteArray = tlv(TAG_SEQUENCE, content)

        /**
         * Encodes a dotted OID. The first two arcs pack into `40*x + y`; the rest use
         * base-128 with the continuation bit set on all but the final byte.
         */
        fun encodeOid(dotted: String): ByteArray {
            val parts = dotted.split('.').map { it.toInt() }
            require(parts.size >= 2) { "malformed OID: $dotted" }
            val out = ArrayList<Byte>()
            out += (40 * parts[0] + parts[1]).toByte()
            for (i in 2 until parts.size) {
                var v = parts[i]
                if (v == 0) {
                    out += 0
                    continue
                }
                val chunk = ArrayList<Byte>()
                while (v > 0) {
                    chunk.add(0, (v and 0x7F).toByte())
                    v = v shr 7
                }
                for (j in chunk.indices) {
                    out += if (j == chunk.size - 1) chunk[j]
                    else (chunk[j].toInt() or 0x80).toByte()
                }
            }
            return out.toByteArray()
        }
    }
}
