package dev.understudy.packaging

import dev.understudy.packaging.sign.SigningIdentity
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Generates the APK that lets CI exercise the **production** caller-authentication path, which
 * nothing else in the suite reaches.
 *
 * The premise CI job installs a proxy signed with the *same* test key as the app, so the
 * `signature`-level `BRIDGE` permission is granted and the bridge works — but that only ever
 * exercises the permission path. In production the proxy is signed with a per-install key that
 * cannot match the app's build-time key, so the permission cannot be the thing that lets the app
 * through; the baked-in generator-certificate digest is. This test produces exactly that artifact:
 *
 *  - signed with a **fresh random key** (`SigningIdentity.generate()`), standing in for the
 *    per-install key `ApkGenerator` uses on a device — deliberately NOT the app's key;
 *  - carrying, at [ProxyApkFactory.GENERATOR_CERT_ASSET], the SHA-256 of the **app's** certificate,
 *    which is the test keystore certificate: the app's debug build is signed with it, so the
 *    on-device `ApkGenerator.ownCertificateSha256Hex()` computes precisely this digest.
 *
 * The result is written to `build/outputs/proxy-prodsign/` — a *different* directory from
 * `ProxyApkFactoryTest`'s `build/outputs/proxy-test/` — so the jvm job's `find … proxy-test …
 * | head -1` verification keeps picking up exactly one APK and this artifact cannot be confused
 * with it.
 *
 * Skips itself, loudly, when there is no template or no test keystore, mirroring
 * [ProxyApkFactoryTest]: a silent skip here would mean the production auth path silently stops
 * being covered.
 */
class ProdSignedProxyTest {

    /** The package the production-signed proxy impersonates. Distinct from the premise's target. */
    private val target = "com.example.prodgame"

    private fun templateApk(): File? = listOf(
        File("src/main/assets/proxy-template.apk"),
        File("../app/src/main/assets/proxy-template.apk"),
        File("../proxy/build/outputs/apk/release/proxy-release.apk"),
    ).firstOrNull { it.isFile }

    private fun outputDir(): File = File("build/outputs/proxy-prodsign").apply { mkdirs() }

    /** The app's build-time identity, i.e. the test keystore the debug build is signed with. */
    private fun appIdentity(): SigningIdentity? {
        val path = System.getProperty("understudy.testKeystore")?.takeIf { it.isNotBlank() } ?: return null
        val file = File(path)
        if (!file.isFile) return null
        val password = (System.getProperty("understudy.testKeystorePassword") ?: "understudy").toCharArray()
        val alias = System.getProperty("understudy.testKeystoreAlias") ?: "understudy"
        return SigningIdentity.fromKeystore(pkcs12 = file.readBytes(), password = password, alias = alias)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `generates a proxy signed with a different key carrying the app certificate digest`() {
        val template = templateApk()
        if (template == null) {
            System.err.println(
                "ProdSignedProxyTest SKIPPED: no template APK found. Run ./gradlew :app:syncProxyTemplate.",
            )
            return
        }
        val app = appIdentity()
        if (app == null) {
            System.err.println(
                "ProdSignedProxyTest SKIPPED: understudy.testKeystore not set/present; cannot " +
                    "compute the app certificate digest that the production path depends on.",
            )
            return
        }

        // certificateDer == certificate.encoded == the bytes Android's Signature.toByteArray()
        // returns for this cert, so SHA-256 over it matches what the platform reports for an APK
        // AGP signed with this keystore — which is what ownCertificateSha256Hex() bakes in on a
        // device. Lowercase, unpunctuated: the format both the generator and matchesGenerator use.
        val digest = sha256Hex(app.certificateDer)

        // A per-install key, fresh every run, so it can never accidentally equal the app's key.
        // If it did, the test would exercise the permission path and prove nothing about the digest.
        val perInstall = SigningIdentity.generate("Understudy PerInstall")
        assertTrue(
            !perInstall.certificateDer.contentEquals(app.certificateDer),
            "the production-scenario proxy must be signed with a DIFFERENT key than the app, " +
                "or the signature permission would be granted and the digest path untested",
        )

        val apk = ProxyApkFactory(perInstall).generate(
            template.readBytes(),
            target,
            generatorCertificateSha256Hex = digest,
        )

        val outFile = File(outputDir(), "proxy-prodsign-$target.apk")
        outFile.writeBytes(apk.bytes)
        println(
            "WROTE-PRODSIGN ${outFile.absolutePath} (${apk.bytes.size} bytes) " +
                "package=${apk.packageName} generatorDigest=$digest " +
                "proxySigner=${perInstall.fingerprintHex} appSigner=${app.fingerprintHex}",
        )

        // Structural sanity, so a malformed artifact fails here rather than as a confusing
        // install error on the emulator.
        assertEquals(target, apk.packageName)
        ZipFile(outFile).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertTrue("AndroidManifest.xml" in names, "manifest missing")
            assertTrue(names.any { it.endsWith(".dex") }, "no dex")
            val certEntry = zip.getEntry(ProxyApkFactory.GENERATOR_CERT_ASSET)
            assertNotNull(certEntry, "the generator-cert digest asset must be baked in")
            assertEquals(
                digest,
                zip.getInputStream(certEntry).readBytes().toString(Charsets.US_ASCII),
                "baked digest does not match the app certificate digest",
            )
        }
    }
}
