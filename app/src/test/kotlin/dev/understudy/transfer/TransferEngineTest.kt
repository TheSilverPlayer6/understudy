package dev.understudy.transfer

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import dev.understudy.bridge.BridgeClient
import dev.understudy.bridge.BridgeContract
import dev.understudy.bridge.BridgeError
import dev.understudy.core.model.StorageRoot
import dev.understudy.proxytpl.bridge.ProxyFileBridge
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The transfer engine's safety rules, exercised end to end.
 *
 * Everything else in this app is recoverable: a bad APK can be regenerated, a failed install
 * retried. A transfer that quietly truncates a save file is not, which is why `TransferEngine` is
 * written the way it is — write-then-swap, resume-by-size, per-file failure isolation, cooperative
 * cancellation. None of that had a test. `TransferModelTest` covers the progress arithmetic; the
 * rules that decide whether a user's data survives were asserted only by their KDoc.
 *
 * Fidelity, in the project's "test against real artifacts" idiom:
 *  - the **source is the real [ProxyFileBridge]**, registered as a real provider and driven through
 *    the real [BridgeClient], exactly as `BridgeIntegrationTest` does. So a pull really streams
 *    `ParcelFileDescriptor`s across a real `ContentResolver`, and a push really lands on the
 *    proxy's own filesystem;
 *  - the **destination is a directory on disk** behind [FakeDestination]. SAF's own quirks (no
 *    atomic rename, provider round trips, grant expiry) are out of scope and belong to the
 *    instrumented suite; what is in scope is the engine's logic, and a real filesystem reproduces
 *    the parts of SAF that logic depends on — including that `openWrite` truncates.
 *
 * `TransferDestination` exists so this test can exist: `SafDestination`'s private constructor wraps
 * a `DocumentFile` tree that needs a real SAF grant, which no JVM test can obtain.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TransferEngineTest {

    private lateinit var context: Context
    private lateinit var client: BridgeClient

    /** The proxy's `Android/data/<pkg>` as the provider itself resolves it. */
    private lateinit var proxyDataRoot: File

    /** The destination tree, i.e. what the user picked in SAF. */
    private lateinit var destRoot: File
    private lateinit var dest: FakeDestination

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val authority = context.packageName
        Robolectric.buildContentProvider(ProxyFileBridge::class.java).create(authority)

        // Derived the same way BridgeIntegrationTest does, and for the same reason: counting levels
        // up from getExternalFilesDir disagrees with the provider under Robolectric, where emulated
        // external storage has no per-package segment.
        val filesDir = context.getExternalFilesDir(null)!!
        proxyDataRoot = File(filesDir.parentFile!!, context.packageName)
        assertTrue(proxyDataRoot.mkdirs() || proxyDataRoot.isDirectory)

        destRoot = File(context.cacheDir, "destination-${System.nanoTime()}")
        assertTrue(destRoot.mkdirs())
        dest = FakeDestination(destRoot)
        client = BridgeClient(context.contentResolver, authority)
    }

    private fun engine(overwrite: Boolean = false) = TransferEngine(client, dest, overwrite)

    /** Writes into the proxy's own tree, i.e. what the target app would have left behind. */
    private fun plant(relativePath: String, bytes: ByteArray) {
        val f = File(proxyDataRoot, relativePath)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
    }

    private fun planted(relativePath: String): File = File(proxyDataRoot, relativePath)

    private fun landed(relativePath: String): File = File(destRoot, relativePath)

    // ---- the happy path -----------------------------------------------------

    @Test
    fun pullCopiesEveryFileInTheSubtreeWithTheRightBytes() = runTest {
        val a = "save.dat".toByteArray()
        val b = ByteArray(300_000) { (it % 251).toByte() } // spans several 1 MiB buffers? no — but > one chunk of the plan
        val c = "nested/deep/config.ini".toByteArray()
        plant("save.dat", a)
        plant("big.bin", b)
        plant("nested/deep/config.ini", c)

        val result = engine().pull(StorageRoot.DATA)

        assertTrue(result.isSuccess, "failures: ${result.failed}")
        assertEquals(3, result.succeeded)
        assertEquals((a.size + b.size + c.size).toLong(), result.bytesCopied)
        assertContentEquals(a, landed("save.dat").readBytes())
        assertContentEquals(b, landed("big.bin").readBytes())
        assertContentEquals(c, landed("nested/deep/config.ini").readBytes())
    }

    @Test
    fun pullCreatesIntermediateDirectoriesInTheDestination() = runTest {
        plant("a/b/c/d.txt", "deep".toByteArray())

        val result = engine().pull(StorageRoot.DATA)

        assertTrue(result.isSuccess, "failures: ${result.failed}")
        assertTrue(landed("a/b/c").isDirectory, "intermediate directories were not created")
        assertEquals("deep", landed("a/b/c/d.txt").readText())
    }

    @Test
    fun aCompletedPullLeavesNoPartFileBehind() = runTest {
        // The `.part` is the whole mechanism; leaving one behind would mean it was promoted without
        // being cleaned up, and the next run would delete it and re-copy for no reason.
        plant("save.dat", "contents".toByteArray())

        val result = engine().pull(StorageRoot.DATA)

        assertTrue(result.isSuccess)
        assertTrue(landed("save.dat").isFile)
        assertFalse(landed("save.dat.part").exists(), "the .part file was not removed after promotion")
        assertEquals(
            emptyList(),
            destRoot.walkTopDown().filter { it.name.endsWith(".part") }.toList(),
            "no .part file may survive a successful transfer anywhere in the tree",
        )
    }

    @Test
    fun anEmptyTreeIsDoneRatherThanFailed() = runTest {
        val states = ArrayList<State>()
        val result = engine().pull(StorageRoot.DATA) { states += it.state }

        assertTrue(result.isSuccess)
        assertEquals(0, result.succeeded)
        assertEquals(0, result.bytesCopied)
        assertEquals(State.DONE, states.last(), "an empty pull must report DONE, not FAILED")
    }

    @Test
    fun aZeroByteFileTransfers() = runTest {
        // Not a resume hit (size 0 is excluded from the skip rule) and not a size mismatch.
        plant("empty.flag", ByteArray(0))
        plant("real.dat", "x".toByteArray())

        val result = engine().pull(StorageRoot.DATA)

        assertTrue(result.isSuccess, "failures: ${result.failed}")
        assertEquals(2, result.succeeded)
        assertTrue(landed("empty.flag").isFile)
        assertEquals(0L, landed("empty.flag").length())
    }

    // ---- resumability -------------------------------------------------------

    @Test
    fun anAlreadyCompleteFileIsSkippedOnTheNextRun() = runTest {
        val bytes = "a save game".toByteArray()
        plant("save.dat", bytes)

        val first = engine().pull(StorageRoot.DATA)
        assertTrue(first.isSuccess)
        val writesAfterFirst = dest.openWriteCalls

        // Simulate an interrupted run that had already finished this file: the destination is at the
        // expected size, so a restart must not re-copy it. For a multi-GB .obb this is the difference
        // between a cheap retry and starting over.
        dest.reset()
        val second = engine().pull(StorageRoot.DATA)

        assertTrue(second.isSuccess, "failures: ${second.failed}")
        assertEquals(1, second.succeeded, "a skipped file still counts as succeeded")
        assertEquals(0, dest.openWriteCalls, "the complete file was re-copied; resume is broken")
        assertEquals(0, dest.deleteCalls, "resume must not delete the file it is skipping")
        assertContentEquals(bytes, landed("save.dat").readBytes())
        assertTrue(writesAfterFirst > 0, "sanity: the first run really did write")
    }

    @Test
    fun overwriteReCopiesEvenWhenTheSizeAlreadyMatches() = runTest {
        plant("save.dat", "original".toByteArray())
        assertTrue(engine().pull(StorageRoot.DATA).isSuccess)

        dest.reset()
        val result = engine(overwrite = true).pull(StorageRoot.DATA)

        assertTrue(result.isSuccess)
        assertTrue(dest.openWriteCalls > 0, "overwrite=true must actually copy")
        assertEquals("original", landed("save.dat").readText())
    }

    @Test
    fun aPartiallyCopiedFileIsNotTreatedAsComplete() = runTest {
        // The exact state an interrupted transfer leaves: a truncated file at the final name.
        // Its size no longer matches what the proxy reports, so it must be re-pulled — this is what
        // makes the non-atomic promotion recoverable.
        val full = ByteArray(4096) { (it % 256).toByte() }
        plant("save.dat", full)
        landed("save.dat").writeBytes(full.copyOfRange(0, 1000))

        val result = engine().pull(StorageRoot.DATA)

        assertTrue(result.isSuccess, "failures: ${result.failed}")
        assertContentEquals(full, landed("save.dat").readBytes(), "the truncated file was not repaired")
    }

    @Test
    fun aStalePartFileIsDeletedBeforeTheNextAttempt() = runTest {
        val bytes = "good bytes".toByteArray()
        plant("save.dat", bytes)
        landed("save.dat.part").writeBytes("garbage from a crashed run".toByteArray())

        val result = engine().pull(StorageRoot.DATA)

        assertTrue(result.isSuccess, "failures: ${result.failed}")
        assertContentEquals(bytes, landed("save.dat").readBytes())
        assertFalse(landed("save.dat.part").exists(), "a stale .part must not survive, let alone be promoted")
    }

    // ---- failure isolation --------------------------------------------------

    @Test
    fun oneUnreadableFileDoesNotAbortTheRest() = runTest {
        plant("good/a.txt", "a".toByteArray())
        plant("good/b.txt", "b".toByteArray())
        // A DANGLING SYMLINK: a realistic thing to find in a save directory, because games and
        // launchers link shared asset directories and the link outlives the target. The proxy
        // resolves canonical paths, so this entry cannot be opened — but it is ONE entry, and a
        // backup that stops because of it is worse than a backup that reports it.
        java.nio.file.Files.createSymbolicLink(
            planted("broken-link").toPath(),
            File("/nonexistent/outside/the/root").toPath(),
        )

        val result = engine().pull(StorageRoot.DATA)

        assertFalse(result.isSuccess, "a failure must be reported")
        assertEquals(2, result.succeeded, "the healthy files must still have been copied")
        assertEquals("a", landed("good/a.txt").readText())
        assertEquals("b", landed("good/b.txt").readText())
        assertTrue(
            result.failed.any { it.path.contains("broken-link") },
            "the failure was not attributed to the symlink: ${result.failed}",
        )
        assertTrue(
            result.failed.none { it.fatal },
            "one unopenable entry must not abort the transfer: ${result.failed}",
        )
    }

    /**
     * The regression this whole suite was written to catch, found by measuring rather than reading.
     *
     * A symlink inside the save tree that points at a **real** file outside it is correctly refused
     * — `BridgePaths` re-checks canonical paths precisely so a link planted inside the tree cannot
     * redirect the proxy, and the bytes are never served. The bug was in how the refusal was
     * *reported*: it crossed Binder as a bare `SecurityException`, indistinguishable from
     * `enforceCaller()` rejecting the app itself, so `BridgeClient` mapped it to
     * `PermissionDenied` — which `TransferEngine` treats as fatal, because a proxy that will not
     * authenticate its caller really will fail every subsequent call.
     *
     * Measured before the fix, one symlink in the tree produced:
     *
     * ```
     * failure path=live-link fatal=true reason=The proxy at '…' refused access: this is not the
     *   Understudy install that generated it. … Regenerate and reinstall the proxy.
     * ```
     *
     * So a save directory containing one link aborted the entire backup, and told the user their
     * Understudy install was broken and that they should uninstall and regenerate every proxy they
     * had. `BridgeContract.PATH_REJECTION_MARKER` is what separates the two now.
     */
    @Test
    fun aSymlinkOutOfTheRootIsRefusedPerFileAndNotBlamedOnTheCaller() = runTest {
        plant("good/a.txt", "a".toByteArray())
        val outside = File(context.cacheDir, "outside-secret.txt").apply { writeText("secret") }
        java.nio.file.Files.createSymbolicLink(planted("live-link").toPath(), outside.toPath())

        // The security property first: the bytes outside the root must never be served.
        val direct = runCatching {
            client.openFile(StorageRoot.DATA, "live-link", "r").use {
                ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8)
            }
        }
        assertTrue(direct.isFailure, "a symlink out of the root was followed — the escape check failed")
        assertIs<BridgeError.RejectedByProxy>(
            direct.exceptionOrNull(),
            "a path refusal must not be reported as a caller-authorisation failure; got " +
                "${direct.exceptionOrNull()} — ${direct.exceptionOrNull()?.message}",
        )
        assertFalse(
            (direct.exceptionOrNull()?.message ?: "").contains("not the Understudy install"),
            "the user was told their install was broken because of one path: " +
                "${direct.exceptionOrNull()?.message}",
        )

        // And the consequence for a transfer: one bad entry, rest of the backup intact.
        val result = engine().pull(StorageRoot.DATA)

        assertFalse(result.isSuccess)
        assertEquals(1, result.succeeded, "the healthy file must still have been copied")
        assertEquals("a", landed("good/a.txt").readText())
        val failure = result.failed.single { it.path.contains("live-link") }
        assertFalse(
            failure.fatal,
            "a per-path refusal aborted the whole transfer: $failure",
        )
        assertTrue(
            "escape" in failure.reason || "root" in failure.reason,
            "the reason should describe the path problem, not the caller: ${failure.reason}",
        )
        assertFalse(
            landed("live-link").exists() || landed("live-link.part").exists(),
            "nothing may be written for a refused path",
        )
    }

    @Test
    fun aPathRefusalThroughCallIsAlsoNonFatal() = runTest {
        // call() carries errors in a Bundle rather than by throwing, so the proxy signals a path
        // refusal with BridgeContract.KEY_PATH_REJECTED instead of a message prefix. Both channels
        // have to classify the same way, or mkdirs/delete/rename would still look fatal.
        val direct = runCatching { client.mkdirs(StorageRoot.DATA, "../escape") }
        assertTrue(direct.isFailure, "mkdirs outside the root must be refused")
        val e = direct.exceptionOrNull()!!
        assertTrue(
            e is BridgeError.InvalidPath || e is BridgeError.RejectedByProxy,
            "a path refusal via call() should not be PermissionDenied or OperationFailed, got $e",
        )
        assertFalse(
            (e.message ?: "").contains(BridgeContract.PATH_REJECTION_MARKER),
            "the wire marker leaked into a user-visible message: ${e.message}",
        )
    }

    @Test
    fun aFileThatChangesDuringThePullIsReportedRatherThanSilentlyTruncated() = runTest {
        // The target app is running while we back it up; a save file being written mid-pull is the
        // normal case, not an exotic one. The plan snapshots sizes up front, so the copy can come
        // up short. The engine must notice, delete the .part, and say so — a short file that looks
        // like a save game is the worst possible outcome.
        val original = ByteArray(8192) { 'A'.code.toByte() }
        plant("save.dat", original)

        var shrunk = false
        val result = engine().pull(StorageRoot.DATA) { progress ->
            if (!shrunk && progress.state == State.RUNNING) {
                shrunk = true
                planted("save.dat").writeBytes(ByteArray(100)) // truncated underneath us
            }
        }

        assertTrue(shrunk, "the test never got the chance to modify the file mid-transfer")
        assertFalse(result.isSuccess, "a short copy must be reported as a failure")
        assertEquals(0, result.succeeded)
        assertTrue(
            result.failed.any { "size mismatch" in it.reason },
            "the failure should name the size mismatch, got: ${result.failed}",
        )
        assertFalse(
            landed("save.dat.part").exists(),
            "the short .part must be deleted, or a later run could promote it",
        )
    }

    @Test
    fun aDestinationThatFailsMidwayLeavesThePreviousGoodCopyAlone() = runTest {
        val good = "the previous good copy".toByteArray()
        plant("save.dat", "new contents".toByteArray())
        landed("save.dat").writeBytes(good)

        // Fail on the .part write only; the final name must not be touched at all, which is the
        // point of writing to a temporary name first.
        dest.failOnWriteContaining = ".part"

        val result = engine(overwrite = true).pull(StorageRoot.DATA)

        assertFalse(result.isSuccess)
        assertContentEquals(
            good,
            landed("save.dat").readBytes(),
            "the previous good copy was damaged by a failed attempt",
        )
    }

    // ---- cancellation -------------------------------------------------------

    @Test
    fun cancellationStopsTheTransferAndIsReported() = runTest {
        repeat(6) { i -> plant("file$i.bin", ByteArray(64 * 1024) { i.toByte() }) }
        val e = engine()

        val seen = ArrayList<TransferProgress>()
        var cancelled = false
        val result = e.pull(StorageRoot.DATA) { progress ->
            seen += progress
            // Cancel once the first file is in flight; the flag is checked between chunks, so it
            // must take effect within one buffer rather than at an arbitrary point.
            if (!cancelled && progress.currentFileBytes > 0) {
                cancelled = true
                e.cancel()
            }
        }

        assertTrue(cancelled, "the transfer never reported any progress, so nothing was cancelled")
        assertTrue(result.cancelled, "the result must say it was cancelled")
        assertFalse(result.isSuccess)
        assertTrue(result.succeeded < 6, "cancellation did not stop the remaining files")
        assertEquals(State.CANCELLED, seen.last().state, "the final progress state must be CANCELLED")
        assertEquals(
            6,
            seen.first { it.state == State.SCANNING }.filesTotal,
            "the plan is built before copying, so cancellation must not change the total",
        )
    }

    @Test
    fun cancellationCanBeResetForTheNextRun() = runTest {
        plant("a.bin", ByteArray(32 * 1024))
        val e = engine()
        e.cancel()

        assertTrue(e.pull(StorageRoot.DATA).cancelled, "a pre-cancelled engine must not copy")

        e.resetCancellation()
        val second = e.pull(StorageRoot.DATA)
        assertTrue(second.isSuccess, "resetCancellation did not restore the engine: ${second.failed}")
    }

    // ---- progress accounting ------------------------------------------------

    @Test
    fun progressReportsRealTotalsBeforeAnythingMoves() = runTest {
        plant("a.txt", ByteArray(1000))
        plant("nested/b.txt", ByteArray(2500))

        val seen = ArrayList<TransferProgress>()
        val result = engine().pull(StorageRoot.DATA) { seen += it }

        assertTrue(result.isSuccess)
        val scanning = seen.first { it.state == State.SCANNING }
        assertEquals(2, scanning.filesTotal, "the file count must be known before copying starts")
        assertEquals(3500L, scanning.bytesTotal, "the byte total must be known before copying starts")
        assertEquals(Direction.PULL, scanning.direction)

        val last = seen.last()
        assertEquals(State.DONE, last.state)
        assertEquals(2, last.filesDone)
        assertEquals(3500L, last.bytesDone)
        assertEquals(1f, last.fractionComplete, 0.001f)
        assertEquals("", last.currentPath, "currentPath must be cleared when the run finishes")
    }

    @Test
    fun planPullDoesNotMoveAnyBytes() = runTest {
        plant("a.txt", ByteArray(1000))
        plant("b.txt", ByteArray(2000))

        val plan = engine().planPull(StorageRoot.DATA)

        assertEquals(Direction.PULL, plan.direction)
        assertEquals(StorageRoot.DATA, plan.root)
        assertEquals(2, plan.fileCount)
        assertEquals(3000L, plan.totalBytes)
        assertEquals(0, dest.openWriteCalls, "planning must not write anything")
        assertEquals(emptyList(), destRoot.listFiles()?.toList().orEmpty())
    }

    // ---- push ---------------------------------------------------------------

    @Test
    fun pushCopiesLocalBytesIntoTheProxyAndCreatesParents() = runTest {
        val payload = ByteArray(200_000) { (it % 199).toByte() }
        val sources = listOf(
            PushSource("restored/save.dat", payload.size.toLong()) { payload.inputStream() },
            PushSource("restored/deep/nested.cfg", 12L) { "config=data\n".byteInputStream() },
        )

        val result = engine().push(StorageRoot.DATA, sources)

        assertTrue(result.isSuccess, "failures: ${result.failed}")
        assertEquals(2, result.succeeded)
        assertEquals(payload.size + 12L, result.bytesCopied)
        // Confirmed through the proxy's own view, not by stat'ing the file ourselves — the same
        // rule BridgePremiseTest had to learn the hard way.
        assertContentEquals(payload, client.openFile(StorageRoot.DATA, "restored/save.dat", "r")
            .use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() })
        val stated = client.statPath(StorageRoot.DATA, "restored/deep/nested.cfg")
        assertTrue(stated.exists && !stated.isDirectory)
        assertEquals(12L, stated.sizeBytes)
    }

    @Test
    fun pushReportsDirectionAndTotals() = runTest {
        val sources = listOf(PushSource("x.bin", 500L) { ByteArray(500).inputStream() })

        val seen = ArrayList<TransferProgress>()
        val result = engine().push(StorageRoot.DATA, sources) { seen += it }

        assertTrue(result.isSuccess)
        assertEquals(Direction.PUSH, seen.first().direction)
        assertEquals(1, seen.first { it.state == State.SCANNING }.filesTotal)
        assertEquals(500L, seen.first { it.state == State.SCANNING }.bytesTotal)
    }

    @Test
    fun aPushSourceThatThrowsIsIsolatedToThatFile() = runTest {
        val good = "good".toByteArray()
        val sources = listOf(
            PushSource("ok.txt", good.size.toLong()) { good.inputStream() },
            PushSource("broken.txt", 10L) { throw IOException("the source tree went away") },
        )

        val result = engine().push(StorageRoot.DATA, sources)

        assertFalse(result.isSuccess)
        assertEquals(1, result.succeeded)
        assertTrue(result.failed.any { it.path == "broken.txt" }, "failures: ${result.failed}")
        assertTrue(result.failed.none { it.fatal })
        val stated = client.statPath(StorageRoot.DATA, "ok.txt")
        assertTrue(stated.exists, "the healthy file should still have been pushed")
    }

    // ---- the engine cannot invent a proxy -----------------------------------

    @Test
    fun pullingFromAnAuthorityNothingAnswersAtThrowsRatherThanReportingSuccess() = runTest {
        // Documented because it is a real behaviour with a real consequence: planning happens before
        // any copying, so a proxy that is not there at all surfaces as an exception out of pull(),
        // not as a TransferResult with failures. Callers must catch BridgeError, not just inspect
        // the result — and the message must not say "not installed" as though that were the only
        // possibility. See BridgeError.ProxyUnreachable.
        val orphan = BridgeClient(context.contentResolver, "com.never.installed.anywhere")
        val e = TransferEngine(orphan, dest)

        val thrown = assertFailsWith<BridgeError> { e.pull(StorageRoot.DATA) }
        assertTrue(
            thrown is BridgeError.ProxyUnreachable || thrown is BridgeError.OperationFailed,
            "unexpected failure type: $thrown",
        )
        assertEquals(0, dest.openWriteCalls, "nothing may be written when there is no source")
    }

    // ---- helpers ------------------------------------------------------------

    /**
     * A destination over a real directory.
     *
     * Not a mock: `openWrite` really truncates, `sizeOf` really returns -1 for a missing file, and
     * descriptors really are `ParcelFileDescriptor`s, which is what makes the engine's
     * `AutoClose{Input,Output}Stream` wrapping behave as it does in production. The counters exist
     * so "skipped" can be distinguished from "copied something identical" — a resume test that
     * only checked the final bytes would pass whether or not resume worked.
     */
    private class FakeDestination(private val root: File) : TransferDestination {

        var openWriteCalls = 0
            private set
        var openReadCalls = 0
            private set
        var deleteCalls = 0
            private set

        /** When set, any `openWrite` whose path contains this substring fails. */
        var failOnWriteContaining: String? = null

        fun reset() {
            openWriteCalls = 0
            openReadCalls = 0
            deleteCalls = 0
        }

        private fun file(relativePath: String): File {
            // Mirrors the validation SafDestination does, so a path-escape bug shows up here too.
            assertFalse(relativePath.startsWith("/"), "path must be relative: $relativePath")
            for (segment in relativePath.split('/')) {
                assertTrue(segment != ".." && segment != ".", "path traversal in '$relativePath'")
            }
            val f = File(root, relativePath)
            assertTrue(
                f.canonicalPath.startsWith(root.canonicalPath),
                "'$relativePath' escaped the destination root",
            )
            return f
        }

        override fun openWrite(relativePath: String): ParcelFileDescriptor {
            openWriteCalls++
            failOnWriteContaining?.let { needle ->
                if (relativePath.contains(needle)) throw IOException("simulated write failure on $relativePath")
            }
            val f = file(relativePath)
            f.parentFile?.mkdirs()
            return ParcelFileDescriptor.open(
                f,
                ParcelFileDescriptor.MODE_WRITE_ONLY or
                    ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE,
            )
        }

        override fun openRead(relativePath: String): ParcelFileDescriptor {
            openReadCalls++
            val f = file(relativePath)
            if (!f.isFile) throw FileNotFoundException("no such file: '$relativePath'")
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun sizeOf(relativePath: String): Long {
            val f = file(relativePath)
            return if (f.isFile) f.length() else -1
        }

        override fun delete(relativePath: String): Boolean {
            deleteCalls++
            val f = file(relativePath)
            return !f.exists() || f.delete()
        }

        override fun exists(relativePath: String): Boolean = file(relativePath).isFile
    }
}
