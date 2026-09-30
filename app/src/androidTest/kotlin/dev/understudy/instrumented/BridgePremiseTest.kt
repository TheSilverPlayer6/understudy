package dev.understudy.instrumented

import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.understudy.bridge.BridgeClient
import dev.understudy.bridge.BridgeError
import dev.understudy.core.model.StorageRoot
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The premise test. Everything else in this project is downstream of one claim:
 *
 * > A proxy APK carrying package name P, installed for user N, can read and write
 * > `/storage/emulated/N/Android/{data,obb}/P` — and can hand those bytes to another app.
 *
 * That claim is about the **kernel's FUSE layer**, so it cannot be tested on a JVM. Robolectric
 * has no FUSE: `BridgeIntegrationTest` proves the protocol is correct but says nothing about
 * whether the platform grants the access. These tests close that gap and must run on a real
 * device or emulator, inside the secondary profile.
 *
 * CI orchestration (`.github/workflows/emulator.yml`) creates user 10, generates a proxy APK,
 * installs it *and* this app for user 10, plants known bytes in the proxy's private storage via
 * `adb shell` (which is exempt from the FUSE filter), then runs this class with
 * `am instrument --user 10`.
 *
 * Arguments:
 *  - `targetPackage` (required) — the package the installed proxy impersonates
 *  - `userId`        (optional) — expected Android user id, cross-checked against the proxy's
 *  - `expectPlanted` (optional, default true) — whether CI planted the fixture files
 */
@RunWith(AndroidJUnit4::class)
class BridgePremiseTest {

    private lateinit var context: Context
    private lateinit var target: String
    private var expectedUser: Int = -1
    private var expectPlanted: Boolean = true

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val args = InstrumentationRegistry.getArguments()
        target = args.getString("targetPackage") ?: ""
        expectedUser = args.getString("userId")?.toIntOrNull() ?: -1
        expectPlanted = args.getString("expectPlanted")?.toBoolean() ?: true

        // Without a target there is nothing to assert; skip rather than fail so that this class
        // can live in the repo without breaking an ordinary `connectedCheck` run.
        assumeTrue("no targetPackage argument; nothing to verify", target.isNotBlank())
    }

    private fun client() = BridgeClient(context.contentResolver, target)

    private fun myUserId() = Process.myUid() / PER_USER_RANGE

    // ---- 1. the premise itself ---------------------------------------------

    @Test
    fun proxyIsReachableAcrossTheProcessBoundary() {
        // This single call proves a great deal at once: the proxy is installed for this user,
        // the signature-level BRIDGE permission was granted (so both APKs share a signing key),
        // the provider process started on demand, and the protocol matches.
        val info = client().ping(target)

        assertEquals(target, info.packageName)
        assertTrue(info.protocolCompatible, "protocol ${info.protocolVersion} is not ours")
        assertTrue(
            info.roots.any { it.root == StorageRoot.DATA && it.exists },
            "the proxy cannot see its own Android/data root: ${info.roots}",
        )
    }

    @Test
    fun proxyRunsInTheIntendedUserProfile() {
        val info = client().ping(target)

        // We must be in the same profile as the proxy, otherwise we are looking at a different
        // user's storage and every subsequent assertion is meaningless.
        assertEquals(
            myUserId(), info.userId,
            "test runs as user ${myUserId()} but the proxy is in user ${info.userId}",
        )
        if (expectedUser >= 0) {
            assertEquals(expectedUser, info.userId, "proxy is not in the requested profile")
        }
    }

    /**
     * The core assertion: bytes planted by `adb shell` into the secondary profile's private
     * storage are readable through the bridge.
     *
     * If this passes, the mechanism works. If it fails, nothing else in the project matters.
     */
    /**
     * Diagnostic, not a premise assertion: narrows down WHERE the data root stops being visible.
     *
     * The obb half of the premise passes on API 34 and 35 while the data half fails, and the two
     * trees are planted and chowned by the same command. `File.listFiles()` returns null for both
     * EACCES and ENOENT and never surfaces errno, so the bridge's "permission denied" wording has
     * been a guess this whole time. These probes separate the candidates without needing one:
     *
     *  - listing the DATA ROOT ITSELF. If the root lists but the `planted` child does not, the
     *    problem is per-entry; if the root does not list either, it is the whole subtree.
     *  - the proxy's OWN `files/` directory, created through its own access path. If that lists
     *    and `planted/` does not, the difference is who created the entry, not where it lives.
     *  - [java.nio.file.Files] instead of [java.io.File], because the NIO layer throws
     *    `java.nio.file.AccessDeniedException` / `NoSuchFileException` — an errno distinction
     *    `File` throws away.
     *  - a direct read of the planted bytes, which is the thing the premise actually claims.
     *
     * It never fails the build: a diagnostic that can turn a real regression into noise is worse
     * than no diagnostic. The output lands in the instrument log, which CI captures.
     */
    @Test
    fun diagnoseDataRootVisibility() {
        val uid = Process.myUid()
        println("DIAG uid=$uid userId=${uid / PER_USER_RANGE} target=$target")

        val base = File("/storage/emulated/${myUserId()}/Android")
        for ((label, dir) in listOf(
            "data-root" to File(base, "data/$target"),
            "data-files" to File(base, "data/$target/files"),
            "data-planted" to File(base, "data/$target/$PLANTED_DIR"),
            "obb-root" to File(base, "obb/$target"),
        )) {
            println(
                "DIAG $label path=${dir.absolutePath} exists=${dir.exists()} " +
                    "isDirectory=${dir.isDirectory} canRead=${dir.canRead()} " +
                    "canExecute=${dir.canExecute()}",
            )
            val names = runCatching { dir.list() }
            val listedText = when {
                names.isFailure -> "threw ${names.exceptionOrNull()}"
                names.getOrNull() == null -> "null (EACCES or ENOENT; File does not say which)"
                else -> (names.getOrNull() ?: emptyArray()).joinToString(prefix = "[", postfix = "]")
            }
            println("DIAG $label File.list() -> $listedText")
            val nio = runCatching {
                java.nio.file.Files.newDirectoryStream(dir.toPath()).use { stream ->
                    stream.map { it.fileName.toString() }.toList()
                }
            }
            // The NIO exception TYPE is the errno signal File.list() discards.
            val nioText = if (nio.isSuccess) {
                nio.getOrNull().toString()
            } else {
                val e = nio.exceptionOrNull()
                "${e?.javaClass?.simpleName}: ${e?.message}"
            }
            println("DIAG $label Files.newDirectoryStream -> $nioText")
        }

        val plantedFile = File(base, "data/$target/$PLANTED_DIR/$PLANTED_FILE")
        println("DIAG planted-file exists=${plantedFile.exists()} canRead=${plantedFile.canRead()}")
        val read = runCatching { plantedFile.readBytes() }
        val readText = if (read.isSuccess) {
            val bytes = read.getOrNull() ?: ByteArray(0)
            "read ${bytes.size} bytes: \"" + String(bytes, Charsets.UTF_8) + "\""
        } else {
            val e = read.exceptionOrNull()
            "${e?.javaClass?.simpleName}: ${e?.message}"
        }
        println("DIAG planted-file read -> $readText")

        // And through the bridge, so the two views sit next to each other in one log.
        for ((label, root, path) in listOf(
            Triple("bridge-data-root", StorageRoot.DATA, ""),
            Triple("bridge-data-files", StorageRoot.DATA, "files"),
            Triple("bridge-data-planted", StorageRoot.DATA, PLANTED_DIR),
            Triple("bridge-obb-root", StorageRoot.OBB, ""),
        )) {
            val listed = runCatching { client().list(root, path).map { it.name } }
            val bridgeText = if (listed.isSuccess) {
                listed.getOrNull().toString()
            } else {
                val e = listed.exceptionOrNull()
                "${e?.javaClass?.simpleName}: ${e?.message}"
            }
            println("DIAG $label -> $bridgeText")
        }
    }

    /**
     * Asks the PROXY what it sees, rather than looking from out here.
     *
     * This is the diagnostic that actually settles the question. Everything printed by
     * [diagnoseDataRootVisibility] describes the view of uid [Process.myUid] — the *test app* —
     * for which `Android/data/<target>` is supposed to be invisible. Run #15 showed exactly that:
     * NoSuchFileException for the data root, and AccessDeniedException for obb, from the test
     * app. Both are the restriction working, and neither says anything about whether the proxy
     * can reach its own files.
     *
     * The proxy's report goes to the instrument log AND to logcat under the UnderstudyBridge
     * tag, because the CI grep only captures one of them reliably.
     */
    @Test
    fun proxyReportsWhatItSeesOfItsOwnRoots() {
        val report = client().selfDiagnostic()
        if (report == null) {
            // An older template without the call. Not a premise failure, but it must be loud:
            // without this report there is no evidence about the proxy's own view at all.
            println("SELF-DIAG unavailable: the installed proxy does not implement selfDiagnostic")
            return
        }
        println("SELF-DIAG-BEGIN")
        report.lineSequence().forEach { println("SELF-DIAG $it") }
        println("SELF-DIAG-END")

        // The report is the evidence; asserting on its contents would make the diagnostic
        // itself the thing under test. One structural check only: it must describe both roots.
        assertTrue("data" in report && "obb" in report, "report should cover both roots: $report")
    }

    @Test
    fun plantedSaveDataIsReadableThroughTheBridge() {
        assumeTrue("CI did not plant fixtures", expectPlanted)

        val entries = client().list(StorageRoot.DATA, PLANTED_DIR)
        val names = entries.map { it.name }
        assertTrue(
            PLANTED_FILE in names,
            "planted file not visible through the bridge; saw $names — the FUSE grant may not apply",
        )

        val entry = entries.first { it.name == PLANTED_FILE }
        assertFalse(entry.isDirectory)
        assertEquals(PLANTED_CONTENTS.length.toLong(), entry.sizeBytes, "size disagrees")

        val read = client().openFile(StorageRoot.DATA, "$PLANTED_DIR/$PLANTED_FILE", "r").use { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }
        assertEquals(PLANTED_CONTENTS, String(read, Charsets.UTF_8), "content disagrees")
    }

    @Test
    fun plantedObbIsReadableThroughTheBridge() {
        assumeTrue("CI did not plant fixtures", expectPlanted)

        val entries = client().list(StorageRoot.OBB)
        val obb = entries.firstOrNull { it.name == PLANTED_OBB }
        assertNotNull(obb, "planted .obb not visible; saw ${entries.map { it.name }}")
        assertTrue(obb.sizeBytes > 0)

        val read = client().openFile(StorageRoot.OBB, PLANTED_OBB, "r").use { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }
        assertEquals(PLANTED_CONTENTS, String(read, Charsets.UTF_8))
    }

    // ---- 2. writes reach the real filesystem -------------------------------

    @Test
    fun writingThroughTheBridgeLandsOnTheRealFilesystem() {
        val path = "$PLANTED_DIR/written-by-test.txt"
        val payload = "written from user ${myUserId()} at ${System.currentTimeMillis()}"

        if (!expectPlanted) {
            // No root in this run, so nothing pre-created the directory, and `openFile` refuses a
            // write whose parent is missing. Create it through the bridge: what this test proves is
            // that a write reaches the real filesystem, not that the directory pre-existed — that
            // is what the two `planted*` tests are for, and they are the ones that need root.
            client().mkdirs(StorageRoot.DATA, PLANTED_DIR)
        }

        client().openFile(StorageRoot.DATA, path, "w").use { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(pfd).use {
                it.write(payload.toByteArray())
            }
        }

        // Read it back through the bridge...
        val viaBridge = client().openFile(StorageRoot.DATA, path, "r").use { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }
        assertEquals(payload, String(viaBridge))

        // ...and confirm it is a real file, not something the provider invented. This is what
        // makes the round trip meaningful.
        //
        // The confirmation has to come FROM THE PROXY. This test used to stat the path itself:
        //   File("/storage/emulated/<user>/Android/data/$target/$path").isFile
        // which can never be true, because the platform hides the target's private directory
        // from every other package — the very restriction
        // [thePlatformStillHidesOtherPackagesPrivateStorageFromUs] asserts two tests below. The
        // two tests contradicted each other and only one could ever pass.
        //
        // So: ask the process that owns the directory. It reports the canonical path the
        // platform resolved plus the byte count, which together are stronger evidence than a
        // local stat would have been — a local stat could only ever prove the file was visible
        // to us, not that it exists where the platform says it does.
        val stated = client().statPath(StorageRoot.DATA, path)
        assertTrue(
            stated.exists && !stated.isDirectory,
            "the proxy does not see a file it just wrote: $stated",
        )
        assertEquals(
            payload.toByteArray().size.toLong(),
            stated.sizeBytes,
            "byte count on disk disagrees with what was written: $stated",
        )
        val canonical = stated.canonicalPath
        assertNotNull(canonical, "the proxy reported no canonical path")
        assertTrue(
            canonical!!.contains("/Android/data/$target/"),
            "the file landed outside the target's private storage: $canonical",
        )
        assertTrue(
            canonical.endsWith("/$PLANTED_DIR/written-by-test.txt"),
            "unexpected canonical path: $canonical",
        )

        // And the directory listing agrees, so this is not a one-off stat artifact.
        val siblings = client().list(StorageRoot.DATA, PLANTED_DIR).map { it.name }
        assertTrue("written-by-test.txt" in siblings, "not in the directory listing: $siblings")
    }

    // ---- 3. the restriction we are working around is real -------------------

    /**
     * Confirms the *problem* still exists, so the test suite cannot pass vacuously.
     *
     * If a future Android release opened `Android/data` back up, this would fail and tell us the
     * whole app had become unnecessary — which is exactly the signal we would want.
     */
    @Test
    fun thePlatformStillHidesOtherPackagesPrivateStorageFromUs() {
        val foreign = File("/storage/emulated/${myUserId()}/Android/data/$target")

        val listed = foreign.listFiles()
        val blocked = listed == null || listed.isEmpty()

        assertTrue(
            blocked,
            "we could read $foreign directly (${listed?.size} entries) — scoped storage is not " +
                "restricting us, so this app's premise no longer holds on this build",
        )

        // And our own directory is still reachable, proving the failure above is a permission
        // boundary rather than a missing mount.
        val own = context.getExternalFilesDir(null)
        assertNotNull(own, "our own external files dir should exist")
        assertTrue(own.isDirectory)
    }

    // ---- 4. safety rails hold on a real device ------------------------------

    @Test
    fun traversalIsRejectedByTheProviderOnDevice() {
        // Client-side validation should catch these first; the assertion is that they are caught
        // somewhere, and that the provider's own copy of the rule agrees.
        for (path in listOf("../etc/passwd", "saves/../../etc", "/absolute")) {
            val error = runCatching { client().list(StorageRoot.DATA, path) }.exceptionOrNull()
            assertNotNull(error, "traversal '$path' was not rejected")
            assertTrue(
                error is BridgeError.InvalidPath || error is BridgeError.RejectedByProxy ||
                    error is SecurityException || error is IllegalArgumentException,
                "unexpected rejection type for '$path': $error",
            )
        }
    }

    @Test
    fun aForgedAuthorityCannotBeUsedToReadAnotherPackage() {
        // The bridge must only ever serve the package it is installed as. Pointing a client at
        // some other authority should fail rather than quietly returning someone else's files.
        val forged = BridgeClient(context.contentResolver, "com.android.shell")
        val result = runCatching { forged.ping("com.android.shell") }
        assertTrue(result.isFailure, "a client pointed at an unrelated package should not succeed")
    }

    /**
     * Measures how much of the proxy is visible to this app, and pins the design decision that
     * follows from it: **presence is determined by calling the provider, never by querying
     * PackageManager.**
     *
     * This exists because a plausible-looking `ProxyInstaller.isInstalled(pkg)` was removed. It
     * asked `getPackageInfo(pkg, 0)` and swallowed `NameNotFoundException` into `false`. Two
     * things were wrong with it, and only a device can settle the first:
     *
     *  1. `getPackageInfo` is named in the platform docs as subject to package-visibility
     *     filtering from API 30, and nothing in the automatic-visibility list covers "an app
     *     whose content provider I queried" — the automatic rule runs the *other* way, which is
     *     what lets the proxy authenticate its caller. So an installed proxy could report as
     *     absent, silently, with no way to distinguish that from a genuine absence.
     *  2. Even a truthful answer is the wrong question. The orchestrator needs "can I reach the
     *     bridge?", and `ping()` answers that strictly more strongly: live provider, granted
     *     signature permission, matching protocol version, correct profile.
     *
     * The individual query APIs are printed rather than asserted, because the honest answer may
     * differ by API level and OEM build, and a red CI run over a diagnostic would train everyone
     * to ignore it. What *is* asserted is the two invariants the product depends on:
     *
     *  - the app holds no `QUERY_ALL_PACKAGES`, so the Play-restricted permission is never needed;
     *  - the proxy resolves through `<queries><intent>` against `BridgeContract.DISCOVERY_ACTION`.
     *    This is the mechanism that makes the provider reachable at all, and run #29 is what
     *    happens without it: five tests failing with `Unknown authority` against a provider that
     *    the shell could query successfully in the same log.
     */
    @Test
    fun theProxyIsVisibleThroughTheDiscoveryIntentWithoutQueryAllPackages() {
        val pm = context.packageManager

        assertEquals(
            android.content.pm.PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(android.Manifest.permission.QUERY_ALL_PACKAGES),
            "this suite must not hold QUERY_ALL_PACKAGES, or it stops proving that the bridge " +
                "works without it — and the app must never request it (Play-restricted)",
        )

        val ping = runCatching { client().ping(target) }
        println("PREMISE-DIAG ping=$ping")
        assertTrue(
            ping.isSuccess,
            "the provider is unreachable while provider access is the only detection mechanism: " +
                "${ping.exceptionOrNull()}",
        )

        // Informational: how the package-query APIs see the proxy from here. Recorded so the
        // removed isInstalled() is not re-added on the strength of a guess.
        val getInfo = runCatching { pm.getPackageInfo(target, 0) }
        val appInfo = runCatching { pm.getApplicationInfo(target, 0) }
        val resolveProvider = runCatching { pm.resolveContentProvider(target, 0) }
        val launch = runCatching { pm.getLaunchIntentForPackage(target) }
        println("PREMISE-DIAG visibility from the app, no QUERY_ALL_PACKAGES:")
        println("PREMISE-DIAG   getPackageInfo       = ${describe(getInfo)}")
        println("PREMISE-DIAG   getApplicationInfo   = ${describe(appInfo)}")
        println("PREMISE-DIAG   resolveContentProvider = ${describe(resolveProvider)}")
        println("PREMISE-DIAG   getLaunchIntentForPackage = ${describe(launch)}")

        // The mechanism the app actually relies on: <queries><intent> against the discovery action
        // every proxy advertises. If this is empty the bridge is unreachable no matter how
        // correct the provider is, and the failure will surface as "Unknown authority".
        val discovery = runCatching<List<String>> { discoveryPackages(pm) }
        println("PREMISE-DIAG   queryIntentReceivers(${dev.understudy.bridge.BridgeContract.DISCOVERY_ACTION}) = ${describe(discovery)}")
        val found = discovery.getOrNull().orEmpty()
        assertTrue(
            target in found,
            "the proxy is not visible through <queries><intent>. Without visibility the platform " +
                "refuses to resolve its provider and every bridge call fails with " +
                "'Unknown authority' — indistinguishable from a proxy that is not installed. " +
                "Resolved: $found",
        )
        println("PREMISE-DIAG   => discovery resolves $target; provider access works")
    }

    /**
     * Which packages advertise [dev.understudy.bridge.BridgeContract.DISCOVERY_ACTION], i.e. which
     * proxies this app's `<queries><intent>` makes visible to it.
     *
     * `queryBroadcastReceivers(Intent, int)` is deprecated on API 33 in favour of a
     * `ResolveInfoFlags` overload, but it is not removed and it is the only form that compiles
     * against minSdk 26 without an SDK branch. Suppressed rather than branched: both call the same
     * resolution path, and this is a diagnostic in an instrumented test, not product code.
     *
     * Note this query is itself subject to the visibility filter, which is the point — it reports
     * what the app is *allowed* to see, not what is installed.
     */
    @Suppress("DEPRECATION")
    private fun discoveryPackages(pm: android.content.pm.PackageManager): List<String> {
        val intent = android.content.Intent(dev.understudy.bridge.BridgeContract.DISCOVERY_ACTION)
        return pm.queryBroadcastReceivers(intent, 0).mapNotNull { it.activityInfo?.packageName }
    }

    private fun describe(r: Result<*>): String = when {
        r.isSuccess -> "OK (${r.getOrNull()})"
        else -> "${r.exceptionOrNull()?.javaClass?.simpleName}: ${r.exceptionOrNull()?.message}"
    }

    companion object {
        private const val PER_USER_RANGE = 100_000
        const val PLANTED_DIR = "planted"
        const val PLANTED_FILE = "save.dat"
        const val PLANTED_OBB = "main.1.com.example.planted.obb"
        const val PLANTED_CONTENTS = "understudy-fuse-premise-check"
    }
}
