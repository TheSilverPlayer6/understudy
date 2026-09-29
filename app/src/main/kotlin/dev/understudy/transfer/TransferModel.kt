package dev.understudy.transfer

import dev.understudy.core.model.StorageRoot

/** What is happening right now, for the progress UI. */
data class TransferProgress(
    val direction: Direction,
    val state: State,
    /** Current file's path relative to its root. */
    val currentPath: String = "",
    val filesTotal: Int = 0,
    val filesDone: Int = 0,
    val bytesTotal: Long = 0,
    val bytesDone: Long = 0,
    /** Bytes moved for the file in flight; resets per file. */
    val currentFileBytes: Long = 0,
    val failures: List<TransferFailure> = emptyList(),
) {
    val fractionComplete: Float
        get() = if (bytesTotal <= 0) {
            if (filesTotal == 0) 0f else filesDone.toFloat() / filesTotal
        } else {
            ((bytesDone + currentFileBytes).toFloat() / bytesTotal).coerceIn(0f, 1f)
        }

    val isRunning: Boolean get() = state == State.RUNNING || state == State.SCANNING
}

enum class Direction { PULL, PUSH }

enum class State { IDLE, SCANNING, RUNNING, CANCELLING, DONE, FAILED, CANCELLED }

data class TransferFailure(
    val path: String,
    val reason: String,
    val fatal: Boolean = false,
)

/**
 * A planned transfer: the file set was resolved up front so the UI can show a real total
 * before anything moves.
 */
data class TransferPlan(
    val direction: Direction,
    val root: StorageRoot,
    val files: List<PlannedFile>,
    val totalBytes: Long,
) {
    val fileCount: Int get() = files.size
}

data class PlannedFile(
    /** Path relative to the storage root, slash-separated. */
    val relativePath: String,
    val sizeBytes: Long,
)
