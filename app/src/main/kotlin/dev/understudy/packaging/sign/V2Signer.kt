package dev.understudy.packaging.sign

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.PrivateKey

/**
 * APK Signature Scheme **v2** (API 24+).
 *
 * v2 signs the APK as a whole rather than entry-by-entry, and it is mandatory in practice:
 * an APK carrying only a v1 signature that declares `targetSdk >= 30` is rejected outright
 * on Android 11+.
 *
 * Layout produced here:
 * ```
 * [ zip entries ][ APK Signing Block ][ central directory ][ EOCD ]
 * ```
 * The verifier finds the block via the EOCD's central-directory offset, so inserting it
 * means shifting every "relative offset of local header" in the central directory by the
 * block size and rewriting the EOCD offset. Both happen in [sign].
 *
 * **What is digested.** Exactly two regions, per `V2SchemeVerifier.verifyIntegrity`:
 *   1. the zip entries — everything before the signing block;
 *   2. the central directory plus EOCD, where the EOCD's central-directory offset field is
 *      treated as pointing at the offset where the signing block *will* start.
 *
 * The signing block itself is deliberately **not** covered. That is what breaks the
 * otherwise-circular dependency (the block contains signatures over a digest that would
 * otherwise include the block), and it is why we can compute the digest before the block
 * exists. The block's integrity is instead anchored structurally: its magic and the two
 * size fields are checked by the verifier, and anything outside the block is covered by
 * the signatures inside it.
 *
 * Digest construction, per `V2SchemeSigner.computeContentDigests`:
 *   - each region is split into consecutive 1 MB chunks (no chunks for an empty region);
 *   - each chunk digests as `0xa5 || u32le chunkLength || chunkBytes`;
 *   - the result is `SHA-256(0x5a || u32le chunkCount || concat(chunkDigests))`.
 *
 * Reference: AOSP `tools/apksig` — `V2SchemeSigner` / `V2SchemeVerifier`.
 */
object V2Signer {

    private const val SIGNATURE_SCHEME_V2_BLOCK_ID = 0x7109871a
    private const val CHUNK_SIZE = 1024 * 1024

    /** RSA PKCS#1 v1.5 + SHA-256: what apksig picks for an RSA key of <= 3072 bits. */
    private const val SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA256 = 0x0103

    /** `APK Sig Block 42`, little-endian as two u64 halves. */
    private const val MAGIC_LO = 0x20676953204b5041L
    private const val MAGIC_HI = 0x3234206b636f6c42L

    private val EOCD_SIGNATURE = 0x06054b50
    private val CD_SIGNATURE = 0x02014b50
    private val LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50

    /**
     * @param apk an *aligned* APK with no signing block yet (v1 signature may be present)
     * @return the final signed APK bytes
     */
    fun sign(apk: ByteArray, privateKey: PrivateKey, certificateDer: ByteArray): ByteArray {
        val eocdOffset = findEocdOffset(apk)
            ?: throw IllegalArgumentException("not a zip: EOCD not found")
        val cdOffset = readIntLe(apk, eocdOffset + 16)
        require(cdOffset in 0..eocdOffset) { "implausible central directory offset $cdOffset" }

        // The digest covers THREE SEPARATE segments, in this order — matching
        // V2SchemeVerifier.verifyIntegrity, which passes {beforeApkSigningBlock, centralDir,
        // modifiedEocd} as three distinct DataSources:
        //   1. the zip entries (everything before where the signing block will go);
        //   2. the central directory on its own;
        //   3. the EOCD on its own, with its central-directory offset rewritten to point at
        //      the signing block's start — which is cdOffset, since the block is inserted
        //      there and pushes the real CD later.
        //
        // Splitting 2 and 3 matters: chunking is per segment, and the chunk count is hashed
        // into the top-level digest (0x5a || u32 chunkCount || chunkDigests...). Merging the
        // CD and EOCD into one segment yields 2 chunks instead of 3 and a different digest,
        // which surfaces as "APK integrity check failed. CHUNKED_SHA256 digest mismatch".
        val entries = apk.copyOfRange(0, cdOffset)
        val centralDir = apk.copyOfRange(cdOffset, eocdOffset)
        val eocd = apk.copyOfRange(eocdOffset, apk.size)
        putIntLe(eocd, 16, cdOffset)

        val contentDigest = computeContentDigest(listOf(entries, centralDir, eocd))
        val pairValue = buildV2PairValue(contentDigest, privateKey, certificateDer)
        val block = buildApkSigningBlock(pairValue)

        val out = ByteArrayOutputStream(apk.size + block.size)
        out.write(apk, 0, cdOffset)
        out.write(block)
        out.write(apk, cdOffset, apk.size - cdOffset)
        val result = out.toByteArray()

        // Inserting the block *before* the central directory moves the CD and the EOCD later
        // by exactly block.size, but leaves every zip entry exactly where it was. So:
        //   * the EOCD's "offset of start of central directory" must be updated;
        //   * the central directory's "relative offset of local header" fields must be left
        //     ALONE — they still address the unmoved entries.
        // Shifting the LHOs here is a plausible-looking mistake that makes every entry point
        // block.size bytes too far and surfaces much later as "invalid LOC header".
        val newCdOffset = cdOffset + block.size
        val newEocdOffset = eocdOffset + block.size
        putIntLe(result, newEocdOffset + 16, newCdOffset)

        // Fail loudly here rather than emitting an APK that only the installer rejects.
        check(readIntLe(result, newCdOffset) == CD_SIGNATURE) {
            "v2: no central directory at $newCdOffset after block insertion"
        }
        val firstLho = readIntLe(result, newCdOffset + 42)
        check(readIntLe(result, firstLho) == LOCAL_FILE_HEADER_SIGNATURE) {
            "v2: first CD entry points at $firstLho, which is not a local file header"
        }
        check(readIntLe(result, newEocdOffset) == EOCD_SIGNATURE) {
            "v2: EOCD did not land at $newEocdOffset"
        }
        return result
    }

    // ---- digest -------------------------------------------------------------

    internal fun computeContentDigest(regions: List<ByteArray>): ByteArray {
        val chunkDigests = ArrayList<ByteArray>()
        for (region in regions) {
            var offset = 0
            while (offset < region.size) {
                val count = minOf(CHUNK_SIZE, region.size - offset)
                val md = MessageDigest.getInstance("SHA-256")
                md.update(0xa5.toByte())
                md.update(intLe(count))
                md.update(region, offset, count)
                chunkDigests += md.digest()
                offset += count
            }
        }

        val md = MessageDigest.getInstance("SHA-256")
        md.update(0x5a.toByte())
        md.update(intLe(chunkDigests.size))
        for (digest in chunkDigests) md.update(digest)
        return md.digest()
    }

    // ---- signer block --------------------------------------------------------

    /**
     * ```
     * signer block ::= len32 {
     *     len32 { signed data }
     *     len32 { SEQUENCE OF (u32 algorithm, len32 signature) }
     *     len32 public key   // X.509 SubjectPublicKeyInfo
     * }
     * signed data  ::= len32 { SEQUENCE OF digests } len32 { SEQUENCE OF certs } len32 { attrs }
     * digest       ::= u32 algorithm, len32 digest
     * ```
     */
    /**
     * Builds one v2 signer block. Framing, per `V2SchemeVerifier.parseSigner`:
     *
     * ```
     * signer      ::= len32 signedData                      // ONE prefix; the verifier reads
     *                 len32 signatures                      // these exact bytes and feeds them
     *                 len32 publicKeyInfo                   // straight to Signature.update()
     * signedData  ::= len32 { len32 { u32 alg, len32 digest } } ...   // digests
     *                 len32 { len32 certificate } ...                 // certificates
     *                 len32 { ... }                                   // additional attributes
     * signatures  ::= len32 { u32 alg, len32 signature } ...
     * ```
     *
     * Two details are easy to get wrong and both produce "Malformed signer block":
     *  - the embedded `signedData` carries a *single* length prefix, but the bytes that get
     *    **signed** are exactly those inside it — no second prefix;
     *  - every digest and every signature is *individually* length-prefixed inside its
     *    sequence, not just the sequence as a whole.
     */
    /**
     * Builds the complete v2 ID-value pair payload.
     *
     * Layout, matching a known-good apksig-produced block field for field:
     * ```
     * u32   0x7109871a                       block id
     * u32   len(signers)
     * u32   len(signer)
     * u32   len(signedData)   signedData
     * u32   len(signatures)   signatures
     * u32   len(publicKey)    publicKey
     * ```
     * Written as explicit concatenation rather than with a nesting helper: the sequence of
     * u32 prefixes is exactly where this format is easy to get wrong, and a double prefix
     * fails only as an opaque "Malformed signer block" from the installer.
     */
    internal fun buildV2PairValue(
        contentDigest: ByteArray,
        privateKey: PrivateKey,
        certificateDer: ByteArray,
    ): ByteArray {
        val publicKey = publicKeyInfo(certificateDer)

        // Verified byte-for-byte against an apksig-produced APK: every one of these
        // sequences carries TWO length prefixes — one for the sequence, one for the element
        // inside it. Dropping the element prefix shortens signedData by 4 bytes per field
        // and apksig then reports "Malformed signer block".
        // digests: len32 { len32 { u32 algorithm || len32 digest } ... }
        val digestElement = intLe(SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA256) +
            prefixed(contentDigest)
        val digests = prefixed(prefixed(digestElement))

        // certificates: len32 { len32 certificate ... }   (two prefixes)
        val certificates = prefixed(prefixed(certificateDer))

        // additional attributes: len32 { } — empty sequence
        val attributes = prefixed(ByteArray(0))

        val signedData = digests + certificates + attributes

        val jcaSigner = java.security.Signature.getInstance("SHA256withRSA")
        jcaSigner.initSign(privateKey)
        // The signature covers exactly these bytes — the verifier reads signedData out of
        // its length-prefixed slice and feeds it to Signature.update() unchanged.
        jcaSigner.update(signedData)
        val signature = jcaSigner.sign()

        // signatures: len32 { len32 { u32 algorithm || len32 signature } ... }  (two prefixes)
        // publicKey, by contrast, is a single len32 around the raw SubjectPublicKeyInfo.
        val signatureElement = intLe(SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA256) + prefixed(signature)
        // NOTE the asymmetry, confirmed against an apksig-produced block:
        //   sigs = len32(264) || u32 alg || len32(256) || sig   <- ONE prefix
        //   dgsts = len32(40) || len32(36) || u32 alg || len32(32) || digest  <- TWO
        // Adding the element prefix here makes apksig read the length 264 (0x108) as an
        // algorithm id and report "Unknown signature algorithm: 0x108".
        val signatures = prefixed(signatureElement)

        // `signer` is the signer's *content*. `signers` is the length-prefixed sequence of
        // signers, and each signer inside it carries its own length prefix — two distinct
        // u32s that are easy to collapse into one. Emitting only the sequence prefix makes
        // apksig read signedData's length as the signer length and report
        // "Malformed APK Signature Scheme v2 signature record #1".
        // ---- assemble, outermost last --------------------------------------
        // signerContent = the signer's three length-prefixed fields, concatenated.
        // signer        = len32 || signerContent   (one signer element)
        // signers       = len32 || signer          (the SEQUENCE OF signers)
        // pairValue     = u32 blockId || signers
        // That is four u32 headers before signedData's own length prefix. Collapsing any
        // two of them is the classic mistake here, and apksig reports it only as an opaque
        // "Malformed signer block", so the sizes are asserted explicitly below.
        val signerContent = prefixed(signedData) + prefixed(signatures) + prefixed(publicKey)
        val signer = prefixed(signerContent)
        val signers = prefixed(signer)
        val pairValue = intLe(SIGNATURE_SCHEME_V2_BLOCK_ID) + signers

        fun u32At(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

        check(signerContent.size == (4 + signedData.size) + (4 + signatures.size) +
            (4 + publicKey.size)) { "signerContent framing is wrong" }
        check(signer.size == 4 + signerContent.size) { "signer framing is wrong" }
        check(signers.size == 4 + signer.size) { "signers framing is wrong" }
        check(pairValue.size == 4 + signers.size) { "pairValue framing is wrong" }

        // The four headers, in order, at their absolute offsets.
        check(u32At(pairValue, 4) == signer.size) {
            "signers header=${u32At(pairValue, 4)} expected=${signer.size}"
        }
        check(u32At(pairValue, 8) == signerContent.size) {
            "signer header=${u32At(pairValue, 8)} expected=${signerContent.size}"
        }
        check(u32At(pairValue, 12) == signedData.size) {
            "signedData header=${u32At(pairValue, 12)} expected=${signedData.size}"
        }
        // signedData begins at +16 with the digests sequence, whose own header stores the
        // size of its *contents* (the digest element), not of the whole prefixed sequence.
        check(u32At(pairValue, 16) == digests.size - 4) {
            "digests header=${u32At(pairValue, 16)} expected=${digests.size - 4}"
        }
        return pairValue
    }

    /** `u32 bytes.size || bytes` — the single primitive this format is built from. */
    private fun prefixed(bytes: ByteArray): ByteArray = intLe(bytes.size) + bytes

    /**
     * v2 embeds the raw SubjectPublicKeyInfo here, not the certificate. We know the exact
     * shape of the certificate we generated, so a short [DerReader] walk suffices — no
     * ASN.1 library needed.
     */
    internal fun publicKeyInfo(certificateDer: ByteArray): ByteArray {
        val certificate = DerReader(certificateDer).root()
        val tbs = DerReader(certificate.content).next()
        val content = tbs.content
        val reader = DerReader(content)

        var pos = 0
        var field = reader.peek(pos)
        if (field.tag == 0xA0) { pos += field.totalLength; field = reader.peek(pos) } // version
        pos += field.totalLength                                     // serialNumber
        field = reader.peek(pos); pos += field.totalLength           // signature
        field = reader.peek(pos); pos += field.totalLength           // issuer
        field = reader.peek(pos); pos += field.totalLength           // validity
        field = reader.peek(pos); pos += field.totalLength           // subject
        field = reader.peek(pos)                                     // subjectPublicKeyInfo
        return content.copyOfRange(pos, pos + field.totalLength)
    }

    // ---- APK Signing Block ---------------------------------------------------

    /**
     * ```
     * u64 sizeOfBlock            // = pairs + 8 + 16, i.e. everything after this field
     * ( u64 pairLength, bytes pairValue )*
     * u64 sizeOfBlock            // same value again
     * 16  magic "APK Sig Block 42"
     * ```
     */
    internal fun buildApkSigningBlock(pairValue: ByteArray): ByteArray {
        val pairWithLength = ByteBuffer.allocate(8 + pairValue.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(pairValue.size.toLong())
            .put(pairValue)
            .array()

        val sizeOfBlock = pairWithLength.size + 8 + 16
        val block = ByteBuffer.allocate(8 + sizeOfBlock).order(ByteOrder.LITTLE_ENDIAN)
        block.putLong(sizeOfBlock.toLong())
        block.put(pairWithLength)
        block.putLong(sizeOfBlock.toLong())
        block.putLong(MAGIC_LO)
        block.putLong(MAGIC_HI)
        return block.array()
    }

    // ---- zip surgery ---------------------------------------------------------

    private fun findEocdOffset(apk: ByteArray): Int? {
        // EOCD is 22 bytes minimum; the comment can add up to 0xFFFF.
        val lowest = maxOf(0, apk.size - 22 - 0xFFFF)
        for (i in apk.size - 22 downTo lowest) {
            if (readIntLe(apk, i) == EOCD_SIGNATURE) {
                // Guard against a false positive inside the comment: the declared comment
                // length must account for exactly the remaining bytes.
                val commentLength = readU16(apk, i + 20)
                if (i + 22 + commentLength == apk.size) return i
            }
        }
        return null
    }


    // ---- length-prefixed writer ----------------------------------------------

    // ---- byte helpers ---------------------------------------------------------

    private fun intLe(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun readIntLe(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun readU16(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun putIntLe(b: ByteArray, o: Int, value: Int) {
        b[o] = (value and 0xFF).toByte()
        b[o + 1] = ((value shr 8) and 0xFF).toByte()
        b[o + 2] = ((value shr 16) and 0xFF).toByte()
        b[o + 3] = ((value shr 24) and 0xFF).toByte()
    }

}
