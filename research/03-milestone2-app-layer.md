# Milestone 2 — application layer (COMPLETE except device testing)

Date: 2026-09-29 · Status: **green**

## Verified end state

Full clean run via `tools/final_verify.sh` (`gradlew clean`, then everything below):

| Check | Result |
|---|---|
| `:app:assembleDebug` | **BUILD SUCCESSFUL** → `app-debug.apk` 20.5 MB |
| `:app:assembleRelease` | **BUILD SUCCESSFUL** → `app-release-unsigned.apk` 2.1 MB |
| `:proxy:assembleRelease` | BUILD SUCCESSFUL → `proxy-release.apk` 696 KB |
| Unit tests | **39 / 39 pass, 0 failures** |
| `apksigner verify` on a runtime-generated proxy | **Verifies** · v2 = true · 1 signer · RSA-2048 · `O=Understudy, CN=Understudy Test` |
| `aapt2 dump badging` | `package: name='com.example.targetgame'` |
| `zipalign -c -p 4` | ALIGNMENT OK |
| Template asset inside `app-debug.apk` | present, 696,250 bytes |
| Sandbox disk | 3.8 G used / 6.3 G free |

## What was added in this milestone

| Package | Files | Responsibility |
|---|---|---|
| `bridge` | `BridgeContract`, `BridgeClient`, `BridgeError` | Binder client for the proxy provider; typed failure modes; independent path validation |
| `core` | `SessionState`, `SessionManager` | The lifecycle state machine |
| `core.model` | `Models.kt` | `ProxyTarget`, `RemoteEntry`, `StorageRoot`, `BridgeInfo`, `TreeStat` |
| `install` | `ApkGenerator`, `ProxyInstaller`, `InstallResultReceiver` | Template → signed APK; `PackageInstaller` staging/commit; result decoding |
| `shell` | `ShellBackend`, `ManualShellBackend`, `ShellCommands` | Backend abstraction + the rename-aside runbook generator |
| `transfer` | `TransferModel`, `TransferEngine` | Streamed, resumable, write-then-swap copying |
| `storage` | `SafDestination` | SAF tree with persisted grants |
| `ui` | `MainViewModel`, `RootUi`, `theme/*` | Compose UI: Session / Files / Shell / About |
| root | `UnderstudyApp`, `Prefs`, `MainActivity` | App-scoped container and persistence |

New tests: `ShellCommandsTest` (10), `TransferModelTest` (11). Plus the 18 from milestone 1.

## Platform APIs that turned out differently than expected

Each of these produced a real compile error and is worth knowing before you touch this code:

1. **`PackageInstaller.Session` has no `createIntentSender(PendingIntent)`.** The correct install
   flow is `session.commit(pendingIntent.intentSender)`; the confirmation UI then comes back
   *asynchronously* as `STATUS_PENDING_USER_ACTION` carrying an `Intent` you must launch. So
   `StagedInstall` carries no `IntentSender` — `stage()` commits and returns.
2. **`PackageInstaller.uninstall(pkg, IntentSender)` returns `Unit`.** Only the
   `uninstall(pkg, int flags)` overload returns an `IntentSender`, and that one needs
   `DELETE_PACKAGES`. Uninstall is therefore fire-and-forget with a status receiver.
3. **`SessionParams.setPackageName()` does not exist** in the public API — the package comes from
   the APK. (`SessionParams` docs mention an app-name hint for observers; that is a different
   setter.)
4. **`SafDestination.openWrite()` returns a `ParcelFileDescriptor`, not an `OutputStream`.**
   Wrap with `ParcelFileDescriptor.AutoCloseOutputStream`; likewise `AutoCloseInputStream` for
   reads. The `AutoClose*` wrappers also close the descriptor, so a nested `use` does not leak.
5. **`ContentProvider` already declares a protected `requireContext()`** — defining one in a
   subclass fails with *"hides member of supertype and needs an 'override' modifier"*.

## Design decisions worth preserving

**Teardown refuses to destroy data by default.** `TeardownStrategy.DESTROY_DATA` requires an
explicit `confirmDataLoss = true` argument, not merely a dialog having been shown, so a UI bug
cannot trigger it. `EVACUATE_THEN_UNINSTALL` is only offered once `recordVerifiedPull` has been
called for that exact target, and if the proxy's self-wipe fails the teardown **aborts** rather
than proceeding to an uninstall that might still delete something.

**`KEEP_HIDDEN` is the default and safest teardown.** The proxy disables its own launcher
activity (an app may always toggle its own components; toggling *another* app's needs
`CHANGE_COMPONENT_ENABLED_STATE`), so `Android/data/<pkg>` is never touched and re-enabling is
instant. No shell required.

**Proxy detection is done by calling the provider, not by querying packages.** That avoids
`QUERY_ALL_PACKAGES` (Play-restricted) entirely, and a successful `ping` is strictly stronger
evidence: it proves the provider is live, the signature permission is granted, and the protocol
version matches. `ping` also returns the proxy's own user id, which the state machine compares
against the requested one — a proxy in the wrong profile would otherwise present as an
empty `Android/data` and look like data loss.

**`SessionManager` lives in `Application`, not a ViewModel.** An install or transfer orphaned by
a rotation would leave a proxy installed with no UI to tear it down, which is exactly the
situation this app exists to prevent.

**The shell backend is honest about its limits.** `ManualShellBackend.execute()` throws
`ShellUnavailable` rather than faking a result, and the orchestrator uses that distinction to
fall back to *showing* the commands. A wireless-ADB backend is the intended upgrade; it is
deliberately **not** stubbed in, because a backend that reports itself available and then cannot
execute is worse than no backend at all.

**SAF destination grants are persisted immediately** after the picker returns. The grant from
`ACTION_OPEN_DOCUMENT_TREE` is only valid for the current task until
`takePersistableUriPermission` is called, so deferring this to first use silently loses access.

**Pulls are resumable.** A destination file already at the expected size is skipped, which makes
restarting an interrupted multi-GB `.obb` pull cheap. Combined with the `.part` write-then-swap,
the destination only ever holds either the previous good copy or a complete new one.

## Not covered — stated plainly

* **Nothing has run on a device or emulator.** The build sandbox is a container with no
  `/dev/kvm`, and `kvm=True` sandbox creation returns
  `DaytonaForbiddenError: KVM sandboxes are not enabled for this organization`. So the provider
  round-trip, the installer UX, SAF writes, the state machine under process death, and every OEM
  variation are **unexercised**. Everything verified so far is JVM unit tests plus external
  verifiers (`apksigner`, `aapt2`, `zipalign`, `keytool`) run over real artifacts.
* **No Robolectric tests** for `BridgeClient` or `SessionManager`, though Robolectric 4.17 is on
  the test classpath. These are the highest-value next tests: `BridgeClient`'s path validation
  and error mapping, and `SessionManager`'s transition guards, are both pure enough to test
  without a device.
* **`:app` has no release signing config**, so release output is unsigned.
* **`syncProxyTemplate` is manual.** Nothing fails if `:proxy` changes and the asset is not
  refreshed; the app would ship a stale template. A hash-comparison task hooked into `check` is
  the obvious fix.

## Suggested next steps

1. Robolectric tests for `BridgeClient` (path validation, error mapping from
   `IllegalArgumentException`/`SecurityException`) and `SessionManager` (transition guards,
   especially the teardown refusals).
2. Release signing config — decide between a committed keystore and first-run generation.
   First-run generation matches how `SigningIdentity` already works for proxies and avoids every
   install sharing one identity.
3. A `checkProxyTemplateFresh` task comparing the committed asset's hash against a freshly built
   `:proxy` APK, wired into `check`.
4. Wireless-ADB `ShellBackend`: pair via `ACTION_REQUEST_PAIRING_WITH_WIRELESS_DEBUGGING`, then an
   ADB client over TLS (SPAKE2). Substantial; the manual backend covers the functionality
   meanwhile.
5. Push direction in the UI. `TransferEngine.push` and `PushSource` exist and are tested at the
   model level, but no screen drives them yet.
6. Device testing, once a KVM-capable environment is available. Priorities: the provider
   round-trip in a real secondary profile, the `hasFragileUserData` uninstall dialog actually
   offering the checkbox, and behaviour on MIUI/ColorOS/One UI.
