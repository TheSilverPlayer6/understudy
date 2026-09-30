# Preliminary Research — "Proxy Package" access to `/Android/data` & `/Android/obb`

Date: 2026-09-29 · Target: Android 17 (API 37)

---

## 1. Platform baseline

| Item | Finding |
|---|---|
| Android 17 | **API level 37**. Beta 3 = platform stability (API surface locked, 26 Mar 2026); Beta 4 = 16 Apr 2026. QPR1 reports as `37.1` / `3700001`. |
| Recommended build config | `compileSdk = 37`, `targetSdk = 37`, `minSdk` = see Q below. |
| Storage model | Unchanged in spirit since R: `/Android/data/<pkg>` and `/Android/obb/<pkg>` are **blocked at the FUSE layer** for every other package, including holders of `MANAGE_EXTERNAL_STORAGE` ("All files access"). Confirmed still true through current releases. |
| Per-user layout | App-specific external dirs are **per user**: `/storage/emulated/<userId>/Android/data/<pkg>`. A proxy installed for user 10 sees **only** user 10's tree — which is exactly what we want. |

Consequence: the only first-party way for a process to touch `/storage/emulated/10/Android/data/com.foo` is to **be** `com.foo` running as user 10. That is the entire premise, and it holds.

---

## 2. The load-bearing constraint: package identity is device-wide, not per-user

This is the single most important finding, and it partially contradicts the brief's assumption that secondary-user installs sidestep the owner-profile requirement.

`PackageManagerService` keeps **one** `PackageSetting` per package name for the whole device. Signing certificates live on that global record, not on the per-user install state. Therefore:

> If `com.target` is installed for **any** user (including user 0) with signature **A**, installing our proxy APK — same package name, signature **B** — for user 10 fails with **`INSTALL_FAILED_UPDATE_INCOMPATIBLE`** ("signatures do not match the previously installed version"). It is not per-user scoped.

Corollaries:

* **Clean case (no conflict):** `com.target` is not installed and not retained anywhere on the device → proxy installs for user 10 with zero privileged help. ✅ Pure secondary-user flow works.
* **Conflict case:** `com.target` installed in user 0 only, or installed/retained in user 10 → proxy install is **blocked**, regardless of which user we target.
* **`-k` makes the conflict permanent.** `pm uninstall -k` (and the "keep app data" checkbox now offered by the system uninstall UI) leaves the package in a *retained* state: the `PackageSetting` — including its signatures — survives, and the package name stays reserved. So "uninstall the real app with `-k` to save the data" **does not** free the name for our proxy. Reinstalling the *same-signed* APK revives the data; installing ours does not.
* **Only a full uninstall for all users clears the record** — and that destroys the data we were trying to protect.

### The rename-aside escape hatch

`adb shell` (uid 2000, `shell_data_file` SELinux domain) is exempt from the FUSE filter and can see and manipulate every user's `/Android/data`. That enables a data-preserving swap even in the conflict case:

```
# N = target user id (e.g. 10)
adb shell mv /storage/emulated/N/Android/data/com.target{,.proxybak}
adb shell mv /storage/emulated/N/Android/obb/com.target{,.proxybak}
adb shell pm uninstall com.target            # ALL users; data dirs are already moved aside
# ... install proxy for user N, do the work ...
adb shell pm uninstall -k --user N com.target # keep the proxy's data dirs
adb shell mv /storage/emulated/N/Android/data/com.target{.proxybak,}   # restore
adb shell mv /storage/emulated/N/Android/obb/com.target{.proxybak,}
# optionally reinstall the real APK; it adopts the restored dirs
```

This is the only known non-root path that survives a signature conflict, and it necessarily needs a shell. It should be a first-class, orchestrated flow in the app rather than a footnote.

---

## 3. What an unprivileged app in a secondary profile can and cannot do

| Capability | Verdict |
|---|---|
| Install an APK for **its own** user via `PackageInstaller` session | ✅ with `REQUEST_INSTALL_PACKAGES`. This is a **per-user** appop — it must be granted separately inside each secondary profile. |
| Install an APK for **another** user (`SessionParams` user targeting) | ❌ Requires `INSTALL_PACKAGES` (signature\|privileged). |
| Uninstall **silently** | ❌ `DELETE_PACKAGES` is signature\|privileged; `PackageInstaller.uninstall()` / `ACTION_DELETE` are gated on it. |
| Uninstall **with data retention** (`DELETE_KEEP_DATA` / `-k`) from an app | ❌ No public API. Shell-only. |
| Uninstall via the system UI (`ACTION_UNINSTALL_PACKAGE`) | ✅ but **always wipes** `/Android/data/<pkg>`. No "keep data" affordance is exposed to the caller. |
| Hide the proxy's launcher icon **from our app** | ❌ `setApplicationEnabledSetting` on a *foreign* package needs `CHANGE_COMPONENT_ENABLED_STATE` (signature\|privileged). |
| Proxy hides **its own** launcher icon | ✅ An app may freely toggle its own components. |
| Query whether the proxy is installed | ⚠️ Package-visibility filtering (Android 11+) applies. Solvable via `<queries>` or an explicit-`ComponentName` probe; `QUERY_ALL_PACKAGES` is Play-restricted. |
| Start the proxy / talk to it | ✅ `ContentProvider` calls and explicit `bindService` auto-start the target process. Background-*activity*-start rules do **not** apply to providers/services — important, since the secondary profile may not be the foreground user. |

**Design consequence:** without a shell, "uninstall the proxy but keep its data" is impossible. The realistic unprivileged options are (a) don't uninstall — keep it installed and self-hidden, or (b) evacuate the tree before uninstalling and restore it after a later reinstall.

---

## 4. Privileged backends available on-device

1. **Shizuku** — runs `app_process` as uid 2000 and proxies Binder + shell. Started from **wireless debugging pairing** (no PC) or root. Must be started in the **owner** profile; its Binder service registers in user 0, so a client in user 10 needs cross-user reach (the standard `rish`/UserService path is documented as constrained by user boundaries). Matches the brief's "owner-profile installation enables adb-class features" model.
2. **Self-pairing wireless ADB** (LADB / Shizuku-style) — the app opens `ACTION_REQUEST_PAIRING_WITH_WIRELESS_DEBUGGING` / `openWifiPairingDialog`, connects to `127.0.0.1:<port>`, completes the SPAKE2 exchange, and then speaks ADB to itself, landing in a uid-2000 shell. Fully self-contained, no companion app. Caveats: Developer Options + Wireless Debugging must already be on (a **global** setting, toggled from the owner profile), OEMs interfere, and the pairing dialog is user-facing.
3. **Root / KernelSU** — trivially solves everything; worth an optional path.
4. **Device Owner (`dpm set-device-owner`)** — exposes `setRetainDataOnUninstall()`. Extremely heavy; not recommended.

Any of 1–3 gives us `pm uninstall -k --user N` and the `mv` rename-aside dance, from the device itself.

---

## 5. Transport options for moving bytes between the two apps

The brief specifies "over the network." Ranked by robustness:

| Option | Notes |
|---|---|
| **Exported `ContentProvider` + `ParcelFileDescriptor`** | Most Android-native. No socket, no port, no firewall, no foreground service, no keep-alive. Provider access auto-starts the proxy process even when user 10 is backgrounded. Streams arbitrary sizes. Supports `openFile` (r/w), directory listing, MIME. |
| **Loopback HTTP server in the proxy** | Literally satisfies "over the network". Costs: a foreground service + notification to survive, port selection, and OEM background-kill risk while the profile isn't foreground. |
| **`DocumentsProvider` (SAF)** | The proxy exposes the tree as a document provider; the main app gets a *system file picker* over `/Android/data/<pkg>`. Superb UX, and third-party file managers can browse it too. |
| **LAN HTTP with token auth** | Lets a desktop or the owner profile reach user 10's files over Wi‑Fi. Genuinely useful, but a real attack surface. |

Note the two apps are signed with **different** keys (main app = ours; proxy = ours too, actually — we control both), so a `signature`-level custom permission **is** usable to protect the provider. That closes the obvious "any app can read the proxy's provider" hole.

---

## 6. Building and signing the proxy APK on-device

* **Template-patch approach** — ship a tiny prebuilt APK (or raw `classes.dex` + `resources.arsc`) as an asset; rewrite the package name and version at runtime; re-sign. Java's `zipfile`/`ZipOutputStream` equivalents exist on Android; patching `resources.arsc` string pool is fiddly but tractable. Alternatively keep the proxy's *package name fixed* and instead vary… no — the package name **is** the payload, so it must be rewritten.
* **Runtime DEX generation** — generate `classes.dex` from scratch (e.g. via `dexlib2`-style emission) plus a hand-built binary `AndroidManifest.xml`. More code, total control, no template asset.
* **`minSdk` of the proxy** — keep low (e.g. 24) so one artifact works everywhere; `targetSdk` 30+ is sufficient because the proxy only ever needs **its own** app-specific dir, which requires **no runtime permissions at all** on any version.
* **Signing key** — a single embedded keystore means the proxy for `com.target` can be updated/reinstalled across sessions without conflict. The proxy never needs to match the real app's signature (it *can't*).
* **v2/v3 signature scheme** required for API 30+ installs; `apksigner`-equivalent must be done in-app (Bouncy Castle / Conscrypt + manual APK Signing Block).

---

## 7. Sandbox constraint (affects deliverable format)

The workspace I'm running in has **no JDK, no Android SDK, no Gradle, no Kotlin compiler, and no package network access** (Python 3.11 only). I therefore **cannot compile, lint, or run** the project here. I can deliver a complete, correctly-structured Gradle project (Kotlin + Compose, both modules, manifest, signing config, README) that you build in Android Studio — but I want that expectation set explicitly rather than discovered later.

---

## 8. Open risks I'd flag now

1. **Signature conflict is the common case, not the edge case.** Most users' target games *are* installed in the owner profile. The brief treats secondary-profile-only as the primary path; in practice the shell-assisted path will carry most of the load.
2. **`-k` retained state blocks re-proxying.** Users who already "kept data" on uninstall are in the worst state: data present, name reserved, signature locked. Needs an explicit detected-and-explained path.
3. **Per-user `REQUEST_INSTALL_PACKAGES`.** Users must grant "install unknown apps" *inside* the secondary profile, and the granting Settings UI for a secondary user can be awkward on some OEM skins.
4. **OEM variance.** MIUI/HyperOS, ColorOS, One UI each add their own installer guards, background-kill rules, and wireless-debugging timeouts.
5. **Play policy.** `QUERY_ALL_PACKAGES`, `MANAGE_EXTERNAL_STORAGE`, and "install unknown apps" all draw review scrutiny; an app whose purpose is to install other-package-name APKs is very unlikely to pass Play review. Assume sideload/F-Droid distribution.
6. **Data-loss asymmetry.** Every failure mode in the uninstall phase risks destroying someone's only copy of a save file. The design should default to *refuse to proceed* whenever the data's safety can't be proven.
