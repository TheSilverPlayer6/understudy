package dev.understudy

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.understudy.ui.MainViewModel
import dev.understudy.ui.RootUi
import dev.understudy.ui.theme.UnderstudyTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MainViewModel(application) as T
        }
    }

    /** Fires the system installer / uninstaller confirmation. */
    private val installLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { /* the authoritative result arrives via InstallResultReceiver */ }

    private val plainIntentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* ditto */ }

    /** SAF destination picker. */
    private val destinationPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        viewModel.onDestinationPicked(uri)
        uri?.let { (application as UnderstudyApp).prefs.lastDestinationUri = it }
    }

    /** "Install unknown apps" for this profile — the appop is per-user, so this must be launched
     *  from inside the profile the user is working in. */
    private val unknownSourcesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        viewModel.restoreDestination()

        setContent {
            UnderstudyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    RootUi(
                        viewModel = viewModel,
                        onPickDestination = { destinationPicker.launch(null) },
                        onLaunchIntentSender = { sender ->
                            runCatching { installLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
                        },
                        onLaunchIntent = { intent ->
                            if (intent != null) runCatching { plainIntentLauncher.launch(intent) }
                        },
                        onOpenUnknownSources = {
                            runCatching {
                                unknownSourcesLauncher.launch(
                                    (application as UnderstudyApp).installer.unknownSourcesSettingsIntent()
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
