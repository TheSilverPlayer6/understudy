package dev.understudy.transfer

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.understudy.core.model.StorageRoot

/**
 * The durable record of the transfer in flight, and the answer to "the process died mid-pull —
 * what was happening, and what can the user do about it?".
 *
 * [TransferEngine] is already *restartable* by construction: a pull skips destination files
 * whose size matches the plan and deletes stale `.part` files before retrying, so re-running
 * the same pull after any interruption resumes it cheaply. What the engine cannot do is
 * remember — it lives and dies with the process. Without a journal, an app killed by the
 * platform during a multi-gigabyte pull restarts into an UI that has no idea a transfer ever
 * existed: the user's half-finished backup is invisible, and so is the one action (resume)
 * that makes finishing it cheap. That is HANDOFF §3.6, and this class is the persistence half
 * of closing it; [dev.understudy.ui.MainViewModel.resumeTransfer] is the other half.
 *
 * Deliberate design choices:
 *
 *  - **One entry, not a history.** The app runs exactly one transfer at a time
 *    ([dev.understudy.ui.MainViewModel] holds a single `transferJob`), and the recovery
 *    question is only ever "was the last one interrupted?". A list would be a UI promise
 *    nobody asked for.
 *  - **[Outcome.RUNNING] is the interrupted state.** It is written at [begin] and replaced
 *    only by a *deliberate* terminal write ([finish]). Process death — the case that matters
 *    — cannot run cleanup code, so "still RUNNING" is exactly its fingerprint. A graceful
 *    coroutine cancellation DOES run its handler and records CANCELLED, which is why an
 *    interrupted entry is never offered after the user cancelled or reset on purpose.
 *  - **Per-file granularity.** Callers update on file-completion boundaries, not per chunk:
 *    SharedPreferences writes are cheap but not free, and resume granularity is per-file
 *    anyway (the engine re-pulls any file that did not reach its final size).
 *  - **Corrupt records read as absent.** A half-written or schema-drifted entry must never
 *    crash app startup — losing the resume offer is recoverable; a boot loop is not.
 */
class TransferJournal(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    enum class Outcome { RUNNING, DONE, FAILED, CANCELLED }

    data class Entry(
        val direction: Direction,
        val root: StorageRoot,
        /** Path inside the root the transfer was copying, i.e. what to re-issue. */
        val relativePath: String,
        val targetPackage: String,
        val userId: Int,
        /** SAF tree the bytes were going to (pull) or coming from (push), as persisted by the grant. */
        val destinationUri: String,
        val destinationLabel: String,
        val startedAtEpochMillis: Long,
        val updatedAtEpochMillis: Long,
        val filesTotal: Int,
        val bytesTotal: Long,
        val filesDone: Int,
        val bytesDone: Long,
        val outcome: Outcome,
    ) {
        /** True when no terminal outcome was ever recorded: the process died mid-transfer. */
        val interrupted: Boolean get() = outcome == Outcome.RUNNING
    }

    private val store: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Records a transfer starting. Any previous entry is replaced — see the class doc. */
    fun begin(
        direction: Direction,
        root: StorageRoot,
        relativePath: String,
        targetPackage: String,
        userId: Int,
        destinationUri: String,
        destinationLabel: String,
    ): Entry {
        val now = clock()
        val entry = Entry(
            direction = direction,
            root = root,
            relativePath = relativePath,
            targetPackage = targetPackage,
            userId = userId,
            destinationUri = destinationUri,
            destinationLabel = destinationLabel,
            startedAtEpochMillis = now,
            updatedAtEpochMillis = now,
            filesTotal = 0,
            bytesTotal = 0,
            filesDone = 0,
            bytesDone = 0,
            outcome = Outcome.RUNNING,
        )
        write(entry)
        return entry
    }

    /**
     * Folds a progress snapshot into the recorded entry. A no-op when nothing was begun or
     * the entry already reached a terminal outcome — a late progress callback from a cancelled
     * transfer must not resurrect it as RUNNING.
     */
    fun update(progress: TransferProgress) {
        val current = peek() ?: return
        if (!current.interrupted) return
        write(
            current.copy(
                filesTotal = progress.filesTotal,
                bytesTotal = progress.bytesTotal,
                filesDone = progress.filesDone,
                bytesDone = progress.bytesDone,
                updatedAtEpochMillis = clock(),
            )
        )
    }

    /** Records why the transfer stopped. Terminal: [update] will not move it after this. */
    fun finish(outcome: Outcome) {
        val current = peek() ?: return
        if (!current.interrupted) return
        write(current.copy(outcome = outcome, updatedAtEpochMillis = clock()))
    }

    /** The last recorded entry, or null when there is none (or it cannot be parsed). */
    fun peek(): Entry? = runCatching {
        if (!store.contains(KEY_OUTCOME)) return@runCatching null
        Entry(
            direction = Direction.valueOf(store.getString(KEY_DIRECTION, null)!!),
            root = StorageRoot.valueOf(store.getString(KEY_ROOT, null)!!),
            relativePath = store.getString(KEY_PATH, null)!!,
            targetPackage = store.getString(KEY_TARGET, null)!!,
            userId = store.getInt(KEY_USER, -1),
            destinationUri = store.getString(KEY_DEST_URI, null)!!,
            destinationLabel = store.getString(KEY_DEST_LABEL, "")!!,
            startedAtEpochMillis = store.getLong(KEY_STARTED, 0L),
            updatedAtEpochMillis = store.getLong(KEY_UPDATED, 0L),
            filesTotal = store.getInt(KEY_FILES_TOTAL, 0),
            bytesTotal = store.getLong(KEY_BYTES_TOTAL, 0L),
            filesDone = store.getInt(KEY_FILES_DONE, 0),
            bytesDone = store.getLong(KEY_BYTES_DONE, 0L),
            outcome = Outcome.valueOf(store.getString(KEY_OUTCOME, null)!!),
        )
    }.getOrNull()

    /** The entry to offer for resume after a restart, i.e. one the process never finished. */
    fun unfinished(): Entry? = peek()?.takeIf { it.interrupted }

    /** Drops the record entirely (the user dismissed the resume offer). */
    fun clear() = store.edit { clear() }

    private fun write(entry: Entry) = store.edit {
        putString(KEY_DIRECTION, entry.direction.name)
        putString(KEY_ROOT, entry.root.name)
        putString(KEY_PATH, entry.relativePath)
        putString(KEY_TARGET, entry.targetPackage)
        putInt(KEY_USER, entry.userId)
        putString(KEY_DEST_URI, entry.destinationUri)
        putString(KEY_DEST_LABEL, entry.destinationLabel)
        putLong(KEY_STARTED, entry.startedAtEpochMillis)
        putLong(KEY_UPDATED, entry.updatedAtEpochMillis)
        putInt(KEY_FILES_TOTAL, entry.filesTotal)
        putLong(KEY_BYTES_TOTAL, entry.bytesTotal)
        putInt(KEY_FILES_DONE, entry.filesDone)
        putLong(KEY_BYTES_DONE, entry.bytesDone)
        putString(KEY_OUTCOME, entry.outcome.name)
    }

    private companion object {
        const val FILE = "transfer-journal"
        const val KEY_DIRECTION = "direction"
        const val KEY_ROOT = "root"
        const val KEY_PATH = "path"
        const val KEY_TARGET = "target"
        const val KEY_USER = "user"
        const val KEY_DEST_URI = "dest_uri"
        const val KEY_DEST_LABEL = "dest_label"
        const val KEY_STARTED = "started"
        const val KEY_UPDATED = "updated"
        const val KEY_FILES_TOTAL = "files_total"
        const val KEY_BYTES_TOTAL = "bytes_total"
        const val KEY_FILES_DONE = "files_done"
        const val KEY_BYTES_DONE = "bytes_done"
        const val KEY_OUTCOME = "outcome"
    }
}
