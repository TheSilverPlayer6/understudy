package dev.understudy.packaging.axml

/**
 * Reader/writer for the binary Android XML (`AXML`) **string pool** chunk.
 *
 * This is the one part of the APK we rewrite on-device: the proxy template ships with
 * `applicationId = dev.understudy.proxytpl`, and to make the platform hand us another app's
 * private storage we must change that identity to the target package name. Everything else
 * in the manifest — resource IDs, the element tree, line numbers — is left byte-for-byte
 * untouched, so we inherit aapt2's correctness instead of reimplementing it.
 *
 * Chunk layout (all little-endian):
 * ```
 * ResChunk_header { u16 type; u16 headerSize; u32 size }        // headerSize == 28
 * u32 stringCount
 * u32 styleCount
 * u32 flags            // bit0 sorted, bit8 UTF-8
 * u32 stringsStart     // offset from THIS chunk's start to the string data
 * u32 stylesStart
 * u32[stringCount] stringOffsets   // relative to stringsStart
 * u32[styleCount]  styleOffsets
 * <string data>
 * <style data>
 * ```
 *
 * The algorithm here is validated against a real AGP 9.4.1 manifest: rebuilding an unmodified
 * pool reproduces the original bytes exactly, and a rename round-trips through
 * `aapt2 dump badging` / `dump xmltree`.
 */
internal object AxmlStringPool {

    const val TYPE_XML: Int = 0x0003
    const val TYPE_STRING_POOL: Int = 0x0001

    private const val HEADER_SIZE: Int = 28
    private const val FLAG_SORTED: Int = 1 shl 0
    private const val FLAG_UTF8: Int = 1 shl 8

    /** A parsed pool plus the raw bytes of everything that follows it in the file. */
    class Document(
        val fileType: Int,
        val fileHeaderSize: Int,
        val flags: Int,
        val strings: MutableList<String>,
        val styleOffsets: IntArray,
        val styles: ByteArray,
        val tail: ByteArray,
    ) {
        val isUtf8: Boolean get() = flags and FLAG_UTF8 != 0
    }

    // ---- parsing -----------------------------------------------------------

    fun parse(buf: ByteArray): Document {
        require(buf.size >= 8) { "manifest too small: ${buf.size} bytes" }
        val fileType = u16(buf, 0)
        val fileHeaderSize = u16(buf, 2)
        require(fileType == TYPE_XML) { "not an AXML file: 0x${fileType.toString(16)}" }

        val poolStart = fileHeaderSize
        require(u16(buf, poolStart) == TYPE_STRING_POOL) {
            "expected a string pool at $poolStart, found 0x${u16(buf, poolStart).toString(16)}"
        }
        val poolHeaderSize = u16(buf, poolStart + 2)
        val poolSize = u32(buf, poolStart + 4)
        val stringCount = u32(buf, poolStart + 8)
        val styleCount = u32(buf, poolStart + 12)
        val flags = u32(buf, poolStart + 16)
        val stringsStart = u32(buf, poolStart + 20)
        val stylesStart = u32(buf, poolStart + 24)
        require(poolHeaderSize == HEADER_SIZE) { "unexpected pool headerSize=$poolHeaderSize" }
        require(poolStart + poolSize <= buf.size) { "pool chunk extends past end of file" }

        val utf8 = flags and FLAG_UTF8 != 0
        val offsets = IntArray(stringCount) { u32(buf, poolStart + 28 + 4 * it) }
        val styleOffsets = IntArray(styleCount) {
            u32(buf, poolStart + 28 + 4 * stringCount + 4 * it)
        }

        val dataStart = poolStart + stringsStart
        val strings = ArrayList<String>(stringCount)
        for (off in offsets) {
            strings += if (utf8) decodeUtf8(buf, dataStart + off) else decodeUtf16(buf, dataStart + off)
        }

        val styles = if (styleCount == 0) {
            ByteArray(0)
        } else {
            val sStart = poolStart + stylesStart
            val sEnd = poolStart + poolSize
            buf.copyOfRange(sStart, sEnd)
        }

        val tail = buf.copyOfRange(poolStart + poolSize, buf.size)
        return Document(fileType, fileHeaderSize, flags, strings, styleOffsets, styles, tail)
    }

    /** UTF-16 lengths are in code units; a set high bit means a second u16 follows. */
    private fun decodeUtf16(buf: ByteArray, pos: Int): String {
        var p = pos
        var n = u16(buf, p); p += 2
        if (n and 0x8000 != 0) {
            n = ((n and 0x7FFF) shl 16) or u16(buf, p)
            p += 2
        }
        val byteLen = n * 2
        require(p + byteLen <= buf.size) { "truncated UTF-16 string at $pos" }
        val s = String(buf, p, byteLen, Charsets.UTF_16LE)
        return s
    }

    /** UTF-8 pools carry two length fields: char count then byte count, each 1 or 2 bytes. */
    private fun decodeUtf8(buf: ByteArray, pos: Int): String {
        var p = pos
        // char count — not needed, but must be skipped correctly
        if (buf[p].toInt() and 0x80 != 0) p += 2 else p += 1
        var byteLen = buf[p].toInt() and 0xFF; p += 1
        if (byteLen and 0x80 != 0) {
            byteLen = ((byteLen and 0x7F) shl 8) or (buf[p].toInt() and 0xFF)
            p += 1
        }
        require(p + byteLen <= buf.size) { "truncated UTF-8 string at $pos" }
        return String(buf, p, byteLen, Charsets.UTF_8)
    }

    // ---- serialisation -----------------------------------------------------

    /** Serialises the pool chunk. String data is 4-byte aligned, as aapt2 emits it. */
    fun encodePool(doc: Document): ByteArray {
        val blobs = doc.strings.map { if (doc.isUtf8) encodeUtf8(it) else encodeUtf16(it) }

        val offsets = IntArray(blobs.size)
        var dataLen = 0
        for (i in blobs.indices) {
            offsets[i] = dataLen
            dataLen += blobs[i].size
        }
        val padding = (4 - dataLen % 4) % 4

        val stringCount = doc.strings.size
        val styleCount = doc.styleOffsets.size
        val offsetsSize = 4 * stringCount + 4 * styleCount
        val stringsStart = HEADER_SIZE + offsetsSize
        val stylesStart = if (styleCount == 0) 0 else stringsStart + dataLen + padding
        val total = stringsStart + dataLen + padding + doc.styles.size

        val out = ByteArray(total)
        var p = 0
        p = putU16(out, p, TYPE_STRING_POOL)
        p = putU16(out, p, HEADER_SIZE)
        p = putU32(out, p, total)
        p = putU32(out, p, stringCount)
        p = putU32(out, p, styleCount)
        p = putU32(out, p, doc.flags)
        p = putU32(out, p, stringsStart)
        p = putU32(out, p, stylesStart)
        for (o in offsets) p = putU32(out, p, o)
        for (o in doc.styleOffsets) p = putU32(out, p, o)
        for (b in blobs) {
            b.copyInto(out, p)
            p += b.size
        }
        p += padding
        if (doc.styles.isNotEmpty()) {
            doc.styles.copyInto(out, p)
            p += doc.styles.size
        }
        check(p == total) { "pool encoder wrote $p bytes but declared $total" }
        return out
    }

    private fun encodeUtf16(s: String): ByteArray {
        val raw = s.toByteArray(Charsets.UTF_16LE)
        val units = raw.size / 2
        val head = if (units < 0x8000) {
            byteArrayOf((units and 0xFF).toByte(), ((units shr 8) and 0xFF).toByte())
        } else {
            val hi = ((units shr 16) and 0x7FFF) or 0x8000
            byteArrayOf(
                (hi and 0xFF).toByte(), ((hi shr 8) and 0xFF).toByte(),
                (units and 0xFF).toByte(), ((units shr 8) and 0xFF).toByte(),
            )
        }
        return head + raw + byteArrayOf(0, 0)
    }

    private fun encodeUtf8(s: String): ByteArray {
        val raw = s.toByteArray(Charsets.UTF_8)
        return encLen8(s.length) + encLen8(raw.size) + raw + byteArrayOf(0)
    }

    private fun encLen8(n: Int): ByteArray =
        if (n < 0x80) byteArrayOf(n.toByte())
        else byteArrayOf((((n shr 8) and 0x7F) or 0x80).toByte(), (n and 0xFF).toByte())

    // ---- byte helpers ------------------------------------------------------

    private fun u16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun putU16(b: ByteArray, o: Int, v: Int): Int {
        b[o] = (v and 0xFF).toByte()
        b[o + 1] = ((v shr 8) and 0xFF).toByte()
        return o + 2
    }

    private fun putU32(b: ByteArray, o: Int, v: Int): Int {
        b[o] = (v and 0xFF).toByte()
        b[o + 1] = ((v shr 8) and 0xFF).toByte()
        b[o + 2] = ((v shr 16) and 0xFF).toByte()
        b[o + 3] = ((v shr 24) and 0xFF).toByte()
        return o + 4
    }
}
