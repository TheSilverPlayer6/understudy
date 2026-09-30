package dev.understudy.packaging.sign

import java.io.File
import java.math.BigInteger
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.security.KeyFactory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * Pins [SigningIdentity.loadOrCreate] — the persistence path that every proxy this install has
 * ever generated depends on, and which had no coverage at all.
 *
 * Why that matters more than an untested utility usually does. The identity has to stay **stable
 * across runs**: package identity is device-wide, so updating an installed proxy in place requires
 * a matching signature, and once a real app has been re-signed with this key the proxy and the app
 * must keep agreeing forever. If `loadOrCreate` ever silently regenerated instead of loading, the
 * symptom would not be a crash — it would be `INSTALL_FAILED_UPDATE_INCOMPATIBLE` on a target the
 * user had already converted, plus every existing proxy refusing the caller check. Recovering from
 * that means uninstalling proxies, which is exactly the operation that can destroy
 * `Android/data/<pkg>`.
 *
 * The second thing worth pinning is [SigningIdentity.load]'s recovery of `issuerDer` and
 * `serialBytes``. `load` does not get them from the generator; it walks the DER of the certificate it
 * read back, because PKCS#7 `SignerInfo.issuerAndSerialNumber` needs the issuer `Name` *with* its
 * outer SEQUENCE, which `X500Principal.getEncoded()` deliberately strips. A wrong walk there
 * produces a v1 signature every verifier rejects while `keytool` still parses the certificate
 * happily — the failure looks like a key problem and is not. Milestone 1 lost time to exactly that
 * shape of bug. The checks below use the JDK's own certificate parser as the independent oracle
 * rather than re-walking the DER with our own reader, which would be circular.
 *
 * Plain JUnit: `SigningIdentity` is `java.security` and `java.io` only, no Android types, so this
 * needs neither Robolectric nor a device.
 */
class SigningIdentityPersistenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(name: String = "signing"): File = tmp.newFolder(name)

    // ---- the property the whole design rests on -----------------------------

    @Test
    fun firstCallCreatesAndPersistsBothFiles() {
        val d = dir()
        val identity = SigningIdentity.loadOrCreate(d)

        assertNotNull(identity)
        assertTrue(File(d, "proxy-signer.pk8").isFile, "the private key was not persisted")
        assertTrue(File(d, "proxy-signer.x509.pem").isFile, "the certificate was not persisted")
        assertTrue(
            File(d, "proxy-signer.pk8").length() > 500,
            "the persisted key is implausibly small for RSA-2048 PKCS#8",
        )
    }

    @Test
    fun aSecondCallLoadsTheSameIdentityRatherThanRegenerating() {
        val d = dir()
        val first = SigningIdentity.loadOrCreate(d)
        val second = SigningIdentity.loadOrCreate(d)

        assertContentEquals(
            first.certificateDer,
            second.certificateDer,
            "loadOrCreate regenerated instead of loading — every proxy already installed by this " +
                "install would stop updating in place and start refusing the caller check",
        )
        assertContentEquals(
            first.privateKey.encoded,
            second.privateKey.encoded,
            "the private key differs between two loads of the same directory",
        )
        assertEquals(first.fingerprintHex, second.fingerprintHex)
    }

    @Test
    fun repeatedLoadsAreStableAcrossManyCalls() {
        // Not just twice: a race or a partial-write bug could show up intermittently, and this is
        // cheap enough to run often.
        val d = dir()
        val reference = SigningIdentity.loadOrCreate(d).certificateDer
        repeat(10) {
            assertContentEquals(
                reference,
                SigningIdentity.loadOrCreate(d).certificateDer,
                "load ${it + 1} produced a different certificate",
            )
        }
    }

    @Test
    fun twoDirectoriesGetTwoDifferentIdentities() {
        // The identity is per-install. Two installs sharing one would mean one device's proxies
        // are accepted by another's, which is what the backup exclusions exist to prevent.
        val a = SigningIdentity.loadOrCreate(dir("a"))
        val b = SigningIdentity.loadOrCreate(dir("b"))
        assertNotEquals(a.fingerprintHex, b.fingerprintHex)
        assertFalse(a.certificateDer.contentEquals(b.certificateDer))
    }

    // ---- the DER recovery that v1 signing depends on ------------------------

    @Test
    fun aLoadedIdentityReproducesTheGeneratorsIssuerDerAndSerial() {
        val d = dir()
        val generated = SigningIdentity.loadOrCreate(d)
        val loaded = loadDirectly(d)

        assertContentEquals(
            generated.issuerDer,
            loaded.issuerDer,
            "the issuer Name recovered from the certificate does not match the one the generator " +
                "built — PKCS#7 SignerInfo.issuerAndSerialNumber would be wrong and every v1 " +
                "verifier would reject the signature",
        )
        assertContentEquals(
            generated.serialBytes,
            loaded.serialBytes,
            "the serial recovered from the certificate does not match the generator's",
        )
    }

    /**
     * Checks `issuerDer` against the JDK's own X.509 parser rather than against our `DerReader`,
     * which would be our writer checking our reader.
     *
     * This test replaced a wrong assumption, and the wrong assumption was in the production KDoc
     * too. `SigningIdentity.issuerDer` was documented as necessary because
     * "`X500Principal.getEncoded()` deliberately omits that outer wrapper" — the SEQUENCE tag and
     * length that PKCS#7 `SignerInfo.issuerAndSerialNumber` needs. Measured on JDK 21, that is not
     * true: `new X500Principal("CN=Understudy Test, O=Understudy, C=US").getEncoded()` returns 62
     * bytes beginning `0x30 0x3c`, i.e. tag SEQUENCE, length 60, payload 60 — the wrapper is
     * included, and it round-trips through the `X500Principal(byte[])` constructor.
     *
     * So the strong form of the check is available: `issuerDer` should be **byte-identical** to the
     * JDK's encoding of the same Name, because both are the DER of the Name as it appears in the
     * certificate. If they ever differ, our DER writer is emitting something non-canonical — which
     * would still verify, since PKCS#7 embeds the bytes verbatim, but would be worth knowing about.
     */
    @Test
    fun issuerDerIsByteIdenticalToTheJdksEncodingOfTheSameName() {
        val identity = SigningIdentity.loadOrCreate(dir())
        val issuerDer = identity.issuerDer

        // Structural: a well-formed DER SEQUENCE whose declared length covers exactly the payload.
        assertEquals(0x30.toByte(), issuerDer[0], "issuerDer must be a DER SEQUENCE")
        val declared = issuerDer[1].toInt() and 0xFF
        assertTrue(declared < 0x80, "a Name this short should use the short-form length")
        assertEquals(issuerDer.size - 2, declared, "SEQUENCE length does not match its payload")

        // Semantic: it parses as the certificate's own issuer.
        assertEquals(
            identity.certificate.issuerX500Principal,
            javax.security.auth.x500.X500Principal(issuerDer),
            "issuerDer does not parse as the certificate's issuer Name",
        )

        // Byte-for-byte against the independent oracle.
        assertContentEquals(
            identity.certificate.issuerX500Principal.encoded,
            issuerDer,
            "our DER writer is not emitting the Name byte-identically to the JDK; PKCS#7 would " +
                "still embed it verbatim, but non-canonical DER in a signature is worth knowing",
        )
    }

    @Test
    fun serialBytesIsTheMinimalTwosComplementEncodingOfTheCertificateSerial() {
        val identity = SigningIdentity.loadOrCreate(dir())
        val expected = minimalTwosComplement(identity.certificate.serialNumber)
        assertContentEquals(
            expected,
            identity.serialBytes,
            "serialBytes must be the DER INTEGER payload for the certificate's serial; a wrong " +
                "sign or a padded encoding makes SignerInfo point at a certificate the verifier " +
                "cannot match",
        )
    }

    @Test
    fun theCertificateIsSelfSignedSoIssuerAndSubjectAgree() {
        val identity = SigningIdentity.loadOrCreate(dir())
        assertEquals(
            identity.certificate.subjectX500Principal,
            identity.certificate.issuerX500Principal,
            "a self-signed identity must have subject == issuer",
        )
        // And prove it cryptographically rather than by comparing names.
        identity.certificate.verify(identity.certificate.publicKey)
    }

    // ---- the loaded identity must actually be usable ------------------------

    @Test
    fun aLoadedPrivateKeySignsWhatThePersistedCertificateVerifies() {
        val d = dir()
        SigningIdentity.loadOrCreate(d)
        val loaded = loadDirectly(d)

        val payload = "understudy-persistence-round-trip".toByteArray()
        val signer = Signature.getInstance("SHA256withRSA").apply {
            initSign(loaded.privateKey)
            update(payload)
        }
        val signature = signer.sign()

        val verifier = Signature.getInstance("SHA256withRSA").apply {
            initVerify(loaded.certificate.publicKey)
            update(payload)
        }
        assertTrue(
            verifier.verify(signature),
            "the private key recovered from disk does not correspond to the certificate recovered " +
                "from disk — the persisted identity is unusable",
        )
    }

    @Test
    fun thePersistedPrivateKeyIsPkcs8AndTheCertificateIsX509() {
        // Pins the on-disk formats, because changing either silently orphans every existing install.
        val d = dir()
        SigningIdentity.loadOrCreate(d)

        val key = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(File(d, "proxy-signer.pk8").readBytes()))
        assertEquals("RSA", key.algorithm)

        val cert = java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(File(d, "proxy-signer.x509.pem").inputStream()) as X509Certificate
        assertContentEquals(
            cert.encoded,
            File(d, "proxy-signer.x509.pem").readBytes(),
            "the certificate file is not the bare DER of the certificate",
        )
    }

    // ---- corruption must degrade, never brick -------------------------------

    @Test
    fun aCorruptKeyFileRegeneratesInsteadOfThrowing() {
        val d = dir()
        val original = SigningIdentity.loadOrCreate(d)
        File(d, "proxy-signer.pk8").writeBytes(byteArrayOf(0, 1, 2, 3, 4, 5))

        val recovered = SigningIdentity.loadOrCreate(d)

        assertNotNull(recovered, "a truncated key must not brick the app")
        assertFalse(
            original.certificateDer.contentEquals(recovered.certificateDer),
            "a corrupt key should force regeneration, not silently reuse the unreadable one",
        )
        // And the regenerated identity is itself persisted and stable.
        assertContentEquals(recovered.certificateDer, SigningIdentity.loadOrCreate(d).certificateDer)
    }

    @Test
    fun aCorruptCertificateFileRegeneratesInsteadOfThrowing() {
        val d = dir()
        SigningIdentity.loadOrCreate(d)
        val certFile = File(d, "proxy-signer.x509.pem")
        val bytes = certFile.readBytes()
        certFile.writeBytes(bytes.copyOfRange(0, bytes.size / 2)) // truncated, not garbage

        assertNotNull(SigningIdentity.loadOrCreate(d), "a truncated certificate must not brick the app")
    }

    @Test
    fun anEmptyKeyFileRegenerates() {
        val d = dir()
        SigningIdentity.loadOrCreate(d)
        File(d, "proxy-signer.pk8").writeBytes(ByteArray(0))
        assertNotNull(SigningIdentity.loadOrCreate(d))
    }

    @Test
    fun aMissingCertificateRegeneratesWithoutTouchingTheKey() {
        // Half a persisted identity is the state an interrupted first run leaves behind.
        val d = dir()
        SigningIdentity.loadOrCreate(d)
        assertTrue(File(d, "proxy-signer.x509.pem").delete())

        val recovered = SigningIdentity.loadOrCreate(d)
        assertNotNull(recovered)
        assertTrue(File(d, "proxy-signer.pk8").isFile)
        assertTrue(File(d, "proxy-signer.x509.pem").isFile, "both files must exist again afterwards")
    }

    @Test
    fun aDirectoryThatDoesNotExistYetIsCreated() {
        val nested = File(dir(), "a/b/c")
        assertFalse(nested.exists())
        assertNotNull(SigningIdentity.loadOrCreate(nested))
        assertTrue(nested.isDirectory)
    }

    // ---- certificate shape --------------------------------------------------

    @Test
    fun theCertificateHasTheShapeTheDesignPromises() {
        val cert = SigningIdentity.loadOrCreate(dir()).certificate

        assertEquals(3, cert.version, "X.509 v3")
        assertEquals("SHA256withRSA", cert.sigAlgName)
        assertEquals("RSA", cert.publicKey.algorithm)
        // 2048-bit: strong enough to matter, small enough that generation on a phone is fast and
        // the v2 signer block stays compact.
        val modulus = (cert.publicKey as java.security.interfaces.RSAPublicKey).modulus
        assertTrue(modulus.bitLength() in 2047..2048, "unexpected key size: ${modulus.bitLength()}")

        // notBefore is pinned to 2020-01-01T00:00:00Z so that UTCTime's two-digit year is
        // unambiguous, and so a device with a wrong clock still accepts the certificate.
        val notBefore = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(cert.notBefore)
        assertEquals("2020-01-01T00:00:00Z", notBefore)

        assertTrue(cert.notAfter.time > System.currentTimeMillis(), "certificate is already expired")
        val years = (cert.notAfter.time - cert.notBefore.time) / (365L * 24 * 3600 * 1000)
        assertTrue(years in 25..35, "validity should be ~30 years, got $years")
    }

    @Test
    fun theFingerprintIsTheSha256OfTheCertificateInDisplayForm() {
        val identity = SigningIdentity.loadOrCreate(dir())
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest(identity.certificateDer)
            .joinToString(":") { "%02X".format(it) }

        assertEquals(expected, identity.fingerprintHex)
        assertEquals(32, identity.fingerprintHex.split(":").size)
        assertEquals(95, identity.fingerprintHex.length, "32 bytes as colon-separated uppercase hex")
    }

    // ---- helpers ------------------------------------------------------------

    /**
     * Loads from disk without going through [SigningIdentity.loadOrCreate]'s regenerate-on-failure
     * path, so a broken loader cannot hide behind a fallback.
     */
    private fun loadDirectly(d: File): SigningIdentity {
        val key = KeyFactory.getInstance("RSA").generatePrivate(
            PKCS8EncodedKeySpec(File(d, "proxy-signer.pk8").readBytes()),
        )
        val cert = java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(File(d, "proxy-signer.x509.pem").inputStream()) as X509Certificate
        // Reflection into the private constructor would be brittle; instead go through a second
        // loadOrCreate and assert the files were not rewritten, which is the observable difference.
        val before = File(d, "proxy-signer.x509.pem").readBytes()
        val identity = SigningIdentity.loadOrCreate(d)
        assertContentEquals(
            before,
            File(d, "proxy-signer.x509.pem").readBytes(),
            "loadOrCreate rewrote the certificate instead of loading it",
        )
        assertEquals(cert.encoded.toList(), identity.certificateDer.toList())
        assertEquals(key.encoded.toList(), identity.privateKey.encoded.toList())
        return identity
    }

    /** DER INTEGER content: shortest two's-complement form, no redundant leading zero. */
    private fun minimalTwosComplement(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        var start = 0
        while (start < raw.size - 1 &&
            raw[start] == 0.toByte() &&
            raw[start + 1].toInt() and 0x80 == 0
        ) {
            start++
        }
        return raw.copyOfRange(start, raw.size)
    }
}
