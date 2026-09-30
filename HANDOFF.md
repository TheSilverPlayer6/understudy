# HANDOFF — read this first

Last updated 2026-09-30. **Milestone 4 is complete: the premise is verified on real Android
emulators.** Milestone 5 fixed the two things that stopped it being *usable* — a signed release
that could not talk to the proxy it generated, and a device that could only ever hold one proxy at
a time — and found a third problem underneath them: the app could not see its proxies at all.
Sections 4 and 4b record what that took; section 3 is what remains.

Companion documents, in reading order:

| File | Contents |
|---|---|
| `README.md` | What the project is, the mechanism, build instructions, module map |
| `research/00-preliminary-research.md` | Platform research; the device-wide-signing finding |
| `research/01-decisions.md` | Locked version matrix and architecture decisions |
| `research/02-milestone1-signing.md` | AXML + v1/v2 signing, and the five bugs that mattered |
| `research/03-milestone2-app-layer.md` | The app layer: bridge, installer, orchestrator, UI |
| `research/04-milestone3-running-it.md` | Robolectric verification, and what blocked a real device |
| `research/05-milestone4-premise-verified.md` | **The premise, verified.** Three stacked defects, and two findings that changed the product |
| `research/06-milestone5-bridge-permission-ownership.md` | **Who owns the bridge permission, and who can see whom.** Includes the AOSP visibility rule the bridge had been relying on by accident |
| `../HANDOFF-NOTE.md` | **Sandbox traps** (outside the repo, in the agent workspace root) — read before touching the build environment |

If you only read one more thing after this file, make it `research/06`. It explains how six
consecutive green CI runs proved the bridge worked while saying nothing about *why*, and how the
reason turned out to be an undocumented platform rule that a well-intentioned fix deleted.

The `research/` tree is committed to this repo as of milestone 5. Before that every one of those
links was dead — the files lived only in the agent workspace.

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
  186 unit tests, 0 failures
  app-debug.apk ~20.5 MB · app-release-unsigned.apk ~2.1 MB · proxy-template.apk ~699 KB
  lint: 0 errors, 2 warnings (both kept deliberately)

apksigner verify --verbose --print-certs <generated proxy>
  → Verifies · v2 scheme: true · 1 signer · RSA-2048
aapt2 dump badging → package: name='com.example.targetgame'
zipalign -c -p 4   → ALIGNMENT OK

aapt2 dump permissions app-debug.apk
  → permission: dev.understudy.permission.BRIDGE            (the app DEFINES it — see §4b)
  → uses-permission: name='dev.understudy.permission.BRIDGE'
aapt2 dump permissions <any generated proxy>
  → nothing. A proxy defines no permission, so any number of them can coexist.
aapt2 dump xmltree app-debug.apk
  → E: queries / E: intent / E: action
       android:name="dev.understudy.action.PROXY_DISCOVERY"   (how the app finds them)
```

CI: `https://github.com/TheSilverPlayer6/understudy`. The `jvm` job covers unit tests, every
variant, lint, `checkProxyTemplateFresh`, and apksigner/aapt2/zipalign over the generated APK. The
two `premise` jobs boot KVM emulators on API 34 and API 35 and report `OK (11 tests)` plus
`PREMISE VERIFIED`.

Runs #18–#27 were green end to end. **#28 and #29 were red**, and are the reason milestone 5
exists. **#31 is green on all three jobs**, and on both API levels reports:

```
OK: 2 proxies coexist for user 10
OK (11 tests)   BridgePremiseTest          OK (2 tests)   CallerAuthPremiseTest
CALLER-AUTH VERIFIED: the app reached a proxy signed with a DIFFERENT key
PREMISE VERIFIED                           OK: 'pm uninstall -k' preserved the data directory
PREMISE-DIAG queryIntentReceivers(dev.understudy.action.PROXY_DISCOVERY)
  = OK ([com.example.prodgame, com.example.targetgame])
CALLERAUTH-DIAG holdsBridge=GRANTED · ownCertificateSha256=bdbdca70… (matches the baked digest)
```

Section 4 records milestone 4, section 4b records what #28 and #29 turned up, and
`research/06` §5b has the full evidence.

### Components

| Package | State | Notes |
|---|---|---|
| `packaging/axml` | **done, device-verified** | AXML string-pool codec + `ManifestPatcher`. Re-encoding an unmodified pool is byte-identical to aapt2's output. |
| `packaging/sign` | **done, verified against apksigner** | DER writer/reader, self-signed X.509 identity, v1 (JAR + hand-built PKCS#7), v2 (signing block, chunked digest, zip surgery). `SigningIdentityPersistenceTest` (17) pins the stability every installed proxy depends on, and that the DER our writer emits is byte-identical to the JDK's. |
| `packaging` | **done** | Aligned `ZipWriter`; `ProxyApkFactory` pipeline. |
| `proxy-core` | **done** | The proxy's real code as an Android *library*, zero dependencies, so `:app` tests can drive it. Now also holds `CallerVerdictCache` (positive-only verdicts — §4b) and `ProxyDiscoveryReceiver` (the inert component that makes the proxy visible to the app). |
| `proxy` | **done** | Manifest + dependency on `:proxy-core`; builds the template APK. |
| `bridge` | **done, Robolectric- and device-verified** | `BridgeClient`, `BridgeError`, contract mirror. `BridgeContract` is duplicated on purpose and compared by reflection, `DISCOVERY_ACTION` included. `BridgeErrorTest` (8) pins the two failure messages, because on a user's device the string *is* the diagnosis. |
| `install` | **device-verified via CI's adb path; the in-app `PackageInstaller` UX is not** | `ApkGenerator`, `ProxyInstaller`, `InstallResultReceiver`. CI installs with `adb install --user`, which proves the *artifacts* install; the session-based flow the app uses is still unexercised. `describe()` — the only explanation a user ever sees on failure — now has 12 tests. There is deliberately **no** `isInstalled()`; see the comment in `ProxyInstaller`. |
| `core` | **done, 34 tests** | `SessionState`, `SessionManager`. Guards the teardown paths — see `SessionManagerTest`. |
| `shell` | **done, 14 tests** | `ShellBackend` + `ShellCommands` runbook generator. `restoreOwnership` is new and load-bearing; section 4 explains why. |
| `transfer` | **built, model-tested** | Streamed, resumable, write-then-swap. Both directions now have a UI. |
| `storage` | **built, not device-verified** | SAF destination with persisted grants; `collectSourcesForPush` walks a user-chosen tree. |
| `ui` | **built, never run** | Compose: Session / Files / Shell / About tabs. |
| `packaging` (tests) | **done** | `BridgePermissionOwnershipTest` (6) pins who defines the bridge permission and how the app finds proxies, on the sources *and* on the committed binary template. `CallerVerdictCacheTest` (8) pins that a rejection is never remembered. |

---

## 3. What remains

Everything on the milestone-4 list is done, and so is the item that headed it: the production
caller-authentication path. In rough priority order, what is actually left:

1. **Confirm milestone 5 on a device, then make release signing the default path.** The design
   problem is solved (§4b): `:app` defines `BRIDGE` so it can hold it against a differently-signed
   proxy, `enforceCaller()` pins the caller to the exact install that generated the proxy, and
   `<queries><intent>` is what lets the app find it at all. What remains is that release signing is
   still opt-in via an uncommitted `keystore.properties`, so release output stays unsigned without
   it. Once a real keystore is in place, the whole flow needs one end-to-end pass on a device with
   a *signed* release build — CI signs both APKs with the committed test key, so it exercises the
   permission path and the digest path but never a genuine release key.
2. **Wireless-ADB `ShellBackend`.** Only the manual command-generating backend ships, by choice: a
   backend that claims to be available and then cannot execute is worse than none. Note the
   finding in section 4 — a plain uid-2000 shell cannot reach another user's storage, so the
   runbook the backend would drive needs root, and self-paired wireless ADB lands in uid 2000.
   That has to be reconciled before the backend is worth writing.
3. **OEM testing** — MIUI/HyperOS, ColorOS, One UI. Everything verified so far is AOSP
   `target: default` on API 34/35 plus `google_apis` on 36. The `<queries><intent>` discovery
   mechanism is the part most worth confirming on OEM builds, since it is what makes the bridge
   reachable at all.
4. **The installer UX on a device.** CI installs with `adb install --user`; the
   `PackageInstaller` session path, `STATUS_PENDING_USER_ACTION` and the per-profile
   `REQUEST_INSTALL_PACKAGES` grant flow are unexercised.
5. **SAF writes** to a user-picked tree, grant persistence across reboot, and the
   `hasFragileUserData` "Keep app data" checkbox on a current build.
6. **Recovery from process death** mid-transfer.
7. **API 37 itself, and the rooted jobs stop at 35.** There is no AOSP `target: default` system
   image above API 35 — `sdkmanager --list` offers `google_apis`, `google_apis_playstore` and
   `google_atd` for 36/37 and nothing else — and `google_apis` is production-signed, so
   `adb root` is refused. Root is not a convenience: planting fixture bytes into a *secondary*
   user's private storage needs uid 0. So API 36 runs a **reduced** job (`ALLOW_NO_ROOT=1`) that
   proves everything except the two claims needing root-planted bytes, and prints
   `PREMISE VERIFIED (REDUCED, no root)` rather than pretending otherwise. API 37 has no image the
   emulator-runner action can address at all (it builds `system-images;android-37;…`; the real ids
   are `android-37.0` / `android-37.1`). The rooted jobs run with `ALLOW_NO_ROOT=0` so that losing
   root fails loudly — run #30 is what a silent degradation looks like.
8. **Proxy APK size** (~690 KB, dominated by the Kotlin stdlib in `classes.dex`).
9. **Debug and release builds cannot coexist on one device.** Both define
   `dev.understudy.permission.BRIDGE`, so the second install fails with
   `INSTALL_FAILED_DUPLICATE_PERMISSION`. Same for upgrading over a build that left an *old* proxy
   installed — those still define the permission themselves. `InstallResultReceiver.describe` now
   says so. Namespacing the permission per `applicationId` would fix it, but that means teaching
   `ManifestPatcher` a second substitution and touching the exact-match rule milestone 4 hardened;
   not worth it for a case that only affects developers.

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

### Signature-level permission in production — identified here, fixed in §4b

The bridge is guarded by a `signature`-level permission, so the app and the proxy it generates
must share a signer. In CI they do: both are signed with the committed test keystore. In
production the proxy is signed with a per-install `SigningIdentity` key, which **cannot** match a
build-time release key. So a signed release build would install a proxy that every bridge call
fails against with `SecurityException`.

That is why release signing was made deliberately opt-in via an uncommitted `keystore.properties`,
with unsigned release output as the fallback: shipping a release build that looks finished but
cannot talk to its own proxy is worse than an obviously unsigned one. The diagnosis at the time
was half right — the digest mechanism does fix the *authorisation*, but it could never have run,
because the platform gate fires first. Section 4b is the rest of it.

---

## 4b. Milestone 5: who owns the permission, and who can see whom

Full write-up in `research/06-milestone5-bridge-permission-ownership.md`. The short version,
because it is the least intuitive thing in the project and it cost three CI runs.

### Run #28 — no two proxies could ever be installed

The new caller-auth step installed a second proxy and died in under ten seconds:

```
INSTALL_FAILED_DUPLICATE_PERMISSION: Package com.example.prodgame attempting to redeclare
  permission dev.understudy.permission.BRIDGE already owned by com.example.targetgame
```

`:proxy`'s manifest **declared** the permission, so every generated proxy declared it, and a
permission name may be defined by only one package on the device. Permission definitions are
device-wide exactly like package identity is. For an app whose purpose is reaching several
targets' save data, that is a product bug: the second game fails, with an error naming a
permission the user has never heard of.

### The design hole underneath it

A `signature` permission is granted to packages signed like whichever package **defines** it. With
the proxy as definer, and the proxy signed with a per-install key, Understudy could never hold
`BRIDGE` in production. The platform checks a provider's `android:permission` **before any
provider code runs**, so every call was refused at the gate and `enforceCaller()` — the
generator-certificate digest check written to solve exactly this — was dead code.

**Fix: `:app` defines `BRIDGE`, the proxy only requires it.** Requiring a permission defined by
another package is ordinary Android. The app matches its own certificate, so the gate opens, and
`enforceCaller()` becomes the real authorisation — "the exact install that generated me", which is
strictly stronger than "some app signed with the release key". If `:app` is absent the permission
is undefined, and an undefined component permission fails **closed**.

### Run #29 — and then the bridge stopped working at all

5 of 10 `BridgePremiseTest` cases failed, including the basic `ping` that had passed on six
consecutive green runs:

```
IllegalArgumentException: Unknown authority com.example.targetgame
```

In the *same log*, seconds earlier, from the shell:

```
+ adb shell content query --user 10 --uri content://com.example.targetgame/data
Row: 0 name=files, isDir=1, … readable=1, writable=1
+ adb shell content call --user 10 --uri content://com.example.targetgame --method ping
Result: Bundle[{protocol=1, package=com.example.targetgame, ok=true, user=10, …}]
```

The provider was alive and serving. The app could not **see** it. Package-visibility filtering
(API 30+) covers `ContentResolver` authority resolution, and `Unknown authority` is
character-for-character the same message as "nothing is installed there".

Nothing in the project ever declared `<queries>`. Visibility had come from the permission itself —
AOSP `AppsFilter.addPackageInternal` populates `mQueryableViaUsesPermission`, so **a package
becomes visible to anything that requests a permission it defines**, in both install orders, with a
guard so defining and requesting your own buys nothing. That rule is not in the published
package-visibility documentation. Moving the definition to `:app` silently deleted the only thing
making proxies visible.

**Fix: `<queries><intent>` on a custom action every proxy advertises.** The target package name is
chosen at generation time, so `<queries><package>` and `<queries><provider>` cannot name it, a
fixed proxy-defined permission collides again, and a per-target one cannot be requested
statically. `QUERY_ALL_PACKAGES` would work and is deliberately not used.

The action lives on a **new** component, `ProxyDiscoveryReceiver`, not as a second filter on
`ProxyStatusActivity` — and that is not a stylistic choice. `KEEP_HIDDEN` teardown hides the proxy
by disabling that activity, and visibility is computed from *enabled* components, so a filter there
would stop matching the moment the proxy hid itself. The app would lose the bridge exactly when it
needs it, to call `showLauncher` and undo the hiding: a permanently unreachable proxy with no icon
and no UI, created by the app itself. The receiver is exported, inert, guarded by `BRIDGE`, and
nothing ever disables it.

### The third bug, found by reading rather than by running

`enforceCaller()` cached **negative** verdicts per uid. It authenticates callers through
`PackageManager`, which is itself visibility-filtered; the caller does become visible ("any app
that accesses a content provider in your app" is on the published automatic list — the reverse
direction, and what makes the digest check possible at all), but that bookkeeping is posted to a
handler, so the **first** call can race it and resolve no packages. One racing call cached `false`
forever: `SecurityException` on every subsequent call from the legitimate owner, unrecoverable
short of reinstalling the proxy. `CallerVerdictCache` now stores positives only, and
`matchesGenerator` distinguishes "no visible packages" (transient) from "wrong certificate"
(permanent) in logcat, because a support report cannot tell them apart otherwise.

### What CI does differently now

* the app installs **before** the proxies — the production order, and the reverse of before;
* `adb install -i` is deliberately **not** used. It would also grant visibility via
  `canQueryAsInstaller` and let the suite pass with `<queries>` broken. Plain `adb install` leaves
  `<queries>` as the only mechanism under test;
* `dumpsys package permission`, `dumpsys package queries` and `cmd package query-receivers` are
  captured every run, so the next visibility failure arrives with the platform's own answer;
* both proxies are asserted installed side by side. Coexistence is the product.

### Confirmed on a device (CI run #31, API 34 and API 35)

Both defects are closed and the mechanism doing the work is identified rather than assumed:
`queryIntentReceivers(PROXY_DISCOVERY)` returns **both** proxies to an app holding no
`QUERY_ALL_PACKAGES`, and once the package is visible every other query API follows — including
`resolveContentProvider`, the one that had been failing. `CallerAuthPremiseTest` reports
`holdsBridge=GRANTED` against a proxy signed with a *different* key, and its
`ownCertificateSha256` equals the digest baked into that proxy — the production authorisation path
running on a device for the first time, with the two numbers that prove it.

Still outstanding: a genuine *release* key. CI signs the app with the committed test keystore, so
the digest is the test certificate's. The mechanism is key-agnostic, but one end-to-end pass with a
real distribution key is owed once `keystore.properties` exists.

### Run #30's API 35 failure was not a product failure

`adb root` returned non-zero on its single attempt while adbd was restarting, so `HAVE_ROOT=0`, so
neither planting route could work, and the run died before any test executed. The script then
printed "adb root unavailable (production-signed image)" — a confident statement of a cause it had
not established, on a `target: default` image that runs #18 and #29 had rooted fine. `adb root` is
now retried six times with `wait-for-device` between attempts, success is confirmed by checking
`id -u` rather than trusting the exit code (it is also 0 for "already running as root"), and a
genuine failure prints `ro.build.type`/`ro.build.tags`/`ro.debuggable` instead of guessing.

The same log review found that the caller-auth phase reported "(no CALLERAUTH-DIAG lines found)"
*while passing*: those lines go to logcat via `System.out`, and the logcat capture had been killed
after the previous phase. The diagnostics written to explain a caller-auth failure were absent in
the only place they would ever be needed. Capture now restarts around that phase.

## 5. Things that will bite you

Ranked by how much time they cost me.

00. **A `SecurityException` from a provider does not tell you *why*.** `enforceCaller()` refusing the
   app and `BridgePaths` refusing one path both cross Binder as the same exception type, and
   `TransferEngine` treats the first as fatal (correctly — every later call would fail too) and the
   second as one bad file. Conflating them meant a single symlink inside a save tree aborted the
   whole backup *and* told the user their Understudy install was not the one that generated the
   proxy. `BridgeContract.PATH_REJECTION_MARKER` separates them on `query`/`openFile`, and
   `KEY_PATH_REJECTED` does the same inside `call()`'s Bundle, which cannot carry an exception type.
0. **`Unknown authority` does not mean "not installed".** From API 30 the platform filters package
   visibility, and the filter covers `ContentResolver` authority resolution. An app that cannot
   *see* a provider gets the same `IllegalArgumentException: Unknown authority` it would get if
   nothing were there — while `adb shell content query` against the same URI returns rows, because
   the shell is not filtered. Six green CI runs hid this. See §4b.
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
