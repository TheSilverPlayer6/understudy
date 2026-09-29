package dev.understudy.proxytpl

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.understudy.proxytpl.bridge.BridgeContract
import java.io.File

/**
 * Minimal status screen for the proxy.
 *
 * It exists for two reasons: the user needs *some* visible evidence of what is installed
 * under the target's name, and the launcher entry gives Understudy a component it can ask
 * the proxy to disable in order to hide the proxy without uninstalling it.
 *
 * Built with plain framework views — this module must stay dependency-free because its dex
 * is extracted and re-signed into a standalone APK.
 */
class ProxyStatusActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        root.addView(
            TextView(this).apply {
                text = "Understudy proxy"
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
            }
        )

        root.addView(
            TextView(this).apply {
                text = "This app is impersonating a package so that its private storage " +
                    "becomes reachable. It holds no data of its own and is removed by " +
                    "Understudy when the transfer finishes."
                textSize = 14f
                setPadding(0, dp(8), 0, dp(20))
            }
        )

        val rows = linkedMapOf(
            "Impersonating" to packageName,
            "Android user" to (Process.myUid() / 100_000).toString(),
            "Protocol" to BridgeContract.PROTOCOL_VERSION.toString(),
        )
        for (name in BridgeContract.ROOTS) {
            rows[rootLabel(name)] = describeRoot(name)
        }

        for ((label, value) in rows) {
            root.addView(
                TextView(this).apply {
                    text = "$label\n$value"
                    textSize = 14f
                    setTypeface(Typeface.MONOSPACE)
                    setPadding(0, dp(6), 0, dp(6))
                }
            )
        }

        root.addView(
            TextView(this).apply {
                text = "Do not uninstall this from system settings — that erases the very " +
                    "files you are trying to rescue. Use Understudy's teardown action."
                textSize = 13f
                setTextColor(0xFFB00020.toInt())
                setPadding(0, dp(20), 0, 0)
            }
        )

        setContentView(
            ScrollView(this).apply {
                addView(
                    root,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { gravity = Gravity.TOP }
                )
            }
        )
    }

    private fun rootLabel(root: String): String = when (root) {
        BridgeContract.ROOT_DATA -> "Android/data"
        BridgeContract.ROOT_OBB -> "Android/obb"
        else -> root
    }

    private fun describeRoot(root: String): String {
        val dir = when (root) {
            BridgeContract.ROOT_DATA -> getExternalFilesDir(null)?.parentFile?.parentFile
            BridgeContract.ROOT_OBB -> getExternalFilesDir(null)?.parentFile?.parentFile
                ?.let { File(it.parentFile, "obb/$packageName") }
            else -> null
        } ?: return "unavailable"

        if (!dir.exists()) return "${dir.absolutePath}\n(not present)"
        val children = dir.listFiles().orEmpty()
        val bytes = children.sumOf { if (it.isDirectory) 0L else it.length() }
        return "${dir.absolutePath}\n${children.size} entries, $bytes bytes at top level"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
