package dev.understudy.bridge

/**
 * Mirror of the proxy module's `BridgeContract`.
 *
 * Duplicated deliberately rather than shared through a common module: the proxy APK's
 * `classes.dex` is extracted and re-signed into a standalone APK, so the proxy module cannot
 * depend on anything — not even a tiny `:contract` library — without that dependency being
 * baked into the generated APK and missing at runtime.
 *
 * [PROTOCOL_VERSION] is the guard: `:app` checks it on every `ping` and regenerates the proxy
 * if the two sides disagree, so a stale installed proxy can never be silently mis-driven.
 */
object BridgeContract {

    const val PROTOCOL_VERSION: Int = 1

    /** The authority is the (re-targeted) package name itself. */
    fun authorityFor(packageName: String): String = packageName

    /**
     * The intent action every proxy advertises purely so that this app can *find* it.
     *
     * Package visibility is filtered from API 30, and `ContentResolver` authority resolution is
     * filtered too — an invisible provider produces `IllegalArgumentException: Unknown authority`,
     * which is indistinguishable from "no proxy is installed". The target package name is only
     * known at generation time, so neither `<queries><package>` nor `<queries><provider>` can
     * name it statically. `<queries><intent>` can: it matches *any* package advertising this
     * action, which is every proxy and nothing else.
     *
     * Must equal the action in `proxy/src/main/AndroidManifest.xml`, and must be advertised by a
     * component that is never disabled — see `ProxyDiscoveryReceiver`.
     */
    const val DISCOVERY_ACTION: String = "dev.understudy.action.PROXY_DISCOVERY"

    const val ROOT_DATA: String = "data"
    const val ROOT_OBB: String = "obb"
    val ROOTS: List<String> = listOf(ROOT_DATA, ROOT_OBB)

    const val CALL_PING: String = "ping"
    const val CALL_LIST: String = "list"
    const val CALL_MKDIRS: String = "mkdirs"
    const val CALL_DELETE: String = "delete"
    const val CALL_DELETE_RECURSIVE: String = "deleteRecursive"
    const val CALL_RENAME: String = "rename"
    const val CALL_STAT_TREE: String = "statTree"
    const val CALL_WIPE_SELF: String = "wipeSelf"
    const val CALL_HIDE_LAUNCHER: String = "hideLauncher"
    const val CALL_SHOW_LAUNCHER: String = "showLauncher"
    const val CALL_SELF_DIAGNOSTIC: String = "selfDiagnostic"
    const val CALL_STAT_PATH: String = "statPath"

    const val ARG_PATH: String = "path"
    const val ARG_TARGET: String = "target"
    const val ARG_ROOT: String = "root"
    const val ARG_CONFIRM: String = "confirm"
    const val CONFIRM_TOKEN: String = "understudy-wipe"

    /**
     * Prefix the proxy puts on a `SecurityException` whose cause is **a path the caller asked for**,
     * as opposed to the caller's identity.
     *
     * Without it the two are indistinguishable, and the difference decides whether a transfer
     * continues. `TransferEngine` treats `BridgeError.PermissionDenied` as fatal — correctly, since
     * a caller the proxy will not authenticate will fail every subsequent call too — and treats a
     * per-path refusal as one failed file among many. Both arrive from the provider as a
     * `SecurityException`, so before this marker existed a single symlink inside a save tree that
     * pointed outside it aborted the entire backup and told the user their Understudy install was
     * not the one that generated the proxy. Measured, not theorised: `TransferEngineTest`.
     *
     * A string prefix rather than a distinct exception type because the two sides cannot share
     * types: `:app` has `:proxy-core` on its *test* classpath only, so at runtime a custom exception
     * thrown by the proxy would not resolve in the app's classloader. `BridgeContract` exists for
     * exactly this kind of shared literal.
     *
     * Compatibility: an old proxy without the marker degrades to today's behaviour (a path
     * rejection is reported as a caller-authorisation failure); an old app seeing the marker shows
     * it verbatim. Neither breaks, which is why `PROTOCOL_VERSION` is not bumped for it.
     */
    const val PATH_REJECTION_MARKER: String = "understudy-path:"

    /**
     * Bundle key the proxy sets on a failed `call()` when the failure was a rejected path rather
     * than anything else. `call()` returns its errors inside the Bundle rather than by throwing, so
     * the marker cannot ride along on an exception type there; this is the same signal, structured.
     */
    const val KEY_PATH_REJECTED: String = "pathRejected"

    const val KEY_OK: String = "ok"
    const val KEY_PROTOCOL: String = "protocol"
    const val KEY_PACKAGE: String = "package"
    const val KEY_USER: String = "user"
    const val KEY_ERROR: String = "error"
    const val KEY_DIAGNOSTIC: String = "diagnostic"
    const val KEY_EXISTS: String = "exists"
    const val KEY_IS_DIRECTORY: String = "isDirectory"
    const val KEY_SIZE: String = "sizeBytes"
    const val KEY_PATH: String = "canonicalPath"
    const val KEY_ROOTS: String = "roots"
    const val KEY_ENTRIES: String = "entryCount"
    const val KEY_BYTES: String = "bytes"

    const val COL_NAME: String = "name"
    const val COL_IS_DIR: String = "isDir"
    const val COL_SIZE: String = "size"
    const val COL_MODIFIED: String = "modified"
    const val COL_READABLE: String = "readable"
    const val COL_WRITABLE: String = "writable"
    const val COL_PATH: String = "path"

    const val MIME_DIR: String = "vnd.android.document/directory"
}
