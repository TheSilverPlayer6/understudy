package dev.understudy.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Receives `PackageInstaller` session results.
 *
 * Not exported: the `PendingIntent` we hand to the session already carries our identity, so
 * only the system installer can deliver to it.
 *
 * Results are published onto [results] as a plain callback rather than a Flow because the
 * receiver is instantiated by the framework and has no injection point; the orchestrator
 * registers itself at startup and clears on teardown.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        val sessionId = intent.getIntExtra(PENDING_SESSION_ID, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val packageName = intent.getStringExtra(PendingInstall.PACKAGE_NAME)

        val outcome = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The system wants the user to confirm; the confirmation UI is carried on the
                // intent itself and must be launched from an activity context.
                Outcome.NeedsConfirmation(
                    sessionId = sessionId,
                    packageName = packageName,
                    confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT),
                )
            }

            PackageInstaller.STATUS_SUCCESS -> Outcome.Success(sessionId, packageName)

            else -> Outcome.Failure(
                sessionId = sessionId,
                packageName = packageName,
                status = status,
                reason = describe(status, message),
            )
        }

        Log.i(TAG, "install result: $outcome")
        listener?.invoke(outcome)
    }

    companion object {
        private const val TAG = "InstallResult"

        /** Extra we add so the receiver can tell which target a result belongs to. */
        const val PENDING_SESSION_ID = "dev.understudy.extra.SESSION_ID"

        /**
         * The bridge permission. Duplicated here rather than imported from `BridgeContract`
         * because the string has to survive into a user-facing message, and because a rename
         * that missed one of the two sites would produce a confusing error message rather than a
         * compile error. `BridgePermissionOwnershipTest` pins the same string in both manifests.
         */
        const val BRIDGE_PERMISSION = "dev.understudy.permission.BRIDGE"

        var listener: ((Outcome) -> Unit)? = null

        /**
         * Turns a raw `PackageInstaller` status into something worth showing a user.
         *
         * Three of these dominate in practice and are called out specifically:
         *  - `INSTALL_FAILED_UPDATE_INCOMPATIBLE` means the package is installed or retained
         *    *somewhere on the device* under a different signature. Because package identity is
         *    device-wide rather than per-user, installing into a fresh secondary profile does
         *    **not** avoid it. This is the single most likely failure and the UI must explain
         *    the rename-aside remedy rather than just printing the code.
         *  - `INSTALL_FAILED_DUPLICATE_PERMISSION` means some other package already *defines*
         *    `dev.understudy.permission.BRIDGE`. Permission definitions are device-wide too, so
         *    this is not per-profile either; in practice it is a proxy left over from an older
         *    build. It reached CI before it reached a user — run #28 died on it in ten seconds.
         *  - `INSTALL_FAILED_USER_REJECTED` is the user declining the prompt, which is not an
         *    error worth alarming anyone about.
         *
         * Matching is on the message text rather than the status code alone, because
         * `PackageInstaller` reports most of these as the same generic
         * [PackageInstaller.STATUS_FAILURE_INVALID] / [PackageInstaller.STATUS_FAILURE_CONFLICT]
         * and puts the real reason in `EXTRA_STATUS_MESSAGE`. The raw text is always included so
         * an unmapped failure is still reportable verbatim.
         */
        fun describe(status: Int, message: String?): String {
            val raw = message?.takeIf { it.isNotBlank() } ?: statusName(status)
            return when {
                raw.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") ||
                    status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                    "$raw — this package is installed or retained elsewhere on the device with a " +
                        "different signature. Package identity is device-wide, so installing into " +
                        "another profile does not avoid the clash. Use the shell-assisted " +
                        "rename-aside flow."

                raw.contains("INSTALL_FAILED_USER_REJECTED") ->
                    "You declined the install prompt. Nothing was installed."

                // Learned the hard way: CI run #28 died on exactly this, in under ten seconds,
                // before a single test ran. A custom permission may be DEFINED by only one
                // package on the whole device, so any second package declaring the same name is
                // refused — whatever user it is for, because permission definitions are
                // device-wide like package identity is.
                //
                // For this app there is exactly one realistic cause: a proxy generated by an
                // older build, when the proxy declared BRIDGE itself rather than requiring the
                // one Understudy defines. See research/06-milestone5-bridge-permission-ownership.md.
                raw.contains("INSTALL_FAILED_DUPLICATE_PERMISSION") ->
                    "$raw — another package already defines $BRIDGE_PERMISSION. Only one package " +
                        "on the device may define a given permission name, and definitions are " +
                        "device-wide rather than per-profile. This is almost certainly a proxy " +
                        "left over from an older Understudy build, which used to declare the " +
                        "permission itself. Uninstall that proxy and try again."

                raw.contains("INSTALL_FAILED_ALREADY_EXISTS") ->
                    "$raw — a package with this name is already installed for this user."

                raw.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE") ->
                    "Not enough storage space to install the proxy."

                raw.contains("INSTALL_FAILED_INVALID_APK") ->
                    "The generated APK was rejected as invalid. Please report this with the " +
                        "target package name."

                else -> raw
            }
        }

        private fun statusName(status: Int): String = when (status) {
            PackageInstaller.STATUS_FAILURE -> "STATUS_FAILURE"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "STATUS_FAILURE_BLOCKED"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "STATUS_FAILURE_ABORTED"
            PackageInstaller.STATUS_FAILURE_INVALID -> "STATUS_FAILURE_INVALID"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "STATUS_FAILURE_CONFLICT"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "STATUS_FAILURE_STORAGE"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "STATUS_FAILURE_INCOMPATIBLE"
            else -> "status=$status"
        }
    }

    sealed class Outcome {
        data class Success(val sessionId: Int, val packageName: String?) : Outcome()

        /**
         * The system needs the user to confirm. [confirmation] is the `Intent` to start; it must
         * be launched from an Activity (it carries `FLAG_ACTIVITY_NEW_TASK` semantics that a
         * receiver cannot satisfy on its own).
         */
        data class NeedsConfirmation(
            val sessionId: Int,
            val packageName: String?,
            val confirmation: Intent?,
        ) : Outcome()

        data class Failure(
            val sessionId: Int,
            val packageName: String?,
            val status: Int,
            val reason: String,
        ) : Outcome()
    }
}

/** Carried on the broadcast so a result can be matched to the target it belongs to. */
object PendingInstall {
    const val PACKAGE_NAME = "dev.understudy.extra.TARGET_PACKAGE"
}
