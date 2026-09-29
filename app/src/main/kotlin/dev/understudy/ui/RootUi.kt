package dev.understudy.ui

import android.content.Intent
import android.content.IntentSender
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.understudy.core.SessionEvent
import dev.understudy.core.SessionState
import dev.understudy.core.TeardownStrategy
import dev.understudy.core.model.RemoteEntry
import dev.understudy.core.model.StorageRoot
import dev.understudy.ui.theme.CommandTypography
import dev.understudy.ui.theme.MonoTypography

private enum class Tab(val title: String) {
    SESSION("Session"),
    FILES("Files"),
    SHELL("Shell"),
    ABOUT("About"),
}

/**
 * Root of the UI: a tabbed scaffold plus the one place where [SessionEvent]s are consumed.
 *
 * Events are handled here rather than in each screen because several of them (launch an
 * `IntentSender`, open the unknown-sources settings) need an Activity-scoped launcher that only
 * [dev.understudy.MainActivity] can supply.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootUi(
    viewModel: MainViewModel,
    onPickDestination: () -> Unit,
    onLaunchIntentSender: (IntentSender) -> Unit,
    onLaunchIntent: (Intent?) -> Unit,
    onOpenUnknownSources: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val event by viewModel.events.collectAsStateWithLifecycle()

    // Surface events once. `consumeEvent` clears the flow so a recomposition cannot replay it,
    // which would re-launch the installer prompt.
    LaunchedEffect(event) {
        when (val e = event) {
            null -> Unit
            is SessionEvent.LaunchIntentSender -> {
                onLaunchIntentSender(e.sender)
                viewModel.consumeEvent()
            }
            is SessionEvent.LaunchIntent -> {
                onLaunchIntent(e.intent)
                viewModel.consumeEvent()
            }
            is SessionEvent.NeedsInstallPermission -> {
                snackbar.showSnackbar(
                    "Understudy needs \"Install unknown apps\" in THIS user profile (user ${e.userId})."
                )
                onOpenUnknownSources()
                viewModel.consumeEvent()
            }
            is SessionEvent.CrossUserUnsupported -> {
                snackbar.showSnackbar(
                    "An app can only install into its own profile (user ${e.current}). " +
                        "For user ${e.requested}, use the adb commands in the Shell tab."
                )
                tab = Tab.SHELL.ordinal
                viewModel.consumeEvent()
            }
            is SessionEvent.ShowCommands -> {
                snackbar.showSnackbar("This backend can only generate commands — see the Shell tab.")
                tab = Tab.SHELL.ordinal
                viewModel.consumeEvent()
            }
            is SessionEvent.ApkReady -> {
                snackbar.showSnackbar(
                    "Proxy APK built: ${e.apk.sizeBytes / 1024} KB, sha256 ${e.apk.sha256Hex.take(12)}…"
                )
                viewModel.consumeEvent()
            }
            is SessionEvent.Message -> {
                snackbar.showSnackbar(e.text)
                viewModel.consumeEvent()
            }
            is SessionEvent.UninstallOfferedKeepData -> {
                snackbar.showSnackbar(
                    "Tick \"Keep app data\" in the uninstall dialog — that is what preserves " +
                        "Android/data/${e.target.packageName}."
                )
                viewModel.consumeEvent()
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Understudy") },
                actions = {
                    StateBadge(state)
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Re-check the proxy")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            TabRow(selectedTabIndex = tab) {
                Tab.entries.forEachIndexed { index, t ->
                    Tab(
                        selected = tab == index,
                        onClick = {
                            tab = index
                            if (t == Tab.FILES) viewModel.refreshListing()
                            if (t == Tab.SHELL) viewModel.loadStrategies()
                        },
                        text = { Text(t.title) },
                    )
                }
            }
            when (Tab.entries[tab]) {
                Tab.SESSION -> SessionScreen(viewModel, state, onPickDestination)
                Tab.FILES -> FilesScreen(viewModel, state)
                Tab.SHELL -> ShellScreen(viewModel)
                Tab.ABOUT -> AboutScreen(viewModel)
            }
        }
    }
}

@Composable
private fun StateBadge(state: SessionState) {
    val (label, tone) = when (state) {
        is SessionState.Idle -> "idle" to MaterialTheme.colorScheme.outline
        is SessionState.Generating -> "generating" to MaterialTheme.colorScheme.primary
        is SessionState.Installing ->
            (if (state.awaitingUser) "awaiting you" else "installing") to
                MaterialTheme.colorScheme.primary
        is SessionState.Verifying -> "verifying" to MaterialTheme.colorScheme.primary
        is SessionState.Ready -> "ready" to MaterialTheme.colorScheme.secondary
        is SessionState.Dormant -> "dormant" to MaterialTheme.colorScheme.tertiary
        is SessionState.TearingDown -> "teardown" to MaterialTheme.colorScheme.tertiary
        is SessionState.Finished ->
            (if (state.dataPreserved) "done, data kept" else "done, data gone") to
                (if (state.dataPreserved) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.error)
        is SessionState.Failed ->
            (if (state.dataAtRisk) "DATA AT RISK" else "failed") to
                MaterialTheme.colorScheme.error
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = tone,
        modifier = Modifier.padding(end = 8.dp),
    )
}

// ---- Session ---------------------------------------------------------------

@Composable
private fun SessionScreen(
    viewModel: MainViewModel,
    state: SessionState,
    onPickDestination: () -> Unit,
) {
    val packageName by viewModel.packageName.collectAsStateWithLifecycle()
    val userId by viewModel.userId.collectAsStateWithLifecycle()
    val validationError by viewModel.validationError.collectAsStateWithLifecycle()
    val destination by viewModel.destination.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = packageName,
            onValueChange = viewModel::onPackageChanged,
            label = { Text("Target package name") },
            supportingText = {
                Text(
                    validationError ?: "The app whose Android/data and Android/obb you want to reach.",
                    color = if (validationError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            isError = validationError != null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = userId.toString(),
            onValueChange = { text -> text.toIntOrNull()?.let(viewModel::onUserChanged) },
            label = { Text("Android user id") },
            supportingText = {
                Text("0 is the owner. Secondary profiles are usually 10, 11, … — see the Shell tab for `pm list users`.")
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider()

        when (state) {
            is SessionState.Idle, is SessionState.Failed, is SessionState.Finished -> {
                Button(
                    onClick = viewModel::generate,
                    enabled = validationError == null && packageName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Generate proxy APK") }
            }
            is SessionState.Generating -> ProgressRow("Building and signing the proxy APK…")
            is SessionState.Installing -> ProgressRow(
                if (state.awaitingUser) "Waiting for you to confirm the install…"
                else "Installing…"
            )
            is SessionState.Verifying -> ProgressRow("Checking the bridge answers…")
            is SessionState.Ready -> ReadyCard(viewModel, state)
            is SessionState.Dormant -> DormantCard(viewModel, state)
            is SessionState.TearingDown -> ProgressRow("Tearing down…")
        }

        if (state !is SessionState.Generating && state !is SessionState.Installing &&
            state !is SessionState.Verifying && state !is SessionState.TearingDown
        ) {
            FilledTonalButton(
                onClick = viewModel::install,
                enabled = packageName.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Install into this profile") }
        }

        if (state is SessionState.Failed) FailureCard(state)

        HorizontalDivider()

        Text("Destination", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    destination?.label ?: "Not chosen",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (destination != null) {
                    SelectionContainer {
                        Text(destination!!.uri.toString(), style = MonoTypography)
                    }
                }
            }
            OutlinedButton(onClick = onPickDestination) { Text("Choose folder") }
        }
        Text(
            "Pulled files land here. The grant is persisted, so a resumed transfer will not ask again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        TransferPanel(viewModel)
    }
}

@Composable
private fun ProgressRow(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ReadyCard(viewModel: MainViewModel, state: SessionState.Ready) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Proxy is live", style = MaterialTheme.typography.titleMedium)
            Text("Impersonating ${state.target.packageName}", style = MonoTypography)
            Text(
                "Running as Android user ${state.bridgeUserId} — this is the profile whose " +
                    "storage you are looking at.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Roots with data: " + state.availableRoots.joinToString(", ") { it.displayName }
                    .ifBlank { "none yet" },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.selectRoot(StorageRoot.DATA) }) { Text("Browse data") }
                OutlinedButton(onClick = { viewModel.selectRoot(StorageRoot.OBB) }) { Text("Browse obb") }
            }
            TeardownSection(viewModel, state.target)
        }
    }
}

@Composable
private fun DormantCard(viewModel: MainViewModel, state: SessionState.Dormant) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Proxy dormant", style = MaterialTheme.typography.titleMedium)
            Text(
                "It is still installed but hidden from the launcher, and its data directories " +
                    "are untouched. Re-enable it any time to resume.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(state.target.packageName, style = MonoTypography)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::verify) { Text("Re-enable") }
                TeardownButton(viewModel, state.target)
            }
        }
    }
}

@Composable
private fun FailureCard(state: SessionState.Failed) {
    val tone = MaterialTheme.colorScheme.errorContainer
    Card(colors = CardDefaults.cardColors(containerColor = tone)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (state.dataAtRisk) "Stopped — data may be at risk" else "Failed",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            SelectionContainer { Text(state.reason, style = MaterialTheme.typography.bodyMedium) }
            if (state.recoveryHint != null) {
                Text(
                    state.recoveryHint,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.dataAtRisk) {
                Text(
                    "Nothing further was done automatically. Inspect the directories from the " +
                        "Shell tab before retrying.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun TeardownSection(viewModel: MainViewModel, target: dev.understudy.core.model.ProxyTarget) {
    val strategies by viewModel.strategies.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadStrategies() }

    Spacer(Modifier.height(4.dp))
    Text("Teardown", style = MaterialTheme.typography.titleSmall)
    strategies.forEach { strategy ->
        TeardownRow(viewModel, target, strategy)
    }
    if (strategies.isEmpty()) {
        Text("No strategy computed yet.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TeardownRow(
    viewModel: MainViewModel,
    target: dev.understudy.core.model.ProxyTarget,
    strategy: TeardownStrategy,
) {
    var confirm by remember { mutableStateOf(false) }
    val (label, detail) = when (strategy) {
        TeardownStrategy.KEEP_HIDDEN -> "Keep installed, hide it" to
            "Safest. Data stays exactly where it is; the icon disappears from the launcher."
        TeardownStrategy.SHELL_KEEP_DATA -> "Uninstall keeping data (adb)" to
            "`pm uninstall -k`. Data stays in Android/data. Needs a shell."
        TeardownStrategy.EVACUATE_THEN_UNINSTALL -> "Evacuate, then uninstall" to
            "Only offered after a completed pull. The directories are emptied first, so a " +
                "normal uninstall destroys nothing — but the data no longer lives in Android/data."
        TeardownStrategy.DESTROY_DATA -> "Uninstall and delete the data" to
            "Irreversible. This is what the system uninstaller does by default."
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedButton(
            onClick = {
                if (strategy == TeardownStrategy.DESTROY_DATA) confirm = true
                else viewModel.teardown(strategy)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(label) }
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete the data?") },
            text = {
                Text(
                    "This uninstalls the proxy for ${target.packageName} and permanently deletes " +
                        "everything in its Android/data and Android/obb directories. There is no " +
                        "undo and no trash."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    viewModel.teardown(strategy, confirmDataLoss = true)
                }) { Text("Delete permanently") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TeardownButton(viewModel: MainViewModel, target: dev.understudy.core.model.ProxyTarget) {
    var menu by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { menu = true }) { Text("Remove proxy…") }
    if (menu) {
        AlertDialog(
            onDismissRequest = { menu = false },
            title = { Text("Remove the proxy") },
            text = { Text("Open teardown options for ${target.packageName}?") },
            confirmButton = {
                TextButton(onClick = {
                    menu = false
                    viewModel.teardown(TeardownStrategy.KEEP_HIDDEN)
                }) { Text("Hide it") }
            },
            dismissButton = { TextButton(onClick = { menu = false }) { Text("Cancel") } },
        )
    }
}

// ---- Transfer --------------------------------------------------------------

@Composable
private fun TransferPanel(viewModel: MainViewModel) {
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val result by viewModel.lastResult.collectAsStateWithLifecycle()
    val destination by viewModel.destination.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val path by viewModel.path.collectAsStateWithLifecycle()
    val pushNotice by viewModel.pushNotice.collectAsStateWithLifecycle()
    val pushSkipped by viewModel.pushSkipped.collectAsStateWithLifecycle()

    if (state !is SessionState.Ready) return

    val busy = progress?.isRunning == true

    HorizontalDivider()
    Text("Transfer", style = MaterialTheme.typography.titleSmall)

    Button(
        onClick = viewModel::pullCurrentTree,
        enabled = destination != null && !busy,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (destination == null) "Choose a destination first" else "Pull everything to destination") }

    // The other half of the round trip: without a push, a backup can be taken but never
    // restored, and restoring is the reason anyone wants this app. Same engine, same
    // progress and cancellation handling as the pull above.
    OutlinedButton(
        onClick = viewModel::pushCurrentTree,
        enabled = destination != null && !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            when {
                destination == null -> "Choose a source folder first"
                path.isEmpty() -> "Push the folder into the proxy's data root"
                else -> "Push the folder into $path"
            },
        )
    }
    Text(
        "Push copies the folder you chose above INTO the proxy's private storage, at the path " +
            "the Files tab is currently showing. Files that already exist there are overwritten.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (pushNotice != null) {
        Text(
            pushNotice!!,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (pushSkipped.isNotEmpty()) {
        Text(
            "${pushSkipped.size} skipped (cannot exist inside Android/data):",
            style = MaterialTheme.typography.bodySmall,
        )
        pushSkipped.take(8).forEach { (name, reason) ->
            Text("  $name — $reason", style = MonoTypography, maxLines = 2)
        }
        if (pushSkipped.size > 8) {
            Text("  …and ${pushSkipped.size - 8} more", style = MonoTypography)
        }
    }

    if (progress != null) {
        val p = progress!!
        LinearProgressIndicator(
            progress = { p.fractionComplete },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "${p.state.name.lowercase()} · ${p.filesDone}/${p.filesTotal} files · " +
                "${humanBytes(p.bytesDone + p.currentFileBytes)} / ${humanBytes(p.bytesTotal)}",
            style = MaterialTheme.typography.bodySmall,
        )
        if (p.currentPath.isNotEmpty()) {
            Text(p.currentPath, style = MonoTypography, maxLines = 1)
        }
        if (p.isRunning) {
            TextButton(onClick = viewModel::cancelTransfer) { Text("Cancel") }
        }
    }

    if (result != null) {
        val r = result!!
        Text(
            buildString {
                append(if (r.isSuccess) "Completed" else "Finished with problems")
                append(": ${r.succeeded} files, ${humanBytes(r.bytesCopied)}")
                if (r.cancelled) append(" (cancelled)")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (r.isSuccess) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
        )
        if (r.failed.isNotEmpty()) {
            Text("${r.failed.size} failed:", style = MaterialTheme.typography.bodySmall)
            r.failed.take(12).forEach { f ->
                Text("  ${f.path}: ${f.reason}", style = MonoTypography, maxLines = 2)
            }
        }
    }
}

// ---- Files -----------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilesScreen(viewModel: MainViewModel, state: SessionState) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val path by viewModel.path.collectAsStateWithLifecycle()
    val root by viewModel.root.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.browseError.collectAsStateWithLifecycle()
    val stat by viewModel.stat.collectAsStateWithLifecycle()

    if (state !is SessionState.Ready && state !is SessionState.Dormant) {
        EmptyState("Install and verify a proxy first — the Files tab reads through it.")
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StorageRoot.entries.forEach { r ->
                TextButton(onClick = { viewModel.selectRoot(r) }) {
                    Text(
                        r.displayName,
                        fontWeight = if (r == root) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = viewModel::statCurrentTree) {
                Icon(Icons.Filled.Refresh, contentDescription = "Measure this folder")
            }
        }

        Breadcrumb(path, viewModel)

        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        if (error != null) {
            Text(
                error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )
        }

        stat?.let {
            Text(
                "${it.entryCount} entries, ${humanBytes(it.totalBytes)} under this path",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        if (entries.isEmpty() && !loading && error == null) {
            EmptyState("Empty.")
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(entries, key = { it.relativePath }) { entry ->
                    EntryRow(entry, onClick = { viewModel.navigateInto(entry) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun Breadcrumb(path: String, viewModel: MainViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { viewModel.navigateUp() },
            enabled = path.isNotEmpty(),
        ) { Icon(Icons.Filled.ArrowBack, contentDescription = "Up one level") }
        SelectionContainer(Modifier.weight(1f)) {
            Text(
                if (path.isEmpty()) "/" else "/$path",
                style = MonoTypography,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun EntryRow(entry: RemoteEntry, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry.isDirectory) {
            Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        } else {
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(
                if (entry.isDirectory) "folder" else humanBytes(entry.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!entry.canRead || !entry.canWrite) {
                Text(
                    listOfNotNull(
                        if (!entry.canRead) "not readable" else null,
                        if (!entry.canWrite) "not writable" else null,
                    ).joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (entry.isDirectory) {
            TextButton(onClick = onClick) { Text("Open") }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- Shell -----------------------------------------------------------------

@Composable
private fun ShellScreen(viewModel: MainViewModel) {
    val pkg by viewModel.packageName.collectAsStateWithLifecycle()
    val user by viewModel.userId.collectAsStateWithLifecycle()
    val commands = remember(pkg, user) { viewModel.commands() }
    val clipboard = LocalClipboardManager.current

    val sections = listOf(
        "Full rename-aside runbook" to commands.runbook,
        "List users" to commands.listUsers,
        "Inspect current state" to commands.inspect,
        "1 · Move data aside" to commands.renameAside,
        "2 · Uninstall everywhere" to commands.uninstallEverywhere,
        "3 · Uninstall keeping data" to commands.uninstallKeepData,
        "4 · Move data back" to commands.renameBack,
        "5 · Repair ownership" to commands.restoreOwnership,
    )

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Text(
                // Understating this is worse than a long paragraph: a user who copies the
                // runbook expecting plain `adb` to work gets a wall of "Permission denied" and
                // concludes their data is lost. It is not — but they cannot tell that from the
                // error, so the requirement goes in the first sentence.
                "These commands need ROOT: `adb root` on a userdebug/eng build, or `su`. A " +
                    "plain `adb` shell is refused — uid 2000 cannot reach another user's " +
                    "storage, and root cannot reach the /storage/emulated view of it either, so " +
                    "the paths below are the raw /data/media ones. An app cannot uninstall " +
                    "another app while keeping its data; there is no public API for " +
                    "`pm uninstall -k`.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(sections) { (title, body) ->
            CommandBlock(title, body, onCopy = { clipboard.setText(AnnotatedString(body)) })
        }
    }
}

@Composable
private fun CommandBlock(title: String, body: String, onCopy: () -> Unit) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onCopy) { Text("Copy") }
            }
            SelectionContainer {
                Text(body, style = CommandTypography)
            }
        }
    }
}

// ---- About -----------------------------------------------------------------

@Composable
private fun AboutScreen(viewModel: MainViewModel) {
    val fingerprint = remember { viewModel.fingerprint }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Understudy", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Since Android 11 the platform hides Android/data and Android/obb from every app " +
                "except their owner — \"All files access\" does not help. Understudy works around " +
                "that by installing a tiny stand-in APK that carries the target's package name, " +
                "so the platform hands it that app's private storage for this profile. The " +
                "stand-in re-exports the files over Binder, and is removed afterwards.",
            style = MaterialTheme.typography.bodyMedium,
        )

        HorizontalDivider()
        Text("Signing identity", style = MaterialTheme.typography.titleSmall)
        Text(
            "Proxy APKs are signed with a key generated on this device and never leaves it. " +
                "It must stay stable: reinstalling a proxy for the same package requires a " +
                "matching signature.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer { Text(fingerprint, style = MonoTypography) }

        HorizontalDivider()
        Text("What this cannot do", style = MaterialTheme.typography.titleSmall)
        listOf(
            "Install into another user's profile — PackageInstaller is limited to the calling " +
                "user without a privileged permission. Use the adb commands in the Shell tab.",
            "Uninstall while keeping data — no public API. Needs `pm uninstall -k` from a shell, " +
                "or the \"Keep app data\" checkbox in the system uninstall dialog.",
            "Beat a signature conflict on its own — package identity is device-wide, so a target " +
                "already installed anywhere under a different signature blocks the proxy until " +
                "the rename-aside runbook runs.",
        ).forEach { bullet ->
            Text("•  $bullet", style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---- formatting ------------------------------------------------------------

private fun humanBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return "%.1f %s".format(value, units[unit])
}
