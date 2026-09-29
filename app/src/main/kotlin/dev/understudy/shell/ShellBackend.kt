package dev.understudy.shell

/**
 * A way to run `adb`-grade shell commands.
 *
 * The app needs shell for exactly two things, both impossible for an unprivileged app:
 *  1. `pm uninstall -k --user N` — uninstall while *keeping* `/Android/data/<pkg>`;
 *  2. `mv` inside another user's `Android/data` — the rename-aside dance that resolves a
 *     device-wide signature conflict without destroying the data.
 *
 * Everything else is done in-profile with no privilege at all.
 */
interface ShellBackend {
    /** Stable identifier, for settings and diagnostics. */
    val id: String

    /** Human-readable name for the UI. */
    val displayName: String

    /** Whether this backend can currently execute commands. */
    suspend fun isAvailable(): Boolean

    /**
     * Runs [command] and returns its exit code and combined output.
     *
     * Implementations that cannot execute should throw [ShellUnavailable] rather than returning
     * a fake failure, so callers can distinguish "the command failed" from "we could not run
     * it" and fall through to the next backend.
     */
    suspend fun execute(command: String): ShellResult

    /**
     * What the user must do to make this backend usable, or null if it already is.
     *
     * Surfacing this as data rather than as an error string lets the UI show a checklist
     * instead of a dead end.
     */
    suspend fun setupHint(): String? = null
}

data class ShellResult(
    val exitCode: Int,
    val output: String,
) {
    val isSuccess: Boolean get() = exitCode == 0
}

class ShellUnavailable(message: String) : Exception(message)

/**
 * Always-available fallback: generate the exact command text for the user to run themselves.
 *
 * This is not a cop-out. The brief's own model is that privileged features come from an
 * owner-profile install, and a user with a USB cable needs precisely this. It also means the
 * app is never *unable* to help — worst case it tells you exactly what to type, with your real
 * user id and paths already filled in, which beats a generic wiki page.
 *
 * Commands are emitted as a single copy-pasteable block with `set -e` so a failure stops the
 * sequence rather than leaving the data half-renamed.
 */
class ManualShellBackend : ShellBackend {

    override val id = "manual"
    override val displayName = "Copy-paste adb commands"

    override suspend fun isAvailable() = true

    override suspend fun execute(command: String): ShellResult =
        throw ShellUnavailable("This backend only generates commands; it cannot execute them.")
}

/**
 * Builds the shell command sequences this app needs.
 *
 * Kept separate from any backend so the *content* of the commands can be unit-tested without a
 * device: getting a rename-aside sequence wrong can destroy the data it was meant to protect,
 * which is the one failure mode this app must not have.
 *
 * Every sequence is written to be idempotent where possible and to fail loudly otherwise.
 */
object ShellCommands {

    private const val EMULATED = "/storage/emulated"

    /**
     * Lists the device's users so the operator can pick the right target.
     *
     * Secondary users are usually 10, 11, …; a work profile is 10+ as well but flagged
     * `managed`. Getting this wrong means operating on the wrong profile's data.
     */
    fun listUsers(): String = "adb shell pm list users"

    /** Absolute path of a root directory for [packageName] in user [userId]. */
    fun dataDir(userId: Int, packageName: String): String = "$EMULATED/$userId/Android/data/$packageName"

    fun obbDir(userId: Int, packageName: String): String = "$EMULATED/$userId/Android/obb/$packageName"

    /**
     * The RAW lower filesystem behind the FUSE view above: what `/storage/emulated/<userId>`
     * is a mount of. Only root can traverse it — and, counter-intuitively, root is *denied*
     * the FUSE view of another user (observed on API 34/35 emulators: even uid 0 gets EACCES
     * on `/storage/emulated/10`), so root-backed operations such as [restoreOwnership] must
     * use these paths instead.
     */
    fun rawDataDir(userId: Int, packageName: String): String = "/data/media/$userId/Android/data/$packageName"

    fun rawObbDir(userId: Int, packageName: String): String = "/data/media/$userId/Android/obb/$packageName"

    /** The backup suffix used by the rename-aside sequence. */
    const val BACKUP_SUFFIX = ".understudy-bak"

    /**
     * Renames both private directories aside so a full uninstall cannot delete them.
     *
     * `adb shell` runs as uid 2000 in the `shell_data_file` SELinux domain, which is exempt
     * from the FUSE filter that hides `Android/data` from apps — that is the only reason this
     * works, and it is also why it can see *every* user's tree.
     *
     * Each `mv` is guarded by a `-d` test so re-running the sequence is harmless, and so a
     * missing directory (never installed for that user) is not treated as an error.
     */
    fun renameAside(userId: Int, packageName: String): String {
        val data = dataDir(userId, packageName)
        val obb = obbDir(userId, packageName)
        return listOf(
            "# Move the target's private storage aside so uninstalling cannot delete it.",
            "# Re-running is safe: each move is guarded by a directory test.",
            "adb shell '",
            "set -e",
            "[ -d '$data' ] && [ ! -e '$data$BACKUP_SUFFIX' ] && mv '$data' '$data$BACKUP_SUFFIX' || true",
            "[ -d '$obb' ] && [ ! -e '$obb$BACKUP_SUFFIX' ] && mv '$obb' '$obb$BACKUP_SUFFIX' || true",
            "ls -ld '$data$BACKUP_SUFFIX' '$obb$BACKUP_SUFFIX' 2>/dev/null || true",
            "'",
        ).joinToString("\n")
    }

    /** Moves the directories back after the proxy work is finished. */
    fun renameBack(userId: Int, packageName: String): String {
        val data = dataDir(userId, packageName)
        val obb = obbDir(userId, packageName)
        return listOf(
            "# Restore the private storage to its real name.",
            "# Will not clobber an existing directory: if the real name is already taken, this",
            "# stops and leaves the backup in place for you to inspect.",
            "#",
            "# mv preserves ownership, so if the data was correctly owned before the rename-aside",
            "# it is still correct now. If you restored it from a backup as root instead, run the",
            "# ownership repair afterwards or the app will not be able to read its own files.",
            "adb shell '",
            "set -e",
            "[ -d '$data$BACKUP_SUFFIX' ] && [ ! -e '$data' ] && mv '$data$BACKUP_SUFFIX' '$data' || true",
            "[ -d '$obb$BACKUP_SUFFIX' ] && [ ! -e '$obb' ] && mv '$obb$BACKUP_SUFFIX' '$obb' || true",
            "ls -ld '$data' '$obb' 2>/dev/null || true",
            "'",
        ).joinToString("\n")
    }

    /**
     * Full uninstall for **all users**, which is what actually clears the device-wide signature
     * record and frees the package name.
     *
     * Safe to run only *after* [renameAside], because this destroys the data directories.
     */
    fun uninstallEverywhere(packageName: String): String = listOf(
        "# Full uninstall. This clears the device-wide signature record and frees the name.",
        "# ONLY run this after the rename-aside step — it deletes Android/{data,obb}/$packageName.",
        "adb shell pm uninstall $packageName",
    ).joinToString("\n")

    /**
     * Uninstalls the proxy while keeping its data directories.
     *
     * The `-k` flag is the whole reason a shell is needed at teardown: there is no app-accessible
     * API for it. Note the retained package keeps its signature record, so re-proxying later
     * requires the same key — which is why the signing identity must be stable.
     */
    fun uninstallKeepingData(userId: Int, packageName: String): String = listOf(
        "# Uninstall the proxy but KEEP its private storage.",
        "adb shell pm uninstall -k --user $userId $packageName",
    ).joinToString("\n")

    /** Installs an APK for one specific user — the owner-profile-only capability. */
    fun installForUser(userId: Int, apkPathOnDevice: String): String = listOf(
        "# Install for user $userId only.",
        "adb install --user $userId -r '$apkPathOnDevice'",
    ).joinToString("\n")

    /** Pushes a generated APK to somewhere `adb install` can read it. */
    fun pushApk(localPath: String, remotePath: String = "/data/local/tmp/proxy.apk"): String =
        "adb push '$localPath' '$remotePath'"

    /**
     * Repairs ownership of a package's app-specific external storage.
     *
     * This is not cosmetic, and it is the single easiest way to "lose" a save file that is still
     * sitting right there on disk. The FUSE layer attributes `Android/data/<pkg>` by **owning
     * uid**, not merely by path: the platform creates those directories as the app's uid, and
     * anything written as root (an adb restore, a `cp` from a backup, a `tar -x`) stays
     * `root:ext_data_rw` mode 2770/660. The owning app then gets EACCES on its *own* directory,
     * and `File.listFiles()` returns null — which looks exactly like "the data is gone".
     *
     * Verified on API 34/35 emulators: a proxy installed as the target package could not see a
     * root-planted `Android/data/<pkg>/planted` until the tree was chowned to its uid — and the
     * uid that works is the **per-user** one.
     *
     * Two traps this sequence is written around, both paid for in CI cycles:
     *  - `dumpsys package` printed `userId=<appId>` up to Android 13 and `appId=<appId>` from
     *    Android 14 on — and in BOTH cases that number is the app id, shared by every user, NOT
     *    the uid the app's processes run as. User [userId]'s processes run as
     *    `userId * 100000 + appId` (`u10_a148` == 1010148). `pm list packages -U --user N`
     *    prints the per-user uid directly, on every API level, so that is what we parse.
     *  - `UID` is a READ-ONLY variable in bash: `UID=$(...)` aborts the operator's script with
     *    "UID: readonly variable". Hence `APP_UID` below.
     *
     * Requires root (`adb root` on userdebug/eng, `su` on rooted production): chown needs
     * CAP_CHOWN, and the raw `/data/media` path is root-only anyway. Root is also *denied* the
     * FUSE view of another user, so these commands target [rawDataDir]/[rawObbDir]. Run after
     * any adb-side restore, and after [renameBack] if the backup was made as root.
     */
    fun restoreOwnership(userId: Int, packageName: String): String {
        val data = rawDataDir(userId, packageName)
        val obb = rawObbDir(userId, packageName)
        // NOTE the ${'$'} escapes: APP_UID is a *shell* variable evaluated on the operator's
        // machine, so it must survive Kotlin string interpolation literally.
        return listOf(
            "# Make the app's own uid own its private storage again.",
            "# Needs root: run `adb root` first (userdebug/eng), or the adb shell lines via su.",
            "# Without this, data restored via adb is unreadable BY THE APP ITSELF: FUSE",
            "# attributes Android/data/<pkg> by owning uid, so root-owned files are denied",
            "# even to the package that owns them, and it presents as an empty directory.",
            "#",
            "# `pm list packages -U --user $userId` prints the PER-USER uid directly; `-u` also",
            "# covers the retained state that `pm uninstall -k` leaves behind. `--user` is",
            "# mandatory — the default queries user 0, where the package may not exist.",
            "APP_UID=${'$'}(adb shell pm list packages -U -u --user $userId $packageName | tr -d '\\r' | grep -F 'package:$packageName uid:' | head -1 | sed -E 's/.*uid:([0-9]+).*/\\1/')",
            "echo \"per-user uid=${'$'}APP_UID\"",
            "[ -n \"${'$'}APP_UID\" ] || echo '!! no uid for $packageName as user $userId — installed or retained nowhere?'",
            "# Owner-only chown: the ext_data_rw/ext_obb_rw group and the setgid bit must survive,",
            "# because that is the state the platform itself creates. Do NOT chmod 771 — it clears",
            "# setgid, and the platform's mode for these directories is 2770, not 771.",
            "[ -n \"${'$'}APP_UID\" ] && adb shell \"chown -R ${'$'}APP_UID '$data' 2>/dev/null || true\"",
            "[ -n \"${'$'}APP_UID\" ] && adb shell \"chown -R ${'$'}APP_UID '$obb' 2>/dev/null || true\"",
            "adb shell \"ls -lan '$data' '$obb'\"",
        ).joinToString("\n")
    }

    /** Inspects what is actually present, before and after any of the above. */
    fun inspect(userId: Int, packageName: String): String = listOf(
        "# What is on disk for user $userId right now?",
        "adb shell ls -la '$EMULATED/$userId/Android/data/' | grep -F '$packageName' || echo '  (nothing in data)'",
        "adb shell ls -la '$EMULATED/$userId/Android/obb/'  | grep -F '$packageName' || echo '  (nothing in obb)'",
        "adb shell pm list packages --user $userId | grep -F '$packageName' || echo '  (not installed for user $userId)'",
        "adb shell dumpsys package $packageName | grep -E 'codePath|signatures|installed=|userId=|appId=' | head -20",
    ).joinToString("\n")

    /**
     * The complete conflict-resolution runbook, in order.
     *
     * This is the sequence for the common real-world case: the target app is installed somewhere
     * on the device under its developer's signature, so the proxy cannot simply be installed.
     */
    fun fullRenameAsideRunbook(userId: Int, packageName: String): String = listOf(
        "### Rename-aside runbook for $packageName (user $userId)",
        "#",
        "# Why this is needed: package identity and signing are DEVICE-WIDE. If $packageName is",
        "# installed for any user with a different signature, installing the proxy for user $userId",
        "# fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE. `pm uninstall -k` does not help: it keeps",
        "# the signature record and the reserved name.",
        "#",
        "# Run each step, check its output, and only then run the next.",
        "",
        "# 1. See the current state.",
        inspect(userId, packageName),
        "",
        "# 2. Move the private storage aside.",
        renameAside(userId, packageName),
        "",
        "# 3. Confirm the data is now under the $BACKUP_SUFFIX names, then full-uninstall.",
        uninstallEverywhere(packageName),
        "",
        "# 4. Install the generated proxy for user $userId (push it first).",
        pushApk("<path-to-generated-proxy.apk>"),
        installForUser(userId, "/data/local/tmp/proxy.apk"),
        "",
        "# 5. Do the transfer from inside Understudy, then tear down keeping the data:",
        uninstallKeepingData(userId, packageName),
        "",
        "# 6. Restore the directories to their real names.",
        renameBack(userId, packageName),
        "",
        "# 6b. Repair ownership. Skip only if you are certain nothing was ever written as root:",
        "#     FUSE attributes Android/data/<pkg> by owning uid, so root-owned files are denied",
        "#     even to the app that owns them, and it presents as an empty directory.",
        restoreOwnership(userId, packageName),
        "",
        "# 7. Optionally reinstall the real app; it adopts the restored directories.",
        "#    (It must be the same signature as before, or step 3 has to be repeated.)",
        inspect(userId, packageName),
    ).joinToString("\n")
}
