package dev.understudy.packaging.sign

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Structural tests for the hand-rolled v2 signer.
 *
 * These parse our own output with an independent reader that mirrors apksig's
 * `V2SchemeVerifier.parseSigners`/`parseSigner` field by field. Encoder and checker share no
 * code, so a framing mistake surfaces as a concrete field mismatch here instead of an opaque
 * "Malformed signer block" from the installer.
 *
 * Field layout, confirmed against an apksig-produced APK byte for byte:
 * ```
 * pairValue    = u32 0x7109871a || len32 signers
 * signers      = len32 signer || ...
 * signer       = len32 signedData || len32 signatures || len32 publicKey
 * signedData   = len32 digests || len32 certificates || len32 attributes
 * digests      = len32 { u32 alg || len32 digest } || ...
 * certificates = len32 cert || ...
 * signatures   = len32 { u32 alg || len32 signature } || ...
 * ```
 */
class V2SignerStructureTest {

    private val identity = SigningIdentity.generate("Understudy Test")

    private fun u32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    /** Reads `u32 length || payload`, returning the payload and the offset just past it. */
    private fun slice(b: ByteArray, o: Int): Pair<ByteArray, Int> {
        val n = u32(b, o)
        assertTrue(n >= 0 && o + 4 + n <= b.size, "length $n at $o overflows ${b.size}")
        return b.copyOfRange(o + 4, o + 4 + n) to (o + 4 + n)
    }

    @Test
    fun `pair value has exactly the apksig field sequence`() {
        val digest = ByteArray(32) { it.toByte() }
        val pair = V2Signer.buildV2PairValue(digest, identity.privateKey, identity.certificateDer)

        assertEquals(0x7109871a, u32(pair, 0), "block id")

        val (signers, afterSigners) = slice(pair, 4)
        assertEquals(pair.size, afterSigners, "trailing bytes after the signers sequence")

        val (signer, afterSigner) = slice(signers, 0)
        assertEquals(signers.size, afterSigner, "exactly one signer must fill the sequence")

        val (signedData, p1) = slice(signer, 0)
        val (signatures, p2) = slice(signer, p1)
        val (publicKey, p3) = slice(signer, p2)
        assertEquals(signer.size, p3, "trailing bytes inside the signer block")

        val (digests, d1) = slice(signedData, 0)
        val (certs, d2) = slice(signedData, d1)
        val (attrs, d3) = slice(signedData, d2)
        assertEquals(signedData.size, d3, "trailing bytes inside signedData")

        // Each sequence holds one length-prefixed element, so unwrapping takes two slices:
        // the sequence prefix, then the element prefix. apksig does exactly this —
        // getLengthPrefixedSlice(digests) per element, then getInt() + readLengthPrefixedByteArray.
        // digests = len32 { len32 { u32 alg || len32 digest } }, so ONE slice yields the
        // element whose first field is the algorithm id. (Confirmed against an
        // apksig-produced block: digests sequence len is 44, element len 40, then 0x0103.)
        val (digestElement, e0) = slice(digests, 0)
        assertEquals(digests.size, e0, "exactly one digest element expected")
        assertEquals(0x0103, u32(digestElement, 0), "RSA PKCS#1 v1.5 + SHA-256")
        val (digestValue, e1) = slice(digestElement, 4)
        assertEquals(digestElement.size, e1, "trailing bytes in the digest element")
        assertEquals(32, digestValue.size, "SHA-256 digest length")
        assertTrue(digest.contentEquals(digestValue), "digest payload changed in transit")

        val (certElement, c0) = slice(certs, 0)
        assertEquals(certs.size, c0, "exactly one certificate expected")
        assertTrue(
            identity.certificateDer.contentEquals(certElement),
            "certificate bytes differ from the identity's",
        )

        assertEquals(0, attrs.size, "additional attributes must be empty")

        // signatures is single-prefixed: len32 { u32 alg || len32 signature }
        val (sigElement, s0) = slice(signatures, 0)
        assertEquals(signatures.size, s0, "exactly one signature expected")
        assertEquals(0x0103, u32(sigElement, 0), "signature algorithm id")
        val (sigValue, s1) = slice(sigElement, 4)
        assertEquals(sigElement.size, s1, "trailing bytes in the signature element")
        assertEquals(256, sigValue.size, "RSA-2048 signature length")

        // publicKey is the SubjectPublicKeyInfo and must equal the certificate's own.
        assertTrue(
            identity.certificate.publicKey.encoded.contentEquals(publicKey),
            "publicKey does not match the certificate's SubjectPublicKeyInfo",
        )
    }

    @Test
    fun `signature verifies over exactly the embedded signedData bytes`() {
        val digest = ByteArray(32) { (it * 7).toByte() }
        val pair = V2Signer.buildV2PairValue(digest, identity.privateKey, identity.certificateDer)

        val (signers, _) = slice(pair, 4)
        val (signer, a1) = slice(signers, 0)
        val (signedData, a2) = slice(signer, 0)
        val (signatures, _) = slice(signer, a2)
        val (sigElement, _) = slice(signatures, 0)
        val (sigValue, _) = slice(sigElement, 4)
        assertTrue(a1 <= signers.size, "signer slice overran the signers sequence")

        val verifier = java.security.Signature.getInstance("SHA256withRSA")
        verifier.initVerify(identity.certificate.publicKey)
        verifier.update(signedData)
        assertTrue(verifier.verify(sigValue), "signature does not verify over signedData")
    }

    @Test
    fun `publicKeyInfo extracts the certificate SubjectPublicKeyInfo`() {
        val spki = V2Signer.publicKeyInfo(identity.certificateDer)
        assertTrue(
            identity.certificate.publicKey.encoded.contentEquals(spki),
            "extracted SPKI differs from CertificateFactory's",
        )
        assertEquals(0x30, spki[0].toInt() and 0xFF, "SPKI must be a DER SEQUENCE")
    }

    @Test
    fun `signing block framing matches the documented layout`() {
        val pairValue = ByteArray(64) { it.toByte() }
        val block = V2Signer.buildApkSigningBlock(pairValue)

        // sizeOfBlock counts the pairs plus the trailing u64 plus the 16-byte magic.
        val sizeOfBlock = (8 + pairValue.size) + 8 + 16
        assertEquals(8 + sizeOfBlock, block.size, "total block size")

        fun u64(b: ByteArray, o: Int): Long {
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
            return v
        }

        assertEquals(sizeOfBlock.toLong(), u64(block, 0), "leading sizeOfBlock")
        assertEquals(sizeOfBlock.toLong(), u64(block, block.size - 24), "trailing sizeOfBlock")
        assertEquals(
            "APK Sig Block 42",
            String(block, block.size - 16, 16, Charsets.US_ASCII),
            "magic",
        )
        assertEquals(pairValue.size.toLong(), u64(block, 8), "pair length")
        assertTrue(
            block.copyOfRange(16, 16 + pairValue.size).contentEquals(pairValue),
            "pair value not stored verbatim",
        )
    }

    @Test
    fun `content digest follows the 0xa5 chunk and 0x5a combine rules`() {
        val data = "understudy".toByteArray()
        val got = V2Signer.computeContentDigest(listOf(data))

        val chunk = java.security.MessageDigest.getInstance("SHA-256").let {
            it.update(0xa5.toByte())
            it.update(byteArrayOf(data.size.toByte(), 0, 0, 0))
            it.update(data)
            it.digest()
        }
        val expected = java.security.MessageDigest.getInstance("SHA-256").let {
            it.update(0x5a.toByte())
            it.update(byteArrayOf(1, 0, 0, 0))
            it.update(chunk)
            it.digest()
        }
        assertTrue(expected.contentEquals(got), "single-chunk digest does not match the spec")
    }

    @Test
    fun `empty regions contribute no chunks`() {
        val data = ByteArray(100) { 1 }
        val withEmpties = V2Signer.computeContentDigest(listOf(ByteArray(0), data, ByteArray(0)))
        val without = V2Signer.computeContentDigest(listOf(data))
        assertTrue(withEmpties.contentEquals(without), "empty regions must produce zero chunks")
    }
}
