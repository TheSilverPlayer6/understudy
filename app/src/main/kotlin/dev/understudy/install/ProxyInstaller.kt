package dev.understudy.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Process
import android.provider.Settings
import java.io.File
import java.io.IOException

/**
 * Installs and uninstalls proxy APKs **for the calling user's own profile**.
 *
 * That limitation is not an oversight, it is the platform: `PackageInstaller` can only target
 * the calling user without `INSTALL_PACKAGES` (signature|privileged), and uninstalls that
 * *retain data* are not exposed to apps at all. Both are what the shell backend is for. See
 * `notes` in the repo README and `ShellBackend` for the escalation paths.
 *
 * `REQUEST_INSTALL_PACKAGES` is a **per-user** appop. It must be granted separately inside each
 * secondary profile, from that profile's own Settings — which is awkward enough that the UI
 * should detect the missing grant and deep-link there rather than letting the commit fail with
 * an opaque status code.
 */
class ProxyInstaller(private val context: Context) {

    private val installer: PackageInstaller get() = context.packageManager.packageInstaller

    /** Our own user id, derived from the uid. Works on every API level we support. */
    val currentUserId: Int get() = Process.myUid() / PER_USER_RANGE

    /** Whether we may stage an install at all. */
    fun canRequestInstalls(): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** Settings screen where the user grants "install unknown apps" for *this* profile. */
    fun unknownSourcesSettingsIntent(): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            android.net.Uri.parse("package:${context.packageName}"),
        )

    /**
     * Stages [apk] into a session and returns an [IntentSender] that launches the system
     * installer confirmation.
     *
     * The result arrives at [InstallResultReceiver] via the supplied [PendingIntent]; this call
     * returns as soon as the bytes are staged, well before the user has decided.
     *
     * @param sessionId out-parameter receiving the session id, so the caller can correlate the
     *   asynchronous result and abandon the session on failure
     */
    fun stage(apk: File, packageName: String, resultIntent: PendingIntent): StagedInstall {
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setSize(apk.length())
            // We never update a proxy in place across signature changes, but if we do update,
            // keeping the data directories is the entire point of the exercise. `setDontKillApp`
            // only exists from API 34; below that the platform decides, and the flag is an
            // optimisation rather than a correctness requirement.
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                setDontKillApp(true)
            }
            // Helps the platform attribute the install; harmless if the file URI is opaque.
            setOriginatingUri(android.net.Uri.fromFile(apk))
        }

        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite(ENTRY_NAME, 0, apk.length()).use { out ->
                apk.inputStream().use { input -> input.copyTo(out) }
                session.fsync(out)
            }
            // NOTE: `Session` has no `createIntentSender(PendingIntent)`. The status receiver is
            // passed to `commit()` as an IntentSender; the confirmation UI is then delivered back
            // to us as STATUS_PENDING_USER_ACTION carrying an Intent we must launch.
            session.commit(resultIntent.intentSender)
        }
        return StagedInstall(sessionId = sessionId, packageName = packageName)
    }

    /**
     * Abandons a staged session. Call this whenever a flow gives up mid-install, otherwise the
     * session lingers in the system's list until it times out.
     */
    fun abandon(sessionId: Int) {
        runCatching { installer.abandonSession(sessionId) }
    }

    /** Sessions we currently own — useful for diagnostics and for cleaning up after a crash. */
    fun mySessions(): List<PackageInstaller.SessionInfo> = installer.mySessions

    /**
     * Launches the system uninstall UI for [packageName].
     *
     * **This destroys `/Android/data/<packageName>`.** There is no app-accessible variant that
     * keeps it; the only mitigations are `hasFragileUserData` (which offers the user a checkbox
     * in this very dialog — the proxy manifest sets it) or a shell `pm uninstall -k`.
     * Callers must have already secured the data, or have told the user in no uncertain terms.
     */
    /**
     * Asks the system to uninstall [packageName], showing its confirmation dialog.
     *
     * Note the API shape: `uninstall(pkg, IntentSender)` returns **Unit** — only the
     * `uninstall(pkg, int flags)` overload returns an `IntentSender`, and that one needs
     * `DELETE_PACKAGES`. So this is fire-and-forget, with the outcome arriving asynchronously
     * at [resultIntent].
     *
     * For a proxy declaring `hasFragileUserData="true"` the dialog offers a "Keep app data"
     * checkbox — the only unprivileged way to preserve `Android/data/<pkg>` across an
     * uninstall. We cannot tick it for the user.
     */
    @androidx.annotation.RequiresPermission(android.Manifest.permission.REQUEST_DELETE_PACKAGES)
    fun requestUninstall(packageName: String, resultIntent: PendingIntent) {
        installer.uninstall(packageName, resultIntent.intentSender)
    }

    /** True when [packageName] appears to be installed for this user. */
    fun isInstalled(packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
    }.isSuccess

    companion object {
        private const val ENTRY_NAME = "proxy.apk"
        private const val PER_USER_RANGE = 100_000
    }
}

/**
 * A staged install.
 *
 * There is no `IntentSender` to launch here: `Session.commit()` already handed the session to
 * the system, which will either install directly or call back with
 * `STATUS_PENDING_USER_ACTION` carrying a confirmation `Intent`. The orchestrator turns that
 * into a [dev.understudy.core.SessionEvent].
 */
data class StagedInstall(
    val sessionId: Int,
    val packageName: String,
)

/** Thrown when staging fails before the user ever sees a prompt. */
class InstallStagingException(message: String, cause: Throwable? = null) : IOException(message, cause)
