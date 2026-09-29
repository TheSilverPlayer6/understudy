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
import dev.understudy.transfer.TransferProgress
import dev.understudy.transfer.TransferResult
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

    /** Copies everything under the current path out of the proxy into the chosen destination. */
    fun pullCurrentTree() {
        val bridge = sessions.bridge()
        val destUri = _destination.value?.uri
        if (bridge == null || destUri == null) return

        val saf = SafDestination.restore(application, destUri) ?: return
        val transfer = TransferEngine(bridge, saf)
        engine = transfer
        _lastResult.value = null

        transferJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                transfer.pull(_root.value, _path.value) { p -> _progress.value = p }
            }
            _lastResult.value = result
            if (result.isSuccess) sessions.recordVerifiedPull(target(_packageName.value.trim()))
        }
    }

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
        super.onCleared()
    }

    private companion object {
        const val REQUEST_INSTALL = 0x5E55
    }
}

