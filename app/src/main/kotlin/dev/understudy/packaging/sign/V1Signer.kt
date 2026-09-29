package dev.understudy.packaging.sign

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.PrivateKey
import java.util.jar.Attributes
import java.util.jar.Manifest

/**
 * APK Signature Scheme **v1** (JAR signing).
 *
 * Still required alongside v2: v1 is what makes the package's contents verifiable on the
 * older code paths, and `apksigner` flags a v2-only APK as incomplete for anything that
 * might be read by tooling expecting a `META-INF` signature. Producing both is what the
 * platform's own signer does for a `minSdk` in the 20s.
 *
 * The three artifacts:
 *  - `META-INF/MANIFEST.MF` — a `SHA-256-Digest` per entry, over the *uncompressed* bytes
 *  - `META-INF/UNDERS.SF`   — the digest of the whole manifest plus a digest per manifest
 *                             section (the "signature file", name capped at 8 chars)
 *  - `META-INF/UNDERS.RSA`  — a PKCS#7 SignedData blob signing the `.SF`
 *
 * Signing uses `NONEwithRSA` over a pre-built DigestInfo rather than `SHA256withRSA` over
 * two passes of the data: identical output, half the work, and it avoids re-reading entries
 * that may be large.
 */
object V1Signer {

    /** Must be <= 8 characters: the SF/RSA names derive from it and hit a legacy limit. */
    private const val SIGNATURE_NAME = "UNDERS"

    private const val DIGEST_ALGORITHM = "SHA-256"

    /** sha256 digest algorithm OID. */
    private const val OID_SHA256 = "2.16.840.1.101.3.4.2.1"
    private const val MANIFEST_PATH = "META-INF/MANIFEST.MF"
    private const val SF_PATH = "META-INF/$SIGNATURE_NAME.SF"
    private const val RSA_PATH = "META-INF/$SIGNATURE_NAME.RSA"

    /** An entry to be covered by the v1 signature. */
    class Entry(val name: String, val uncompressedData: ByteArray)

    class Output(
        val manifest: ByteArray,
        val signatureFile: ByteArray,
        val rsaBlock: ByteArray,
        val manifestPath: String = MANIFEST_PATH,
        val signatureFilePath: String = SF_PATH,
        val rsaPath: String = RSA_PATH,
    )

    fun sign(entries: List<Entry>, identity: SigningIdentity): Output {
        val manifestBytes = buildManifest(entries)
        val sfBytes = buildSignatureFile(manifestBytes, entries)
        val rsa = buildPkcs7(
            data = sfBytes,
            privateKey = identity.privateKey,
            certificateDer = identity.certificateDer,
            issuerDer = identity.issuerDer,
            serialBytes = identity.serialBytes,
        )
        return Output(manifestBytes, sfBytes, rsa)
    }

    /** True for entries that JAR signing must skip (the signature itself, directories). */
    fun isSignable(name: String): Boolean {
        if (name.endsWith("/")) return false
        val upper = name.uppercase()
        if (upper == "META-INF/MANIFEST.MF") return false
        if (upper.startsWith("META-INF/") &&
            (upper.endsWith(".SF") || upper.endsWith(".RSA") ||
                upper.endsWith(".DSA") || upper.endsWith(".EC"))
        ) {
            return false
        }
        return true
    }

    // ---- MANIFEST.MF -------------------------------------------------------

    private fun buildManifest(entries: List<Entry>): ByteArray {
        val manifest = Manifest()
        manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        manifest.mainAttributes[Attributes.Name("Created-By")] = "Understudy"

        for (entry in entries) {
            if (!isSignable(entry.name)) continue
            val attrs = Attributes()
            attrs[Attributes.Name(DIGEST_ATTRIBUTE)] = base64(sha256(entry.uncompressedData))
            manifest.entries[entry.name] = attrs
        }
        return writeManifest(manifest)
    }

    private const val DIGEST_ATTRIBUTE = "SHA-256-Digest"

    // ---- UNDERS.SF ---------------------------------------------------------

    /**
     * The SF contains the digest of the entire manifest, plus one section per manifest
     * entry holding the digest of *that section's bytes as they appear in MANIFEST.MF*.
     * Those per-section digests are what let an installer verify a single entry without
     * re-reading the whole manifest.
     */
    private fun buildSignatureFile(manifestBytes: ByteArray, entries: List<Entry>): ByteArray {
        val sections = splitManifestSections(manifestBytes)

        val out = ByteArrayOutputStream()
        writeLine(out, "Signature-Version: 1.0")
        writeLine(out, "Created-By: Understudy")
        writeLine(out, "$DIGEST_ATTRIBUTE: ${base64(sha256(manifestBytes))}")
        out.write(CRLF)

        for ((name, sectionBytes) in sections) {
            if (!isSignable(name)) continue
            writeLine(out, "Name: $name")
            writeLine(out, "$DIGEST_ATTRIBUTE: ${base64(sha256(sectionBytes))}")
            out.write(CRLF)
        }
        // Keep entries referenced but not recomputed, so unused `entries` is still honoured
        // by callers that pass a filtered list.
        require(entries.isNotEmpty() || sections.isEmpty())
        return out.toByteArray()
    }

    /**
     * Splits MANIFEST.MF into its per-entry sections. A section runs from its `Name:` line
     * up to (but not including) the next blank line, and the main section is skipped.
     */
    private fun splitManifestSections(manifestBytes: ByteArray): List<Pair<String, ByteArray>> {
        val text = String(manifestBytes, Charsets.UTF_8)
        val blocks = text.split("\r\n\r\n")
        val result = ArrayList<Pair<String, ByteArray>>()
        for (block in blocks) {
            if (block.isBlank()) continue
            val nameLine = block.lineSequence().firstOrNull { it.startsWith("Name:") } ?: continue
            val name = nameLine.removePrefix("Name:").trim()
            // The section bytes are the block plus its trailing CRLF, matching what the
            // manifest writer emitted.
            val section = block + "\r\n"
            result += name to section.toByteArray(Charsets.UTF_8)
        }
        return result
    }

    // ---- PKCS#7 / UNDERS.RSA -----------------------------------------------

    /**
     * A PKCS#7 SignedData blob. The platform only ever needs to walk this with
     * `CertificateFactory.generateCertificates()` plus `Signature.verify()`, so the
     * structure below is the minimal conforming form:
     *
     * ```
     * ContentInfo ::= SEQUENCE { contentType OID(data), [0] EXPLICIT SignedData }
     * SignedData  ::= SEQUENCE {
     *     version INTEGER(1),
     *     digestAlgorithms SET OF AlgorithmIdentifier,
     *     contentInfo SEQUENCE { OID(data) },
     *     certificates [0] IMPLICIT SET OF Certificate,
     *     signerInfos SET OF SignerInfo }
     * SignerInfo  ::= SEQUENCE {
     *     version INTEGER(1),
     *     issuerAndSerialNumber SEQUENCE { Name, INTEGER },
     *     digestAlgorithm AlgorithmIdentifier,
     *     digestEncryptionAlgorithm AlgorithmIdentifier,
     *     encryptedDigest OCTET STRING,
     *     unauthenticatedAttributes [1] IMPLICIT SET OF (empty) }
     * ```
     */
    private fun buildPkcs7(
        data: ByteArray,
        privateKey: PrivateKey,
        certificateDer: ByteArray,
        issuerDer: ByteArray,
        serialBytes: ByteArray,
    ): ByteArray {
        val digest = sha256(data)
        val signature = signDigestInfo(digest, privateKey)

        val signerInfo = DerWriter().apply {
            sequence {
                int(1)
                sequence {
                    raw(issuerDer)
                    bigInt(serialBytes)
                }
                // digestAlgorithm identifies the hash alone; digestEncryptionAlgorithm is
                // the one that carries the RSA OID.
                algorithm(OID_SHA256, withNullParam = true)
                algorithm(DerWriter.OID_SHA256_WITH_RSA, withNullParam = true)
                octetString(signature)
            }
        }.toByteArray()

        val signedData = DerWriter().apply {
            sequence {
                int(1)
                set { algorithm(OID_SHA256, withNullParam = true) }
                sequence { oid("1.2.840.113549.1.7.1") }
                // [0] IMPLICIT certificates
                raw(DerWriter.tlv(0xA0, certificateDer))
                set { raw(signerInfo) }
            }
        }.toByteArray()

        return DerWriter().apply {
            sequence {
                oid("1.2.840.113549.1.7.2")
                raw(DerWriter.tlv(0xA0, signedData))
            }
        }.toByteArray()
    }

    private fun DerWriter.algorithm(oid: String, withNullParam: Boolean) {
        val body = DerWriter.tlv(DerWriter.TAG_OBJECT_IDENTIFIER, DerWriter.encodeOid(oid)) +
            if (withNullParam) DerWriter.tlv(DerWriter.TAG_NULL, ByteArray(0)) else ByteArray(0)
        raw(DerWriter.sequenceOf(body))
    }

    /**
     * Signs a pre-computed SHA-256 digest by wrapping it in the DigestInfo prefix and using
     * `NONEwithRSA`, so the provider does the raw PKCS#1 v1.5 padding only.
     */
    private fun signDigestInfo(digest: ByteArray, privateKey: PrivateKey): ByteArray {
        // DigestInfo ::= SEQUENCE {
        //     digestAlgorithm AlgorithmIdentifier,   -- itself a SEQUENCE { OID, NULL }
        //     digest          OCTET STRING }
        //
        // For SHA-256 this is the well-known 19-byte prefix 3031300d0609608648016503040201
        // 05000420 followed by the 32-byte digest. Wrapping the concatenation in an extra
        // SEQUENCE (i.e. using sequenceOf instead of tlv) drops that inner AlgorithmIdentifier
        // wrapper and yields `302f0609...`, which every verifier rejects.
        val digestInfo = DerWriter.tlv(
            DerWriter.TAG_SEQUENCE,
            DerWriter.sequenceOf(
                DerWriter.tlv(DerWriter.TAG_OBJECT_IDENTIFIER, DerWriter.encodeOid(OID_SHA256)) +
                    DerWriter.tlv(DerWriter.TAG_NULL, ByteArray(0))
            ) + DerWriter.tlv(DerWriter.TAG_OCTET_STRING, digest)
        )
        val expectedPrefix = "3031300d060960864801650304020105000420"
        check(digestInfo.size == 51 && digestInfo.take(19).joinToString("") { "%02x".format(it) } == expectedPrefix) {
            "DigestInfo encoding drifted from the standard SHA-256 prefix"
        }

        val signer = java.security.Signature.getInstance("NONEwithRSA")
        signer.initSign(privateKey)
        signer.update(digestInfo)
        return signer.sign()
    }

    // ---- helpers ------------------------------------------------------------

    private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())

    private fun writeLine(out: ByteArrayOutputStream, line: String) {
        // The JAR manifest format wraps at 72 bytes; our lines are short and ASCII, but
        // honour the rule anyway so a long entry name cannot corrupt the file.
        val bytes = line.toByteArray(Charsets.UTF_8)
        var offset = 0
        var first = true
        while (offset < bytes.size) {
            val limit = if (first) 70 else 69
            val count = minOf(limit, bytes.size - offset)
            if (!first) out.write(' '.code)
            out.write(bytes, offset, count)
            out.write(CRLF)
            offset += count
            first = false
        }
        if (bytes.isEmpty()) out.write(CRLF)
    }

    private fun writeManifest(manifest: Manifest): ByteArray {
        val out = ByteArrayOutputStream()
        manifest.write(out)
        return out.toByteArray()
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance(DIGEST_ALGORITHM).digest(data)

    private fun base64(data: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(data)
}

