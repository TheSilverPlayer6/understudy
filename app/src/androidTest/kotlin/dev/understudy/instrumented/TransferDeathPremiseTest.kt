package dev.understudy.instrumented

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.understudy.bridge.BridgeClient
import dev.understudy.core.model.StorageRoot
import dev.understudy.transfer.Direction
import dev.understudy.transfer.TransferDestination
import dev.understudy.transfer.TransferEngine
import dev.understudy.transfer.TransferJournal
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Process death mid-transfer, on a device — the device half of HANDOFF §3.6.
 *
 * [TransferJournalTest] pins the journal's *semantics* on the JVM. What no JVM test can pin is
 * the thing the journal exists for: a REAL `SIGKILL` in the middle of a REAL pull over the
 * REAL bridge, with the next process finding the record and finishing the job. This test is
 * that, split into two instrument invocations because a dead process cannot keep testing:
 *
 *  - `deathPhase=die`: build a known tree inside the proxy's storage through the bridge,
 *    begin a journaled pull into app-specific external storage, and — the moment the big file
 *    is demonstrably mid-copy — `Process.killProcess(myPid())`. That is `SIGKILL` to self:
 *    no shutdown handlers, no coroutine cancellation, no `journal.finish`; exactly what the
 *    platform's low-memory killer and "swipe away" do to a transfer in flight. (The script
 *    could also kill the pid externally; self-kill removes the polling race without removing
 *    any fidelity — the signal and its timing semantics are the same.)
 *  - `deathPhase=resume`: a fresh process reads the journal, asserts the entry says RUNNING
 *    for this exact transfer, re-runs the pull, and proves three things: every file ends up
 *    byte-exact (SHA-256 against the deterministic generator), the completed-before-death
 *    files were NOT re-copied (their mtimes are untouched — the on-device analogue of the JVM
 *    suite's write-counting), and the journal reaches a terminal state so the offer never
 *    reappears.
 *
 * The destination is app-specific external storage rather than SAF: no tree grant exists in
 * CI (see the script's SAF probe), the directory survives process death exactly like a SAF
 * tree does, and the journal round-trips its URI the same way. What is under test is the
 * journal+engine recovery contract, not the destination flavour.
 *
 * The script verifies between phases, as root, that the journal file on disk really holds
 * `RUNNING` — so a die phase that died BEFORE journaling cannot masquerade as a resume that
 * found nothing to do.
 */
@RunWith(AndroidJUnit4::class)
class TransferDeathPremiseTest {

    private lateinit var context: Context
    private lateinit var target: String
    private var expectedUser: Int = -1
    private var phase: String = ""

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val args = InstrumentationRegistry.getArguments()
        target = args.getString("targetPackage") ?: ""
        expectedUser = args.getString("userId")?.toIntOrNull() ?: -1
        phase = args.getString("deathPhase") ?: ""
        assumeTrue("no targetPackage/deathPhase arguments", target.isNotBlank() && phase.isNotBlank())
    }

    // ---- the scenario, fixed so both phases agree without talking to each other ----

    private val smallFiles = listOf("s0.bin" to 101, "s1.bin" to 202, "s2.bin" to 303)
    private val smallSize = 256 * 1024L
    private val bigFile = "big.bin"
    private val bigSeed = 999
    private val bigSize = 64L * 1024 * 1024
    private val killAfterBytes = 8L * 1024 * 1024
    private val srcDir = "death-test-src"

    private fun destRoot(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "death-test-dest")

    @Test
    fun deathPhase() {
        when (phase) {
            "die" -> die()
            "resume" -> resume()
            else -> throw IllegalArgumentException("unknown deathPhase '$phase'")
        }
    }

    // ---- phase 1 ----

    private fun die() {
        val bridge = BridgeClient(context.contentResolver, target)
        val info = bridge.ping(target)
        println("DEATH-DIAG die: bridge OK package=${info.packageName} user=${info.userId}")
        if (expectedUser >= 0) assertEquals(expectedUser, info.userId)

        // 1. Build the source tree inside the proxy's DATA root through the bridge — the same
        //    write path the product uses, so the bytes really live in FUSE-attributed storage.
        bridge.mkdirs(StorageRoot.DATA, srcDir)
        for ((name, seed) in smallFiles) {
            writeThroughBridge(bridge, "$srcDir/$name", seed, smallSize)
        }
        writeThroughBridge(bridge, "$srcDir/$bigFile", bigSeed, bigSize)
        println("DEATH-DIAG die: source tree written (${smallFiles.size} small + ${bigSize / (1024 * 1024)} MB)")

        // Proof, at death time and from the proxy's own mouth, that the tree exists — so the
        // resume phase's view can be compared against a recorded fact rather than a memory.
        val preDeathStat = runCatching { bridge.statPath(StorageRoot.DATA, srcDir) }
        println("DEATH-DIAG die: source statPath pre-death -> " +
            (preDeathStat.getOrNull() ?: preDeathStat.exceptionOrNull()))
        val preDeathList = runCatching { bridge.list(StorageRoot.DATA, srcDir).map { "${it.name}=${it.sizeBytes}" } }
        println("DEATH-DIAG die: source list pre-death -> " +
            (preDeathList.getOrNull() ?: preDeathList.exceptionOrNull()))

        // 2. A clean destination, and the journal entry a real pull would write.
        destRoot().deleteRecursively()
        val dest = FileDestination(destRoot())
        val journal = TransferJournal(context)
        journal.begin(
            direction = Direction.PULL,
            root = StorageRoot.DATA,
            relativePath = srcDir,
            targetPackage = target,
            userId = if (expectedUser >= 0) expectedUser else info.userId,
            destinationUri = android.net.Uri.fromFile(destRoot()).toString(),
            destinationLabel = destRoot().name,
        )

        // 3. Pull, journaling like the ViewModel does, and die mid-flight. The kill condition
        //    is checked INSIDE the progress callback, which fires per 1 MiB chunk, so there is
        //    no race with anything external: the moment the big file passes the threshold the
        //    process ends. If the transfer somehow completes first (a very fast pipe and an
        //    unlucky walk order), the fallback still dies with the journal RUNNING and before
        //    any terminal write — the resume phase's assertions hold either way, and the
        //    mtime-skip proof becomes even stronger (every file must survive untouched).
        val engine = TransferEngine(bridge, dest)
        var lastJournaled = -1
        var killed = false
        fun dieNow(why: String): Nothing {
            killed = true
            println("DEATH-DIAG die: $why — SIGKILL self, journal left RUNNING on purpose")
            System.out.flush()
            // Let the last SharedPreferences.apply() reach the disk. begin() flushed seconds
            // ago (the 64 MB write); this covers the most recent progress update.
            Thread.sleep(500)
            Process.killProcess(Process.myPid())
            throw IllegalStateException("unreachable")
        }

        val result = runCatching {
            runBlocking {
                engine.pull(StorageRoot.DATA, srcDir) { p ->
                    if (p.filesDone != lastJournaled) {
                        lastJournaled = p.filesDone
                        journal.update(p)
                    }
                    if (!killed && p.currentPath.endsWith(bigFile) &&
                        p.currentFileBytes >= killAfterBytes
                    ) {
                        dieNow("mid-copy of $bigFile at ${p.currentFileBytes} bytes, " +
                            "${p.filesDone} file(s) already complete")
                    }
                }
            }
        }
        // A pull that THREW is a real failure (bridge dead, path refused) — fail loudly here
        // rather than dying with a journal entry that the resume phase would then chase.
        result.exceptionOrNull()?.let { e ->
            throw AssertionError("die phase: the pull threw before any kill window: $e", e)
        }
        // Only reachable when the pull finished before the kill window (see fallback note).
        println("DEATH-DIAG die: pull completed before the kill window " +
            "(result=${result.getOrNull()}) — dying anyway, journal deliberately left RUNNING")
        dieNow("fallback: post-completion, pre-terminal-write")
    }

    // ---- phase 2 ----

    private fun resume() {
        // 1. The journal is the only channel between the two processes.
        val journal = TransferJournal(context)
        val entry = journal.unfinished()
        assertNotNull(
            entry,
            "no RUNNING journal entry survived the kill — either the die phase never began " +
                "its transfer, or the record did not reach the disk. The script checks the " +
                "on-disk XML between phases, so a failure here means the two disagree.",
        )
        println("DEATH-DIAG resume: found journal entry dir=${entry.direction} root=${entry.root} " +
            "path=${entry.relativePath} target=${entry.targetPackage} user=${entry.userId} " +
            "filesDone=${entry.filesDone}/${entry.filesTotal} bytesDone=${entry.bytesDone}")
        assertEquals(Direction.PULL, entry.direction)
        assertEquals(StorageRoot.DATA, entry.root)
        assertEquals(srcDir, entry.relativePath)
        assertEquals(target, entry.targetPackage)

        // 2. What the dead process had already finished. Sizes are the engine's resume oracle;
        //    mtimes are the proof that skipping really happened.
        val dest = FileDestination(destRoot())
        val completedBefore = (smallFiles.map { it.first } + bigFile)
            .map { it to File(destRoot(), it) }
            .filter { it.second.isFile && it.second.length() > 0 }
        val mtimesBefore = completedBefore.associate { (name, f) -> name to f.lastModified() }
        println("DEATH-DIAG resume: files already on disk: " +
            completedBefore.joinToString { "${it.first}=${it.second.length()}B@${it.second.lastModified()}" })

        // 3. Re-run the pull exactly as resumeTransfer() would: same root, same path, same
        //    destination, journal updated and terminated. Plan first and LOUDLY: an empty
        //    plan makes pull() "succeed" without moving a byte, which surfaces as a
        //    per-file-missing assertion far from the cause (run #55's exact failure shape —
        //    four jobs, identical). The diagnostics below distinguish "tree absent",
        //    "tree denied" and "tree listed empty" in one run instead of three.
        val bridge = BridgeClient(context.contentResolver, target)
        val engine = TransferEngine(bridge, dest)
        val plan = runBlocking { engine.planPull(entry.root, entry.relativePath) }
        println("DEATH-DIAG resume: planned ${plan.fileCount} file(s), ${plan.totalBytes} bytes: " +
            plan.files.joinToString { "${it.relativePath}=${it.sizeBytes}" })
        if (plan.fileCount == 0) {
            val ping = runCatching { bridge.ping(target) }
            println("DEATH-DIAG resume: ping -> " +
                (ping.getOrNull() ?: "${ping.exceptionOrNull()?.javaClass?.name}: ${ping.exceptionOrNull()?.message}"))
            val rootList = runCatching { bridge.list(entry.root, "").map { it.name } }
            println("DEATH-DIAG resume: list(root) -> " +
                (rootList.getOrNull() ?: "${rootList.exceptionOrNull()?.javaClass?.name}: ${rootList.exceptionOrNull()?.message}"))
            val dirList = runCatching { bridge.list(entry.root, entry.relativePath).map { it.name } }
            println("DEATH-DIAG resume: list(${entry.relativePath}) -> " +
                (dirList.getOrNull() ?: "${dirList.exceptionOrNull()?.javaClass?.name}: ${dirList.exceptionOrNull()?.message}"))
            val st = runCatching { bridge.statPath(entry.root, entry.relativePath) }
            println("DEATH-DIAG resume: statPath(${entry.relativePath}) -> " +
                (st.getOrNull() ?: "${st.exceptionOrNull()?.javaClass?.name}: ${st.exceptionOrNull()?.message}"))
            val stBig = runCatching { bridge.statPath(entry.root, "$entry.relativePath/$bigFile") }
            println("DEATH-DIAG resume: statPath($bigFile) -> " +
                (stBig.getOrNull() ?: "${stBig.exceptionOrNull()?.javaClass?.name}: ${stBig.exceptionOrNull()?.message}"))
        }
        assertTrue(
            plan.fileCount > 0,
            "the source tree the die phase wrote — and proved present through the bridge " +
                "immediately before dying — is invisible to the resumed process. The " +
                "DEATH-DIAG lines above and the script's raw-filesystem ls say whether it is " +
                "absent, denied, or listed empty.",
        )
        var lastJournaled = -1
        val result = runBlocking {
            engine.pull(entry.root, entry.relativePath) { p ->
                if (p.filesDone != lastJournaled) {
                    lastJournaled = p.filesDone
                    journal.update(p)
                }
            }
        }
        journal.finish(if (result.isSuccess) TransferJournal.Outcome.DONE else TransferJournal.Outcome.FAILED)
        assertTrue(
            result.isSuccess,
            "the resumed pull failed: ${result.failed.joinToString { "${it.path}: ${it.reason}" }}",
        )

        // 4. Every file byte-exact against the deterministic generator.
        val expected = smallFiles.map { (name, seed) -> Triple(name, smallSize, seed) } +
            Triple(bigFile, bigSize, bigSeed)
        for ((name, size, seed) in expected) {
            val f = File(destRoot(), name)
            assertTrue(f.isFile, "$name missing after resume")
            assertEquals(size, f.length(), "$name has the wrong size after resume")
            val actual = f.inputStream().use { input ->
                val md = MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }
            assertEquals(patternDigest(seed, size), actual, "$name content diverged after resume")
        }

        // 5. No .part survivor.
        val parts = destRoot().walkTopDown().filter { it.name.endsWith(".part") }.toList()
        assertTrue(parts.isEmpty(), "stale .part files survived a completed resume: $parts")

        // 6. The skip proof: files that were COMPLETE when the process died must not have been
        //    rewritten — same size, same mtime. (The big file may legitimately have a new
        //    mtime: dying mid-copy means it was re-pulled, which is the documented behaviour.)
        for ((name, mtime) in mtimesBefore) {
            val f = File(destRoot(), name)
            if (name != bigFile || f.lastModified() == mtime) {
                // small files: always skipped when they completed pre-death. big: skipped only
                // in the fallback case where it completed pre-death — and then its mtime is
                // unchanged too, which is exactly what this asserts.
                assertEquals(mtime, f.lastModified(), "$name was re-copied despite being complete")
            }
        }

        // 7. Terminal: the offer must not reappear.
        assertNull(journal.unfinished(), "the journal still reads RUNNING after a finished resume")
        assertEquals(TransferJournal.Outcome.DONE, journal.peek()?.outcome)
        println("DEATH-DIAG resume: PROCESS-DEATH RECOVERY VERIFIED — ${expected.size} files " +
            "byte-exact, ${mtimesBefore.count { it.key != bigFile }} skip-proven by mtime, journal DONE")
    }

    // ---- helpers ----

    private fun writeThroughBridge(bridge: BridgeClient, path: String, seed: Int, size: Long) {
        bridge.openFile(StorageRoot.DATA, path, "w").use { pfd ->
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
                writePattern(seed, size, out)
            }
        }
    }

    /** Deterministic content so the resume phase can recompute digests without keeping bytes. */
    private fun writePattern(seed: Int, size: Long, out: OutputStream) {
        var x = seed.toLong() or 1L
        val buf = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            for (i in buf.indices) {
                x = x xor (x shl 13)
                x = x xor (x ushr 7)
                x = x xor (x shl 17)
                buf[i] = (x and 0xFF).toByte()
            }
            val n = minOf(buf.size.toLong(), left).toInt()
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun patternDigest(seed: Int, size: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        writePattern(seed, size, object : OutputStream() {
            override fun write(b: Int) = md.update(b.toByte())
            override fun write(b: ByteArray, off: Int, len: Int) = md.update(b, off, len)
        })
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * File-backed [TransferDestination], mirroring the JVM suite's FakeDestination contract:
     * truncating writes, -1 for absent sizes, traversal refused. App-specific external storage
     * survives process death and needs no SAF grant, which is exactly what this test requires.
     */
    private class FileDestination(private val root: File) : TransferDestination {

        private fun file(relativePath: String): File {
            require(!relativePath.startsWith("/")) { "path must be relative: $relativePath" }
            for (segment in relativePath.split('/')) {
                require(segment != ".." && segment != ".") { "path traversal in '$relativePath'" }
            }
            val f = File(root, relativePath)
            require(f.canonicalPath.startsWith(root.canonicalPath)) {
                "'$relativePath' escaped the destination root"
            }
            return f
        }

        override fun openWrite(relativePath: String): ParcelFileDescriptor {
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
            val f = file(relativePath)
            if (!f.isFile) throw FileNotFoundException("no such file: '$relativePath'")
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun sizeOf(relativePath: String): Long {
            val f = file(relativePath)
            return if (f.isFile) f.length() else -1
        }

        override fun delete(relativePath: String): Boolean {
            val f = file(relativePath)
            return !f.exists() || f.delete()
        }

        override fun exists(relativePath: String): Boolean = file(relativePath).isFile
    }
}
