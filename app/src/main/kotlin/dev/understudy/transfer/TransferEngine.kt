package dev.understudy.transfer

import android.util.Log
import dev.understudy.bridge.BridgeClient
import dev.understudy.bridge.BridgeError
import dev.understudy.core.model.StorageRoot
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import android.os.ParcelFileDescriptor

/**
 * Moves bytes between the proxy's private storage and a SAF destination.
 *
 * Design rules, all of which exist because the alternative loses someone's save game:
 *
 *  - **Stream, never buffer.** Both directions copy through a fixed byte array. A 4 GB `.obb`
 *    must not require 4 GB of RAM, and must not require 4 GB of free space in a temp file.
 *  - **Write-then-swap.** A pull writes to `<name>.part` and only promotes it once the byte
 *    count matches what the proxy reported. An interrupted *download* therefore leaves the
 *    previous good copy intact rather than a truncated one.
 *
 *    Stated precisely, because the guarantee has a hole and pretending otherwise is how a user
 *    loses data: SAF has no atomic rename, so promotion is a second pass over the bytes straight
 *    onto the final name, which truncates it immediately. Dying *during promotion* leaves a
 *    truncated final file. What still holds — and what `TransferEngineTest` pins — is that the
 *    truncated file is never mistaken for a good one: its size no longer matches what the proxy
 *    reported, so the next run re-pulls it, and the `.part` is deleted before every attempt so a
 *    stale one cannot be promoted. Recovery is guaranteed; atomicity is not, and cannot be.
 *  - **A failure on one file does not abort the rest**, unless it is fatal (proxy gone,
 *    destination gone). The user gets a list at the end rather than a half-copied tree and no
 *    explanation.
 *  - **Cancellation is cooperative and checked between chunks**, so it takes effect within one
 *    buffer rather than at an arbitrary point.
 */
class TransferEngine(
    private val bridge: BridgeClient,
    private val destination: TransferDestination,
    /**
     * When false (the default) a pull skips any destination file that already has the expected
     * size, making interrupted transfers resumable.
     */
    private val overwrite: Boolean = false,
) {

    /**
     * Copies the whole subtree at [relativePath] out of the proxy into the destination.
     *
     * @param onProgress invoked after each chunk; keep it cheap, it runs on the I/O thread
     */
    suspend fun pull(
        root: StorageRoot,
        relativePath: String = "",
        onProgress: (TransferProgress) -> Unit = {},
    ): TransferResult {
        val plan = planPull(root, relativePath)
        return run(plan, onProgress) { file, emit ->
            pullOne(root, file, emit)
        }
    }

    /**
     * Copies a local tree *into* the proxy.
     *
     * [sources] maps a destination-relative path to an opener for its bytes. Callers supply
     * this from a SAF tree walk; taking a lambda rather than a URI keeps this class free of
     * DocumentFile traversal and makes it unit-testable.
     */
    suspend fun push(
        root: StorageRoot,
        sources: List<PushSource>,
        onProgress: (TransferProgress) -> Unit = {},
    ): TransferResult {
        val plan = TransferPlan(
            direction = Direction.PUSH,
            root = root,
            files = sources.map { PlannedFile(it.relativePath, it.sizeBytes) },
            totalBytes = sources.sumOf { it.sizeBytes },
        )
        val byPath = sources.associateBy { it.relativePath }
        return run(plan, onProgress) { file, emit ->
            val source = byPath[file.relativePath]
                ?: throw IllegalStateException("no source for ${file.relativePath}")
            pushOne(root, source, emit)
        }
    }

    // ---- planning ----------------------------------------------------------

    /**
     * Walks the proxy's tree to produce a file list and byte total.
     *
     * Done before any copying so the progress bar has a real denominator, and so the user can
     * be warned when the destination is too small.
     */
    suspend fun planPull(root: StorageRoot, relativePath: String = ""): TransferPlan {
        val files = ArrayList<PlannedFile>()
        walk(root, relativePath, files)
        return TransferPlan(
            direction = Direction.PULL,
            root = root,
            files = files,
            totalBytes = files.sumOf { it.sizeBytes },
        )
    }

    private fun walk(root: StorageRoot, path: String, out: MutableList<PlannedFile>) {
        val entries = try {
            bridge.list(root, path)
        } catch (e: BridgeError.NotFound) {
            return
        }
        for (entry in entries) {
            if (entry.isDirectory) {
                walk(root, entry.relativePath, out)
            } else {
                out += PlannedFile(entry.relativePath, entry.sizeBytes)
            }
        }
    }

    // ---- execution ---------------------------------------------------------

    private suspend inline fun run(
        plan: TransferPlan,
        crossinline onProgress: (TransferProgress) -> Unit,
        crossinline transferOne: (PlannedFile, (Long) -> Unit) -> Unit,
    ): TransferResult {
        var progress = TransferProgress(
            direction = plan.direction,
            state = State.SCANNING,
            filesTotal = plan.fileCount,
            bytesTotal = plan.totalBytes,
        )
        onProgress(progress)

        if (plan.files.isEmpty()) {
            progress = progress.copy(state = State.DONE)
            onProgress(progress)
            return TransferResult(succeeded = 0, failed = emptyList(), bytesCopied = 0)
        }

        progress = progress.copy(state = State.RUNNING)
        onProgress(progress)

        var done = 0
        var bytesDone = 0L
        val failures = ArrayList<TransferFailure>()

        for (file in plan.files) {
            if (cancelled) {
                progress = progress.copy(state = State.CANCELLED, failures = failures)
                onProgress(progress)
                return TransferResult(done, failures, bytesDone, cancelled = true)
            }

            var fileBytes = 0L
            progress = progress.copy(currentPath = file.relativePath, currentFileBytes = 0)
            onProgress(progress)

            try {
                transferOne(file) { delta ->
                    fileBytes += delta
                    onProgress(
                        progress.copy(
                            currentPath = file.relativePath,
                            currentFileBytes = fileBytes,
                        )
                    )
                }
                done++
                bytesDone += fileBytes
            } catch (e: BridgeError.ProxyUnreachable) {
                // The proxy is gone mid-transfer; nothing further can succeed.
                failures += TransferFailure(file.relativePath, e.message ?: "proxy gone", fatal = true)
                progress = progress.copy(state = State.FAILED, failures = failures)
                onProgress(progress)
                return TransferResult(done, failures, bytesDone)
            } catch (e: BridgeError.PermissionDenied) {
                failures += TransferFailure(file.relativePath, e.message ?: "denied", fatal = true)
                progress = progress.copy(state = State.FAILED, failures = failures)
                onProgress(progress)
                return TransferResult(done, failures, bytesDone)
            } catch (e: IOException) {
                failures += TransferFailure(file.relativePath, e.message ?: "I/O error")
            } catch (e: BridgeError) {
                failures += TransferFailure(file.relativePath, e.message ?: e::class.java.simpleName)
            } catch (e: Exception) {
                Log.w(TAG, "unexpected failure on ${file.relativePath}", e)
                failures += TransferFailure(file.relativePath, "${e::class.java.simpleName}: ${e.message}")
            }

            progress = progress.copy(
                filesDone = done,
                bytesDone = bytesDone,
                currentFileBytes = 0,
                failures = failures,
            )
            onProgress(progress)
        }

        progress = progress.copy(
            state = if (failures.isEmpty()) State.DONE else State.FAILED,
            currentPath = "",
        )
        onProgress(progress)
        return TransferResult(done, failures, bytesDone)
    }

    /**
     * Pulls one file via the write-then-swap protocol.
     *
     * The `.part` suffix means a crash, a full disk or a cancelled transfer never leaves a
     * file that looks complete but is truncated — which for a save game is the difference
     * between "retry" and "corrupted, unrecoverable".
     */
    private fun pullOne(root: StorageRoot, file: PlannedFile, emit: (Long) -> Unit) {
        // Resume: a previous run that completed this file left it at the right size. Skipping
        // it makes an interrupted multi-GB pull cheap to restart, which matters because the
        // alternative is re-copying everything the user already waited for.
        if (!overwrite && destination.sizeOf(file.relativePath) == file.sizeBytes &&
            file.sizeBytes > 0
        ) {
            emit(file.sizeBytes)
            return
        }

        val partPath = file.relativePath + PART_SUFFIX
        if (destination.exists(partPath)) destination.delete(partPath)

        val copied = bridge.openFile(root, file.relativePath, "r").use { pfd ->
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                ParcelFileDescriptor.AutoCloseOutputStream(
                    destination.openWrite(partPath)
                ).use { output ->
                    copy(input, output, emit)
                }
            }
        }

        if (file.sizeBytes > 0 && copied != file.sizeBytes) {
            destination.delete(partPath)
            throw IOException(
                "size mismatch for ${file.relativePath}: copied $copied, expected ${file.sizeBytes}"
            )
        }
        if (!finalise(partPath, file.relativePath)) {
            throw IOException("could not finalise ${file.relativePath}")
        }
    }

    private fun pushOne(root: StorageRoot, source: PushSource, emit: (Long) -> Unit) {
        // Make sure the parent chain exists in the proxy before writing the leaf.
        val parent = source.relativePath.substringBeforeLast('/', missingDelimiterValue = "")
        if (parent.isNotEmpty()) bridge.mkdirs(root, parent)

        source.open().use { input ->
            bridge.openFile(root, source.relativePath, "w").use { pfd ->
                ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { output ->
                    copy(input, output, emit)
                }
            }
        }
    }

    /**
     * Moves the verified `.part` file onto its final name.
     *
     * SAF has no rename, so this is a second pass over the bytes plus a delete. That is a real
     * cost — but it is what makes an interrupted transfer safe: the destination only ever
     * contains either the previous good copy or a complete new one, never a truncated file
     * that looks like a save game.
     */
    private fun finalise(from: String, to: String): Boolean = try {
        ParcelFileDescriptor.AutoCloseInputStream(destination.openRead(from)).use { input ->
            ParcelFileDescriptor.AutoCloseOutputStream(destination.openWrite(to)).use { output ->
                copy(input, output) {}
            }
        }
        destination.delete(from)
    } catch (e: Exception) {
        Log.w(TAG, "finalise failed for $to", e)
        false
    }

    private fun copy(input: InputStream, output: OutputStream, emit: (Long) -> Unit): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            if (cancelled) throw TransferCancelled()
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            total += n
            emit(n.toLong())
        }
        output.flush()
        return total
    }

    // ---- cancellation ------------------------------------------------------

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    fun resetCancellation() {
        cancelled = false
    }

    companion object {
        private const val TAG = "TransferEngine"

        /** 1 MiB: large enough to keep FUSE overhead down, small enough to stay cache-friendly. */
        const val BUFFER_SIZE = 1024 * 1024
        const val PART_SUFFIX = ".part"
    }
}

class TransferCancelled : IOException("transfer cancelled")

data class TransferResult(
    val succeeded: Int,
    val failed: List<TransferFailure>,
    val bytesCopied: Long,
    val cancelled: Boolean = false,
) {
    val isSuccess: Boolean get() = failed.isEmpty() && !cancelled
}

/**
 * A local file to push into the proxy.
 *
 * [open] is a lambda rather than a URI so the engine stays independent of however the caller
 * enumerates the source tree, and so tests can supply in-memory bytes.
 */
class PushSource(
    val relativePath: String,
    val sizeBytes: Long,
    private val opener: () -> InputStream,
) {
    fun open(): InputStream = opener()
}
