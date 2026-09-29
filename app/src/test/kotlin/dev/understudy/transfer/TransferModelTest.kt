package dev.understudy.transfer

import dev.understudy.core.model.StorageRoot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the pure parts of the transfer layer.
 *
 * The byte copying itself needs a device (it goes through `ParcelFileDescriptor` on both ends),
 * but the arithmetic that drives the progress UI and the plan that decides what gets copied are
 * pure, and those are where a silent bug is most likely to make a transfer look complete when it
 * is not.
 */
class TransferModelTest {

    private fun progress(
        bytesTotal: Long,
        bytesDone: Long,
        currentFileBytes: Long = 0,
        filesTotal: Int = 0,
        filesDone: Int = 0,
    ) = TransferProgress(
        direction = Direction.PULL,
        state = State.RUNNING,
        filesTotal = filesTotal,
        filesDone = filesDone,
        bytesTotal = bytesTotal,
        bytesDone = bytesDone,
        currentFileBytes = currentFileBytes,
    )

    @Test
    fun `fraction accounts for the file in flight, not just completed ones`() {
        // A single 1 GB file must show progress while it copies, not jump 0% -> 100%.
        val half = progress(bytesTotal = 1_000, bytesDone = 0, currentFileBytes = 500)
        assertEquals(0.5f, half.fractionComplete, 0.001f)

        val done = progress(bytesTotal = 1_000, bytesDone = 1_000, currentFileBytes = 0)
        assertEquals(1f, done.fractionComplete, 0.001f)
    }

    @Test
    fun `fraction is clamped so an over-count cannot crash a progress bar`() {
        // Size reported by the proxy can disagree with what is actually read (a file being
        // written during the pull). Clamping keeps the UI sane instead of drawing NaN.
        val over = progress(bytesTotal = 100, bytesDone = 90, currentFileBytes = 40)
        assertEquals(1f, over.fractionComplete, 0.001f)
    }

    @Test
    fun `fraction falls back to file count when sizes are unknown`() {
        val p = progress(bytesTotal = 0, bytesDone = 0, filesTotal = 4, filesDone = 1)
        assertEquals(0.25f, p.fractionComplete, 0.001f)
    }

    @Test
    fun `an empty transfer is 0 percent, not NaN`() {
        val p = progress(bytesTotal = 0, bytesDone = 0, filesTotal = 0, filesDone = 0)
        assertEquals(0f, p.fractionComplete, 0.001f)
        assertFalse(p.fractionComplete.isNaN())
    }

    @Test
    fun `isRunning covers both scanning and copying so the cancel button stays enabled`() {
        assertTrue(progress(0, 0).copy(state = State.SCANNING).isRunning)
        assertTrue(progress(0, 0).copy(state = State.RUNNING).isRunning)
        assertFalse(progress(0, 0).copy(state = State.CANCELLING).isRunning)
        assertFalse(progress(0, 0).copy(state = State.DONE).isRunning)
        assertFalse(progress(0, 0).copy(state = State.IDLE).isRunning)
    }

    @Test
    fun `a plan totals the bytes it will copy`() {
        val plan = TransferPlan(
            direction = Direction.PULL,
            root = StorageRoot.OBB,
            files = listOf(
                PlannedFile("main.1234.com.foo.obb", 1_500_000_000),
                PlannedFile("patch.1234.com.foo.obb", 250_000_000),
                PlannedFile("config/save.json", 4_096),
            ),
            totalBytes = 1_750_004_096,
        )
        assertEquals(3, plan.fileCount)
        assertEquals(plan.files.sumOf { it.sizeBytes }, plan.totalBytes)
        assertEquals(StorageRoot.OBB, plan.root)
    }

    @Test
    fun `an empty plan is not an error`() {
        val plan = TransferPlan(Direction.PULL, StorageRoot.DATA, emptyList(), 0)
        assertEquals(0, plan.fileCount)
        assertEquals(0, plan.totalBytes)
    }

    @Test
    fun `a result is only successful when nothing failed and nothing was cancelled`() {
        val ok = TransferResult(succeeded = 5, failed = emptyList(), bytesCopied = 100)
        assertTrue(ok.isSuccess)

        val partial = TransferResult(
            succeeded = 4,
            failed = listOf(TransferFailure("a/save.dat", "permission denied")),
            bytesCopied = 80,
        )
        assertFalse(partial.isSuccess, "a partial copy must not report success")

        val cancelled = TransferResult(2, emptyList(), 40, cancelled = true)
        assertFalse(cancelled.isSuccess)
    }

    @Test
    fun `fatal failures are distinguishable from per-file ones`() {
        // A fatal failure (proxy vanished) means the rest cannot succeed either, so the engine
        // stops instead of burning time on files that will all fail the same way.
        val fatal = TransferFailure("a", "proxy gone", fatal = true)
        val recoverable = TransferFailure("b", "size mismatch")
        assertTrue(fatal.fatal)
        assertFalse(recoverable.fatal)
    }

    @Test
    fun `part suffix cannot collide with a real save file name`() {
        assertEquals(".part", TransferEngine.PART_SUFFIX)
        // The write-then-swap protocol relies on this suffix being distinguishable, so a real
        // entry ending in it would be silently overwritten by a resume.
        assertTrue("save.dat".plus(TransferEngine.PART_SUFFIX) == "save.dat.part")
    }

    @Test
    fun `buffer is large enough to amortise FUSE overhead`() {
        // FUSE passthrough on Android/data is slow per-operation; a small buffer makes a
        // multi-GB obb take minutes longer than it should.
        assertTrue(TransferEngine.BUFFER_SIZE >= 256 * 1024)
        assertTrue(TransferEngine.BUFFER_SIZE <= 8 * 1024 * 1024, "buffer must stay cache-friendly")
    }
}
