package dev.understudy.ui

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.understudy.UnderstudyApp
import dev.understudy.core.SessionEvent
import dev.understudy.core.SessionManager
import dev.understudy.core.SessionState
import dev.understudy.core.TeardownStrategy
import dev.understudy.core.model.ProxyTarget
import dev.understudy.core.model.RemoteEntry
import dev.understudy.core.model.StorageRoot
import dev.understudy.core.model.TreeStat
import dev.understudy.install.InstallResultReceiver
import dev.understudy.shell.ShellCommands
import dev.understudy.storage.SafDestination
import dev.understudy.transfer.TransferEngine
import dev.understudy.transfer.TransferJournal
import dev.understudy.transfer.TransferProgress
import dev.understudy.transfer.TransferResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The single observable surface the Compose layer binds to.
 *
 * Holds UI-only concerns (package-name input, browsing position, destination choice, transfer
 * progress) that do not belong in [SessionManager], which stays a pure lifecycle state machine.
 */
class MainViewModel(private val application: Application) : ViewModel() {

    private val container: UnderstudyApp = application as UnderstudyApp
    private val sessions: SessionManager = container.sessions

    val state: StateFlow<SessionState> = sessions.state
    val events: StateFlow<SessionEvent?> = sessions.events

    // ---- target input ------------------------------------------------------

    private val _packageName = MutableStateFlow("")
    val packageName: StateFlow<String> = _packageName.asStateFlow()

    private val _userId = MutableStateFlow(currentUserId())
    val userId: StateFlow<Int> = _userId.asStateFlow()

    private val _validationError = MutableStateFlow<String?>(null)
    val validationError: StateFlow<String?> = _validationError.asStateFlow()

    /** Live package-name validation, so the user learns before generating, not after. */
    fun onPackageChanged(value: String) {
        _packageName.value = value
        _validationError.value = if (value.isBlank()) {
            null
        } else {
            runCatching {
                dev.understudy.packaging.axml.ManifestPatcher.requireValidPackageName(value)
            }.exceptionOrNull()?.message
        }
    }

    fun onUserChanged(value: Int) {
        _userId.value = value.coerceAtLeast(0)
    }

    // ---- destination -------------------------------------------------------

    private val _destination = MutableStateFlow<Destination?>(null)
    val destination: StateFlow<Destination?> = _destination.asStateFlow()

    data class Destination(val uri: Uri, val label: String)

    fun onDestinationPicked(uri: Uri?) {
        if (uri == null) return
        val saf = SafDestination.restore(application, uri) ?: SafDestination.open(application, uri)
        saf.persist()
        _destination.value = Destination(uri, saf.displayName)
    }

    /** Restores a previously persisted destination on startup, if the grant survived. */
    fun restoreDestination() {
        if (_destination.value != null) return
        val uri = container.prefs.lastDestinationUri ?: return
        val saf = SafDestination.restore(application, uri) ?: return
        _destination.value = Destination(uri, saf.displayName)
    }

    // ---- session actions ---------------------------------------------------

    fun generate() {
        val pkg = _packageName.value.trim()
        if (pkg.isBlank()) {
            _validationError.value = "Enter a package name."
            return
        }
        _validationError.value = null
        sessions.generate(target(pkg))
    }

    fun install() {
        sessions.install(target(_packageName.value.trim())) {
            PendingIntent.getBroadcast(
                application,
                REQUEST_INSTALL,
                Intent(application, InstallResultReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }

    fun verify() {
        viewModelScope.launch { sessions.verify(target(_packageName.value.trim())) }
    }

    /** Re-check whether the proxy is reachable, e.g. after the user returns from Settings. */
    fun refresh() {
        viewModelScope.launch { sessions.verify(target(_packageName.value.trim())) }
    }

    fun consumeEvent() = sessions.consumeEvent()

    // ---- browsing ----------------------------------------------------------

    private val _root = MutableStateFlow(StorageRoot.DATA)
    val root: StateFlow<StorageRoot> = _root.asStateFlow()

    private val _path = MutableStateFlow("")
    val path: StateFlow<String> = _path.asStateFlow()

    private val _entries = MutableStateFlow<List<RemoteEntry>>(emptyList())
    val entries: StateFlow<List<RemoteEntry>> = _entries.asStateFlow()

    private val _browseError = MutableStateFlow<String?>(null)
    val browseError: StateFlow<String?> = _browseError.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    fun selectRoot(root: StorageRoot) {
        _root.value = root
        _path.value = ""
        refreshListing()
    }

    fun navigateInto(entry: RemoteEntry) {
        if (!entry.isDirectory) return
        _path.value = entry.relativePath
        refreshListing()
    }

    fun navigateUp(): Boolean {
        val current = _path.value
        if (current.isEmpty()) return false
        _path.value = current.substringBeforeLast('/', missingDelimiterValue = "")
        refreshListing()
        return true
    }

    fun refreshListing() {
        val bridge = sessions.bridge()
        if (bridge == null) {
            _browseError.value = "No active proxy session."
            _entries.value = emptyList()
            return
        }
        _loading.value = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { bridge.list(_root.value, _path.value) }
            }
            result.onSuccess {
                _entries.value = it
                _browseError.value = null
            }.onFailure { e ->
                _entries.value = emptyList()
                _browseError.value = e.message
            }
            _loading.value = false
        }
    }

    private val _stat = MutableStateFlow<TreeStat?>(null)
    val stat: StateFlow<TreeStat?> = _stat.asStateFlow()

    fun statCurrentTree() {
        val bridge = sessions.bridge() ?: return
        viewModelScope.launch {
            _stat.value = withContext(Dispatchers.IO) {
                runCatching { bridge.statTree(_root.value, _path.value) }.getOrNull()
            }
        }
    }

    // ---- transfer ----------------------------------------------------------

    private val _progress = MutableStateFlow<TransferProgress?>(null)
    val progress: StateFlow<TransferProgress?> = _progress.asStateFlow()

    private val _lastResult = MutableStateFlow<TransferResult?>(null)
    val lastResult: StateFlow<TransferResult?> = _lastResult.asStateFlow()

    private var engine: TransferEngine? = null
    private var transferJob: Job? = null

    /**
     * The transfer a previous process was running when it died, if any.
     *
     * Read once at construction — which is exactly the moment that matters, because the case
     * being recovered from is "the app was killed and has just been rebuilt from nothing".
     * Null while a transfer runs under this process (that one has live progress UI), and null
     * after the user dismisses the offer.
     */
    private val journal: TransferJournal = container.transferJournal

    private val _resumableTransfer = MutableStateFlow(journal.unfinished())
    val resumableTransfer: StateFlow<TransferJournal.Entry?> = _resumableTransfer.asStateFlow()

    private val _resumeNotice = MutableStateFlow<String?>(null)
    /** Why a resume could not start, when it could not. The entry stays offered. */
    val resumeNotice: StateFlow<String?> = _resumeNotice.asStateFlow()

    /**
     * Re-issues the journaled transfer after a process death.
     *
     * Preconditions are checked rather than assumed, and every refusal names what the user can
     * do — a resume that silently no-ops is indistinguishable from a broken button:
     *  - the session must be Ready *for the same package and user* the journal names, or the
     *    bytes would flow to a different proxy than the half-copied tree belongs to;
     *  - the SAF grant must still restore, or there is nowhere to put (pull) or read (push)
     *    the bytes.
     *
     * A resumed pull is cheap by construction: [TransferEngine] skips destination files whose
     * size already matches the plan. A resumed push re-copies the whole tree — the engine has
     * no size-skip in that direction, and inventing one would risk leaving a truncated file
     * *inside the save directory* looking restored. Slower and safe beats faster and lossy.
     */
    fun resumeTransfer() {
        val entry = _resumableTransfer.value ?: return
        val session = state.value
        if (session !is SessionState.Ready ||
            session.target.packageName != entry.targetPackage ||
            session.target.userId != entry.userId
        ) {
            _resumeNotice.value =
                "Re-establish the session for ${entry.targetPackage} (user ${entry.userId}) " +
                "first — the proxy must be verified before its transfer can resume."
            return
        }
        val uri = Uri.parse(entry.destinationUri)
        val saf = SafDestination.restore(application, uri)
        if (saf == null) {
            _resumeNotice.value =
                "The folder ${entry.destinationLabel.ifBlank { "used for the transfer" }} is no " +
                "longer accessible — its permission grant did not survive. Pick the folder " +
                "again, then start the transfer from the Files tab."
            return
        }
        _resumeNotice.value = null
        // Restore the exact context the journaled transfer ran in, then re-issue it through the
        // normal code path so progress, cancellation and the journal itself behave identically.
        _packageName.value = entry.targetPackage
        _userId.value = entry.userId
        _destination.value = Destination(uri, entry.destinationLabel)
        _root.value = entry.root
        _path.value = entry.relativePath
        _resumableTransfer.value = null
        when (entry.direction) {
            dev.understudy.transfer.Direction.PULL -> pullCurrentTree()
            dev.understudy.transfer.Direction.PUSH -> pushCurrentTree()
        }
        refreshListing()
    }

    /** The user does not want the offer; forget the interrupted transfer for good. */
    fun dismissResumableTransfer() {
        journal.clear()
        _resumableTransfer.value = null
        _resumeNotice.value = null
    }

    private fun outcomeOf(result: TransferResult): TransferJournal.Outcome = when {
        result.cancelled -> TransferJournal.Outcome.CANCELLED
        result.isSuccess -> TransferJournal.Outcome.DONE
        else -> TransferJournal.Outcome.FAILED
    }

    /** Copies everything under the current path out of the proxy into the chosen destination. */
    fun pullCurrentTree() {
        val bridge = sessions.bridge()
        val destUri = _destination.value?.uri
        if (bridge == null || destUri == null) return

        val saf = SafDestination.restore(application, destUri) ?: return
        val transfer = TransferEngine(bridge, saf)
        engine = transfer
        _lastResult.value = null
        beginJournal(dev.understudy.transfer.Direction.PULL, destUri)
        var journaledFiles = -1

        transferJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    transfer.pull(_root.value, _path.value) { p ->
                        _progress.value = p
                        // Per-file granularity: see TransferJournal's class doc.
                        if (p.filesDone != journaledFiles) {
                            journaledFiles = p.filesDone
                            journal.update(p)
                        }
                    }
                }
                _lastResult.value = result
                journal.finish(outcomeOf(result))
                if (result.isSuccess) sessions.recordVerifiedPull(target(_packageName.value.trim()))
            } catch (e: CancellationException) {
                // Graceful cancellation (reset, ViewModel cleared) is NOT process death: it
                // reaches this handler and records a terminal outcome, so the resume offer is
                // only ever shown for a transfer that truly died with the process.
                journal.finish(TransferJournal.Outcome.CANCELLED)
                throw e
            }
        }
    }

    private fun beginJournal(direction: dev.understudy.transfer.Direction, destUri: Uri) {
        journal.begin(
            direction = direction,
            root = _root.value,
            relativePath = _path.value,
            targetPackage = _packageName.value.trim(),
            userId = _userId.value,
            destinationUri = destUri.toString(),
            destinationLabel = _destination.value?.label.orEmpty(),
        )
        _resumableTransfer.value = null
        _resumeNotice.value = null
    }

    /**
     * Copies the chosen local directory *into* the proxy's current path.
     *
     * The mirror of [pullCurrentTree], and the half that makes a restore possible: pull gets the
     * saves out, push puts them back. Both directions go through the same [TransferEngine], so
     * the streaming, cancellation and per-file failure reporting behave identically.
     *
     * Two differences from pull are deliberate:
     *
     *  - The source tree is walked *before* anything is copied, so a file that cannot be
     *    represented inside `Android/data/<pkg>` is reported up front instead of failing
     *    halfway through a multi-gigabyte transfer.
     *  - A push never records a verified pull. [dev.understudy.core.SessionManager] unlocks
     *    evacuate-then-uninstall only on the strength of a completed *pull*, because that is
     *    the direction which proves a copy of the data exists somewhere else. Pushing data in
     *    proves no such thing, and treating it as equivalent would let a teardown delete the
     *    only copy.
     */
    fun pushCurrentTree() {
        val bridge = sessions.bridge()
        val destUri = _destination.value?.uri
        if (bridge == null || destUri == null) return

        val saf = SafDestination.restore(application, destUri) ?: return
        _lastResult.value = null

        transferJob = viewModelScope.launch {
            val skipped = ArrayList<Pair<String, String>>()
            val sources = withContext(Dispatchers.IO) {
                saf.collectSourcesForPush(_path.value.ifEmpty { "" }) { path, reason ->
                    skipped += path to reason
                }
            }
            _pushSkipped.value = skipped
            if (sources.isEmpty()) {
                // Not _browseError: that channel reports failures to READ the proxy's tree, and
                // mixing a push outcome into it would make the Files tab show an error for a
                // browse that succeeded.
                _pushNotice.value = if (skipped.isEmpty()) {
                    "Nothing to push: the selected folder is empty."
                } else {
                    "Nothing to push: ${skipped.size} " +
                        (if (skipped.size == 1) "entry" else "entries") +
                        " cannot be represented inside the proxy's storage."
                }
                return@launch
            }
            _pushNotice.value = null

            val transfer = TransferEngine(bridge, saf)
            engine = transfer
            // Only journalled once we know a transfer will actually run: a journaled RUNNING
            // entry is a promise that bytes were in flight, and "nothing to push" broke it.
            beginJournal(dev.understudy.transfer.Direction.PUSH, destUri)
            var journaledFiles = -1
            try {
                val result = withContext(Dispatchers.IO) {
                    transfer.push(_root.value, sources) { p ->
                        _progress.value = p
                        if (p.filesDone != journaledFiles) {
                            journaledFiles = p.filesDone
                            journal.update(p)
                        }
                    }
                }
                _lastResult.value = result
                journal.finish(outcomeOf(result))
                // Refresh so the user sees what landed, rather than a listing from before the push.
                refreshListing()
            } catch (e: CancellationException) {
                journal.finish(TransferJournal.Outcome.CANCELLED)
                throw e
            }
        }
    }

    private val _pushSkipped = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    /** Entries the source tree contained that cannot be pushed, with the reason for each. */
    val pushSkipped: StateFlow<List<Pair<String, String>>> = _pushSkipped.asStateFlow()

    private val _pushNotice = MutableStateFlow<String?>(null)
    /** Why a push did not start, when it did not. Null once one is under way. */
    val pushNotice: StateFlow<String?> = _pushNotice.asStateFlow()

    fun cancelTransfer() {
        engine?.cancel()
    }

    // ---- teardown ----------------------------------------------------------

    private val _strategies = MutableStateFlow<List<TeardownStrategy>>(emptyList())
    val strategies: StateFlow<List<TeardownStrategy>> = _strategies.asStateFlow()

    fun loadStrategies() {
        viewModelScope.launch {
            _strategies.value = sessions.availableTeardownStrategies(target(_packageName.value.trim()))
        }
    }

    fun teardown(strategy: TeardownStrategy, confirmDataLoss: Boolean = false) {
        viewModelScope.launch {
            sessions.teardown(target(_packageName.value.trim()), strategy, confirmDataLoss)
        }
    }

    fun reset() {
        transferJob?.cancel()
        engine = null
        _progress.value = null
        _lastResult.value = null
        _pushSkipped.value = emptyList()
        _pushNotice.value = null
        _entries.value = emptyList()
        _path.value = ""
        _stat.value = null
        sessions.reset()
    }

    // ---- shell -------------------------------------------------------------

    val shellAvailable: Boolean get() = container.shell != null

    /** Every command sequence the UI might want to show, pre-rendered for the current target. */
    fun commands(): ShellCommandSet {
        val pkg = _packageName.value.trim().ifBlank { "com.example.target" }
        val user = _userId.value
        return ShellCommandSet(
            listUsers = ShellCommands.listUsers(),
            inspect = ShellCommands.inspect(user, pkg),
            renameAside = ShellCommands.renameAside(user, pkg),
            uninstallEverywhere = ShellCommands.uninstallEverywhere(pkg),
            uninstallKeepData = ShellCommands.uninstallKeepingData(user, pkg),
            renameBack = ShellCommands.renameBack(user, pkg),
            restoreOwnership = ShellCommands.restoreOwnership(user, pkg),
            runbook = ShellCommands.fullRenameAsideRunbook(user, pkg),
        )
    }

    data class ShellCommandSet(
        val listUsers: String,
        val inspect: String,
        val renameAside: String,
        val uninstallEverywhere: String,
        val uninstallKeepData: String,
        val renameBack: String,
        val restoreOwnership: String,
        val runbook: String,
    )

    // ---- signing identity --------------------------------------------------

    val fingerprint: String get() = container.apkGenerator.fingerprint()

    // ---- helpers -----------------------------------------------------------

    private fun target(pkg: String) = ProxyTarget(
        packageName = pkg,
        userId = _userId.value,
    )

    private fun currentUserId(): Int = android.os.Process.myUid() / 100_000

    override fun onCleared() {
        transferJob?.cancel()
        // No super call: ViewModel.onCleared is annotated @EmptySuper, and lint is right that
        // calling it adds nothing. Leaving it out also means a future androidx version cannot
        // change what runs here behind our back.
    }

    private companion object {
        const val REQUEST_INSTALL = 0x5E55
    }
}

