package dev.understudy.packaging

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * A minimal, dependency-free ZIP writer that emits **aligned** APKs.
 *
 * Alignment is normally a separate `zipalign` pass, but uncompressed entries must start on
 * a 4-byte boundary or `apksigner` refuses to sign the result, so it is done inline here:
 * one less pass over the bytes and no external tool to shell out to on-device.
 *
 * v2 signing happens after this and is unaffected by alignment — the v2 digest covers the
 * zip entries and the central directory as they stand, and inserting the signing block only
 * shifts central-directory offsets, never entry data.
 *
 * Only what an APK needs is implemented: STORED and DEFLATE, no data descriptors, no zip64,
 * no encryption, no multi-disk. Stateless and therefore safe to call concurrently.
 */
internal object ZipWriter {

    enum class Method(val id: Int) { STORED(0), DEFLATE(8) }

    class Entry(
        val name: String,
        val data: ByteArray,
        val method: Method,
        /** 4 for ordinary entries; 4096 would be used for uncompressed native libraries. */
        val alignment: Int = 4,
    )

    /**
     * A fixed DOS timestamp keeps the output byte-reproducible for identical input, which
     * makes a generated APK hashable, cacheable and unit-testable.
     */
    private const val DOS_TIME: Short = 0
    private const val DOS_DATE: Short = 0x21 // 1980-01-01

    private const val LOCAL_FILE_HEADER_SIG = 0x04034b50
    private const val CENTRAL_DIRECTORY_SIG = 0x02014b50
    private const val EOCD_SIG = 0x06054b50

    /** An entry whose compression outcome has been decided. */
    private class Prepared(
        val name: String,
        val nameBytes: ByteArray,
        val uncompressedSize: Int,
        val crc: Long,
        val payload: ByteArray,
        val method: Method,
        val offset: Int,
        val padding: Int,
    )

    fun build(entries: List<Entry>): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        val prepared = ArrayList<Prepared>(entries.size)

        for (entry in entries) {
            val crc = CRC32().apply { update(entry.data) }.value
            val nameBytes = entry.name.toByteArray(Charsets.UTF_8)

            // Deflate can *expand* incompressible data (already-compressed dex, png, obb);
            // fall back to STORED whenever it does not actually win.
            val deflated = if (entry.method == Method.DEFLATE) deflate(entry.data) else null
            val useDeflate = deflated != null && deflated.size < entry.data.size
            val payload = if (useDeflate) deflated!! else entry.data
            val method = if (useDeflate) Method.DEFLATE else Method.STORED

            val localHeaderSize = 30 + nameBytes.size
            val offset = out.size()
            val dataStart = offset + localHeaderSize
            val padding = (entry.alignment - dataStart % entry.alignment) % entry.alignment

            writeLocalHeader(out, nameBytes, payload.size, entry.data.size, crc, method, padding)
            if (padding > 0) out.write(ByteArray(padding))
            out.write(payload)

            prepared += Prepared(
                name = entry.name, nameBytes = nameBytes,
                uncompressedSize = entry.data.size, crc = crc, payload = payload,
                method = method, offset = offset, padding = padding,
            )
        }

        val cdStart = out.size()
        for (p in prepared) writeCentralDirectoryEntry(out, p)
        val cdSize = out.size() - cdStart
        writeEocd(out, prepared.size, cdSize, cdStart)

        return out.toByteArray()
    }

    private fun writeLocalHeader(
        out: ByteArrayOutputStream,
        nameBytes: ByteArray,
        compressedSize: Int,
        uncompressedSize: Int,
        crc: Long,
        method: Method,
        padding: Int,
    ) {
        val b = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(LOCAL_FILE_HEADER_SIG)
        b.putShort(20)                       // version needed to extract
        b.putShort(generalPurposeFlags(nameBytes))
        b.putShort(method.id.toShort())
        b.putShort(DOS_TIME)
        b.putShort(DOS_DATE)
        b.putInt(crc.toInt())
        b.putInt(compressedSize)
        b.putInt(uncompressedSize)
        b.putShort(nameBytes.size.toShort())
        b.putShort(padding.toShort())        // extra field carries the alignment padding
        out.write(b.array())
        out.write(nameBytes)
    }

    private fun writeCentralDirectoryEntry(out: ByteArrayOutputStream, p: Prepared) {
        val b = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(CENTRAL_DIRECTORY_SIG)
        b.putShort(20)                       // version made by
        b.putShort(20)                       // version needed
        b.putShort(generalPurposeFlags(p.nameBytes))
        b.putShort(p.method.id.toShort())
        b.putShort(DOS_TIME)
        b.putShort(DOS_DATE)
        b.putInt(p.crc.toInt())
        b.putInt(p.payload.size)
        b.putInt(p.uncompressedSize)
        b.putShort(p.nameBytes.size.toShort())
        b.putShort(0)                        // extra length
        b.putShort(0)                        // comment length
        b.putShort(0)                        // disk number start
        b.putShort(0)                        // internal attributes
        b.putInt(0)                          // external attributes
        b.putInt(p.offset)                   // relative offset of local header
        out.write(b.array())
        out.write(p.nameBytes)
    }

    private fun writeEocd(out: ByteArrayOutputStream, count: Int, cdSize: Int, cdOffset: Int) {
        val b = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(EOCD_SIG)
        b.putShort(0)                        // this disk
        b.putShort(0)                        // disk with central directory
        b.putShort(count.toShort())
        b.putShort(count.toShort())
        b.putInt(cdSize)
        b.putInt(cdOffset)
        b.putShort(0)                        // comment length
        out.write(b.array())
    }

    /** Bit 11 (UTF-8 names) only when a name actually needs it. */
    private fun generalPurposeFlags(nameBytes: ByteArray): Short =
        if (String(nameBytes, Charsets.UTF_8).any { it.code > 0x7F }) 0x0800 else 0

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size / 2 + 64)
            val buffer = ByteArray(64 * 1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(buffer)
                if (n <= 0) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }
}
