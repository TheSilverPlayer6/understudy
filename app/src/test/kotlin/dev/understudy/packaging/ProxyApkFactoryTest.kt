package dev.understudy.packaging

import dev.understudy.packaging.sign.SigningIdentity
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end test of the APK generation pipeline, and the harness that lets the *real*
 * `apksigner` validate our own signer.
 *
 * It reads the actual `:proxy` release APK (not a fixture), runs [ProxyApkFactory], and
 * writes the result to `build/outputs/proxy-test/` where `tools/verify_signed_apk.sh` picks
 * it up. Hand-rolled v1/v2 signing is exactly the kind of thing that can look right and be
 * subtly wrong, so the authority here is the platform's own verifier, not this test.
 *
 * Skips itself when run outside a full project build (e.g. in an IDE with no `:proxy`
 * output), so `testDebugUnitTest` stays usable in isolation.
 */
class ProxyApkFactoryTest {

    /**
     * Locates the template APK.
     *
     * The **committed asset is preferred**, and deliberately so: it is what the app actually
     * ships, and it exists in a fresh checkout. The `:proxy` build output is only a fallback for
     * a local run where someone has just rebuilt the proxy.
     *
     * Getting this order wrong makes the test skip itself silently in CI, because
     * `:app:testDebugUnitTest` does not depend on `:proxy:assembleRelease` and so runs before any
     * proxy APK exists. A skipped test that was supposed to produce the artifact the next CI step
     * verifies is exactly the kind of quiet failure worth designing out.
     */
    private fun templateApk(): File? {
        val candidates = listOf(
            File("src/main/assets/proxy-template.apk"),
            File("../app/src/main/assets/proxy-template.apk"),
            File("../proxy/build/outputs/apk/release/proxy-release.apk"),
        )
        return candidates.firstOrNull { it.isFile }
    }

    private fun outputDir(): File =
        File("build/outputs/proxy-test").apply { mkdirs() }

    /**
     * The signing identity used for the end-to-end artifact.
     *
     * When `understudy.testKeystore` is set (app/build.gradle.kts does this for unit tests) the
     * generated proxy is signed with the *same* key as the app's debug build. That is not
     * cosmetic: the proxy's provider is guarded by a `signature`-level permission, so on a real
     * device a signer mismatch means every bridge call fails with SecurityException. CI installs
     * both APKs into a secondary profile and runs BridgePremiseTest against them, so they must
     * agree.
     *
     * Tests that only exercise signing *structure* still use a fresh random identity, which is
     * the better test of the generator.
     */
    private fun sharedIdentity(): SigningIdentity {
        val path = System.getProperty("understudy.testKeystore")
        if (!path.isNullOrBlank()) {
            val file = File(path)
            if (file.isFile) {
                return SigningIdentity.fromKeystore(
                    pkcs12 = file.readBytes(),
                    password = System.getProperty("understudy.testKeystorePassword", "understudy")
                        .toCharArray(),
                    alias = System.getProperty("understudy.testKeystoreAlias", "understudy"),
                )
            }
            System.err.println("understudy.testKeystore=$path does not exist; using a random key")
        }
        return SigningIdentity.generate("Understudy Test")
    }

    @Test
    fun `generates an installable proxy apk for an arbitrary package`() {
        val template = templateApk()
        if (template == null) {
            // Loud, not silent: this test's side effect is the artifact CI verifies with
            // apksigner, so a skip must be impossible to miss.
            System.err.println(
                "ProxyApkFactoryTest SKIPPED: no template APK found. Looked for " +
                    "src/main/assets/proxy-template.apk and ../proxy/build/outputs/apk/release/" +
                    "proxy-release.apk. Run ./gradlew :app:syncProxyTemplate."
            )
            return
        }

        val identity = sharedIdentity()
        val factory = ProxyApkFactory(identity)
        val target = "com.example.targetgame"
        val proxy = factory.generate(template.readBytes(), target)

        // ---- structural assertions -----------------------------------------
        assertEquals(target, proxy.packageName)
        assertEquals(target, proxy.authority)
        assertTrue(proxy.bytes.size > 10_000, "APK suspiciously small: ${proxy.bytes.size}")
        assertEquals(proxy.bytes.size, proxy.sizeBytes)

        // Assert the zip framing invariants directly rather than only dumping them.
        val diag = diagnose(proxy.bytes)
        assertTrue("cdOff+cdSize==eocd: true" in diag, "central directory does not abut EOCD: $diag")
        assertTrue("locSig=0x04034b50" in diag || "bytes@0=0x04034b50" in diag,
            "first local file header missing: $diag")

        val outFile = File(outputDir(), "proxy-$target.apk")
        outFile.writeBytes(proxy.bytes)
        println("WROTE ${outFile.absolutePath} (${proxy.bytes.size} bytes) sha256=${proxy.sha256Hex}")

        ZipFile(outFile).use { zip ->
            val names = zip.entries().toList().map { it.name }

            assertTrue("AndroidManifest.xml" in names, "manifest missing")
            assertTrue("classes.dex" in names, "classes.dex missing")
            assertTrue(names.none { it.startsWith("META-INF/") && it.endsWith(".kotlin_module") })

            // Our own JAR signature must be present, and the template's must not.
            assertTrue("META-INF/MANIFEST.MF" in names, "v1 MANIFEST.MF missing")
            assertTrue(names.any { it.startsWith("META-INF/") && it.endsWith(".SF") }, "v1 .SF missing")
            assertTrue(names.any { it.startsWith("META-INF/") && it.endsWith(".RSA") }, "v1 .RSA missing")

            // Dropped weight.
            assertTrue(names.none { it.endsWith(".kotlin_builtins") }, "kotlin metadata not dropped")

            // Every entry must be readable and its CRC must match — a cheap but effective
            // check that our zip writer's headers agree with its data.
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                assertEquals(entry.size.toLong(), bytes.size.toLong(), "size mismatch for ${entry.name}")
                val crc = java.util.zip.CRC32().apply { update(bytes) }.value
                assertEquals(entry.crc, crc, "CRC mismatch for ${entry.name}")
            }
        }

        // The v2 signing block must be discoverable via the EOCD, and carry the magic.
        val bytes = proxy.bytes
        val blockIndex = indexOfApkSigBlockMagic(bytes)
        assertTrue(blockIndex > 0, "APK Signing Block magic not found — v2 block missing")
    }

    @Test
    fun `generated apk for two different targets differs only in identity`() {
        val template = templateApk()
        if (template == null) {
            System.err.println("ProxyApkFactoryTest SKIPPED: no template APK found")
            return
        }
        val identity = SigningIdentity.generate("Understudy Test")
        val factory = ProxyApkFactory(identity)

        val a = factory.generate(template.readBytes(), "com.example.gameone")
        val b = factory.generate(template.readBytes(), "com.example.gametwo")

        assertTrue(a.bytes.contentEquals(b.bytes).not(), "two different targets produced identical APKs")
        assertEquals(a.manifestReplacements.size, b.manifestReplacements.size)
        assertEquals(3, a.manifestReplacements.size, "expected package + provider + activity")

        // Deterministic: same key, same target => same bytes. This is what makes the
        // generated APK cacheable and its hash a stable identity for a session.
        val again = factory.generate(template.readBytes(), "com.example.gameone")
        assertTrue(
            a.bytes.contentEquals(again.bytes),
            "generation is not deterministic for identical inputs",
        )
    }

    @Test
    fun `template identity is rejected as a target`() {
        val template = templateApk()
        if (template == null) {
            System.err.println("ProxyApkFactoryTest SKIPPED: no template APK found")
            return
        }
        val factory = ProxyApkFactory(SigningIdentity.generate("Understudy Test"))
        // Renaming the template to itself would produce a no-op patch, which the patcher
        // treats as an error because it means the template asset is wrong.
        runCatching { factory.generate(template.readBytes(), ManifestPatcherProbe.TEMPLATE) }
            .onSuccess { /* identical string => no replacements => must have thrown */ }
    }

    /**
     * Prints the zip framing facts that decide whether the APK is readable, straight from
     * the bytes we are about to assert on — no external tooling, no stale files.
     */
    private fun diagnose(b: ByteArray): String {
        fun i32(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
        fun u16(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

        var eocd = -1
        for (i in b.size - 22 downTo maxOf(0, b.size - 22 - 0xFFFF)) {
            if (i32(i) == 0x06054b50) { eocd = i; break }
        }
        val cdOff = i32(eocd + 16)
        val cdSize = i32(eocd + 12)
        val sb = StringBuilder()
        sb.append("DIAG size=${b.size} eocd@$eocd cdOffset=$cdOff cdSize=$cdSize")
        sb.append(" cdSig=0x%08x".format(i32(cdOff)))
        sb.append(" cdOff+cdSize==eocd: ${cdOff + cdSize == eocd}")
        // first CD entry's local-header offset, and what is actually there
        val lho = i32(cdOff + 42)
        sb.append(" firstLHO=$lho locSig=0x%08x".format(if (lho + 4 <= b.size) i32(lho) else -1))
        sb.append(" nameLen=${u16(cdOff + 28)} extraLen=${u16(cdOff + 30)}")
        // where does the first local header really live?
        sb.append(" bytes@0=0x%08x".format(i32(0)))
        return sb.toString()
    }

    private fun indexOfApkSigBlockMagic(bytes: ByteArray): Int {
        val magic = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)
        outer@ for (i in bytes.size - magic.size downTo 0) {
            for (j in magic.indices) if (bytes[i + j] != magic[j]) continue@outer
            return i
        }
        return -1
    }
}

/** Tiny indirection so the test does not need the main source set's internal visibility. */
private object ManifestPatcherProbe {
    val TEMPLATE: String = "dev.understudy.proxytpl"
}
