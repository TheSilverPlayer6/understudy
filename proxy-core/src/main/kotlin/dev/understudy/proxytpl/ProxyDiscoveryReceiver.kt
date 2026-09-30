package dev.understudy.proxytpl

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Makes this proxy **findable** by the Understudy install that generated it. It does nothing else,
 * and doing nothing is the whole job.
 *
 * ## Why it has to exist
 *
 * From API 30 the platform filters package visibility, and the filtering applies to
 * `ContentResolver` authority resolution as well as to the `queryIntent*`/`getPackageInfo` family.
 * An app that cannot see a provider gets:
 *
 * ```
 * java.lang.IllegalArgumentException: Unknown authority com.example.targetgame
 * ```
 *
 * which is byte-for-byte indistinguishable from "no proxy is installed in this profile". This is
 * not hypothetical: it is what CI run #29 produced on both API 34 and API 35, in a build where the
 * provider was demonstrably alive — `adb shell content query --user 10 --uri
 * content://com.example.targetgame/data` returned rows and `content call … --method ping` returned
 * `ok=true, user=10` in the same log, seconds before the app failed to resolve the same authority.
 * The shell is not subject to the filter; the app is.
 *
 * Up to that point the bridge worked without this, by accident. The proxy used to *define*
 * `dev.understudy.permission.BRIDGE` and the app *requested* it, and AOSP `AppsFilter` makes a
 * package visible to anything that requests a permission it defines (`mQueryableViaUsesPermission`,
 * populated in `addPackageInternal`). Moving that definition to `:app` — necessary, because a
 * `signature` permission is granted only to packages signed like its *definer*, and in production
 * the proxy's per-install key can never match the app's build-time key — silently removed the only
 * thing making the proxy visible.
 *
 * ## Why an intent filter and not something simpler
 *
 * The target package name is chosen when the APK is generated, so `:app` cannot name it
 * statically:
 *
 * | Mechanism | Works? |
 * |---|---|
 * | `<queries><package android:name="…">` | No — the name is not known at build time |
 * | `<queries><provider android:authorities="…">` | No — the authority *is* the package name |
 * | a permission the proxy defines and the app requests | No — a fixed name collides between two proxies (`INSTALL_FAILED_DUPLICATE_PERMISSION`), and a per-target name cannot be requested statically |
 * | `QUERY_ALL_PACKAGES` | Yes, but Play-restricted and far broader than needed |
 * | **`<queries><intent>` with this action** | **Yes** — matches every proxy and nothing else |
 *
 * ## Why a separate component rather than a filter on `ProxyStatusActivity`
 *
 * `KEEP_HIDDEN` — the default and safest teardown — hides the proxy by disabling its launcher
 * activity, which an app may always do to its own components. Visibility is computed from
 * *enabled* components, so a filter on that activity would stop matching the moment the proxy
 * hid itself: the app would lose the ability to reach the bridge at exactly the moment it needs it
 * to call `showLauncher` again. A permanently-disabled, unreachable proxy with no UI is the worst
 * outcome this app can produce, so discovery lives on a component nothing ever disables.
 *
 * ## Security
 *
 * Exported, and guarded by the same `signature`-level `BRIDGE` permission as the provider, so a
 * stranger cannot broadcast to it and wake this process. The guard costs nothing: AOSP matches
 * `<queries>` intents against *parsed* intent filters (`canQueryViaComponents` takes
 * `AndroidPackage` objects, not a resolver), so a component permission is not consulted — and even
 * if some build did consult it, the only caller that matters holds `BRIDGE` by definition.
 *
 * `onReceive` is empty on purpose. There is nothing to answer: the app reads the bridge through
 * the provider, and this component's only observable effect is existing in the manifest.
 */
class ProxyDiscoveryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        // Nothing to do, and that is the contract. Anything added here runs in the target
        // package's process on the main thread with a ~10s ANR budget, for no benefit.
    }
}
