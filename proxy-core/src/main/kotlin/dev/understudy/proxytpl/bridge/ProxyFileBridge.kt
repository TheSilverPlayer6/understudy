package dev.understudy.proxytpl.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * Re-exports this package's own `Android/data` and `Android/obb` trees to Understudy.
 *
 * Runs inside the proxy APK, whose package name has been rewritten to the target's, so the
 * platform treats these directories as *ours* and the Android 11+ FUSE filter lets us
 * through. Understudy reaches the tree over Binder — no socket, no port, no foreground
 * service, and the provider is started on demand even when this user profile is in the
 * background.
 *
 * Security posture: exported, but guarded by the `signature`-level
 * `dev.understudy.permission.BRIDGE`, and every path goes through [BridgePaths] so a caller
 * cannot escape the two roots.
 */
class ProxyFileBridge : ContentProvider() {

    private val resolver = BridgePaths.RootResolver { root -> rootDir(root) }

    // ---- lifecycle ---------------------------------------------------------

    override fun onCreate(): Boolean {
        Log.i(TAG, "bridge up for ${context?.packageName} as user ${currentUserId()}")
        return true
    }

    // ---- root resolution ---------------------------------------------------

    /**
     * Resolved once and cached.
     *
     * Caching is not just an optimisation: `getExternalFilesDir(null)` **creates** the `files`
     * directory as a side effect. Calling it inside `wipeSelf` therefore resurrected a child
     * immediately after deletion, so the data root could never actually be emptied — which
     * would have made the evacuate-then-uninstall teardown leave a stray directory behind.
     */
    private val roots: Map<String, File?> by lazy {
        val ctx = context
        val pkg = ctx?.packageName
        if (ctx == null || pkg == null) {
            emptyMap()
        } else {
            val external = ctx.getExternalFilesDir(null)
            val dataDir = external?.parentFile?.parentFile
                ?: File(File(android.os.Environment.getExternalStorageDirectory(), "Android/data"), pkg)
            val obbDir = dataDir.parentFile?.let { File(File(it, "obb"), pkg) }
                ?: File(File(android.os.Environment.getExternalStorageDirectory(), "Android/obb"), pkg)
            mapOf(BridgeContract.ROOT_DATA to dataDir, BridgeContract.ROOT_OBB to obbDir)
        }
    }

    private fun rootDir(root: String): File? = roots[root]

    private fun resolve(uri: Uri): BridgePaths.Resolution {
        val segments = uri.pathSegments
        if (segments.isEmpty()) return BridgePaths.Resolution.Rejected("empty path: missing root")
        val root = segments.first()
        val relative = segments.drop(1).joinToString("/")
        return BridgePaths.resolve(resolver, root, relative)
    }

    // ---- query: directory listing ------------------------------------------

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val r = requireOk(uri)
        val file = r.file
        if (!file.exists()) throw FileNotFoundException("no such entry: $uri")
        val cursor = MatrixCursor(COLUMNS)
        if (file.isDirectory) {
            val children = file.listFiles()
                ?: throw FileNotFoundException("cannot list: $uri (permission denied)")
            // Deterministic order: directories first, then case-insensitive name.
            val ordered: List<File> = children.sortedWith(
                compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
            )
            ordered.forEach { child ->
                cursor.addRow(
                    arrayOf<Any>(
                        child.name,
                        if (child.isDirectory) 1 else 0,
                        if (child.isDirectory) 0L else child.length(),
                        child.lastModified(),
                        if (child.canRead()) 1 else 0,
                        if (child.canWrite()) 1 else 0,
                        BridgePaths.child(r.relative, child.name),
                    )
                )
            }
        } else {
            cursor.addRow(
                arrayOf<Any>(
                    file.name, 0, file.length(), file.lastModified(),
                    if (file.canRead()) 1 else 0, if (file.canWrite()) 1 else 0,
                    r.relative,
                )
            )
        }
        return cursor
    }

    override fun getType(uri: Uri): String {
        val file = requireOk(uri).file
        if (file.isDirectory) return BridgeContract.MIME_DIR
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    // ---- openFile: zero-copy streaming -------------------------------------

    /**
     * Streams via [ParcelFileDescriptor], so multi-gigabyte `.obb` expansion packs never
     * get buffered in memory and transfer at essentially filesystem speed.
     */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val file = requireOk(uri).file
        val fdMode = when (mode) {
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            "w", "wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            "wa" -> ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_APPEND
            "rw" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
            else -> throw IllegalArgumentException("unsupported mode: $mode")
        }

        if (mode == "r" && !file.exists()) throw FileNotFoundException("no such file: $uri")
        if (mode == "r" && file.isDirectory) throw FileNotFoundException("is a directory: $uri")

        // A write mode must not silently create a *directory* entry, and must not follow a
        // path whose parent is missing.
        if (mode != "r") {
            val parent = file.parentFile
            if (parent != null && !parent.isDirectory) {
                throw FileNotFoundException("parent is not a directory: $uri")
            }
        }

        return try {
            ParcelFileDescriptor.open(file, fdMode)
        } catch (e: SecurityException) {
            Log.w(TAG, "open denied for $uri", e)
            throw FileNotFoundException("permission denied: $uri")
        }
    }

    // ---- call: metadata & mutations ----------------------------------------

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val out = Bundle()
        try {
            when (method) {
                BridgeContract.CALL_PING -> ping(out)
                BridgeContract.CALL_LIST -> list(arg, extras, out)
                BridgeContract.CALL_MKDIRS -> mkdirs(extras, out)
                BridgeContract.CALL_DELETE -> delete(extras, recursive = false, out)
                BridgeContract.CALL_DELETE_RECURSIVE -> delete(extras, recursive = true, out)
                BridgeContract.CALL_RENAME -> rename(extras, out)
                BridgeContract.CALL_STAT_TREE -> statTree(extras, out)
                BridgeContract.CALL_WIPE_SELF -> wipeSelf(extras, out)
                BridgeContract.CALL_HIDE_LAUNCHER -> setLauncherEnabled(false, out)
                BridgeContract.CALL_SHOW_LAUNCHER -> setLauncherEnabled(true, out)
                else -> {
                    out.putBoolean(BridgeContract.KEY_OK, false)
                    out.putString(BridgeContract.KEY_ERROR, "unknown method: $method")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "call($method) failed", t)
            out.putBoolean(BridgeContract.KEY_OK, false)
            out.putString(BridgeContract.KEY_ERROR, "${t.javaClass.simpleName}: ${t.message}")
        }
        return out
    }

    private fun ping(out: Bundle) {
        val ctx = ctxOrFail()
        out.putBoolean(BridgeContract.KEY_OK, true)
        out.putInt(BridgeContract.KEY_PROTOCOL, BridgeContract.PROTOCOL_VERSION)
        out.putString(BridgeContract.KEY_PACKAGE, ctx.packageName)
        out.putInt(BridgeContract.KEY_USER, currentUserId())
        val roots = Bundle()
        for (name in BridgeContract.ROOTS) {
            val dir = rootDir(name)
            val info = Bundle()
            info.putBoolean("exists", dir?.exists() == true)
            info.putString("path", dir?.absolutePath)
            info.putLong("usableBytes", dir?.takeIf { it.exists() }?.usableSpace ?: 0L)
            roots.putBundle(name, info)
        }
        out.putBundle(BridgeContract.KEY_ROOTS, roots)
    }

    private fun list(arg: String?, extras: Bundle?, out: Bundle) {
        val uri = arg?.let { Uri.parse(it) } ?: throw IllegalArgumentException("missing uri arg")
        val r = requireOk(uri)
        val file = r.file
        out.putBoolean(BridgeContract.KEY_OK, true)
        if (!file.exists()) {
            out.putInt(BridgeContract.KEY_ENTRIES, 0)
            return
        }
        val names = ArrayList<String>()
        val sizes = ArrayList<Long>()
        val dirs = ArrayList<Boolean>()
        if (file.isDirectory) {
            for (child in file.listFiles().orEmpty()) {
                names.add(child.name)
                sizes.add(if (child.isDirectory) 0L else child.length())
                dirs.add(child.isDirectory)
            }
        }
        out.putStringArrayList("names", names)
        val sizeArr = LongArray(sizes.size) { sizes[it] }
        out.putLongArray("sizes", sizeArr)
        out.putBooleanArray("dirs", dirs.toBooleanArray())
        out.putInt(BridgeContract.KEY_ENTRIES, names.size)
    }

    private fun mkdirs(extras: Bundle?, out: Bundle) {
        val r = requireOk(extras?.let { extrasUri(it) } ?: throw IllegalArgumentException("missing path"))
        val file = r.file
        val ok = file.isDirectory || file.mkdirs()
        out.putBoolean(BridgeContract.KEY_OK, ok)
        if (!ok) out.putString(BridgeContract.KEY_ERROR, "mkdirs failed: ${r.file}")
    }

    private fun delete(extras: Bundle?, recursive: Boolean, out: Bundle) {
        val uri = extras?.let { extrasUri(it) } ?: throw IllegalArgumentException("missing path")
        // Refuse the root *before* resolving: an empty relative path is otherwise rejected as a
        // traversal attempt, which reports the wrong reason for what is really a policy decision.
        // Deleting a root would be self-inflicted, unrecoverable data loss.
        val segments = uri.pathSegments
        if (segments.size <= 1) {
            out.putBoolean(BridgeContract.KEY_OK, false)
            out.putString(BridgeContract.KEY_ERROR, "refusing to delete the root itself")
            return
        }
        val r = requireOk(uri)
        val file = r.file
        if (!file.exists()) {
            out.putBoolean(BridgeContract.KEY_OK, true) // idempotent
            return
        }
        if (file == r.root) {
            out.putBoolean(BridgeContract.KEY_OK, false)
            out.putString(BridgeContract.KEY_ERROR, "refusing to delete the root itself")
            return
        }
        val ok = if (recursive) deleteRecursively(file) else file.delete()
        out.putBoolean(BridgeContract.KEY_OK, ok)
        if (!ok) out.putString(BridgeContract.KEY_ERROR, "delete failed: $file")
    }

    private fun rename(extras: Bundle?, out: Bundle) {
        extras ?: throw IllegalArgumentException("missing args")
        val from = requireOk(extrasUri(extras, BridgeContract.ARG_PATH))
        val to = requireOk(extrasUri(extras, BridgeContract.ARG_TARGET))
        if (to.file.exists()) {
            out.putBoolean(BridgeContract.KEY_OK, false)
            out.putString(BridgeContract.KEY_ERROR, "target already exists: ${to.relative}")
            return
        }
        to.file.parentFile?.mkdirs()
        val ok = from.file.renameTo(to.file)
        out.putBoolean(BridgeContract.KEY_OK, ok)
        if (!ok) out.putString(BridgeContract.KEY_ERROR, "rename failed")
    }

    private fun statTree(extras: Bundle?, out: Bundle) {
        val uri = extras?.let { extrasUri(it) } ?: throw IllegalArgumentException("missing path")
        val r = requireOk(uri)
        var bytes = 0L
        var count = 0L
        walk(r.file) { f ->
            count++
            if (!f.isDirectory) bytes += f.length()
        }
        out.putBoolean(BridgeContract.KEY_OK, true)
        out.putLong(BridgeContract.KEY_BYTES, bytes)
        out.putLong(BridgeContract.KEY_ENTRIES, count)
    }

    /**
     * Empties the proxy's own trees so the following *normal* uninstall has nothing left to
     * erase. Understudy only calls this once it has verified the data is safely elsewhere.
     */
    private fun wipeSelf(extras: Bundle?, out: Bundle) {
        if (extras?.getString(BridgeContract.ARG_CONFIRM) != BridgeContract.CONFIRM_TOKEN) {
            out.putBoolean(BridgeContract.KEY_OK, false)
            out.putString(BridgeContract.KEY_ERROR, "wipeSelf requires the confirmation token")
            return
        }
        var failures = 0
        for (name in BridgeContract.ROOTS) {
            val dir = rootDir(name) ?: continue
            for (child in dir.listFiles().orEmpty()) {
                if (!deleteRecursively(child)) failures++
            }
        }
        out.putBoolean(BridgeContract.KEY_OK, failures == 0)
        if (failures > 0) out.putString(BridgeContract.KEY_ERROR, "$failures entries could not be removed")
    }

    private fun setLauncherEnabled(enabled: Boolean, out: Bundle) {
        val ctx = ctxOrFail()
        // Derived at runtime, never hardcoded: the manifest has been retargeted to the
        // impersonated package, but the class itself still lives under the template's
        // original package inside the dex.
        val component = android.content.ComponentName(
            ctx.packageName,
            "dev.understudy.proxytpl.ProxyStatusActivity",
        )
        ctx.packageManager.setComponentEnabledSetting(
            component,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        out.putBoolean(BridgeContract.KEY_OK, true)
    }

    // ---- helpers -----------------------------------------------------------

    private fun extrasUri(
        extras: Bundle,
        key: String = BridgeContract.ARG_PATH,
    ): Uri {
        val raw = extras.getString(key) ?: throw IllegalArgumentException("missing '$key'")
        return Uri.parse(raw)
    }

    private fun requireOk(uri: Uri): BridgePaths.Resolution.Ok {
        val r = resolve(uri)
        if (r is BridgePaths.Resolution.Rejected) throw SecurityException(r.reason)
        return r as BridgePaths.Resolution.Ok
    }

    private fun walk(file: File, visit: (File) -> Unit) {
        visit(file)
        if (file.isDirectory) {
            for (child in file.listFiles().orEmpty()) walk(child, visit)
        }
    }

    private fun deleteRecursively(file: File): Boolean {
        if (file.isDirectory) {
            for (child in file.listFiles().orEmpty()) {
                if (!deleteRecursively(child)) return false
            }
        }
        return file.delete()
    }

    private fun ctxOrFail() = context ?: throw IllegalStateException("provider not attached")

    /**
     * `Context.getUserId()` and `Process.myUserHandle()` are awkward to depend on here; the
     * uid arithmetic is stable, dependency-free and works on every API level we support.
     * This is what lets the UI prove the bridge really is running in the intended profile.
     */
    private fun currentUserId(): Int = Os.getuid() / PER_USER_RANGE

    // ---- unused ContentProvider surface ------------------------------------

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("use openFile() with mode 'w' to create files")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("immutable metadata")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("use call(delete) instead")

    companion object {
        private const val TAG = "UnderstudyBridge"

        /** `UserHandle.PER_USER_RANGE` — inlined to avoid an API-level dependency. */
        private const val PER_USER_RANGE = 100000

        private val COLUMNS = arrayOf(
            BridgeContract.COL_NAME,
            BridgeContract.COL_IS_DIR,
            BridgeContract.COL_SIZE,
            BridgeContract.COL_MODIFIED,
            BridgeContract.COL_READABLE,
            BridgeContract.COL_WRITABLE,
            BridgeContract.COL_PATH,
        )
    }
}
