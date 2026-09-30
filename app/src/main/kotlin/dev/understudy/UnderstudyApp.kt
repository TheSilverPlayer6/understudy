package dev.understudy

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import dev.understudy.core.SessionManager
import dev.understudy.install.ApkGenerator
import dev.understudy.install.ProxyInstaller
import dev.understudy.shell.ManualShellBackend
import dev.understudy.shell.ShellBackend
import dev.understudy.transfer.TransferJournal

/**
 * Application-scoped container.
 *
 * Hand-rolled rather than using a DI framework: the object graph is four singletons with no
 * scoping subtleties, and pulling in Hilt would cost cold-start time and build complexity in an
 * app whose stated goal is to feel snappy. Everything here is lazy so that the process start
 * path does no disk I/O beyond what the framework already does — in particular the signing
 * identity is *not* loaded until a proxy is actually generated.
 *
 * [sessions] lives here rather than in a ViewModel because it must outlive configuration
 * changes: an install or a transfer that is orphaned by a rotation would leave a proxy
 * installed with no UI to tear it down, which is precisely the situation this app exists to
 * prevent.
 */
class UnderstudyApp : Application() {

    val prefs: Prefs by lazy { Prefs(this) }

    val apkGenerator: ApkGenerator by lazy { ApkGenerator(this) }

    val installer: ProxyInstaller by lazy { ProxyInstaller(this) }

    /**
     * Durable record of the transfer in flight. Application-scoped like [sessions] because the
     * point is to outlive the process: after the platform kills the app mid-pull, the next
     * launch reads this to offer a resume. See [TransferJournal].
     */
    val transferJournal: TransferJournal by lazy { TransferJournal(this) }

    /**
     * The active shell backend, or null when none is configured.
     *
     * Only the manual backend ships today: it always works and needs no privilege. A wireless-ADB
     * backend is the intended upgrade path — see `notes` in the repo — but it is deliberately not
     * stubbed in, because a backend that reports itself available and then cannot execute is
     * worse than no backend at all.
     */
    var shell: ShellBackend? = ManualShellBackend()
        private set

    val sessions: SessionManager by lazy {
        SessionManager(
            context = this,
            apkGenerator = apkGenerator,
            installer = installer,
            shell = shell,
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Touching `sessions` here would construct SessionManager eagerly; we do not want that.
        // It is created on first access from the UI.
    }
}

/**
 * The handful of things worth persisting.
 *
 * Backed by plain `SharedPreferences` rather than DataStore: the data is three scalars read at
 * startup, and DataStore's coroutine-only API would force a blocking read here anyway. If this
 * grows, migrate then.
 */
class Prefs(context: Context) {

    private val store = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Last SAF destination, so a resumed transfer does not re-prompt for a folder. */
    var lastDestinationUri: Uri?
        get() = store.getString(KEY_DESTINATION, null)?.let(Uri::parse)
        set(value) = store.edit { putString(KEY_DESTINATION, value?.toString()) }

    /** Package name the user last worked on — resuming a session matters more than privacy here. */
    var lastTargetPackage: String?
        get() = store.getString(KEY_TARGET, null)
        set(value) = store.edit { putString(KEY_TARGET, value) }

    var lastUserId: Int
        get() = store.getInt(KEY_USER, 0)
        set(value) = store.edit { putInt(KEY_USER, value) }

    /**
     * Set once the user has acknowledged that a proxy is installed.
     *
     * Used to warn on next launch: an orphaned proxy holding someone's `Android/data` is easy to
     * forget about, and forgetting it means the real app cannot be reinstalled under its own
     * signature.
     */
    var outstandingProxyPackage: String?
        get() = store.getString(KEY_OUTSTANDING, null)
        set(value) = store.edit { putString(KEY_OUTSTANDING, value) }

    private companion object {
        const val FILE = "understudy"
        const val KEY_DESTINATION = "destination_uri"
        const val KEY_TARGET = "target_package"
        const val KEY_USER = "target_user"
        const val KEY_OUTSTANDING = "outstanding_proxy"
    }
}
