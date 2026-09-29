# HANDOFF — read this first

Last updated 2026-09-29. **Milestone 4 is complete: the premise is verified on real Android
emulators and CI is green end to end.** Section 4 records what that took and what it turned up;
section 3 is what remains.

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
gradlew clean :app:assembleDebug :app:assembleRelease :proxy:assembleRelease
        :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:check
  → BUILD SUCCESSFUL
  106 unit tests, 0 failures
  app-debug.apk ~20.5 MB · app-release-unsigned.apk ~2.1 MB · proxy-template.apk ~698 KB

apksigner verify --verbose --print-certs <generated proxy>
  → Verifies · v2 scheme: true · 1 signer · RSA-2048
aapt2 dump badging → package: name='com.example.targetgame'
zipalign -c -p 4   → ALIGNMENT OK
```

CI: `https://github.com/TheSilverPlayer6/understudy` — **all three jobs green** (run #18). The
`jvm` job covers unit tests, every variant, `checkProxyTemplateFresh`, and
apksigner/aapt2/zipalign over the generated APK. The two `premise` jobs boot KVM emulators on
API 34 and API 35 and report `OK (10 tests)` plus `PREMISE VERIFIED`. Section 4 records what
that took.

### Components

| Package | State | Notes |
|---|---|---|
| `packaging/axml` | **done, device-verified** | AXML string-pool codec + `ManifestPatcher`. Re-encoding an unmodified pool is byte-identical to aapt2's output. |
| `packaging/sign` | **done, verified against apksigner** | DER writer/reader, self-signed X.509 identity, v1 (JAR + hand-built PKCS#7), v2 (signing block, chunked digest, zip surgery). |
| `packaging` | **done** | Aligned `ZipWriter`; `ProxyApkFactory` pipeline. |
| `proxy-core` | **done** | The proxy's real code as an Android *library*, zero dependencies, so `:app` tests can drive it. |
| `proxy` | **done** | Manifest + dependency on `:proxy-core`; builds the template APK. |
| `bridge` | **done, Robolectric-verified** | `BridgeClient`, `BridgeError`, contract mirror. |
| `install` | **device-verified via CI's adb path; the in-app `PackageInstaller` UX is not** | `ApkGenerator`, `ProxyInstaller`, `InstallResultReceiver`. CI installs with `adb install --user`, which proves the *artifacts* install; the session-based flow the app uses is still unexercised. |
| `core` | **done, 34 tests** | `SessionState`, `SessionManager`. Guards the teardown paths — see `SessionManagerTest`. |
| `shell` | **done, 12 tests** | `ShellBackend` + `ShellCommands` runbook generator. `restoreOwnership` is new and load-bearing; section 4 explains why. |
| `transfer` | **built, model-tested** | Streamed, resumable, write-then-swap. Both directions now have a UI. |
| `storage` | **built, not device-verified** | SAF destination with persisted grants; `collectSourcesForPush` walks a user-chosen tree. |
| `ui` | **built, never run** | Compose: Session / Files / Shell / About tabs. |

---

## 3. What remains

Items 1–5 of the previous list are done: the premise CI job is green, `SessionManager` has 34
tests, release signing is wired (opt-in, see below), `checkProxyTemplateFresh` runs in `check`,
and the push direction has a UI. In rough priority order, what is actually left:

1. **Production signing vs the `signature`-level bridge permission.** The single largest gap, and
   it is a design problem rather than a task. The proxy's provider is guarded by a
   `signature`-level permission, so app and proxy must share a signer. In CI they do — both use
   the committed test keystore. In production the proxy is signed with a per-install
   `SigningIdentity` key, which cannot match a build-time release key, so a signed release would
   install a proxy that every bridge call fails against. The fix is for the proxy to verify the
   caller against the *generator's* certificate, baked in at generation time, rather than against
   its own. Until then release output is deliberately unsigned.
2. **Wireless-ADB `ShellBackend`.** Only the manual command-generating backend ships, by choice: a
   backend that claims to be available and then cannot execute is worse than none. Note the
   finding in section 4 — a plain uid-2000 shell cannot reach another user's storage, so the
   runbook the backend would drive needs root, and self-paired wireless ADB lands in uid 2000.
   That has to be reconciled before the backend is worth writing.
3. **OEM testing** — MIUI/HyperOS, ColorOS, One UI. Everything verified so far is AOSP
   `target: default` on API 34/35 emulators.
4. **The installer UX on a device.** CI installs with `adb install --user`; the
   `PackageInstaller` session path, `STATUS_PENDING_USER_ACTION` and the per-profile
   `REQUEST_INSTALL_PACKAGES` grant flow are unexercised.
5. **SAF writes** to a user-picked tree, grant persistence across reboot, and the
   `hasFragileUserData` "Keep app data" checkbox on a current build.
6. **Recovery from process death** mid-transfer.
7. **Proxy APK size** (~690 KB, dominated by the Kotlin stdlib in `classes.dex`).

---

## 4. Milestone 4: the premise, verified

CI run #18 is green: `OK (10 tests)` in the instrumented suite and `PREMISE VERIFIED` on both
API 34 and API 35. A proxy APK generated at runtime, installed for a secondary user, reads and
writes that user's `Android/data/<pkg>` and `Android/obb/<pkg>`, streams the bytes to another app
over Binder, and `pm uninstall -k` preserves the directories afterwards.

It took runs #10–#18. Three separate defects were stacked, and each one hid the next:

**1. The uid lookup could never have worked.** `dumpsys package <pkg> | grep userId=` matches
nothing on API 34+: Android 14 renamed the field to `appId=` (AOSP `Settings.java`:
`pw.print("  appId="); pw.println(ps.getAppId())`). And the value in either field is the **app
id**, shared by every user, while FUSE attributes app-specific storage by the **per-user** uid —
`userId * 100000 + appId`, so `u10_a148` is 1010148. Chowning to the bare appId leaves user 10's
files owned by a user-0-range uid and the app still cannot read them.

The reliable source is `pm list packages -U --user N <pkg>`: `PackageManagerShellCommand` prints
`applicationInfo.uid`, which `PackageInfoUtils.initForUser` computes as
`UserHandle.getUid(userId, appId)` — already per-user, stable format, every API level, and `-u`
also covers the retained state `pm uninstall -k` leaves behind. `--user` is mandatory; the
default queries user 0. CI cross-checks it against `stat -c %u /data/user/N/<pkg>`, the ground
truth installd creates.

Two more traps in the same command: `UID` is **read-only in bash**, so `UID=$(...)` aborts the
operator's shell before any chown runs; and `chmod 771` clears the setgid bit the platform relies
on (its mode is 2770, not 771), so ownership repair must chown the owner only.

**2. The data root was one directory too high.** `rootDir("data")` was
`getExternalFilesDir(null).parentFile.parentFile`, which assumes the platform returns
`.../Android/data/<pkg>/files`. On API 34/35 it returns `.../Android/data/<pkg>`, so two levels up
landed on `.../Android/data` — the shared parent of every package's private storage, and exactly
what scoped storage hides from all apps. Listing it returned `AccessDeniedException`, which read
as "the premise is false" when the restriction was working correctly on a path we should never
have pointed at. Meanwhile obb worked, because its path was built with the package segment
intact. That asymmetry is what made it look like a platform difference between data and obb
rather than a bug in one of two sibling expressions.

**3. The write test contradicted the suite it lived in.** It confirmed a bridge write by stat'ing
the path *from the test app's uid* — which the platform forbids, and which
`thePlatformStillHidesOtherPackagesPrivateStorageFromUs` asserts two tests later. Only one of the
two could ever pass. "Did my write reach the real filesystem?" is answerable only from inside the
proxy, so the bridge now has `statPath`, which reports exists/isDirectory/size plus the canonical
path the platform resolved.

### The lesson worth carrying

For a claim about what a process can see, the only admissible evidence is **that process's own
report**. Five runs of increasingly subtle external observation — mount namespaces, inode
identity, logcat filters, reading MediaProvider source — built a correct and complete picture of
everything except the one number that mattered, because none of them asked the proxy what it
thought its own root directory was. `ProxyFileBridge.selfDiagnostic` exists so that question can
always be asked, in CI and from a support request on a real device.

Second lesson: `File.listFiles()` returns null for both EACCES and ENOENT and never surfaces
errno. Every "permission denied" string in this project was an inference until the diagnostics
used `java.nio.file`, whose `AccessDeniedException` versus `NoSuchFileException` is the
distinction that mattered. There is no NDK on the CI runner and no compiler for a static Android
binary, so a C probe was not an option — that was verified, not assumed.

### Two findings that changed the product, not just the tests

**FUSE attributes app-specific storage by owning uid.** Run #10's `ls -laR` is the evidence: the
`files/` directory the proxy created through FUSE is `u10_a148 ext_data_rw` mode 2770, while the
`planted/` directory root created next to it stayed `root ext_data_rw` and was invisible to the
proxy. So restoring save data via adb as root leaves it **unreadable by the app that owns it**,
presenting as an empty directory — indistinguishable from "the data is gone". This is now
`ShellCommands.restoreOwnership` and step 6b of the rename-aside runbook; the original design had
no ownership repair at all.

**A plain `adb shell` cannot reach another user's storage.** Run #12 dropped to uid 2000
(`u:r:shell:s0`) and got `Permission denied` on `/storage/emulated/10`, on
`/storage/emulated/10/Android/data/<pkg>`, on reading a file inside it, and on `/data/media/10`.
Root was refused the FUSE view too (run #11: even uid 0 gets EACCES on `/storage/emulated/10`).

This **contradicts the research the project started from**, which assumed uid 2000 is exempt from
the FUSE filter for every user's tree. On API 34/35 AOSP it is exempt only within its own user.
Consequences:

* the runbook's `mv /storage/emulated/N/...` steps need **root**, not merely adb, and should use
  the raw `/data/media/N/...` paths, since root is denied the FUSE view;
* `chown` needs `CAP_CHOWN` regardless, so ownership repair was always root-or-su-only;
* on a production device without root there is **no** shell path into another user's private
  storage. The unprivileged flows — install into one's own profile, `hasFragileUserData`'s "Keep
  app data" checkbox, keep-installed-and-hidden — are the whole product for those users, not a
  subset of it.

Still unverified: whether OEM builds differ. This is AOSP `target: default` on API 34 and 35.

### Signature-level permission in production — the next real problem

The bridge is guarded by a `signature`-level permission, so the app and the proxy it generates
must share a signer. In CI they do: both are signed with the committed test keystore. In
production the proxy is signed with a per-install `SigningIdentity` key, which **cannot** match a
build-time release key. So a signed release build would install a proxy that every bridge call
fails against with `SecurityException`.

That is why release signing is deliberately opt-in via an uncommitted `keystore.properties` and
why the release output stays unsigned without it: shipping a release build that looks finished
but cannot talk to its own proxy is worse than an obviously unsigned one. The fix is for the
proxy to verify the caller against the *generator's* certificate (baked in at generation time)
rather than against its own, which is what a `signature`-level permission cannot express. Not
implemented; see `research/05-milestone4-premise-verified.md`.

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
