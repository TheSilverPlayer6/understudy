package dev.understudy.transfer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.understudy.core.model.StorageRoot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The persistence half of process-death recovery (HANDOFF §3.6).
 *
 * The engine's restart behaviour is already pinned by [TransferEngineTest] (completed files are
 * skipped by size, stale `.part` files are deleted). What those tests cannot express is the
 * part that lives *outside* the process: that a transfer which died with its process leaves a
 * record the next process can read, and that a transfer which ended *deliberately* — cancelled,
 * failed, done — leaves no resume offer behind. The distinction is the whole feature: showing
 * "resume?" after the user pressed cancel is a lie, and NOT showing it after the platform
 * killed the app is the bug this journal exists to fix.
 *
 * "Process death" is simulated the only honest way available on the JVM: a second
 * [TransferJournal] instance over the same [Context]. The class holds nothing in memory — every
 * read goes to SharedPreferences — so a fresh instance reading a RUNNING entry is exactly what
 * a relaunched app does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferJournalTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private var now = 1_000L
    private fun journal() = TransferJournal(context, clock = { now })

    private fun beginPull(j: TransferJournal) = j.begin(
        direction = Direction.PULL,
        root = StorageRoot.DATA,
        relativePath = "files/saves",
        targetPackage = "com.example.targetgame",
        userId = 10,
        destinationUri = "content://com.android.externalstorage.documents/tree/primary%3ABackups",
        destinationLabel = "Backups",
    )

    private fun progress(done: Int, total: Int, bytesDone: Long, bytesTotal: Long) =
        TransferProgress(
            direction = Direction.PULL,
            state = State.RUNNING,
            currentPath = "files/saves/chunk$done",
            filesTotal = total,
            filesDone = done,
            bytesTotal = bytesTotal,
            bytesDone = bytesDone,
        )

    @Test
    fun `an interrupted transfer is offered for resume after a simulated process death`() {
        val first = journal()
        beginPull(first)
        now = 2_000L
        first.update(progress(done = 7, total = 20, bytesDone = 700, bytesTotal = 2_000))

        // "The process died": no finish() ever ran. A brand-new instance must see it.
        val reborn = journal()
        val entry = reborn.unfinished()
        assertNotNull(entry, "a RUNNING entry must survive the death of the process that wrote it")
        assertTrue(entry.interrupted)
        assertEquals(Direction.PULL, entry.direction)
        assertEquals(StorageRoot.DATA, entry.root)
        assertEquals("files/saves", entry.relativePath)
        assertEquals("com.example.targetgame", entry.targetPackage)
        assertEquals(10, entry.userId)
        assertEquals("Backups", entry.destinationLabel)
        assertEquals(7, entry.filesDone)
        assertEquals(20, entry.filesTotal)
        assertEquals(700L, entry.bytesDone)
        assertEquals(2_000L, entry.bytesTotal)
        assertEquals(1_000L, entry.startedAtEpochMillis)
        assertEquals(2_000L, entry.updatedAtEpochMillis)
    }

    @Test
    fun `every deliberate outcome is terminal and never offered for resume`() {
        for (outcome in listOf(
            TransferJournal.Outcome.DONE,
            TransferJournal.Outcome.FAILED,
            TransferJournal.Outcome.CANCELLED,
        )) {
            val j = journal()
            beginPull(j)
            j.update(progress(1, 3, 10, 30))
            now += 500
            j.finish(outcome)

            val reborn = journal()
            assertNull(
                reborn.unfinished(),
                "$outcome ended deliberately; a resume offer after it would be a lie",
            )
            assertEquals(outcome, reborn.peek()?.outcome)
        }
    }

    @Test
    fun `a late progress callback cannot resurrect a finished entry as RUNNING`() {
        // The engine's copy loop can emit one more chunk callback after cancellation was
        // requested; if that write landed after finish(), the next launch would offer a
        // resume for a transfer the user explicitly stopped.
        val j = journal()
        beginPull(j)
        j.finish(TransferJournal.Outcome.CANCELLED)
        now += 100
        j.update(progress(99, 99, 999, 999))

        assertEquals(TransferJournal.Outcome.CANCELLED, journal().peek()?.outcome)
        assertNull(journal().unfinished())
    }

    @Test
    fun `finish without a begun transfer is a no-op, not a crash`() {
        val j = journal()
        j.finish(TransferJournal.Outcome.DONE)
        j.update(progress(1, 1, 1, 1))
        assertNull(j.peek())
    }

    @Test
    fun `a new transfer replaces the previous record, including a finished one`() {
        val j = journal()
        beginPull(j)
        j.finish(TransferJournal.Outcome.DONE)

        now = 5_000L
        j.begin(
            direction = Direction.PUSH,
            root = StorageRoot.OBB,
            relativePath = "",
            targetPackage = "com.example.othergame",
            userId = 11,
            destinationUri = "content://x/tree/y",
            destinationLabel = "Restore",
        )

        val entry = journal().peek()
        assertNotNull(entry)
        assertEquals(Direction.PUSH, entry.direction)
        assertEquals(StorageRoot.OBB, entry.root)
        assertEquals("", entry.relativePath)
        assertEquals("com.example.othergame", entry.targetPackage)
        assertEquals(11, entry.userId)
        assertEquals(5_000L, entry.startedAtEpochMillis)
        assertTrue(entry.interrupted)
    }

    @Test
    fun `clear removes the record entirely`() {
        val j = journal()
        beginPull(j)
        j.clear()
        assertNull(journal().peek())
        assertNull(journal().unfinished())
    }

    @Test
    fun `a corrupt record reads as absent instead of crashing startup`() {
        // Half-written values or an enum that a future version renamed must cost at most the
        // resume offer. An exception escaping peek() would run on the construction path of the
        // ViewModel — i.e. boot-loop the app over a stale preference file.
        beginPull(journal())
        val prefs = context.getSharedPreferences("transfer-journal", Context.MODE_PRIVATE)
        prefs.edit().putString("direction", "SIDEWAYS").putString("outcome", "EXPLODED").apply()

        val j = journal()
        assertNull(j.peek())
        assertNull(j.unfinished())
        // And the journal is still usable afterwards.
        beginPull(j)
        assertNotNull(journal().unfinished())
    }

    @Test
    fun `an empty journal reads as absent`() {
        assertNull(journal().peek())
        assertNull(journal().unfinished())
    }

    @Test
    fun `update before begin does not fabricate an entry`() {
        val j = journal()
        j.update(progress(3, 3, 30, 30))
        assertNull(j.peek())
    }

    @Test
    fun `the destination URI round-trips byte for byte`() {
        // The resume path re-grants SAF access from this exact string; a lossy round-trip
        // (encoding, trimming) would strand the offer with an unrestorable destination.
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdata/document/primary%3AAndroid%2Fdata"
        val j = journal()
        j.begin(
            direction = Direction.PULL,
            root = StorageRoot.DATA,
            relativePath = "",
            targetPackage = "com.example.targetgame",
            userId = 0,
            destinationUri = uri,
            destinationLabel = "",
        )
        assertEquals(uri, journal().peek()?.destinationUri)
    }

    @Test
    fun `the clock is only consulted through the injected source`() {
        // Guards the tests themselves: if the class secretly used System.currentTimeMillis(),
        // the timestamp assertions above would be flaky rather than wrong, which is worse.
        var calls = 0
        val j = TransferJournal(context, clock = { calls++; 42L })
        j.begin(
            direction = Direction.PULL,
            root = StorageRoot.DATA,
            relativePath = "",
            targetPackage = "p",
            userId = 0,
            destinationUri = "u",
            destinationLabel = "",
        )
        assertTrue(calls > 0)
        assertEquals(42L, j.peek()?.startedAtEpochMillis)
    }
}
