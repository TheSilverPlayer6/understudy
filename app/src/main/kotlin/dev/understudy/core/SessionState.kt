package dev.understudy.core

import dev.understudy.core.model.ProxyTarget
import dev.understudy.core.model.StorageRoot

/**
 * Where a proxy session is in its lifecycle.
 *
 * Modelled as an explicit state machine rather than a pile of booleans because the transitions
 * are the part that must not get wrong: a teardown that runs before a transfer completes, or an
 * install that proceeds while a signature conflict is unresolved, destroys data. Every state
 * names what the app is waiting for and who has to act.
 */
sealed class SessionState {

    /** Nothing in flight. */
    data object Idle : SessionState()

    /** Building the proxy APK: reading the template, patching the manifest, signing. */
    data class Generating(val target: ProxyTarget) : SessionState()

    /**
     * The APK is built and staged; the system installer prompt is on screen or about to be.
     * [awaitingUser] distinguishes "we are waiting on a human" from "we are waiting on the
     * system", which the UI renders very differently.
     */
    data class Installing(
        val target: ProxyTarget,
        val sessionId: Int,
        val awaitingUser: Boolean,
    ) : SessionState()

    /**
     * The system reports success; we are confirming the bridge actually answers and speaks our
     * protocol. A proxy that installed but cannot be reached is a distinct failure worth
     * naming, because the usual cause is a stale proxy from an older signing identity.
     */
    data class Verifying(val target: ProxyTarget) : SessionState()

    /** Bridge confirmed. The user can browse and transfer. */
    data class Ready(
        val target: ProxyTarget,
        val bridgeUserId: Int,
        val availableRoots: List<StorageRoot>,
        val hiddenFromLauncher: Boolean,
    ) : SessionState()

    /**
     * Data has been evacuated but the proxy is still installed.
     *
     * This is the preferred resting state: `/Android/data/<pkg>` is untouched, the proxy is
     * invisible in the launcher, and re-enabling it later costs nothing. No shell required.
     */
    data class Dormant(val target: ProxyTarget) : SessionState()

    /** Running the teardown sequence. [strategy] says whether the data can be kept in place. */
    data class TearingDown(val target: ProxyTarget, val strategy: TeardownStrategy) : SessionState()

    /** Uninstall happened without data retention; the private storage is gone. */
    data class Finished(val target: ProxyTarget, val dataPreserved: Boolean) : SessionState()

    data class Failed(
        val target: ProxyTarget?,
        val reason: String,
        /** Set when the failure may have left data in an ambiguous state. */
        val dataAtRisk: Boolean,
        val recoveryHint: String? = null,
    ) : SessionState()
}

/**
 * How teardown will dispose of the proxy, in decreasing order of data safety.
 *
 * The app picks the safest one the current capabilities allow and tells the user which it
 * chose and why, because "your save data was deleted" is not a recoverable mistake.
 */
enum class TeardownStrategy(
    val preservesDataInPlace: Boolean,
    val needsShell: Boolean,
) {
    /**
     * Leave the proxy installed, hide its launcher icon, touch nothing.
     *
     * Safest and needs no privilege. Costs a ~700 KB install and an entry in Settings → Apps.
     */
    KEEP_HIDDEN(true, false),

    /**
     * `pm uninstall -k --user N`: real uninstall, data stays exactly where it was.
     *
     * Needs a shell. Note the retained package keeps its signature record, so re-proxying later
     * requires the same signing key — which is why the identity must be stable across runs.
     */
    SHELL_KEEP_DATA(true, true),

    /**
     * Copy everything out, let the proxy wipe its own directories, then uninstall normally.
     *
     * Needs no privilege and genuinely uninstalls, but the data does not stay in
     * `Android/data/<pkg>` — it lives wherever the user sent it. Only offered once a pull has
     * verifiably completed.
     */
    EVACUATE_THEN_UNINSTALL(false, false),

    /** Plain system uninstall. Destroys the private storage. Last resort, loudly warned about. */
    DESTROY_DATA(false, false),
}
