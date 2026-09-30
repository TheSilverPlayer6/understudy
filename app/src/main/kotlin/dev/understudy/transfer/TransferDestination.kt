package dev.understudy.transfer

import android.os.ParcelFileDescriptor

/**
 * The write side of a transfer, as [TransferEngine] sees it.
 *
 * This interface exists so the engine's safety rules can be tested. Those rules are the reason the
 * class is written the way it is — write-then-swap, resume-by-size, per-file failure isolation,
 * cooperative cancellation — and every one of them is about not destroying someone's only copy of
 * a save file. None of them had a test, because the only implementation was `SafDestination`, whose
 * private constructor wraps a `DocumentFile` tree that needs a real SAF grant to obtain.
 *
 * Five methods, all of them file-shaped, so a directory on disk is a complete and faithful fake:
 * `ParcelFileDescriptor.open(File, mode)` gives the same descriptor type the SAF path returns, and
 * the engine's `AutoClose{Input,Output}Stream` wrapping behaves identically. What a fake cannot
 * reproduce is SAF's own quirks — no atomic rename, provider round trips, grant expiry — which is
 * why the interface is deliberately narrow: it pins the engine's *logic*, and leaves SAF's
 * behaviour to the instrumented suite.
 *
 * Paths are slash-separated and relative to the destination root. Implementations must validate
 * them; `SafDestination.splitAndValidate` is the reference, and the paths originate from the
 * proxy's filesystem rather than from the user.
 *
 * @see dev.understudy.storage.SafDestination the production implementation
 */
interface TransferDestination {

    /**
     * Opens [relativePath] for writing, **replacing any existing content**.
     *
     * Truncation is part of the contract, not an implementation detail: `SafDestination` cannot get
     * it from `DocumentFile.createFile` and goes through the resolver with mode `"wt"` instead. A
     * fake that appended would let a size-mismatch bug pass.
     */
    fun openWrite(relativePath: String): ParcelFileDescriptor

    /** Opens [relativePath] for reading. Throws if it does not exist. */
    fun openRead(relativePath: String): ParcelFileDescriptor

    /**
     * Size in bytes of an existing file, or **-1** when it is absent.
     *
     * The engine compares this against the size the proxy reported to decide whether a file is
     * already complete, which is what makes an interrupted multi-gigabyte pull cheap to restart.
     * Returning 0 for a missing file would make every absent file look like a completed empty one.
     */
    fun sizeOf(relativePath: String): Long

    /** Deletes [relativePath]; true if it is gone afterwards. Deleting an absent file is not an error. */
    fun delete(relativePath: String): Boolean

    /** Whether [relativePath] exists as a file. */
    fun exists(relativePath: String): Boolean
}
