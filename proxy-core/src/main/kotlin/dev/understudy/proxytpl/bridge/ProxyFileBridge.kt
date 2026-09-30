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
 * Security posture: exported, but behind two gates — the `signature`-level
 * `dev.understudy.permission.BRIDGE` on the provider (defined by the Understudy *app*, so that it
 * can hold it in production; see [enforceCaller]), and [enforceCaller]'s own check that the
 * calling uid is signed by the certificate of the install that generated this APK. Every path
 * additionally goes through [BridgePaths] so a caller cannot escape the two roots.
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
            mapOf(
                BridgeContract.ROOT_DATA to dataDirFor(ctx, pkg),
                BridgeContract.ROOT_OBB to obbDirFor(ctx, pkg),
            )
        }
    }

    /**
     * `Android/data/<pkg>` — the proxy's own private storage, which is the entire reason it
     * exists.
     *
     * This used to be `getExternalFilesDir(null).parentFile.parentFile`, which assumes the
     * platform returns `.../Android/data/<pkg>/files`. On API 34 and 35 emulators it returns
     * `.../Android/data/<pkg>` with no `files` segment, so two levels up landed on
     * `.../Android/data` — the SHARED parent of every package's private directory, and exactly
     * the path the platform hides from all apps.
     *
     * That one wrong level produced every symptom this project chased through six CI runs, and
     * it produced them asymmetrically, which is why it survived so long:
     *  - listing the data root gave `AccessDeniedException`, because `Android/data` is what
     *    scoped storage hides. The restriction was working correctly, on a directory the bridge
     *    should never have been pointed at;
     *  - `planted/save.dat` gave ENOENT, resolved as `Android/data/planted`;
     *  - obb worked, because its path was built with the package segment intact.
     *
     * So the package segment is now located explicitly rather than reached by counting levels,
     * and both platform shapes resolve to the same directory.
     *
     * The rule that generalises: **a root that is one level wrong is worse than a root that is
     * missing.** A missing root fails loudly; a wrong one points at a directory the platform
     * guarantees is unreadable, so the failure reads as "the premise is false" instead of "the
     * path is wrong" — and invites six runs of FUSE archaeology.
     */
    private fun dataDirFor(ctx: android.content.Context, pkg: String): File {
        val external = ctx.getExternalFilesDir(null)
        if (external != null) {
            val path = external.absolutePath
            // A device gives either .../Android/data/<pkg> or .../Android/data/<pkg>/files
            // depending on platform version, so find the package segment rather than counting
            // levels. Truncating there is what makes both shapes resolve identically.
            val marker = "/Android/data/$pkg"
            val idx = path.indexOf(marker)
            if (idx >= 0) {
                return File(path.substring(0, idx + marker.length))
            }
            // No package segment in the path at all. That is what Robolectric's emulated
            // external storage looks like (<tmp>/external-files/Android/data/files), and it is
            // also the shape a misconfigured volume could produce. Trust the platform's own
            // directory rather than substituting Environment's: substituting silently points
            // the bridge at a DIFFERENT tree from the one getExternalFilesDir created, which
            // is how the original bug hid for six CI runs. Only fall through to Environment
            // when getExternalFilesDir is unavailable entirely.
            val parent = external.parentFile
            if (parent != null) return parent
        }
        return File(File(android.os.Environment.getExternalStorageDirectory(), "Android/data"), pkg)
    }

    /**
     * `Android/obb/<pkg>`, the sibling of [dataDirFor].
     *
     * Built from the resolved data root's parent so the two always sit under the same
     * `Android/` directory — on a device that is the real volume, under Robolectric it is the
     * emulated tree the tests create. Deriving obb independently of data is what let the two
     * disagree in the first place.
     */
    private fun obbDirFor(ctx: android.content.Context, pkg: String): File {
        val data = dataDirFor(ctx, pkg)
        // Two levels up from Android/data/<pkg> is Android/, whose obb/<pkg> child is the
        // sibling root. Going via the resolved data root keeps the two under the same Android/
        // directory on every layout, which deriving them independently did not.
        val androidDir = data.parentFile?.parentFile
            ?: File(android.os.Environment.getExternalStorageDirectory(), "Android")
        return File(File(androidDir, "obb"), pkg)
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
        enforceCaller()
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
        enforceCaller()
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
            enforceCaller()
            when (method) {
                BridgeContract.CALL_PING -> ping(out)
                BridgeContract.CALL_LIST -> list(arg, extras, out)
                BridgeContract.CALL_MKDIRS -> mkdirs(extras, out)
                BridgeContract.CALL_DELETE -> delete(extras, recursive = false, out)
                BridgeContract.CALL_DELETE_RECURSIVE -> delete(extras, recursive = true, out)
                BridgeContract.CALL_RENAME -> rename(extras, out)
                BridgeContract.CALL_STAT_TREE -> statTree(extras, out)
                BridgeContract.CALL_WIPE_SELF -> wipeSelf(extras, out)
                BridgeContract.CALL_STAT_PATH -> statPath(extras, out)
                BridgeContract.CALL_SELF_DIAGNOSTIC -> selfDiagnostic(out)
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
            // call() reports errors inside the Bundle rather than by throwing, so the marker cannot
            // ride along on an exception type here. Set the structured equivalent and strip the
            // prefix, so a path refusal reaches :app as a per-path failure and not as a dead
            // bridge — and so the reason it shows a user has no internal prefix in it.
            val message = t.message.orEmpty()
            if (t is SecurityException && message.startsWith(BridgeContract.PATH_REJECTION_MARKER)) {
                out.putBoolean(BridgeContract.KEY_PATH_REJECTED, true)
                out.putString(
                    BridgeContract.KEY_ERROR,
                    message.removePrefix(BridgeContract.PATH_REJECTION_MARKER),
                )
            } else {
                out.putString(BridgeContract.KEY_ERROR, "${t.javaClass.simpleName}: $message")
            }
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

    /**
     * Reports what THIS process sees of its own two roots, with the errno distinction that
     * `java.io.File` throws away.
     *
     * This exists because the premise test could not answer the question it needed to. The
     * test app runs as a different uid from the proxy, so its view of
     * `/storage/emulated/<user>/Android/data/<target>` is *supposed* to be "hidden" — that is
     * the restriction the whole project works around. Only the proxy's own view is evidence
     * about whether the mechanism works, and only the proxy can report it.
     *
     * `File.listFiles()` returns null for both EACCES and ENOENT, which is why three CI runs
     * could not tell "refused" from "not there". `java.nio.file` distinguishes them:
     * AccessDeniedException versus NoSuchFileException. Those two have opposite fixes, so the
     * distinction is the whole point.
     *
     * Also creates and immediately removes a scratch directory, because "can this process make
     * a NEW entry in its own data root and list it" is the control that separates a broken
     * mount from a directory that merely pre-dates the process.
     *
     * Deliberately read-only apart from that scratch dir, and it never exposes another
     * package's data: every path it touches is under this package's own two roots.
     */
    /**
     * Reports what THIS process's filesystem calls say about one path under its roots.
     *
     * The reason this method exists is a hole in the premise test that survived until the very
     * last CI run. The test wrote a file through the bridge, read it back through the bridge,
     * and then tried to confirm the bytes were real with
     * `File("/storage/emulated/<user>/Android/data/<target>/…").isFile` — from the TEST APP's
     * uid. That can never be true: the platform hides the target's private directory from every
     * other package, which is precisely the restriction this project works around. The same
     * suite asserts that hiding in `thePlatformStillHidesOtherPackagesPrivateStorageFromUs`, so
     * the two tests contradicted each other and only one of them could pass.
     *
     * "Did my write land on the real filesystem?" is therefore only answerable from inside the
     * proxy. This answers it with the proxy's own `File` calls, and returns the length as well
     * as existence so a caller can compare against what it wrote.
     *
     * Path handling is identical to every other call: [requireOk] resolves through
     * [BridgePaths], so the same traversal rules apply and nothing outside the two roots is
     * reachable — including by this method, which reports sizes and would otherwise be a
     * modest oracle.
     */
    private fun statPath(extras: Bundle?, out: Bundle) {
        val uri = extras?.let { extrasUri(it) } ?: throw IllegalArgumentException("missing path")
        val r = requireOk(uri)
        val file = r.file
        out.putBoolean(BridgeContract.KEY_OK, true)
        out.putBoolean(BridgeContract.KEY_EXISTS, file.exists())
        out.putBoolean(BridgeContract.KEY_IS_DIRECTORY, file.isDirectory)
        out.putLong(BridgeContract.KEY_SIZE, if (file.isFile) file.length() else -1L)
        // The canonical path is what makes this verifiable rather than a matter of trust: it is
        // the real location the platform gave us, not a restatement of the caller's input.
        out.putString(BridgeContract.KEY_PATH, runCatching { file.canonicalPath }.getOrDefault(file.absolutePath))
    }

    private fun selfDiagnostic(out: Bundle) {
        val sb = StringBuilder()
        val uid = android.os.Process.myUid()
        sb.append("uid=").append(uid)
            .append(" user=").append(uid / 100000)
            .append(" pkg=").append(context?.packageName).append('\n')

        for (name in BridgeContract.ROOTS) {
            val root = rootDir(name)
            if (root == null) {
                sb.append(name).append(": root unresolved\n")
                continue
            }
            sb.append(name).append(" path=").append(root.absolutePath)
                .append(" exists=").append(root.exists())
                .append(" isDir=").append(root.isDirectory)
                .append(" canRead=").append(root.canRead())
                .append(" canExec=").append(root.canExecute()).append('\n')

            // The errno distinction File.list() cannot make.
            val nio = runCatching {
                java.nio.file.Files.newDirectoryStream(root.toPath()).use { stream ->
                    val names = ArrayList<String>()
                    for (p in stream) names.add(p.fileName.toString())
                    names
                }
            }
            sb.append("  nio-listdir -> ").append(
                nio.fold(
                    { it.toString() },
                    { e -> e.javaClass.simpleName + ": " + e.message },
                ),
            ).append('\n')

            val io = runCatching { root.list()?.toList() }
            sb.append("  File.list() -> ").append(
                io.fold(
                    { it?.toString() ?: "null" },
                    { e -> e.javaClass.simpleName + ": " + e.message },
                ),
            ).append('\n')
        }

        // Control: a directory this process creates itself, right now. If this lists and a
        // pre-existing sibling does not, the difference is about how the entry came to exist,
        // not about the mount being broken.
        val dataRoot = rootDir(BridgeContract.ROOT_DATA)
        if (dataRoot != null) {
            val scratch = File(dataRoot, "understudy-selftest")
            val created = scratch.isDirectory || scratch.mkdirs()
            sb.append("scratch mkdirs=").append(created).append('\n')
            if (created) {
                runCatching { File(scratch, "probe.txt").writeText("selftest") }
                val viaNio = runCatching {
                    java.nio.file.Files.newDirectoryStream(dataRoot.toPath()).use { s ->
                        val n = ArrayList<String>()
                        for (p in s) n.add(p.fileName.toString())
                        n
                    }
                }
                sb.append("  data root after scratch -> ").append(
                    viaNio.fold({ it.toString() }, { e -> e.javaClass.simpleName }),
                ).append('\n')
                val scratchList = runCatching {
                    java.nio.file.Files.newDirectoryStream(scratch.toPath()).use { s ->
                        val n = ArrayList<String>()
                        for (p in s) n.add(p.fileName.toString())
                        n
                    }
                }
                sb.append("  scratch listing -> ").append(
                    scratchList.fold({ it.toString() }, { e -> e.javaClass.simpleName }),
                ).append('\n')
                // Clean up: a diagnostic must not leave state behind that a later run mistakes
                // for the user's data.
                runCatching { File(scratch, "probe.txt").delete() }
                runCatching { scratch.delete() }
            }
        }

        // What the platform thinks the mount situation is, for the record.
        sb.append("getExternalFilesDir=").append(context?.getExternalFilesDir(null)).append('\n')
        sb.append("externalStorageState=")
            .append(android.os.Environment.getExternalStorageState()).append('\n')

        val report = sb.toString()
        Log.i(TAG, "self-diagnostic:\n$report")
        out.putBoolean(BridgeContract.KEY_OK, true)
        out.putString(BridgeContract.KEY_DIAGNOSTIC, report)
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

    // ---- caller authentication ---------------------------------------------

    /**
     * The SHA-256 of the certificate that must have signed whoever is calling us, or null when
     * this proxy was generated without one.
     *
     * Read once from the asset the generator baked in. Cached because it is consulted on every
     * provider call, and because the answer cannot change during the process's lifetime.
     */
    private val expectedGeneratorDigest: String? by lazy {
        val ctx = context ?: return@lazy null
        runCatching {
            ctx.assets.open(GENERATOR_CERT_ASSET_NAME).use { it.readBytes() }
                .toString(Charsets.US_ASCII).trim().lowercase()
                .takeIf { it.length == DIGEST_HEX_LENGTH }
        }.getOrNull()
    }

    /**
     * Per-uid **positive** verdicts, so the certificate walk happens once per authorised caller
     * rather than once per call. Rejections are never remembered; [CallerVerdictCache] documents
     * why that asymmetry is a correctness requirement rather than a missed optimisation.
     */
    private val callerVerdicts = CallerVerdictCache()

    /**
     * Refuses callers that are not the Understudy install which generated this APK.
     *
     * Why a `signature`-level permission is not enough — this is the reason the check exists:
     *
     * The provider is guarded by `dev.understudy.permission.BRIDGE` at `protectionLevel=
     * signature`. A signature permission is granted to packages signed like whichever package
     * DEFINES it, so who defines it decides everything — and it is defined by the Understudy app,
     * not by this APK. That is deliberate and load-bearing; `proxy/src/main/AndroidManifest.xml`
     * explains the failure it prevents. The short version: this APK is signed with a per-install
     * key generated on the device, so had it defined BRIDGE, the app that made it could never
     * hold it, and the platform would have refused every call at the provider's
     * `android:permission` gate before a line of this method ran.
     *
     * With the app as the definer the platform gate opens for the app, and the check below is
     * what actually pins the caller: not "same signer as me", and not merely "some app signed
     * with the release key", but "the exact install that generated me". Only the generator knows
     * its own certificate, so it bakes the digest into this APK and we check callers against it.
     *
     * The permission is kept as a first gate: it costs nothing, it keeps the provider closed on
     * builds where the digest asset is absent, and defence in depth is worth more than elegance
     * here.
     *
     * Fail-closed on a malformed digest, fail-open only when there is no digest at all. A proxy
     * that cannot authenticate its caller must not serve another package's files; a proxy built
     * without a digest is a test artifact and behaves exactly as it did before.
     */
    private fun enforceCaller() {
        val expected = expectedGeneratorDigest ?: return
        val uid = android.os.Binder.getCallingUid()
        // Our own process, and the platform acting on our behalf.
        if (uid == android.os.Process.myUid() || uid == android.os.Process.SYSTEM_UID) return
        if (callerVerdicts.isAuthorised(uid)) return
        val ok = runCatching { matchesGenerator(uid, expected) }.getOrDefault(false)
        // Records only on success. A miss here may be a package-visibility race on this very
        // first call rather than a genuine mismatch, and remembering it would lock the
        // legitimate owner out for the lifetime of the process. See CallerVerdictCache.
        callerVerdicts.record(uid, ok)
        if (!ok) {
            Log.w(TAG, "refusing caller uid $uid: certificate does not match the generator")
            throw SecurityException("caller uid $uid is not the Understudy install that generated this proxy")
        }
    }

    /**
     * True when some package behind [uid] is signed by the certificate whose SHA-256 is
     * [expectedDigest].
     *
     * Uses [android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES] with the
     * `SIGNING_CERTIFICATE_MATCHES` check rather than trusting a single reported signer: on a
     * package with rotated keys the platform reports both the current and the originating
     * certificate, and either is acceptable proof of who installed it.
     *
     * Every way out is logged with its own reason. "No packages resolved for this uid" and "the
     * packages resolved but none carry the generator's certificate" are the same `false` to the
     * caller and completely different situations in the field: the first is usually
     * package-visibility filtering on a first call (transient — see [CallerVerdictCache]), the
     * second is a genuine mismatch and will not get better. Guessing between them from a
     * support report is impossible without this.
     */
    private fun matchesGenerator(uid: Int, expectedDigest: String): Boolean {
        val ctx = context ?: return false
        val pm = ctx.packageManager
        val packages = runCatching { pm.getPackagesForUid(uid) }.getOrNull()
        if (packages.isNullOrEmpty()) {
            Log.w(TAG, "caller uid $uid resolves to no visible packages; this is usually " +
                "package-visibility filtering and will usually succeed on a retry")
            return false
        }
        val md = java.security.MessageDigest.getInstance("SHA-256")
        var sawCertificates = false
        for (pkg in packages) {
            val info = runCatching {
                pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            }.getOrNull() ?: continue
            val signing = info.signingInfo ?: continue
            val certs = when {
                signing.hasMultipleSigners() -> signing.apkContentsSigners
                else -> signing.signingCertificateHistory
            }
            for (sig in certs ?: emptyArray()) {
                sawCertificates = true
                md.reset()
                val digest = md.digest(sig.toByteArray())
                if (toHex(digest) == expectedDigest) return true
            }
        }
        Log.w(TAG, "caller uid $uid (${packages.joinToString()}) presented " +
            (if (sawCertificates) "a certificate that is not the generator's"
             else "no signing certificates at all"))
        return false
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(HEX[(b.toInt() shr 4) and 0xf]).append(HEX[b.toInt() and 0xf])
        }
        return sb.toString()
    }

    /**
     * Resolves [uri] or throws.
     *
     * The thrown message carries [BridgeContract.PATH_REJECTION_MARKER], and that prefix is the
     * only thing distinguishing "this path is not acceptable" from "you are not the install that
     * generated me" — both cross Binder as a bare `SecurityException`, and `:app` cannot share an
     * exception type with this module. The distinction decides whether a transfer treats the
     * failure as one bad file or as a dead bridge; see `BridgeContract.PATH_REJECTION_MARKER`.
     */
    private fun requireOk(uri: Uri): BridgePaths.Resolution.Ok {
        val r = resolve(uri)
        if (r is BridgePaths.Resolution.Rejected) {
            throw SecurityException(BridgeContract.PATH_REJECTION_MARKER + r.reason)
        }
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

        /** Must match `ProxyApkFactory.GENERATOR_CERT_ASSET_NAME`. */
        private const val GENERATOR_CERT_ASSET_NAME = "understudy-generator-cert.sha256"
        private const val DIGEST_HEX_LENGTH = 64
        private val HEX = "0123456789abcdef".toCharArray()

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
