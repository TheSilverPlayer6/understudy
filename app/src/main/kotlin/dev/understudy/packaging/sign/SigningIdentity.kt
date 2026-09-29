package dev.understudy.packaging.sign

import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The signing identity used for every proxy APK this install produces.
 *
 * Generated once on first run and persisted, because the identity has to stay *stable*:
 * reinstalling or updating a proxy for the same target package requires a matching
 * signature, and once a real app has been re-signed with this key the proxy and the real
 * app must keep agreeing forever after.
 *
 * Why a software key rather than the Android Keystore: we need to export the private key
 * material to feed it to our own v1/v2 signer, and Keystore keys are non-exportable by
 * design. That is an acceptable trade here — this key only ever signs throwaway proxy APKs
 * whose sole privilege is a `signature`-level permission back into Understudy. It never
 * signs anything a user installs deliberately, and it is stored in app-private storage.
 */
class SigningIdentity private constructor(
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
    /**
     * The DER of the issuer `Name` exactly as it appears in the certificate.
     *
     * Kept rather than re-parsed: PKCS#7 `SignerInfo.issuerAndSerialNumber` needs the whole
     * `Name` SEQUENCE, and `X500Principal.getEncoded()` deliberately omits that outer
     * wrapper. Deriving it here, where we built it, removes an entire class of ASN.1
     * walking bugs.
     */
    val issuerDer: ByteArray,
    /** The certificate serial number, big-endian, sign bit included. */
    val serialBytes: ByteArray,
) {

    val certificateDer: ByteArray get() = certificate.encoded

    /** SHA-256 fingerprint, for showing the user which key signed a given proxy. */
    val fingerprintHex: String by lazy {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.digest(certificateDer).joinToString(":") { "%02X".format(it) }
    }

    companion object {

        private const val KEY_ALGORITHM = "RSA"
        private const val KEY_SIZE = 2048
        private const val SIGNATURE_ALGORITHM = "SHA256withRSA"

        /** Validity: 1970-ish start to avoid clock-skew rejections, ~30 years out. */
        private const val NOT_BEFORE_MILLIS = 1_577_836_800_000L // 2020-01-01T00:00:00Z
        private const val VALIDITY_DAYS = 365 * 30

        private const val PRIVATE_KEY_FILE = "proxy-signer.pk8"
        private const val CERTIFICATE_FILE = "proxy-signer.x509.pem"

        /**
         * Loads the persisted identity, generating and storing one if none exists yet.
         *
         * @param dir an app-private directory (e.g. `context.filesDir`)
         */
        fun loadOrCreate(dir: File, commonName: String = "Understudy"): SigningIdentity {
            dir.mkdirs()
            val keyFile = File(dir, PRIVATE_KEY_FILE)
            val certFile = File(dir, CERTIFICATE_FILE)

            if (keyFile.isFile && certFile.isFile) {
                runCatching { load(keyFile, certFile) }
                    .onSuccess { return it }
                    // A truncated or corrupt key must not brick the app: fall through and
                    // regenerate. Losing the old identity only means a previously installed
                    // proxy cannot be updated in place, which the flow already handles by
                    // uninstalling first.
            }

            val identity = generate(commonName)
            keyFile.writeBytes(identity.privateKey.encoded)
            certFile.writeBytes(identity.certificateDer)
            // Best-effort tightening; app-private storage is already 0700 for our uid.
            runCatching {
                keyFile.setReadable(false, false)
                keyFile.setReadable(true, true)
            }
            return identity
        }

        private fun load(keyFile: File, certFile: File): SigningIdentity {
            val keyFactory = KeyFactory.getInstance(KEY_ALGORITHM)
            val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            val factory = CertificateFactory.getInstance("X.509")
            val certificate = factory.generateCertificate(certFile.inputStream()) as X509Certificate
            // Recovered by walking the certificate we wrote ourselves; DerReader keeps this
            // to a few lines and fails loudly if the layout ever changes.
            val (issuerDer, serialBytes) = extractIssuerAndSerial(certificate.encoded)
            return SigningIdentity(privateKey, certificate, issuerDer, serialBytes)
        }

        /**
         * Recovers the issuer `Name` and serial from a certificate we generated ourselves.
         *
         * `TBSCertificate` is `version [0] EXPLICIT` (which we always emit), then
         * serialNumber, signature, issuer, validity, subject, subjectPublicKeyInfo. We need
         * the full DER of the issuer — tag and length included — because PKCS#7
         * `SignerInfo.issuerAndSerialNumber` embeds `Name` verbatim, and
         * `X500Principal.getEncoded()` strips the outer SEQUENCE.
         */
        private fun extractIssuerAndSerial(certDer: ByteArray): Pair<ByteArray, ByteArray> {
            val certificate = DerReader(certDer).root()
            val tbs = DerReader(certificate.content).next()
            val content = tbs.content
            val reader = DerReader(content)

            var pos = 0
            var field = reader.peek(pos)
            pos += field.totalLength
            if (field.tag == 0xA0) {                     // [0] EXPLICIT version
                field = reader.peek(pos)                 // -> serialNumber
                pos += field.totalLength
            }
            val serialBytes = field.content

            field = reader.peek(pos); pos += field.totalLength   // signature AlgorithmIdentifier
            field = reader.peek(pos)                             // issuer Name
            val issuerDer = content.copyOfRange(pos, pos + field.totalLength)

            return issuerDer to serialBytes
        }

        fun generate(commonName: String = "Understudy"): SigningIdentity {
            val keyPair = generateKeyPair()
            val serial = BigInteger(64, SecureRandom()).setBit(0)
            val issuerDer = buildName(commonName)
            val certificate = selfSign(keyPair, commonName, serial, issuerDer)
            return SigningIdentity(
                privateKey = keyPair.private,
                certificate = certificate,
                issuerDer = issuerDer,
                serialBytes = minimalTwosComplement(serial),
            )
        }

        /**
         * Strips leading zero bytes but keeps one when the next byte has its high bit set,
         * so the value stays positive when re-encoded as a DER INTEGER.
         */
        private fun minimalTwosComplement(value: BigInteger): ByteArray {
            val raw = value.toByteArray()
            var start = 0
            while (start < raw.size - 1 && raw[start] == 0.toByte() &&
                raw[start + 1].toInt() and 0x80 == 0
            ) {
                start++
            }
            return raw.copyOfRange(start, raw.size)
        }

        private fun generateKeyPair(): KeyPair {
            val generator = KeyPairGenerator.getInstance(KEY_ALGORITHM)
            generator.initialize(KEY_SIZE, SecureRandom())
            return generator.generateKeyPair()
        }

        /**
         * Builds a self-signed X.509 v3 certificate by hand.
         *
         * ```
         * Certificate ::= SEQUENCE {
         *     tbsCertificate       TBSCertificate,
         *     signatureAlgorithm   AlgorithmIdentifier,
         *     signatureValue       BIT STRING
         * }
         * ```
         */
        private fun selfSign(
            keyPair: KeyPair,
            commonName: String,
            serial: BigInteger,
            issuerDer: ByteArray,
        ): X509Certificate {
            val notBefore = Date(NOT_BEFORE_MILLIS)
            val notAfter = Date(NOT_BEFORE_MILLIS + VALIDITY_DAYS * 24L * 3600_000L)

            val tbs = DerWriter().apply {
                sequence {
                    // [0] EXPLICIT Version ::= INTEGER (v3 == 2)
                    explicitTag(0) { int(2) }
                    bigInt(serial.toByteArray())
                    algorithmIdentifier()
                    raw(issuerDer)                         // issuer  (self-signed)
                    sequence {                             // validity
                        utcTime(toUtcTime(notBefore))
                        utcTime(toUtcTime(notAfter))
                    }
                    raw(issuerDer)                         // subject
                    // SubjectPublicKeyInfo: RSAPublicKey.getEncoded() already IS the DER
                    // of SubjectPublicKeyInfo, so it drops straight in.
                    raw(keyPair.public.encoded)
                }
            }.toByteArray()

            val signer = java.security.Signature.getInstance(SIGNATURE_ALGORITHM)
            signer.initSign(keyPair.private)
            signer.update(tbs)
            val signature = signer.sign()

            val certificateDer = DerWriter().apply {
                sequence {
                    raw(tbs)
                    algorithmIdentifier()
                    bitString(signature)
                }
            }.toByteArray()

            val factory = CertificateFactory.getInstance("X.509")
            return factory.generateCertificate(certificateDer.inputStream()) as X509Certificate
        }

        private fun DerWriter.algorithmIdentifier() {
            raw(
                DerWriter.sequenceOf(
                    DerWriter.tlv(DerWriter.TAG_OBJECT_IDENTIFIER, DerWriter.encodeOid(DerWriter.OID_SHA256_WITH_RSA)) +
                        DerWriter.tlv(DerWriter.TAG_NULL, ByteArray(0))
                )
            )
        }

        /**
         * A two-RDN Name: CN then O. Order matters — RDNSequence is a SEQUENCE and the
         * issuer and subject byte strings must match exactly for a self-signed cert to
         * verify.
         */
        /**
         * A two-RDN `Name`: CN then O. RDN order is part of the encoded bytes, and issuer
         * and subject must match exactly for a self-signed certificate to verify.
         */
        private fun buildName(commonName: String): ByteArray = DerWriter().apply {
            sequence {
                set {
                    sequence {
                        oid(DerWriter.OID_COMMON_NAME)
                        printableString(commonName)
                    }
                }
                set {
                    sequence {
                        oid(DerWriter.OID_ORGANIZATION)
                        printableString("Understudy")
                    }
                }
            }
        }.toByteArray()

        /**
         * UTCTime as `YYMMDDHHMMSSZ`. Two-digit years are interpreted by X.509 as
         * 19xx when >= 50 and 20xx otherwise, which is why the not-before date is pinned
         * well inside an unambiguous range.
         */
        private fun toUtcTime(date: Date): String {
            val format = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            return format.format(date)
        }
    }
}
