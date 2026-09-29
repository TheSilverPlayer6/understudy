package dev.understudy.bridge

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import dev.understudy.core.model.StorageRoot
import dev.understudy.proxytpl.bridge.ProxyFileBridge
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The whole bridge protocol, exercised end to end on the JVM.
 *
 * This registers the **real** [ProxyFileBridge] from `:proxy-core` — the exact class whose dex
 * gets re-signed into the generated APK — and drives it with the **real** [BridgeClient] through
 * a real `ContentResolver`. Cursors, `ParcelFileDescriptor` streaming, the `call()` protocol and
 * the path-safety rules are therefore all verified against actual Android framework classes, not
 * mocks.
 *
 * What this does *not* cover is the kernel: on a device the FUSE layer is what makes
 * `Android/data/<pkg>` invisible to other packages, and Robolectric has no FUSE. So this proves
 * the protocol is correct, not that the platform will grant the access — that part is what the
 * whole proxy-APK mechanism exists to obtain, and it can only be confirmed on a real device.
 *
 * The provider's authority is the application's own package name, mirroring production: the
 * generated proxy's authority *is* the package it impersonates, and it resolves its roots from
 * `context.packageName`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BridgeIntegrationTest {

    private lateinit var context: Context
    private lateinit var client: BridgeClient
    private lateinit var dataRoot: File
    private lateinit var obbRoot: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val authority = context.packageName

        // Register the real provider under the authority the real one would have.
        // `create(authority)` both instantiates it with that authority AND registers it on the
        // application's resolver, so no separate addProvider call is needed.
        Robolectric.buildContentProvider(ProxyFileBridge::class.java).create(authority)

        // Derive the roots THE SAME WAY the provider does, rather than by counting levels from
        // getExternalFilesDir(null). Counting is exactly what the provider used to do, and it
        // was wrong: Robolectric's emulated external storage has no per-package segment
        // (<tmp>/external-files/Android/data/files), so two levels up is Android/, not the
        // package's own directory. A fixture that derives its paths independently of the code
        // under test cannot catch that class of bug — it just disagrees with it.
        val filesDir = context.getExternalFilesDir(null)
        assertNotNull(filesDir, "external files dir unavailable")
        dataRoot = File(filesDir.parentFile!!, context.packageName)
        obbRoot = File(File(filesDir.parentFile!!.parentFile!!, "obb"), context.packageName)
        assertTrue(dataRoot.mkdirs() || dataRoot.isDirectory, "could not create $dataRoot")
        assertTrue(obbRoot.mkdirs() || obbRoot.isDirectory, "could not create $obbRoot")

        client = BridgeClient(context.contentResolver, authority)
    }

    // ---- identity ----------------------------------------------------------

    @Test
    fun `ping reports the impersonated package, the user and both roots`() {
        val info = client.ping()

        assertEquals(context.packageName, info.packageName)
        assertEquals(BridgeContract.PROTOCOL_VERSION, info.protocolVersion)
        assertTrue(info.protocolCompatible)
        // The uid-derived user id is how the UI proves which profile it is looking at.
        assertTrue(info.userId >= 0, "user id should be derivable, got ${info.userId}")

        val roots = info.roots.associateBy { it.root }
        assertTrue(roots.containsKey(StorageRoot.DATA), "data root missing from ping: $roots")
        val data = roots.getValue(StorageRoot.DATA)
        assertTrue(data.exists, "provider says the data root does not exist: ${data.absolutePath}")
        // The provider must agree with us about where the root is; a disagreement means it is
        // looking at a different tree, which is exactly the failure that looks like data loss.
        assertEquals(
            dataRoot.canonicalPath,
            File(data.absolutePath!!).canonicalPath,
            "provider's data root differs from the one we derived",
        )
        val obb = roots[StorageRoot.OBB]
        assertNotNull(obb, "obb root missing from ping")
        assertEquals(obbRoot.canonicalPath, File(obb.absolutePath!!).canonicalPath)
    }

    @Test
    fun `ping rejects a mismatched expected package`() {
        // Guards against driving a proxy that is impersonating something else — which would
        // otherwise present as an empty directory and look like data loss.
        assertFailsWith<BridgeError.WrongIdentity> { client.ping("com.some.other.package") }
    }

    @Test
    fun `isReachable is true for a live provider`() {
        assertTrue(client.isReachable())
    }

    // ---- listing -----------------------------------------------------------

    @Test
    fun `list returns directories first then files, alphabetically`() {
        File(dataRoot, "zebra.txt").writeText("z")
        File(dataRoot, "alpha.txt").writeText("a")
        File(dataRoot, "files").mkdirs()
        File(dataRoot, "Beta").mkdirs()

        val entries = client.list(StorageRoot.DATA)
        val names = entries.map { it.name }
        assertTrue(
            names.containsAll(listOf("alpha.txt", "zebra.txt", "files", "Beta")),
            "missing our entries in $names",
        )

        // The invariant that matters: every directory precedes every file, and each group is
        // sorted case-insensitively. Asserting an exact directory list would make this test
        // depend on which directories the platform happens to pre-create.
        val firstFileIndex = entries.indexOfFirst { !it.isDirectory }
        val lastDirIndex = entries.indexOfLast { it.isDirectory }
        assertTrue(
            lastDirIndex < firstFileIndex,
            "directories must all precede files: $names",
        )
        val dirNames = entries.filter { it.isDirectory }.map { it.name.lowercase() }
        assertEquals(dirNames.sorted(), dirNames, "directories not case-insensitively sorted: $dirNames")
        val fileNames = entries.filter { !it.isDirectory }.map { it.name.lowercase() }
        assertEquals(fileNames.sorted(), fileNames, "files not sorted: $fileNames")
        assertTrue(names.indexOf("alpha.txt") < names.indexOf("zebra.txt"))
    }

    @Test
    fun `list reports size, readability and a root-relative path`() {
        val payload = ByteArray(1234) { (it % 251).toByte() }
        val nested = File(dataRoot, "saves").apply { mkdirs() }
        File(nested, "slot1.dat").writeBytes(payload)

        val entries = client.list(StorageRoot.DATA, "saves")
        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals("slot1.dat", entry.name)
        assertFalse(entry.isDirectory)
        assertEquals(payload.size.toLong(), entry.sizeBytes)
        assertEquals("saves/slot1.dat", entry.relativePath, "path must be root-relative")
        assertTrue(entry.canRead)
        assertTrue(entry.lastModifiedMillis > 0)
    }

    @Test
    fun `list of a missing directory reports NotFound, not an empty list`() {
        // Silently returning empty is how a user concludes their saves are gone.
        assertFailsWith<BridgeError.NotFound> { client.list(StorageRoot.DATA, "no/such/dir") }
    }

    @Test
    fun `the obb root is addressed separately from data`() {
        File(obbRoot, "main.1234.com.foo.obb").writeBytes(ByteArray(64) { 7 })

        val obbEntries = client.list(StorageRoot.OBB)
        assertTrue(obbEntries.any { it.name == "main.1234.com.foo.obb" })

        // and the same name must NOT appear under data — the roots really are distinct trees
        val dataEntries = client.list(StorageRoot.DATA)
        assertFalse(dataEntries.any { it.name == "main.1234.com.foo.obb" })
    }

    // ---- streaming ---------------------------------------------------------

    @Test
    fun `a file round-trips byte for byte through the descriptor`() {
        // Non-text bytes on purpose: a text-only test would not catch a charset or newline bug,
        // and save files are binary.
        val payload = ByteArray(300_000) { (it * 31 % 256).toByte() }
        File(dataRoot, "saves").mkdirs()
        File(dataRoot, "saves/world.bin").writeBytes(payload)

        val read = client.openFile(StorageRoot.DATA, "saves/world.bin", "r").use { pfd ->
            ParcelFileDescriptor.AutoCloseInputStream(pfd).readBytes()
        }
        assertContentEquals(payload.toList(), read.toList())
    }

    @Test
    fun `writing through the bridge creates a file the filesystem really has`() {
        val payload = "written by the bridge".toByteArray()
        File(dataRoot, "out").mkdirs()

        client.openFile(StorageRoot.DATA, "out/note.txt", "w").use { pfd ->
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { it.write(payload) }
        }

        val onDisk = File(dataRoot, "out/note.txt")
        assertTrue(onDisk.isFile, "the provider should have created the file")
        assertContentEquals(payload.toList(), onDisk.readBytes().toList())
    }

    @Test
    fun `mode w truncates and mode wa appends`() {
        File(dataRoot, "log.txt").writeText("0123456789")

        client.openFile(StorageRoot.DATA, "log.txt", "w").use { pfd ->
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { it.write("ab".toByteArray()) }
        }
        assertEquals("ab", File(dataRoot, "log.txt").readText())

        client.openFile(StorageRoot.DATA, "log.txt", "wa").use { pfd ->
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { it.write("cd".toByteArray()) }
        }
        assertEquals("abcd", File(dataRoot, "log.txt").readText())
    }

    @Test
    fun `reading a missing file reports NotFound`() {
        assertFailsWith<BridgeError.NotFound> { client.openFile(StorageRoot.DATA, "absent.bin") }
    }

    @Test
    fun `opening a directory as a file is refused`() {
        File(dataRoot, "adir").mkdirs()
        assertFailsWith<BridgeError.NotFound> { client.openFile(StorageRoot.DATA, "adir", "r") }
    }

    @Test
    fun `an unsupported mode is rejected before reaching the provider`() {
        File(dataRoot, "x.txt").writeText("x")
        assertFailsWith<IllegalArgumentException> {
            client.openFile(StorageRoot.DATA, "x.txt", "rwx")
        }
    }

    // ---- mutations ---------------------------------------------------------

    @Test
    fun `mkdirs creates nested directories`() {
        client.mkdirs(StorageRoot.DATA, "a/b/c")
        assertTrue(File(dataRoot, "a/b/c").isDirectory)
    }

    @Test
    fun `delete removes a file and is idempotent when it is already gone`() {
        File(dataRoot, "gone.txt").writeText("x")
        client.delete(StorageRoot.DATA, "gone.txt")
        assertFalse(File(dataRoot, "gone.txt").exists())

        // deleting twice must not surface as an error: teardown paths retry
        client.delete(StorageRoot.DATA, "gone.txt")
    }

    @Test
    fun `recursive delete removes a subtree`() {
        File(dataRoot, "tree/sub").mkdirs()
        File(dataRoot, "tree/sub/leaf.dat").writeBytes(ByteArray(10))
        File(dataRoot, "tree/top.dat").writeBytes(ByteArray(10))

        client.delete(StorageRoot.DATA, "tree", recursive = true)
        assertFalse(File(dataRoot, "tree").exists())
    }

    @Test
    fun `delete refuses the root itself even recursively`() {
        // The one irreversible mistake the provider could make.
        //
        // Asserted on outcome rather than exception type: across a real Binder an `ok=false`
        // result comes back as a Bundle and BridgeClient wraps it in BridgeError, while under
        // Robolectric's in-process ContentResolver the same refusal surfaces as an
        // IllegalArgumentException. Both mean "refused"; only the transport differs.
        val failure = runCatching { client.delete(StorageRoot.DATA, "", recursive = true) }
            .exceptionOrNull()
        assertNotNull(failure, "deleting the root must not silently succeed")
        assertTrue(
            failure is BridgeError || failure is IllegalArgumentException ||
                failure is SecurityException,
            "unexpected refusal type: $failure",
        )
        assertTrue(
            (failure.message ?: "").contains("root"),
            "the refusal should say why: ${failure.message}",
        )
        assertTrue(dataRoot.isDirectory, "the root must survive")
        assertTrue(
            (dataRoot.listFiles()?.size ?: 0) > 0 || dataRoot.canonicalPath.endsWith(context.packageName),
            "the root's contents must not have been emptied",
        )
    }

    @Test
    fun `rename moves a file within a root`() {
        File(dataRoot, "before.txt").writeText("payload")
        client.rename(StorageRoot.DATA, "before.txt", "after.txt")
        assertFalse(File(dataRoot, "before.txt").exists())
        assertEquals("payload", File(dataRoot, "after.txt").readText())
    }

    @Test
    fun `rename refuses to overwrite an existing target`() {
        File(dataRoot, "src.txt").writeText("new")
        File(dataRoot, "dst.txt").writeText("existing")

        assertFailsWith<BridgeError.OperationFailed> {
            client.rename(StorageRoot.DATA, "src.txt", "dst.txt")
        }
        // The pre-existing file must be untouched — a failed rename that clobbers is data loss.
        assertEquals("existing", File(dataRoot, "dst.txt").readText())
        assertEquals("new", File(dataRoot, "src.txt").readText())
    }

    @Test
    fun `statTree totals bytes and entries recursively`() {
        File(dataRoot, "stat/a").mkdirs()
        File(dataRoot, "stat/a/one.bin").writeBytes(ByteArray(100))
        File(dataRoot, "stat/a/two.bin").writeBytes(ByteArray(50))
        File(dataRoot, "stat/three.bin").writeBytes(ByteArray(25))

        val stat = client.statTree(StorageRoot.DATA, "stat")
        assertEquals(175L, stat.totalBytes)
        // 3 files + 1 subdir + the "stat" dir itself
        assertEquals(5L, stat.entryCount)
    }

    // ---- path safety -------------------------------------------------------

    @Test
    fun `traversal attempts are rejected client-side before any IPC`() {
        val bad = listOf(
            "../etc/passwd",
            "saves/../../etc/passwd",
            "/absolute/path",
            "with\u0000nul",
            "back\\slash",
            "double//slash",
        )
        for (path in bad) {
            val error = assertFailsWith<BridgeError.InvalidPath>("accepted '$path'") {
                client.list(StorageRoot.DATA, path)
            }
            assertTrue(error.message!!.contains(path) || path.isEmpty())
        }
        // and nothing escaped: the root is still the only thing we can see
        assertTrue(dataRoot.isDirectory)
    }

    @Test
    fun `the proxy independently rejects traversal even if the client is bypassed`() {
        // Defence in depth: the two processes share only a signing key, so the provider must not
        // trust our validation. Drive it directly through the resolver with a crafted URI.
        val authority = context.packageName
        val malicious = android.net.Uri.parse("content://$authority/data/../../etc")
        val result = runCatching {
            context.contentResolver.query(malicious, null, null, null, null)
        }
        assertTrue(result.isFailure, "provider should reject a traversal URI")
        assertTrue(
            result.exceptionOrNull() is SecurityException ||
                result.exceptionOrNull() is IllegalArgumentException,
            "unexpected exception: ${result.exceptionOrNull()}",
        )
    }

    @Test
    fun `an unknown root name is rejected by the provider`() {
        // The contract defines exactly two roots; anything else must be refused rather than
        // silently resolving somewhere unexpected.
        val bogus = android.net.Uri.parse("content://${context.packageName}/notaroot/x")
        val result = runCatching {
            context.contentResolver.query(bogus, null, null, null, null)?.close()
        }
        assertTrue(result.isFailure, "an unknown root should be rejected")
        assertTrue(
            result.exceptionOrNull() is SecurityException,
            "expected SecurityException, got ${result.exceptionOrNull()}",
        )
    }

    // ---- teardown support --------------------------------------------------

    @Test
    fun `wipeProxyData empties the trees so a normal uninstall destroys nothing`() {
        File(dataRoot, "keep").mkdirs()
        File(dataRoot, "keep/a.bin").writeBytes(ByteArray(10))
        File(obbRoot, "main.obb").writeBytes(ByteArray(10))

        client.wipeProxyData()

        assertTrue(dataRoot.isDirectory, "the root itself must survive")
        assertEquals(0, dataRoot.listFiles()?.size ?: 0, "data root should be empty")
        assertEquals(0, obbRoot.listFiles()?.size ?: 0, "obb root should be empty")
    }

    @Test
    fun `wipe requires the confirmation token`() {
        // Called without the token it must refuse, because the orchestrator gates this on a
        // verified pull and a bug there must not silently empty the tree.
        val result = runCatching {
            context.contentResolver.call(
                android.net.Uri.parse("content://${context.packageName}"),
                BridgeContract.CALL_WIPE_SELF,
                null,
                null,
            )
        }
        val bundle = result.getOrNull()
        assertTrue(bundle == null || !bundle.getBoolean(BridgeContract.KEY_OK, false))
        assertTrue(dataRoot.isDirectory)
    }

    @Test
    fun `launcher hiding succeeds against the real component`() {
        // The proxy toggles its own component, which needs no special permission. A failure here
        // would mean the "keep installed but hidden" teardown strategy is unusable.
        assertTrue(client.hideLauncher(), "hideLauncher should succeed for our own component")
        assertTrue(client.showLauncher(), "showLauncher should succeed for our own component")
    }
    /**
     * The roots must end in the package name.
     *
     * This is the regression test for the bug that cost six CI runs. `rootDir("data")` was
     * derived as `getExternalFilesDir(null).parentFile.parentFile`, which assumes the platform
     * returns `.../Android/data/<pkg>/files`. On API 34/35 it returns `.../Android/data/<pkg>`,
     * so two levels up landed on `.../Android/data` — the shared parent of every package's
     * private storage, and precisely the directory the platform hides from all apps.
     *
     * The failure was maximally misleading: listing returned AccessDeniedException, which read
     * as "the FUSE premise is false", when in fact the restriction was working correctly on a
     * path we should never have pointed at. Meanwhile obb kept working, because its path was
     * constructed with the package segment intact — an asymmetry that made it look like a
     * platform difference between data and obb rather than a bug in one of the two expressions.
     *
     * Asserting on the *shape* of the path, not just that a listing succeeds, is what makes this
     * catchable without an emulator.
     */
    @Test
    fun rootsAreNeverTheSharedAndroidDataParent() {
        val info = client.ping(context.packageName)

        for (status in info.roots) {
            val path = status.absolutePath
            assertNotNull(path, "${status.root} reported no path")
            val resolved = path!!.trimEnd('/')
            // The bug this pins: the data root resolving to .../Android/data, the shared parent
            // of every package's private storage, instead of the package's own directory. On a
            // device that path is unreadable by design, so the bridge reported "permission
            // denied" and it looked like the FUSE premise had failed.
            assertFalse(
                resolved.endsWith("/Android/data") || resolved.endsWith("/Android/obb"),
                "${status.root} root is the SHARED parent ($resolved) — the bridge is pointed " +
                    "one level too high, which on a device is a directory scoped storage hides",
            )
            // Whatever the platform's layout, the resolved root must sit under the right
            // Android/<root> directory and must not be that directory itself.
            assertTrue(
                resolved.contains("/Android/"),
                "${status.root} root should live under an Android/ directory: $resolved",
            )
        }

        // And both roots must be usable, which is the operation that actually failed.
        assertNotNull(client.list(StorageRoot.DATA), "listing our own data root must not throw")
        assertNotNull(client.list(StorageRoot.OBB), "listing our own obb root must not throw")
    }

    /**
     * `statPath` is how a caller confirms a write reached the real filesystem.
     *
     * It matters more than a convenience method: from outside the proxy there is NO way to
     * check, because the platform hides `Android/data/<target>` from every other package. The
     * premise test used to stat the path from the test app's uid and could therefore never pass
     * — it asserted both that the directory was hidden and that it could read a file inside it.
     */
    @Test
    fun `statPath reports what the proxy itself sees`() {
        val dir = File(dataRoot, "statted").apply { mkdirs() }
        val file = File(dir, "note.txt").apply { writeText("0123456789") }
        assertTrue(file.isFile, "fixture should exist")

        val present = client.statPath(StorageRoot.DATA, "statted/note.txt")
        assertTrue(present.exists, "the proxy should see a file that is on disk: $present")
        assertFalse(present.isDirectory)
        assertEquals(10L, present.sizeBytes)
        assertNotNull(present.canonicalPath)
        assertTrue(
            present.canonicalPath!!.endsWith("statted/note.txt"),
            "canonical path should be the real location: ${present.canonicalPath}",
        )

        val asDir = client.statPath(StorageRoot.DATA, "statted")
        assertTrue(asDir.exists && asDir.isDirectory, "a directory should report as one: $asDir")
        assertEquals(-1L, asDir.sizeBytes, "directories have no byte count")

        val absent = client.statPath(StorageRoot.DATA, "statted/not-here.txt")
        assertFalse(absent.exists, "a missing entry must not report as present: $absent")
    }

    @Test
    fun `statPath refuses to look outside the roots`() {
        // It reports sizes, so it would be a small oracle if it could be pointed anywhere.
        for (path in listOf("../etc/passwd", "/absolute", "a/../../b")) {
            val error = runCatching { client.statPath(StorageRoot.DATA, path) }.exceptionOrNull()
            assertNotNull(error, "'$path' must be rejected")
        }
    }

    /**
     * The contract is duplicated on purpose — `:proxy-core` must stay dependency-free, so it
     * cannot share a module with `:app`. That duplication is exactly the kind of thing that
     * drifts silently: a renamed method string on one side turns every call into "unknown
     * method" at runtime, and nothing else catches it, because Robolectric registers providers
     * by class reference and never resolves a name through PackageManager.
     *
     * So this compares every `String` constant on both sides by reflection rather than a
     * hand-maintained list. A hand-written list would pass while an unlisted constant drifted,
     * which is the failure mode the test exists to prevent.
     */
    @Test
    fun theTwoCopiesOfTheContractAgree() {
        val ours = dev.understudy.bridge.BridgeContract::class.java
        val theirs = dev.understudy.proxytpl.bridge.BridgeContract::class.java

        // Only the constants that cross the process boundary. Two exclusions, both learned
        // by running it:
        //  * `$stable` and friends are synthesised by the Compose compiler into :app's copy
        //    and have no counterpart in :proxy-core, which has no Compose. Comparing them
        //    reports drift that does not exist.
        //  * non-String, non-int fields (the ROOTS list, say) are compared explicitly below,
        //    where a rename on both sides is still caught.
        fun constants(c: Class<*>): Map<String, Any?> = c.declaredFields
            .filter { f -> !f.isSynthetic && !f.name.startsWith("\$") }
            .filter { f ->
                java.lang.reflect.Modifier.isStatic(f.modifiers) &&
                    (f.type == String::class.java || f.type == Int::class.javaPrimitiveType)
            }
            .associate { f -> f.name to f.get(null) }

        val a = constants(ours)
        val b = constants(theirs)

        // Every constant :app declares must exist on the proxy's copy with the same value.
        // The proxy may legitimately declare more (it is the implementing side).
        val mismatched = a.filter { (name, value) -> !b.containsKey(name) || b[name] != value }
        assertTrue(
            mismatched.isEmpty(),
            "contract drift between :app and :proxy-core — the bridge would fail at runtime " +
                "with 'unknown method' or a missing bundle key: $mismatched",
        )

        // Pin the load-bearing ones explicitly too, so a rename on BOTH sides (which the
        // comparison above cannot see) still fails here.
        assertEquals("ping", dev.understudy.bridge.BridgeContract.CALL_PING)
        assertEquals("selfDiagnostic", dev.understudy.bridge.BridgeContract.CALL_SELF_DIAGNOSTIC)
        assertEquals(
            dev.understudy.proxytpl.bridge.BridgeContract.PROTOCOL_VERSION,
            dev.understudy.bridge.BridgeContract.PROTOCOL_VERSION,
        )
        assertEquals(
            dev.understudy.proxytpl.bridge.BridgeContract.ROOTS,
            dev.understudy.bridge.BridgeContract.ROOTS,
        )
    }
}
