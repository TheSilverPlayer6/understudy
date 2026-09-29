package dev.understudy.bridge

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import dev.understudy.core.model.BridgeInfo
import dev.understudy.core.model.RemoteEntry
import dev.understudy.core.model.RootStatus
import dev.understudy.core.model.StorageRoot
import dev.understudy.core.model.TreeStat
import java.io.FileNotFoundException

/**
 * Client for the proxy's exported `ContentProvider`.
 *
 * All traffic is Binder: `query()` for listings, `openFileDescriptor()` for byte streams, and
 * `call()` for metadata and mutations. There is deliberately no socket, no port and no
 * foreground service involved — a provider call **auto-starts the proxy process on demand**,
 * which is what makes this work when the target profile is not the foreground user and
 * background-activity-start restrictions would block any other approach.
 *
 * Every path we send is re-validated here even though the proxy validates it too. The two
 * processes share nothing but a signing key, so neither should trust the other's parsing.
 *
 * All methods are blocking; call them from a coroutine on [kotlinx.coroutines.Dispatchers.IO].
 */
class BridgeClient(
    private val resolver: ContentResolver,
    private val authority: String,
) {

    /**
     * Confirms a proxy is reachable *and* is the one we think it is.
     *
     * This is also how we detect installation without `QUERY_ALL_PACKAGES`: a successful call
     * is stronger evidence than a package query, because it proves the provider is live, the
     * permission is granted, and the protocol matches.
     *
     * @throws BridgeError.ProxyUnreachable if nothing answers at [authority]
     * @throws BridgeError.PermissionDenied if something answers but refuses us
     */
    @Throws(BridgeError::class)
    fun ping(expectedPackage: String = authority): BridgeInfo {
        val result = call(BridgeContract.CALL_PING)

        val protocol = result.getInt(BridgeContract.KEY_PROTOCOL, -1)
        if (protocol != BridgeContract.PROTOCOL_VERSION) {
            throw BridgeError.ProtocolMismatch(protocol, BridgeContract.PROTOCOL_VERSION)
        }

        val pkg = result.getString(BridgeContract.KEY_PACKAGE).orEmpty()
        if (pkg != expectedPackage) {
            throw BridgeError.WrongIdentity(expectedPackage, pkg)
        }

        val roots = result.getBundle(BridgeContract.KEY_ROOTS)?.let { bundle ->
            BridgeContract.ROOTS.mapNotNull { name ->
                val info = bundle.getBundle(name) ?: return@mapNotNull null
                val root = StorageRoot.fromWire(name) ?: return@mapNotNull null
                RootStatus(
                    root = root,
                    exists = info.getBoolean("exists"),
                    absolutePath = info.getString("path"),
                    usableBytes = info.getLong("usableBytes"),
                )
            }
        }.orEmpty()

        return BridgeInfo(
            packageName = pkg,
            userId = result.getInt(BridgeContract.KEY_USER, -1),
            protocolVersion = protocol,
            roots = roots,
        )
    }

    /** True when a compatible proxy answers. Never throws — safe to call from UI. */
    fun isReachable(): Boolean = runCatching { ping() }.isSuccess

    /**
     * Lists a directory.
     *
     * @param relativePath empty string means the root itself
     * @throws BridgeError.NotFound if the directory does not exist
     */
    @Throws(BridgeError::class)
    fun list(root: StorageRoot, relativePath: String = ""): List<RemoteEntry> {
        validate(relativePath)
        val uri = uriFor(root, relativePath)
        val cursor: Cursor = try {
            resolver.query(uri, null, null, null, null)
                ?: throw BridgeError.OperationFailed("list", "provider returned no cursor for $uri")
        } catch (e: FileNotFoundException) {
            throw BridgeError.NotFound(describe(root, relativePath))
        } catch (e: SecurityException) {
            throw BridgeError.PermissionDenied(authority, e)
        } catch (e: IllegalArgumentException) {
            throw BridgeError.ProxyUnreachable(authority, e)
        }

        cursor.use { c ->
            val nameIdx = c.getColumnIndexOrThrow(BridgeContract.COL_NAME)
            val dirIdx = c.getColumnIndexOrThrow(BridgeContract.COL_IS_DIR)
            val sizeIdx = c.getColumnIndexOrThrow(BridgeContract.COL_SIZE)
            val modIdx = c.getColumnIndexOrThrow(BridgeContract.COL_MODIFIED)
            val readIdx = c.getColumnIndexOrThrow(BridgeContract.COL_READABLE)
            val writeIdx = c.getColumnIndexOrThrow(BridgeContract.COL_WRITABLE)
            val pathIdx = c.getColumnIndexOrThrow(BridgeContract.COL_PATH)

            val out = ArrayList<RemoteEntry>(c.count)
            while (c.moveToNext()) {
                out += RemoteEntry(
                    name = c.getString(nameIdx),
                    isDirectory = c.getInt(dirIdx) != 0,
                    sizeBytes = c.getLong(sizeIdx),
                    lastModifiedMillis = c.getLong(modIdx),
                    canRead = c.getInt(readIdx) != 0,
                    canWrite = c.getInt(writeIdx) != 0,
                    relativePath = c.getString(pathIdx),
                )
            }
            return out
        }
    }

    /**
     * Opens a file for reading or writing.
     *
     * Returns a [ParcelFileDescriptor] that streams directly from the proxy's filesystem —
     * nothing is buffered in either process, which is what makes multi-gigabyte `.obb` files
     * tractable. The caller owns the descriptor and must close it.
     *
     * @param mode one of `r`, `w` (create+truncate), `wa` (create+append), `rw`
     */
    @Throws(BridgeError::class)
    fun openFile(root: StorageRoot, relativePath: String, mode: String = "r"): ParcelFileDescriptor {
        validate(relativePath)
        require(relativePath.isNotEmpty()) { "cannot open the root itself as a file" }
        require(mode in MODES) { "unsupported mode '$mode'; expected one of $MODES" }
        return try {
            resolver.openFileDescriptor(uriFor(root, relativePath), mode)
                ?: throw BridgeError.Io(
                    describe(root, relativePath),
                    FileNotFoundException("provider returned no descriptor"),
                )
        } catch (e: FileNotFoundException) {
            throw BridgeError.NotFound(describe(root, relativePath))
        } catch (e: SecurityException) {
            throw BridgeError.PermissionDenied(authority, e)
        } catch (e: IllegalArgumentException) {
            throw BridgeError.ProxyUnreachable(authority, e)
        }
    }

    @Throws(BridgeError::class)
    fun mkdirs(root: StorageRoot, relativePath: String) {
        validate(relativePath)
        expectOk(BridgeContract.CALL_MKDIRS, pathArgs(root, relativePath), "mkdirs")
    }

    /** Idempotent: deleting something absent is reported as success. */
    @Throws(BridgeError::class)
    fun delete(root: StorageRoot, relativePath: String, recursive: Boolean = false) {
        validate(relativePath)
        require(relativePath.isNotEmpty()) { "refusing to delete the root itself" }
        val method = if (recursive) BridgeContract.CALL_DELETE_RECURSIVE else BridgeContract.CALL_DELETE
        expectOk(method, pathArgs(root, relativePath), "delete")
    }

    @Throws(BridgeError::class)
    fun rename(root: StorageRoot, from: String, to: String) {
        validate(from)
        validate(to)
        val args = Bundle().apply {
            putString(BridgeContract.ARG_PATH, uriFor(root, from).toString())
            putString(BridgeContract.ARG_TARGET, uriFor(root, to).toString())
        }
        expectOk(BridgeContract.CALL_RENAME, args, "rename")
    }

    @Throws(BridgeError::class)
    fun statTree(root: StorageRoot, relativePath: String = ""): TreeStat {
        validate(relativePath)
        val result = call(BridgeContract.CALL_STAT_TREE, pathArgs(root, relativePath))
        return TreeStat(
            entryCount = result.getLong(BridgeContract.KEY_ENTRIES, 0),
            totalBytes = result.getLong(BridgeContract.KEY_BYTES, 0),
        )
    }

    /**
     * Empties the proxy's own trees so a subsequent *normal* uninstall has nothing to erase.
     *
     * Irreversible and therefore gated behind an explicit confirmation token on both sides.
     * Callers must have already verified the data is safely elsewhere.
     */
    @Throws(BridgeError::class)
    fun wipeProxyData() {
        val args = Bundle().apply {
            putString(BridgeContract.ARG_CONFIRM, BridgeContract.CONFIRM_TOKEN)
        }
        expectOk(BridgeContract.CALL_WIPE_SELF, args, "wipeSelf")
    }

    /** Hides the proxy's launcher icon. The proxy can only toggle its own components. */
    fun hideLauncher(): Boolean = runCatching {
        call(BridgeContract.CALL_HIDE_LAUNCHER).getBoolean(BridgeContract.KEY_OK, false)
    }.getOrDefault(false)

    fun showLauncher(): Boolean = runCatching {
        call(BridgeContract.CALL_SHOW_LAUNCHER).getBoolean(BridgeContract.KEY_OK, false)
    }.getOrDefault(false)

    // ---- internals ---------------------------------------------------------

    private fun call(method: String, args: Bundle? = null): Bundle {
        val result: Bundle? = try {
            resolver.call(uriForRoot(), method, null, args)
        } catch (e: SecurityException) {
            throw BridgeError.PermissionDenied(authority, e)
        } catch (e: IllegalArgumentException) {
            throw BridgeError.ProxyUnreachable(authority, e)
        }
        if (result == null) {
            throw BridgeError.OperationFailed(method, "provider returned no result")
        }
        if (!result.getBoolean(BridgeContract.KEY_OK, false) && method != BridgeContract.CALL_PING) {
            throw BridgeError.OperationFailed(
                method,
                result.getString(BridgeContract.KEY_ERROR) ?: "unknown reason",
            )
        }
        return result
    }

    private fun expectOk(method: String, args: Bundle, label: String) {
        call(method, args)
    }

    private fun pathArgs(root: StorageRoot, relativePath: String) = Bundle().apply {
        putString(BridgeContract.ARG_PATH, uriFor(root, relativePath).toString())
    }

    private fun uriForRoot(): Uri = Uri.parse("content://$authority")

    fun uriFor(root: StorageRoot, relativePath: String): Uri {
        val builder = uriForRoot().buildUpon().appendPath(root.wireName)
        if (relativePath.isNotEmpty()) {
            for (segment in relativePath.split('/')) {
                if (segment.isNotEmpty()) builder.appendPath(segment)
            }
        }
        return builder.build()
    }

    private fun describe(root: StorageRoot, relativePath: String) =
        if (relativePath.isEmpty()) root.displayName else "${root.displayName}/$relativePath"

    /**
     * Rejects, before anything leaves this process, the paths that could escape the root.
     *
     * Mirrors the proxy's own `BridgePaths` rules. Defence in depth: a bug on either side
     * should not by itself be enough to read or write outside `Android/{data,obb}/<pkg>`.
     */
    private fun validate(relativePath: String) {
        if (relativePath.isEmpty()) return
        if (relativePath.indexOf('\u0000') >= 0) {
            throw BridgeError.InvalidPath(relativePath, "contains NUL")
        }
        if (relativePath.startsWith("/")) {
            throw BridgeError.InvalidPath(relativePath, "must be relative")
        }
        if (relativePath.contains('\\')) {
            throw BridgeError.InvalidPath(relativePath, "must not contain backslashes")
        }
        if (relativePath.contains("//")) {
            throw BridgeError.InvalidPath(relativePath, "must not contain empty segments")
        }
        for (segment in relativePath.split('/')) {
            if (segment == "..") {
                throw BridgeError.InvalidPath(relativePath, "must not contain '..'")
            }
        }
    }

    companion object {
        val MODES = setOf("r", "w", "wa", "rw")
    }
}
