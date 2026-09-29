package dev.understudy.proxytpl.bridge

/**
 * Wire contract between Understudy and the proxy APK.
 *
 * These constants are duplicated deliberately: the proxy module must stay dependency-free
 * (its dex is extracted and re-signed into a standalone APK), so it cannot share a common
 * module with `:app`. The [PROTOCOL_VERSION] handshake exists so `:app` can detect a stale
 * proxy and regenerate it.
 */
object BridgeContract {

    /** Bumped whenever the `call`/`query` contract changes incompatibly. */
    const val PROTOCOL_VERSION: Int = 1

    /** Authority suffix-free: the authority *is* the (re-targeted) package name. */
    fun authorityFor(packageName: String): String = packageName

    // ---- roots -------------------------------------------------------------

    /** `Android/data/<pkg>` — in-app save data, caches, external files. */
    const val ROOT_DATA: String = "data"

    /** `Android/obb/<pkg>` — expansion packs. */
    const val ROOT_OBB: String = "obb"

    val ROOTS: List<String> = listOf(ROOT_DATA, ROOT_OBB)

    // ---- call() methods ----------------------------------------------------

    /** Returns protocol version, identity and per-root status. No arguments. */
    const val CALL_PING: String = "ping"

    /** Lists a directory as a Bundle-encoded tree. Arg: [ARG_PATH]. */
    const val CALL_LIST: String = "list"

    /** Creates a directory. Arg: [ARG_PATH]. */
    const val CALL_MKDIRS: String = "mkdirs"

    /** Deletes a file or empty directory. Arg: [ARG_PATH]. */
    const val CALL_DELETE: String = "delete"

    /** Recursively deletes a directory. Arg: [ARG_PATH]. */
    const val CALL_DELETE_RECURSIVE: String = "deleteRecursive"

    /** Renames/moves within a single root. Args: [ARG_PATH], [ARG_TARGET]. */
    const val CALL_RENAME: String = "rename"

    /** Total bytes and entry count under a path. Arg: [ARG_PATH]. */
    const val CALL_STAT_TREE: String = "statTree"

    /**
     * Empties the proxy's own external data/obb trees so that a subsequent *normal*
     * uninstall destroys nothing. Used by the "evacuate then uninstall" teardown path.
     * Requires [ARG_CONFIRM] to equal [CONFIRM_TOKEN] — this is destructive.
     */
    const val CALL_WIPE_SELF: String = "wipeSelf"

    /** Disables the proxy's launcher activity so it disappears from the app drawer. */
    const val CALL_HIDE_LAUNCHER: String = "hideLauncher"

    /** Re-enables the proxy's launcher activity. */
    const val CALL_SHOW_LAUNCHER: String = "showLauncher"

    // ---- arguments ---------------------------------------------------------

    const val ARG_PATH: String = "path"
    const val ARG_TARGET: String = "target"
    const val ARG_ROOT: String = "root"
    const val ARG_CONFIRM: String = "confirm"

    /** Must be echoed back in [ARG_CONFIRM] for destructive calls. */
    const val CONFIRM_TOKEN: String = "understudy-wipe"

    // ---- result keys -------------------------------------------------------

    const val KEY_OK: String = "ok"
    const val KEY_PROTOCOL: String = "protocol"
    const val KEY_PACKAGE: String = "package"
    const val KEY_USER: String = "user"
    const val KEY_ERROR: String = "error"
    const val KEY_ROOTS: String = "roots"
    const val KEY_ENTRIES: String = "entryCount"
    const val KEY_BYTES: String = "bytes"

    // ---- cursor / entry schema ---------------------------------------------

    const val COL_NAME: String = "name"
    const val COL_IS_DIR: String = "isDir"
    const val COL_SIZE: String = "size"
    const val COL_MODIFIED: String = "modified"
    const val COL_READABLE: String = "readable"
    const val COL_WRITABLE: String = "writable"
    const val COL_PATH: String = "path"

    /** MIME reported for directories, matching `DocumentsContract.Document.MIME_TYPE_DIR`. */
    const val MIME_DIR: String = "vnd.android.document/directory"

    /**
     * Builds a bridge URI.
     *
     * @param authority the re-targeted package name of the installed proxy
     * @param root one of [ROOTS]
     * @param relativePath slash-separated path inside the root, may be empty
     */
    fun uri(authority: String, root: String, relativePath: String = ""): android.net.Uri {
        val builder = android.net.Uri.parse("content://$authority")
            .buildUpon()
            .appendPath(root)
        if (relativePath.isNotEmpty()) {
            for (segment in relativePath.split('/')) {
                if (segment.isNotEmpty()) builder.appendPath(segment)
            }
        }
        return builder.build()
    }
}
