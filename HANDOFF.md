# HANDOFF — read this first

Last updated 2026-09-29 by the agent that built phases 1–3. Work stopped mid-task; section 4
says exactly where.

Companion documents, in reading order:

| File | Contents |
|---|---|
| `README.md` | What the project is, the mechanism, build instructions, module map |
| `research/00-preliminary-research.md` | Platform research; the device-wide-signing finding |
| `research/01-decisions.md` | Locked version matrix and architecture decisions |
| `research/02-milestone1-signing.md` | AXML + v1/v2 signing, and the five bugs that mattered |
| `research/03-milestone2-app-layer.md` | The app layer: bridge, installer, orchestrator, UI |
| `research/04-milestone3-running-it.md` | Robolectric verification, and what blocked a real device |
| `../HANDOFF-NOTE.md` | **Sandbox traps** (outside the repo, in the workspace root) — read before touching the build environment |

If you only read one more thing after this file, make it `../HANDOFF-NOTE.md` section 6
("Verification methodology"). It explains why several hours were lost to correct code.

---

## 1. What this project is

**Understudy** restores access to `/Android/data/<pkg>` and `/Android/obb/<pkg>` on Android 11+
(API 30+), including from **secondary user profiles** where `adb` and Shizuku cannot reach.

Since Android 11 the platform hides those directories from every package except their owner,
enforced in the **FUSE layer** — so `MANAGE_EXTERNAL_STORAGE` ("All files access") does *not*
help. Understudy therefore builds a **proxy APK carrying the target's package name**, installs it
into the target profile, and lets it re-export its own private storage over Binder.

Target: Android 17 / API 37. Kotlin + Jetpack Compose. AGP 9.4.1, Gradle 9.8.0, Kotlin 2.4.20.

### The constraint that shapes the whole design

**Package identity and signing are device-wide, not per-user.** If the target is installed for
*any* user with a different signature, installing the proxy into a fresh secondary profile still
fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. And `pm uninstall -k` makes it worse: it leaves
the package *retained*, signature record intact and name reserved.

So there are two paths, and the app must tell the user which one they are on:

* Target installed nowhere → pure in-profile flow, no shell needed.
* Target installed or retained anywhere → needs a shell for the **rename-aside runbook**
  (`ShellCommands.fullRenameAsideRunbook`).

---

## 2. What is built and verified

### Verified green

```
gradlew clean :app:assembleDebug :app:assembleRelease :proxy:assembleRelease :app:testDebugUnitTest
  → BUILD SUCCESSFUL
  66 unit tests, 0 failures
  app-debug.apk ~20.5 MB · app-release-unsigned.apk ~2.1 MB · proxy-template.apk ~697 KB

apksigner verify --verbose --print-certs <generated proxy>
  → Verifies · v2 scheme: true · 1 signer · RSA-2048
aapt2 dump badging → package: name='com.example.targetgame'
zipalign -c -p 4   → ALIGNMENT OK
```

CI: `https://github.com/TheSilverPlayer6/understudy` — the `jvm` job passes fully (unit tests,
all variants, apksigner/aapt2/zipalign over the generated APK). The `premise` job is the work in
progress; see section 4.

### Components

| Package | State | Notes |
|---|---|---|
| `packaging/axml` | **done, device-verified** | AXML string-pool codec + `ManifestPatcher`. Re-encoding an unmodified pool is byte-identical to aapt2's output. |
| `packaging/sign` | **done, verified against apksigner** | DER writer/reader, self-signed X.509 identity, v1 (JAR + hand-built PKCS#7), v2 (signing block, chunked digest, zip surgery). |
| `packaging` | **done** | Aligned `ZipWriter`; `ProxyApkFactory` pipeline. |
| `proxy-core` | **done** | The proxy's real code as an Android *library*, zero dependencies, so `:app` tests can drive it. |
| `proxy` | **done** | Manifest + dependency on `:proxy-core`; builds the template APK. |
| `bridge` | **done, Robolectric-verified** | `BridgeClient`, `BridgeError`, contract mirror. |
| `install` | **built, not device-verified** | `ApkGenerator`, `ProxyInstaller`, `InstallResultReceiver`. |
| `core` | **built, untested** | `SessionState`, `SessionManager`. No test coverage yet. |
| `shell` | **done** | `ShellBackend` + `ShellCommands` runbook generator (10 tests). |
| `transfer` | **built, model-tested** | Streamed, resumable, write-then-swap. |
| `storage` | **built, not device-verified** | SAF destination with persisted grants. |
| `ui` | **built, never run** | Compose: Session / Files / Shell / About tabs. |

---

## 3. What remains

In rough priority order:

1. **Finish the premise CI job** (section 4). This is the only thing standing between the project
   and a verified central claim.
2. **`SessionManager` tests.** The transition guards are the safety-critical logic in the app —
   teardown must never destroy data it has not accounted for — and they have zero coverage.
3. **`:app` release signing.** Release output is currently unsigned. Decide between a committed
   keystore and first-run generation; first-run matches how `SigningIdentity` already works.
4. **`checkProxyTemplateFresh`.** A `check`-hooked task comparing the committed
   `app/src/main/assets/proxy-template.apk` against a freshly built `:proxy` APK. Today
   `./gradlew :app:syncProxyTemplate` is manual and nothing fails if you forget it — the app
   would silently ship a stale template. (It must stay a standalone task: writing into `src/`
   conflicts with `lintVital*` in the same task graph.)
5. **Push direction in the UI.** `TransferEngine.push` and `PushSource` exist and are model-tested;
   no screen drives them.
6. **Wireless-ADB `ShellBackend`.** Only the manual command-generating backend ships, by choice:
   a backend that claims to be available and then cannot execute is worse than none.
7. **OEM testing** — MIUI/HyperOS, ColorOS, One UI.

---

## 4. Exactly where work stopped

### The last CI run (#10) got further than any before it

On API 34/35 emulators with KVM, the run achieved:

* `adb root` succeeded (needed `target: default` / AOSP; `google_apis` is production-signed)
* secondary user 10 created and started
* the **runtime-generated proxy APK installed into user 10**
* `:app` and the test APK installed into user 10
* fixtures planted into `/data/media/10/Android/{data,obb}/com.example.targetgame`
* the proxy process **started and answered** — `ping()` passed, so the `signature`-level
  `BRIDGE` permission was genuinely granted across the two APKs
* `thePlatformStillHidesOtherPackagesPrivateStorageFromUs` **passed** — confirming on a real
  device that the restriction Understudy works around is real, so the suite cannot pass vacuously

Three tests still failed, all with the same root cause:

```
cannot list: content://com.example.targetgame/data (permission denied)
no such entry: content://com.example.targetgame/data/planted
```

### Root cause found, fix written but NOT yet verified

`File.listFiles()` returned null inside the proxy. The planted directories were
`drwxrws--- root ext_data_rw` — created by `adb shell` running as **root**.

**The FUSE layer attributes app-specific external storage by owning uid, not merely by path.**
So a root-owned `Android/data/<pkg>` is denied even to the package that owns it, and it presents
as an empty directory. The platform creates these directories as the app's uid; anything restored
from a backup as root has to be chowned or the real app cannot read its own files.

This is a **product finding, not just a test-fixture issue**: anyone restoring save data via adb
will hit it. That is why the fix has two halves:

* **Test fixture** — `.github/scripts/run-premise-test.sh` now looks up the proxy's uid via
  `dumpsys package` and `chown -R`s the planted tree to it, then `chmod 771`.
* **Product** — `ShellCommands.restoreOwnership(userId, packageName)` was added, wired into
  `fullRenameAsideRunbook` as step 6b (after `renameBack`), exposed through
  `MainViewModel.ShellCommandSet` and shown in the Shell tab. `renameBack` now warns that `mv`
  preserves ownership but an adb restore does not.

### Uncommitted at the moment of stopping

```
 M .github/scripts/run-premise-test.sh          # chown planted fixtures to the proxy uid
 M app/src/main/kotlin/dev/understudy/shell/ShellBackend.kt   # + restoreOwnership()
 M app/src/main/kotlin/dev/understudy/ui/MainViewModel.kt     # + restoreOwnership in command set
 M app/src/main/kotlin/dev/understudy/ui/RootUi.kt            # + "5 · Repair ownership" block
 M app/src/test/kotlin/dev/understudy/shell/ShellCommandsTest.kt  # + 2 ownership tests
```

**These five files were never built or tested.** `restoreOwnership` was rewritten once already
because a Python heredoc mangled the `${'$'}` shell-variable escapes; the current text looks
syntactically correct but that is unverified. So the immediate next actions are:

```bash
./tools/dtsync
./tools/dtbuild ":app:assembleDebug :app:testDebugUnitTest --rerun-tasks"   # expect 68 tests
git add -A && git commit && REPO=understudy ./tools/ghpush origin main      # then watch CI
```

Expected test count after this lands: **68** (66 + the two new ownership tests).

---

## 5. Things that will bite you

Ranked by how much time they cost me.

1. **Robolectric does not enforce permissions.** The missing
   `<uses-permission android:name="dev.understudy.permission.BRIDGE"/>` in `:app` was invisible
   in 26 passing integration tests and would have been a total failure on every real device.
2. **Robolectric registers providers by class reference**, so it never resolves component names
   through PackageManager. That is why the manifest-renaming bug in section 6 survived a green
   suite.
3. **`am instrument` exits 0 even when tests fail.** The CI script greps the output for
   `FAILURES!!!` / `Error in ` instead of trusting the exit code.
4. **Gradle serves tests from cache.** A test whose *side effect* is an artifact a later CI step
   verifies must actually run: `org.gradle.caching=false` plus `--rerun-tasks`.
5. **`tar -x` sync does not delete.** A file moved out of a module survives remotely and keeps
   being compiled, producing a duplicate-class dex error that points at two `build/` directories
   and nowhere near the cause. `tools/dtsync` now mirrors (`rm -rf` first).
6. **A task writing into `src/` cannot share a task graph with `assembleRelease`** — `lintVital*`
   also reads that directory and Gradle 9 fails on the implicit dependency.
7. **`adb root` needs a userdebug/eng image.** `target: google_apis` is production-signed and
   refuses it, and uid 2000 cannot reach *another* user's emulated storage at all.
8. The workspace traps (mount namespaces, heredoc substitution, snapshot lag, silently-failing
   edits) are in `../HANDOFF-NOTE.md` section 1. They cost more time than the cryptography did.

---

## 6. The bug that only a device could find

`ManifestPatcher` originally did a **prefix** substitution, rewriting fully-qualified component
names along with the package. The reasoning was that aapt resolves `.bridge.ProxyFileBridge`
against the manifest package at build time, so the names "must follow".

That reasoning is wrong. A component class's package has **no obligation** to match the
application's package. The dex kept `Ldev/understudy/proxytpl/bridge/ProxyFileBridge;` while the
manifest asked for `com.example.targetgame.bridge.ProxyFileBridge`, so the proxy died at process
start:

```
RuntimeException: Unable to get provider com.example.targetgame.bridge.ProxyFileBridge:
  ClassNotFoundException: Didn't find class "com.example.targetgame.bridge.ProxyFileBridge"
```

Substitution is now **exact match on the bare package string only**. Because the AXML string pool
is deduplicated, `<manifest package>` and the provider's `authorities` share one string index, so
a single edit retargets both — and `EXPECTED_IDENTITY_STRINGS` asserts that count, so a template
that drifts fails at generation time rather than on a device.

The lesson worth carrying: **the JVM test suite asserted the buggy behaviour and passed.** Nine
`ManifestPatcherTest` cases checked that component names *were* rewritten. Tests encode your
current understanding, not the truth; for a project whose premise is a kernel behaviour, only the
kernel can confirm it.

---

## 7. Environment and credentials

* **Build sandbox:** Daytona, id in `../tools/sandbox.json`. Debian 13, 4 vCPU / 8 GB / **10 GB
  disk (hard cap)**, ~6 GB free. JDK 21, Android SDK at `/opt/android-sdk`, Gradle 9.8.0 at
  `/opt/gradle`. Recreate with `../tools/bootstrap_sandbox.sh`.
* **No `/dev/kvm`** in the Daytona sandbox (the node exists but is `nobody:nogroup` and
  `sudo chmod` is denied; `kvm=True` returns *"KVM sandboxes are not enabled for this
  organization"*). **That is why device testing runs on GitHub Actions instead.**
* **GitHub:** repo `TheSilverPlayer6/understudy`, account `TheSilverPlayer6` (free plan, so
  public repos get unlimited Actions minutes — that is why the repo is public). Push with
  `REPO=understudy ../tools/ghpush origin main`; it uses an ephemeral credential helper and
  scrubs itself. Watch runs with `python3 ../tools/gh_runs.py` and logs with
  `python3 ../tools/gh_logs.py`.
* **`keys/github.token` and `keys/daytona.key`** live in the *workspace root*, outside the repo,
  and are gitignored. Both were pasted into a chat transcript at some point and **should be
  rotated**. The GitHub token is a classic token with full account access.
* The committed `keystore/understudy-test.p12` (password `understudy`, alias `understudy`) is a
  **test-only** identity, deliberately committed so `:app`'s debug build and the generated proxy
  share a signer — without which the `signature`-level permission cannot be granted. Production
  uses `SigningIdentity.loadOrCreate`, a per-install key. Do not reuse the test keystore for a
  release build.
