package dev.understudy.storage

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/**
 * Write side of a transfer: a user-chosen directory reached through the Storage Access
 * Framework.
 *
 * SAF rather than `MANAGE_EXTERNAL_STORAGE` or MediaStore for three reasons:
 *  - it is the only mechanism that gives durable, arbitrary-directory write access on
 *    Android 11+ without a Play-restricted permission;
 *  - a persisted URI permission survives reboot and app restart, so a resumed transfer does
 *    not need to re-prompt;
 *  - the user picks the destination, which is the right shape for "back up this save game" —
 *    they may want it on an SD card, in a synced folder, or anywhere else.
 */
class SafDestination private constructor(
    private val context: Context,
    val treeUri: Uri,
) : dev.understudy.transfer.TransferDestination {

    private val root: DocumentFile =
        DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("cannot resolve tree URI $treeUri")

    val displayName: String get() = root.name ?: treeUri.lastPathSegment ?: "destination"

    /**
     * Persists read/write access across reboots.
     *
     * Must be called after the user picks the tree; the grant returned by
     * `ACTION_OPEN_DOCUMENT_TREE` is only for the current task otherwise.
     */
    fun persist() {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    /** Whether a previously persisted grant is still in force. */
    fun hasPersistedAccess(): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission && it.isWritePermission
        }

    /**
     * Resolves [relativePath] (slash-separated) to a [DocumentFile], creating any missing
     * intermediate directories.
     *
     * Segments are validated because they originate from the *proxy's* filesystem — a
     * directory named `..` or containing a slash would otherwise let a hostile or merely
     * odd save-game layout write outside the chosen destination.
     */
    fun resolveForWrite(relativePath: String): DocumentFile {
        val segments = splitAndValidate(relativePath)
        require(segments.isNotEmpty()) { "cannot resolve an empty path" }

        var current = root
        for (segment in segments.dropLast(1)) {
            current = current.findFile(segment) ?: current.createDirectory(segment)
                ?: throw SafError("could not create directory '$segment' in ${current.name}")
        }
        val leaf = segments.last()
        return current.findFile(leaf) ?: current.createFile(APPLICATION_OCTET_STREAM, leaf)
            ?: throw SafError("could not create file '$leaf' in ${current.name}")
    }

    /** Like [resolveForWrite] but returns the existing file, creating only directories. */
    fun resolveDirectory(relativePath: String): DocumentFile {
        val segments = splitAndValidate(relativePath)
        var current = root
        for (segment in segments) {
            current = current.findFile(segment) ?: current.createDirectory(segment)
                ?: throw SafError("could not create directory '$segment'")
        }
        return current
    }

    /** Opens [relativePath] for writing, replacing any existing content. */
    override fun openWrite(relativePath: String): android.os.ParcelFileDescriptor {
        val file = resolveForWrite(relativePath)
        val uri = file.uri
        // `findFile` + `createFile` cannot truncate, so go through the resolver with "wt".
        return context.contentResolver.openFileDescriptor(uri, "wt")
            ?: throw SafError("could not open '$relativePath' for writing")
    }

    /** Opens [relativePath] for reading. Throws [SafError] if it does not exist. */
    override fun openRead(relativePath: String): android.os.ParcelFileDescriptor {
        val segments = splitAndValidate(relativePath)
        var current: DocumentFile? = root
        for (segment in segments.dropLast(1)) {
            current = current?.findFile(segment)
                ?: throw SafError("no such directory: '$relativePath'")
        }
        val file = current?.findFile(segments.last())
            ?: throw SafError("no such file: '$relativePath'")
        return context.contentResolver.openFileDescriptor(file.uri, "r")
            ?: throw SafError("could not open '$relativePath' for reading")
    }

    /**
     * Size of an existing file, or -1 when absent.
     *
     * Used by the transfer engine to decide whether a file is already complete, which is what
     * makes an interrupted pull resumable.
     */
    override fun sizeOf(relativePath: String): Long {
        val segments = splitAndValidate(relativePath)
        if (segments.isEmpty()) return -1
        var current: DocumentFile? = root
        for (segment in segments.dropLast(1)) {
            current = current?.findFile(segment) ?: return -1
        }
        return current?.findFile(segments.last())?.length() ?: -1
    }

    override fun delete(relativePath: String): Boolean {
        val segments = splitAndValidate(relativePath)
        if (segments.isEmpty()) return false
        var current: DocumentFile? = root
        for (segment in segments.dropLast(1)) {
            current = current?.findFile(segment) ?: return false
        }
        return current?.findFile(segments.last())?.delete() == true
    }

    override fun exists(relativePath: String): Boolean {
        val segments = splitAndValidate(relativePath)
        var current: DocumentFile? = root
        for (segment in segments) {
            current = current?.findFile(segment) ?: return false
        }
        return true
    }

    /**
     * Walks a subdirectory of the chosen tree and returns one [dev.understudy.transfer.PushSource]
     * per file, with paths relative to [relativePath].
     *
     * This is the read side of a push: the engine wants "path → how to open its bytes", and only
     * a SAF tree walk can produce that. Keeping the traversal here (rather than in the ViewModel
     * or the engine) means [dev.understudy.transfer.TransferEngine] stays free of DocumentFile
     * and therefore unit-testable, which is why it takes a lambda in the first place.
     *
     * Names are validated on the way *in* as well as out: the tree belongs to the user and may
     * live on an SD card formatted by anything, so a name the proxy could not represent is
     * skipped with a reason rather than being pushed and failing halfway through a 4 GB file.
     *
     * @param onSkip called for each entry that cannot be represented, so the UI can say so
     */
    fun collectSourcesForPush(
        relativePath: String = "",
        onSkip: (String, String) -> Unit = { _, _ -> },
    ): List<dev.understudy.transfer.PushSource> {
        val segments = splitAndValidate(relativePath)
        var current: DocumentFile = root
        for (segment in segments) {
            current = current.findFile(segment)
                ?: return emptyList() // nothing to push from a directory that is not there
            if (!current.isDirectory) return emptyList()
        }

        val out = ArrayList<dev.understudy.transfer.PushSource>()
        walkForPush(current, "", out, onSkip)
        return out
    }

    private fun walkForPush(
        dir: DocumentFile,
        prefix: String,
        out: MutableList<dev.understudy.transfer.PushSource>,
        onSkip: (String, String) -> Unit,
    ) {
        for (child in dir.listFiles()) {
            val name = child.name
            if (name == null) {
                onSkip(prefix, "unnamed entry")
                continue
            }
            val relative = if (prefix.isEmpty()) name else "$prefix/$name"
            if (!isRepresentable(name)) {
                onSkip(relative, "name cannot be represented inside the proxy's storage")
                continue
            }
            if (child.isDirectory) {
                walkForPush(child, relative, out, onSkip)
                continue
            }
            val size = child.length()
            val uri = child.uri
            // Resolve the opener against the URI, not the DocumentFile: holding the tree open
            // for the duration of a multi-gigabyte push is what leaks file descriptors.
            out += dev.understudy.transfer.PushSource(relative, size) {
                context.contentResolver.openInputStream(uri)
                    ?: throw java.io.FileNotFoundException("cannot open $uri")
            }
        }
    }

    /**
     * Whether [name] can exist as an entry inside `Android/data/<pkg>`.
     *
     * Deliberately stricter than the SAF side: these names become real paths on the proxy's
     * filesystem, where a leading `~`, a control character, or a `.` / `..` entry would either
     * be rejected or — worse — resolve somewhere unintended. The proxy validates too, but
     * failing here means the user learns before the transfer rather than during it.
     */
    private fun isRepresentable(name: String): Boolean {
        if (name.isEmpty() || name == "." || name == "..") return false
        if (name.contains('\u0000')) return false
        if (name.any { it.code < 0x20 || it.code == 0x7f }) return false
        // The bridge rejects backslashes outright; some filesystems accept them and would
        // create a name that the proxy can never address again.
        if (name.contains('\\')) return false
        if (name == ".nomedia") return false
        return true
    }

    private fun splitAndValidate(relativePath: String): List<String> {
        if (relativePath.isEmpty()) return emptyList()
        require(!relativePath.startsWith("/")) { "path must be relative: $relativePath" }
        val segments = relativePath.split('/').filter { it.isNotEmpty() }
        for (segment in segments) {
            require(segment != "." && segment != "..") { "path traversal in '$relativePath'" }
            require(!segment.contains('\u0000')) { "NUL in path '$relativePath'" }
            // DocumentFile names are matched literally against the provider; a slash would
            // create a name the provider cannot represent.
            require(!segment.contains('/')) { "slash inside segment of '$relativePath'" }
        }
        return segments
    }

    companion object {
        private const val APPLICATION_OCTET_STREAM = "application/octet-stream"

        /**
         * Wraps a freshly-picked tree URI and takes the persistable grant.
         *
         * `ACTION_OPEN_DOCUMENT_TREE` returns a grant that is only valid for the current task
         * until `takePersistableUriPermission` is called, so this must happen immediately after
         * the picker returns — not lazily on first use.
         */
        fun open(context: Context, treeUri: Uri): SafDestination =
            SafDestination(context, treeUri).also { it.persist() }

        /** Intent that asks the user to choose the destination directory. */
        fun pickTreeIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }

        /**
         * Restores a previously persisted destination, or null if the grant is gone.
         *
         * Grants do not survive clearing app data, and a user can revoke them from Settings,
         * so callers must handle null rather than assume persistence is permanent.
         */
        fun restore(context: Context, treeUri: Uri): SafDestination? {
            val usable = context.contentResolver.persistedUriPermissions.any {
                it.uri == treeUri && it.isReadPermission && it.isWritePermission
            }
            if (!usable) return null
            return runCatching { SafDestination(context, treeUri) }.getOrNull()
        }

        /** Lists every tree URI we currently hold write access to. */
        fun persistedTrees(context: Context): List<Uri> =
            context.contentResolver.persistedUriPermissions
                .filter { it.isWritePermission }
                .map { it.uri }

        fun isTreeUri(uri: Uri): Boolean =
            DocumentsContract.isTreeUri(uri)

        @Suppress("unused")
        private fun unusedPermissionConstant() = PackageManager.PERMISSION_GRANTED
    }
}

class SafError(message: String) : Exception(message)
