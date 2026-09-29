package dev.understudy.core

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.understudy.bridge.BridgeClient
import dev.understudy.bridge.BridgeError
import dev.understudy.core.model.ProxyTarget
import dev.understudy.core.model.StorageRoot
import dev.understudy.install.ApkGenerator
import dev.understudy.install.GeneratedApk
import dev.understudy.install.InstallResultReceiver
import dev.understudy.install.ProxyInstaller
import dev.understudy.shell.ShellBackend
import dev.understudy.shell.ShellUnavailable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the proxy lifecycle: generate → install → verify → use → tear down.
 *
 * Deliberately a plain class with a [StateFlow] rather than a ViewModel, so the state machine
 * is testable on the JVM and survives configuration changes when hosted in an
 * application-scoped container. The UI observes [state] and issues intents; it never mutates
 * state directly, because the transitions are the safety-critical part.
 *
 * Two invariants the whole design rests on:
 *  - **never uninstall before the data is accounted for.** [teardown] refuses
 *    [TeardownStrategy.DESTROY_DATA] unless the caller passes `confirmDataLoss = true`, and
 *    [TeardownStrategy.EVACUATE_THEN_UNINSTALL] requires a recorded successful pull.
 *  - **never assume the bridge is alive.** Every operation re-checks reachability, because the
 *    proxy process can be killed at any moment and the profile may not even be foreground.
 */
class SessionManager(
    private val context: Context,
    private val apkGenerator: ApkGenerator,
    private val installer: ProxyInstaller,
    private val shell: ShellBackend?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _lastGenerated = MutableStateFlow<GeneratedApk?>(null)
    val lastGenerated: StateFlow<GeneratedApk?> = _lastGenerated.asStateFlow()

    private val _events = MutableStateFlow<SessionEvent?>(null)
    /** One-shot things the UI must act on, e.g. launching an IntentSender. */
    val events: StateFlow<SessionEvent?> = _events.asStateFlow()

    /** Records that a pull completed successfully, which unlocks evacuate-then-uninstall. */
    private var verifiedPullFor: ProxyTarget? = null

    private var bridge: BridgeClient? = null

    init {
        // Install results arrive on a BroadcastReceiver, which has no coroutine context of its
        // own; hop into the scope here.
        dev.understudy.install.InstallResultReceiver.listener = { outcome ->
            scope.launch { onInstallOutcome(outcome) }
        }
    }

    // ---- generate ----------------------------------------------------------

    /**
     * Builds the proxy APK for [target].
     *
     * Fails fast with an actionable message if `REQUEST_INSTALL_PACKAGES` is not granted for
     * *this* profile, because that appop is per-user and users routinely grant it in the owner
     * profile and assume it applies everywhere.
     */
    fun generate(target: ProxyTarget) {
        if (_state.value is SessionState.Generating || _state.value is SessionState.Installing) {
            Log.w(TAG, "generate() ignored: already ${_state.value::class.simpleName}")
            return
        }
        _state.value = SessionState.Generating(target)
        scope.launch {
            val result = withContext(io) {
                runCatching { apkGenerator.generate(target.packageName) }
            }
            result.onSuccess { apk ->
                _lastGenerated.value = apk
                // Generating an APK does not install it; the next step is always the user's.
                _events.value = SessionEvent.ApkReady(apk)
            }.onFailure { e ->
                _state.value = SessionState.Failed(
                    target = target,
                    reason = e.message ?: e::class.java.simpleName,
                    dataAtRisk = false,
                    recoveryHint = when (e) {
                        is IllegalArgumentException -> "Check the package name is valid."
                        else -> null
                    },
                )
            }
        }
    }

    // ---- install -----------------------------------------------------------

    /**
     * Stages the generated APK and asks the UI to launch the system installer.
     *
     * @return false if there is nothing staged or the grant is missing, in which case
     *   [events] carries the reason
     */
    fun install(target: ProxyTarget, resultIntentFactory: () -> android.app.PendingIntent): Boolean {
        val apk = _lastGenerated.value
        if (apk == null || apk.packageName != target.packageName) {
            _events.value = SessionEvent.Message("Generate the proxy APK first.")
            return false
        }
        if (!installer.canRequestInstalls()) {
            _events.value = SessionEvent.NeedsInstallPermission(target.userId)
            return false
        }
        if (target.userId != installer.currentUserId) {
            // PackageInstaller cannot target another user without INSTALL_PACKAGES. Say so
            // plainly and offer the shell path instead of letting the commit fail obscurely.
            _events.value = SessionEvent.CrossUserUnsupported(
                requested = target.userId,
                current = installer.currentUserId,
                commands = shell?.let {
                    dev.understudy.shell.ShellCommands.installForUser(target.userId, "/data/local/tmp/proxy.apk")
                },
            )
            return false
        }

        return try {
            val staged = installer.stage(apk.file, apk.packageName, resultIntentFactory())
            // commit() has already been called inside stage(); from here we wait for the
            // receiver. awaitingUser = true because the common path is a confirmation prompt.
            _state.value = SessionState.Installing(target, staged.sessionId, awaitingUser = true)
            true
        } catch (e: Exception) {
            _state.value = SessionState.Failed(
                target = target,
                reason = "Could not stage the install: ${e.message}",
                dataAtRisk = false,
            )
            false
        }
    }

    private suspend fun onInstallOutcome(outcome: dev.understudy.install.InstallResultReceiver.Outcome) {
        val current = _state.value
        val target = (current as? SessionState.Installing)?.target ?: return

        when (outcome) {
            is dev.understudy.install.InstallResultReceiver.Outcome.Success -> {
                _state.value = SessionState.Verifying(target)
                verify(target)
            }

            is dev.understudy.install.InstallResultReceiver.Outcome.NeedsConfirmation -> {
                _state.value = SessionState.Installing(target, outcome.sessionId, awaitingUser = true)
                _events.value = SessionEvent.LaunchIntent(outcome.confirmation)
            }

            is dev.understudy.install.InstallResultReceiver.Outcome.Failure -> {
                installer.abandon(outcome.sessionId)
                _state.value = SessionState.Failed(
                    target = target,
                    reason = outcome.reason,
                    dataAtRisk = false,
                    // The dominant real-world failure deserves its own remedy text.
                    recoveryHint = if (outcome.reason.contains("UPDATE_INCOMPATIBLE")) {
                        "This package is installed or retained elsewhere on the device under a " +
                            "different signature. Use the rename-aside runbook from the Shell tab."
                    } else {
                        null
                    },
                )
            }
        }
    }

    // ---- verify ------------------------------------------------------------

    /**
     * Confirms the proxy is installed, reachable, speaking our protocol, and running in the
     * profile we intended.
     *
     * The user-id check is not cosmetic: if the proxy somehow landed in the wrong profile the
     * user would be looking at an empty `Android/data` and concluding their saves were gone.
     */
    suspend fun verify(target: ProxyTarget) {
        _state.value = SessionState.Verifying(target)
        val client = BridgeClient(context.contentResolver, target.packageName)
        val result = withContext(io) { runCatching { client.ping(target.packageName) } }

        result.onSuccess { info ->
            if (info.userId != target.userId) {
                _state.value = SessionState.Failed(
                    target = target,
                    reason = "The proxy is running in user ${info.userId}, not user ${target.userId}.",
                    dataAtRisk = false,
                    recoveryHint = "You are looking at the wrong profile's storage. Reinstall for user ${target.userId}.",
                )
                return
            }
            bridge = client
            _state.value = SessionState.Ready(
                target = target,
                bridgeUserId = info.userId,
                availableRoots = info.roots.filter { it.exists }.map { it.root },
                hiddenFromLauncher = false,
            )
        }.onFailure { e ->
            val hint = when (e) {
                is BridgeError.ProtocolMismatch ->
                    "A proxy from an older build is installed. Uninstall it and generate a new one."
                is BridgeError.PermissionDenied ->
                    "The installed proxy was signed with a different key. If the signing identity " +
                        "was regenerated, uninstall the old proxy first."
                is BridgeError.ProxyUnreachable ->
                    "The install may not have completed, or the app is not installed for user " +
                        "${target.userId}."
                else -> null
            }
            _state.value = SessionState.Failed(
                target = target,
                reason = e.message ?: e::class.java.simpleName,
                dataAtRisk = false,
                recoveryHint = hint,
            )
        }
    }

    /** The live bridge, or null if no session is [SessionState.Ready]. */
    fun bridge(): BridgeClient? = bridge

    /** Call after a successful pull so evacuate-then-uninstall becomes available. */
    fun recordVerifiedPull(target: ProxyTarget) {
        verifiedPullFor = target
    }

    // ---- teardown ----------------------------------------------------------

    /**
     * Which strategies are actually available right now, safest first.
     *
     * Computed rather than stored, because availability depends on a shell being reachable and
     * on whether a pull has been verified — both of which change underneath us.
     */
    suspend fun availableTeardownStrategies(target: ProxyTarget): List<TeardownStrategy> {
        val out = ArrayList<TeardownStrategy>()
        out += TeardownStrategy.KEEP_HIDDEN

        val shellReady = shell?.let { runCatching { it.isAvailable() }.getOrDefault(false) } == true
        if (shellReady) out += TeardownStrategy.SHELL_KEEP_DATA

        if (verifiedPullFor == target) out += TeardownStrategy.EVACUATE_THEN_UNINSTALL

        out += TeardownStrategy.DESTROY_DATA
        return out
    }

    /**
     * Disposes of the proxy.
     *
     * @param confirmDataLoss must be true for [TeardownStrategy.DESTROY_DATA]. This is the one
     *   irreversible action in the app and it is gated on an explicit user decision rather than
     *   on a dialog having been shown, so a UI bug cannot trigger it.
     */
    suspend fun teardown(
        target: ProxyTarget,
        strategy: TeardownStrategy,
        confirmDataLoss: Boolean = false,
    ): Boolean {
        _state.value = SessionState.TearingDown(target, strategy)

        val ok = when (strategy) {
            TeardownStrategy.KEEP_HIDDEN -> {
                val hidden = withContext(io) { bridge?.hideLauncher() ?: false }
                if (hidden) {
                    _state.value = SessionState.Dormant(target)
                    true
                } else {
                    fail(target, "Could not hide the proxy's launcher icon.", dataAtRisk = false)
                    false
                }
            }

            TeardownStrategy.SHELL_KEEP_DATA -> {
                val backend = shell
                if (backend == null) {
                    fail(target, "No shell backend is configured.", dataAtRisk = false)
                    return false
                }
                val command = dev.understudy.shell.ShellCommands.uninstallKeepingData(
                    target.userId,
                    target.packageName,
                )
                runShell(backend, command, target, preservesData = true)
            }

            TeardownStrategy.EVACUATE_THEN_UNINSTALL -> {
                if (verifiedPullFor != target) {
                    fail(
                        target,
                        "Refusing to evacuate-then-uninstall: no completed pull is recorded for " +
                            "${target.packageName}.",
                        dataAtRisk = false,
                    )
                    return false
                }
                val wiped = withContext(io) { runCatching { bridge?.wipeProxyData() }.isSuccess }
                if (!wiped) {
                    // Do NOT proceed to uninstall: we could not confirm the directories are
                    // empty, so a normal uninstall might still delete something.
                    fail(
                        target,
                        "The proxy could not empty its own directories, so uninstalling might " +
                            "still delete data. Aborting teardown.",
                        dataAtRisk = true,
                    )
                    return false
                }
                launchSystemUninstall(target, preservesData = false)
            }

            TeardownStrategy.DESTROY_DATA -> {
                if (!confirmDataLoss) {
                    fail(
                        target,
                        "Refusing to destroy data without explicit confirmation.",
                        dataAtRisk = false,
                    )
                    return false
                }
                launchSystemUninstall(target, preservesData = false)
            }
        }
        return ok
    }

    private suspend fun runShell(
        backend: ShellBackend,
        command: String,
        target: ProxyTarget,
        preservesData: Boolean,
    ): Boolean = try {
        val result = backend.execute(command)
        if (result.isSuccess) {
            bridge = null
            _state.value = SessionState.Finished(target, dataPreserved = preservesData)
            true
        } else {
            fail(
                target,
                "Shell command failed (exit ${result.exitCode}): ${result.output.take(500)}",
                // Unknown state: the uninstall may have partially run.
                dataAtRisk = true,
            )
            false
        }
    } catch (e: ShellUnavailable) {
        _events.value = SessionEvent.ShowCommands(command)
        fail(target, "This shell backend can only generate commands, not run them.", dataAtRisk = false)
        false
    }

    /**
     * Hands off to the system uninstall UI via a real `IntentSender`.
     *
     * For a proxy built with `hasFragileUserData="true"` that dialog offers a "Keep app data"
     * checkbox — the only unprivileged route to a data-preserving uninstall. We cannot tick it
     * for the user, so the UI must point it out; [SessionEvent.UninstallOfferedKeepData] exists
     * purely to trigger that message.
     *
     * The state moves to [SessionState.Finished] optimistically, because the uninstall result
     * arrives on the same receiver as installs and the user may simply back out. [verify] is
     * the authority: it will report the proxy still reachable if the uninstall did not happen.
     */
    private fun launchSystemUninstall(target: ProxyTarget, preservesData: Boolean): Boolean {
        val resultIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_UNINSTALL,
            Intent(context, InstallResultReceiver::class.java),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        try {
            installer.requestUninstall(target.packageName, resultIntent)
        } catch (e: Exception) {
            fail(target, "Could not start the uninstall flow: ${e.message}", dataAtRisk = false)
            return false
        }

        if (!preservesData) {
            _events.value = SessionEvent.UninstallOfferedKeepData(target)
        }
        bridge = null
        _state.value = SessionState.Finished(target, dataPreserved = preservesData)
        return true
    }

    private fun fail(target: ProxyTarget, reason: String, dataAtRisk: Boolean, hint: String? = null) {
        _state.value = SessionState.Failed(target, reason, dataAtRisk, hint)
    }

    /** Clears a consumed event so it is not re-delivered after a recomposition. */
    fun consumeEvent() {
        _events.value = null
    }

    fun reset() {
        bridge = null
        verifiedPullFor = null
        _lastGenerated.value = null
        _state.value = SessionState.Idle
    }

    companion object {
        private const val TAG = "SessionManager"
        private const val REQUEST_UNINSTALL = 0x5E56

        /** Convenience for previews and tests. */
        fun forTesting(application: Application, shell: ShellBackend? = null) = SessionManager(
            context = application,
            apkGenerator = ApkGenerator(application),
            installer = ProxyInstaller(application),
            shell = shell,
        )
    }
}

/** Things the UI must act on, as opposed to state it merely renders. */
sealed class SessionEvent {
    data class ApkReady(val apk: GeneratedApk) : SessionEvent()
    data class LaunchIntentSender(val sender: android.content.IntentSender) : SessionEvent()
    data class LaunchIntent(val intent: android.content.Intent?) : SessionEvent()

    /** The user must grant "install unknown apps" inside this profile. */
    data class NeedsInstallPermission(val userId: Int) : SessionEvent()

    /**
     * The requested user is not ours, and `PackageInstaller` cannot cross that boundary.
     * [commands] is the adb equivalent, when a shell route exists.
     */
    data class CrossUserUnsupported(
        val requested: Int,
        val current: Int,
        val commands: String?,
    ) : SessionEvent()

    data class ShowCommands(val commands: String) : SessionEvent()
    data class Message(val text: String) : SessionEvent()

    /**
     * Emitted just before the system uninstall dialog, to tell the user about the "Keep app
     * data" checkbox that `hasFragileUserData` adds — the only unprivileged way to preserve
     * `Android/data/<pkg>` through an uninstall.
     */
    data class UninstallOfferedKeepData(val target: ProxyTarget) : SessionEvent()
}

/** Which roots a given target actually has data in, for pruning the UI. */
fun List<StorageRoot>.displayNames(): String =
    if (isEmpty()) "none" else joinToString(", ") { it.displayName }
