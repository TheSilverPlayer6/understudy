@file:OptIn(ExperimentalKotlinTestApi::class)

package dev.understudy.packaging.axml

// Kotlin 2.4 marks the message-lambda overloads of assertTrue/assertEquals as experimental.
import kotlin.test.ExperimentalKotlinTestApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs the real, AGP-9.4.1-generated binary manifest from `:proxy` through the patcher.
 *
 * The fixture is `proxy/build/outputs/apk/debug/proxy-debug.apk!/AndroidManifest.xml`,
 * committed at `src/test/resources/.../template-manifest.axml`. Testing against real aapt2
 * output — rather than a synthetic AXML — is the point: it pins the string-pool layout,
 * flags and alignment that AGP actually emits, so a future toolchain change that shifts
 * any of them fails here instead of on a user's device.
 */
class ManifestPatcherTest {

    private fun template(): ByteArray {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("dev/understudy/packaging/axml/template-manifest.axml")
        assertNotNull(stream)
        return stream.use { it.readBytes() }
    }

    @Test
    fun `template fixture parses as a UTF-16 string pool`() {
        val doc = AxmlStringPool.parse(template())
        assertEquals(AxmlStringPool.TYPE_XML, doc.fileType)
        assertEquals(8, doc.fileHeaderSize)
        assertEquals(0, doc.styleOffsets.size, "AGP emits no styles in a manifest pool")
        assertTrue(doc.strings.size >= 40, "expected a real manifest, got ${doc.strings.size} strings")
        assertTrue(ManifestPatcher.TEMPLATE_PACKAGE in doc.strings) {
            "template package not present in pool: ${doc.strings}"
        }
    }

    @Test
    fun `re-encoding an unmodified pool is byte-identical`() {
        val raw = template()
        val doc = AxmlStringPool.parse(raw)
        val reencoded = AxmlStringPool.encodePool(doc)

        val poolSize = readU32(raw, doc.fileHeaderSize + 4)
        val original = raw.copyOfRange(doc.fileHeaderSize, doc.fileHeaderSize + poolSize)
        assertEquals(original.size, reencoded.size, "pool length changed on a no-op re-encode")
        assertTrue(
            original.contentEquals(reencoded),
            "no-op re-encode is not byte-identical; first diff at ${firstDiff(original, reencoded)}",
        )
    }

    @Test
    fun `rename rewrites package, authority and component names but not the permission`() {
        val result = ManifestPatcher.rename(template(), "com.example.targetgame")
        val doc = AxmlStringPool.parse(result.bytes)

        // 1. the manifest package attribute
        assertTrue("com.example.targetgame" in doc.strings) {
            "bare target package missing (package attr / provider authority)"
        }
        // 2. fully-qualified component names follow the new prefix
        assertTrue("com.example.targetgame.bridge.ProxyFileBridge" in doc.strings)
        assertTrue("com.example.targetgame.ProxyStatusActivity" in doc.strings)
        // 3. nothing of the old identity survives
        assertTrue(doc.strings.none { it.startsWith(ManifestPatcher.TEMPLATE_PACKAGE) }) {
            "stale template strings remain: ${doc.strings.filter { it.startsWith("dev.understudy.proxytpl") }}"
        }
        // 4. the permission name is shared with :app on purpose and must NOT move
        assertTrue("dev.understudy.permission.BRIDGE" in doc.strings) {
            "permission name was rewritten; the signature-level grant would break"
        }
        // 5. framework namespace + attribute names untouched
        assertTrue("http://schemas.android.com/apk/res/android" in doc.strings)
        assertTrue("authorities" in doc.strings)
        assertTrue("manifest" in doc.strings)
    }

    @Test
    fun `rename keeps the string count and chunk chain intact`() {
        val before = AxmlStringPool.parse(template())
        val after = ManifestPatcher.rename(template(), "a.very.different.pkg.name").bytes
        val parsed = AxmlStringPool.parse(after)

        assertEquals(before.strings.size, parsed.strings.size, "string count must not change")
        assertEquals(before.fileType, parsed.fileType)
        assertEquals(before.fileHeaderSize, parsed.fileHeaderSize)

        // File-level declared size must equal the actual length, or PackageManager refuses it.
        assertEquals(after.size, readU32(after, 4), "file header size does not match payload")

        // And the tail (resource map + XML nodes) must still walk cleanly to the end.
        var pos = parsed.fileHeaderSize
        pos += readU32(after, pos + 4)
        var chunks = 0
        while (pos < after.size) {
            val size = readU32(after, pos + 4)
            assertTrue(size > 0, "non-positive chunk size at $pos")
            pos += size
            chunks++
        }
        assertEquals(after.size, pos, "chunk chain does not land exactly on EOF")
        assertTrue(chunks >= 20, "expected the full element tree, walked only $chunks chunks")
    }

    @Test
    fun `rename handles a target longer than the template`() {
        val long = "com.somecompany.someverylongtargetpackagename.deep.nested"
        val out = ManifestPatcher.rename(template(), long).bytes
        val doc = AxmlStringPool.parse(out)
        assertTrue(long in doc.strings)
        assertTrue("$long.bridge.ProxyFileBridge" in doc.strings)
        assertEquals(out.size, readU32(out, 4))
    }

    @Test
    fun `rename reports exactly which strings moved`() {
        val result = ManifestPatcher.rename(template(), "com.foo.bar")
        val moved = result.replacedStrings.toMap()
        assertEquals("com.foo.bar", moved[ManifestPatcher.TEMPLATE_PACKAGE])
        assertEquals(
            "com.foo.bar.bridge.ProxyFileBridge",
            moved["dev.understudy.proxytpl.bridge.ProxyFileBridge"],
        )
        assertEquals(3, moved.size, "expected package + provider + activity, got $moved")
    }

    @Test
    fun `invalid package names are rejected before any bytes are written`() {
        val bad = listOf(
            "", " ", "noDots", "com..double", ".leading.dot", "trailing.dot.",
            "com.1digit", "com.example.9lives", "com/exam.ple", "com.exam ple",
            // Kotlin's isLetterOrDigit() is Unicode-aware; Android package names are not.
            "com.example.ünïcode", "日本語.パッケージ",
            "com.class", "com.example.int", "_", "a.b-c", "com.example.app;",
            // Reserved namespaces.
            "android", "android.intent.foo", "com.android.shell",
            "com.google.android.gms", "com.samsung.android.knox",
        )
        for (name in bad) {
            assertFailsWith<IllegalArgumentException>("accepted invalid package: $name") {
                ManifestPatcher.rename(template(), name)
            }
        }
    }

    @Test
    fun `valid package names are accepted`() {
        val good = listOf(
            "com.example.app", "a.b", "com.company.game2", "org.f_droid.app",
            "_private._pkg", "com.example.a1.b2.c3",
        )
        for (name in good) {
            val out = ManifestPatcher.rename(template(), name)
            assertTrue(name in AxmlStringPool.parse(out.bytes).strings, "failed for $name")
        }
    }

    @Test
    fun `a non-AXML input is rejected rather than silently mangled`() {
        assertFailsWith<IllegalArgumentException> {
            ManifestPatcher.rename("not a manifest".toByteArray(), "com.example.app")
        }
        assertFailsWith<IllegalArgumentException> {
            ManifestPatcher.rename(ByteArray(4), "com.example.app")
        }
    }

    private fun readU32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun firstDiff(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) if (a[i] != b[i]) return i
        return -1
    }
}
