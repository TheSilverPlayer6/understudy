# Understudy

> **Picking this up?** Start with [`HANDOFF.md`](HANDOFF.md) — current state, what is
> verified, what remains, and exactly where the last session stopped.

Reach an app's `Android/data` and `Android/obb` on Android 11+ — including from a **secondary
user profile**, where the usual workarounds do not apply.

## The problem

Since Android 11 (API 30) the platform hides `/Android/data/<pkg>` and `/Android/obb/<pkg>`
from every app except their owner. This is enforced in the FUSE layer, so `MANAGE_EXTERNAL_STORAGE`
("All files access") does **not** help — a file manager holding that permission still gets
`EACCES`. The only first-party route is the Android Backup feature, which apps must opt into and
which does not cover expansion packs.

`adb` and Shizuku do restore access, but both run as the **owner** profile and cannot operate on
a secondary user's storage from inside that profile. So a device shared by a family, or a work
profile, or a games-only profile, has save data that nothing can back up.

## The approach

The platform hands a process the private storage of whatever package name it *is*, in whatever
user it runs as. So Understudy builds a tiny **proxy APK carrying the target's package name**,
installs it into the target profile, and lets it re-export its own storage over Binder.

```
                      ┌──────────────────────── user 10 ────────────────────────┐
                      │                                                          │
  Understudy ──Binder─▶  proxy APK  (package = com.target.game)                  │
                      │       │                                                  │
                      │       └──▶ /storage/emulated/10/Android/data/com.target.game
                      │       └──▶ /storage/emulated/10/Android/obb/com.target.game
                      └──────────────────────────────────────────────────────────┘
```

The proxy exposes a `ContentProvider` that streams files via `ParcelFileDescriptor`, guarded by a
`signature`-level permission so only Understudy can reach it. No socket, no port, no foreground
service — and because provider access auto-starts the process, it works even when that profile is
not in the foreground.

Teardown keeps the data: preferably by leaving the proxy installed but hidden from the launcher,
or via `pm uninstall -k` when a shell is available, or via the "Keep app data" checkbox that
`android:hasFragileUserData="true"` adds to the system uninstall dialog.

## The constraint that shapes everything

**Package identity and signing are device-wide, not per-user.**

If the target app is installed for *any* user with a different signature, installing the proxy
into a fresh secondary profile still fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. And
`pm uninstall -k` makes this worse rather than better: it leaves the package *retained*, with its
signature record intact and the name reserved.

So there are two paths:

| Situation | Path |
|---|---|
| Target installed nowhere on the device | Pure in-profile flow. No adb, no shell, no owner-profile install. |
| Target installed or retained anywhere | Needs **root** for the rename-aside runbook (see below). |

The rename-aside runbook moves the private directories to a `.understudy-bak` name, does a full
uninstall to clear the device-wide record, installs the proxy, does the work, uninstalls with
`-k`, then moves the directories back. The **Shell** tab generates every command with the real
user id and paths filled in.

**It needs root, and that was measured rather than assumed.** The original design rested on
`adb shell` (uid 2000) being exempt from the FUSE filter for *every* user's tree. On API 34/35
AOSP emulators it is not: uid 2000 gets `Permission denied` on `/storage/emulated/10`, on
`/storage/emulated/10/Android/data/<pkg>`, on a file inside it, and on `/data/media/10`. Root is
refused the FUSE view too — even uid 0 gets EACCES on `/storage/emulated/10` — which is why the
runbook operates on the raw lower filesystem. So the conflict path requires `adb root`
(userdebug/eng) or `su`. On a production device with neither, the conflict case has **no**
non-destructive resolution from this app; the unprivileged flows below are the whole product for
those users, not a subset of it. OEM behaviour is untested and may differ.

## What it cannot do

* **Install into another user's profile.** `PackageInstaller` is limited to the calling user
  without `INSTALL_PACKAGES` (signature|privileged). Cross-user installs need adb from the owner
  profile; the app says so and shows the command.
* **Uninstall while keeping data, unprivileged.** There is no public API for `DELETE_KEEP_DATA`.
  The mitigations are the three above, and the UI explains which one applies.
* **Resolve a signature conflict on its own.** See the table above.

## Build

Requires JDK 17+ (21 used here), Android SDK platform 37, build-tools 37.0.0.

```bash
./gradlew :app:syncProxyTemplate    # rebuild the proxy template asset after changing :proxy
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

The Gradle wrapper's `distributionUrl` points at the `gradle/gradle-distributions` GitHub mirror,
not `services.gradle.org`, because the reference build sandbox blocks the latter. Change it back
if that does not apply to you.

AGP 9 note: this project uses **built-in Kotlin**, so `org.jetbrains.kotlin.android` must not be
applied anywhere. The Compose compiler plugin is still required.

## Modules

**`:app`** — the UI and everything that drives the proxy.

| Package | Contents |
|---|---|
| `packaging/axml` | Binary-manifest string-pool codec and the package renamer |
| `packaging/sign` | DER writer/reader, self-signed X.509 identity, v1 (JAR) and v2 signers |
| `packaging` | Aligned ZIP writer and the `ProxyApkFactory` pipeline |
| `bridge` | `BridgeClient`, the contract mirror, and typed errors |
| `install` | `ApkGenerator`, `ProxyInstaller`, `InstallResultReceiver` (whose `describe()` turns raw platform status codes into the explanation a user actually gets) |
| `core` | `SessionState` machine and `SessionManager` |
| `shell` | `ShellBackend` and the `ShellCommands` runbook generator |
| `transfer` | `TransferEngine` — streamed, resumable, write-then-swap |
| `storage` | `SafDestination` — SAF tree with persisted grants |
| `ui` | Compose UI |

**`:proxy-core`** — the proxy's actual code, as an Android *library*, so `:app`'s tests can
compile against it. **Zero dependencies**, framework APIs only: this code ends up inside the
generated APK's `classes.dex`, which is installed standalone.

Besides `ProxyFileBridge`, `BridgePaths` and `ProxyStatusActivity` it holds two classes that exist
for platform-mechanics reasons rather than functional ones, and both are load-bearing:

* `ProxyDiscoveryReceiver` — inert, exported, never disabled. Its only job is to advertise
  `BridgeContract.DISCOVERY_ACTION` so `:app`'s `<queries><intent>` makes this package *visible*;
  an invisible provider cannot be resolved at all. See "Security notes".
* `CallerVerdictCache` — the positive-only memo behind `enforceCaller()`. Split out from the
  provider so the invariant "a rejection is never remembered" can be tested directly instead of
  argued about.

Two constraints that look contradictory but are not:
* the **class package must stay `dev.understudy.proxytpl`**, because `ManifestPatcher` substitutes
  that exact string to re-target the manifest, and the component class names in the manifest are
  aapt's resolution of it. Renaming the classes would silently break proxy installation with a
  `ClassNotFoundException` at first contact;
* the **module namespace must be unique**, so it is `dev.understudy.proxycore`. Namespace only
  governs `BuildConfig`/`R`, neither of which this module has.

**`:proxy`** — the template APK. Manifest plus a dependency on `:proxy-core`. Keeping the code in
a library rather than inline also collapsed the template from five dex files to one.

## How the proxy APK is made

`:proxy` is a real Gradle module with `applicationId = dev.understudy.proxytpl`, so its Kotlin is
compiled and unit-tested normally. Its release APK is committed at
`app/src/main/assets/proxy-template.apk`. At runtime:

1. read the template's binary `AndroidManifest.xml`;
2. rewrite the string pool so `dev.understudy.proxytpl` → the target package. This is an **exact
   match on the bare package string**, which retargets both the `<manifest package>` attribute
   and the provider's `authorities` in one substitution — the AXML pool is deduplicated, so both
   attributes share a single string index.

   Fully-qualified component names are deliberately **left alone**. aapt resolved them against
   the template package at build time, so they read `dev.understudy.proxytpl.bridge.ProxyFileBridge`
   — and that is still the class the dex contains. A component class's package has no obligation
   to match the application's package, so rewriting those names makes PackageManager look for a
   class that does not exist and the proxy dies at process start:

   ```
   RuntimeException: Unable to get provider com.example.targetgame.bridge.ProxyFileBridge:
     ClassNotFoundException: Didn't find class "com.example.targetgame.bridge.ProxyFileBridge"
   ```

   `dev.understudy.permission.BRIDGE` is preserved for the same class of reason: both sides of
   the `signature`-level grant must agree on the name.

   > This was gotten wrong once, and the JVM test suite passed while asserting the buggy
   > behaviour — the string-pool tests checked that component names *were* rewritten. Only an
   > emulator run caught it, because Robolectric registers providers by class reference and never
   > resolves the name through PackageManager. `ManifestPatcher` now fails loudly if the number
   > of identity strings in the template ever changes;
3. repack `AndroidManifest.xml` + `classes*.dex` into an aligned ZIP, dropping `resources.arsc`,
   `META-INF/` and the Kotlin metadata;
4. sign with v1 + v2 using a key generated on first run and persisted thereafter.

No `apksigner`, no `zipalign`, no BouncyCastle at runtime.

## Testing

139 JVM tests, all passing, plus two instrumented suites that run on real Android emulators in
CI (`.github/workflows/emulator.yml`) — because the central claim is about the kernel's FUSE layer,
and the second claim is about platform package-visibility and permission rules, and no JVM test can
reach either.


| Suite | Covers |
|---|---|
| `BridgeIntegrationTest` (31) | **Robolectric.** Registers the *real* `ProxyFileBridge` from `:proxy-core` and drives it with the *real* `BridgeClient` through a real `ContentResolver`: identity handshake, cursor schema, streaming both ways, truncate vs append, mkdirs/delete/rename/statTree/statPath, wipe, launcher hiding, and path-safety from both sides — including a hand-crafted traversal URI that bypasses the client. Also asserts both roots end in the package name (see below), and that the two copies of `BridgeContract` agree on every constant that crosses the process boundary. |
| `SessionManagerTest` (34) | **Robolectric.** The transition guards the app's safety rests on, against the real `ProxyFileBridge`, real `ProxyInstaller` (on Robolectric's `PackageInstaller` shadow) and real `ApkGenerator`. Mostly about *refusals*: `DESTROY_DATA` without confirmation never even asks the system to uninstall; a failed wipe or shell command sets `dataAtRisk` instead of proceeding; a recorded pull for one package does not unlock evacuation of another; a dead bridge is not treated as a successful wipe. |
| `ShellCommandsTest` (14) | The runbook's **ordering** — data moved aside before anything can delete it — plus per-user paths, idempotence guards, quoting, that the destructive form carries no `-k`, that ownership repair targets the *per-user* uid via `pm list packages -U` rather than dumpsys, and that every command an operator is given runs against the raw lower filesystem (a plain shell is refused the FUSE view of another user, so a runbook written against it fails on every line). |
| `TransferModelTest` (11) | Progress arithmetic, clamping, empty-transfer edge cases, and the success predicate that must not report success on a partial copy. |
| `ManifestPatcherTest` (10) | Parses the **real AGP-produced manifest** committed as a fixture. Asserts a no-op re-encode is byte-identical, that only the bare package string is rewritten, that the permission is preserved, that the chunk chain still lands exactly on EOF, and that invalid package names are rejected. |
| `V2SignerStructureTest` (6) | Re-parses our own signer block with an independent reader mirroring apksig's field order; verifies the signature over exactly the embedded `signedData`; checks the signing-block framing and the `0xa5`/`0x5a` chunked-digest rules. |
| `BridgePermissionOwnershipTest` (6) | Who **defines** `dev.understudy.permission.BRIDGE` and how the app **finds** its proxies — the two facts that decided CI runs #28 and #29. Checks the sources *and* the committed binary template, using the project's own AXML parser: `protectionLevel` appears in a string pool only as a `<permission>` element's attribute, so its absence proves a proxy defines nothing while the permission name's presence proves the provider still requires it. Also pins `<queries>` for the discovery action, and that retargeting preserves the action and every component class name. |
| `CallerVerdictCacheTest` (8) | That a **negative** caller verdict is never cached. `enforceCaller()` authenticates through `PackageManager`, which is visibility-filtered, and a caller only becomes visible because it accessed our provider — bookkeeping that is posted to a handler, so the first call can race it. A cached miss would lock the legitimate owner out for the life of the process. |
| `InstallFailureDescriptionTest` (12) | `InstallResultReceiver.describe()`, the only explanation a user ever sees when an install fails: that `UPDATE_INCOMPATIBLE` says package identity is device-wide so another profile will not help, that `DUPLICATE_PERMISSION` names the permission and says to remove the stale proxy, that a declined prompt is not dressed up as a conflict, and that an unmapped failure keeps the platform's raw text. |
| `ProdSignedProxyTest` (1) | Generates the artifact CI uses for the **production** key layout: signed with a fresh random key (never the app's) and carrying the SHA-256 of the app's certificate. Skips loudly rather than silently if either input is missing. |
| `ProxyApkFactoryTest` (6) | End-to-end generation: zip CRC/size integrity, required and dropped entries, determinism, that two targets differ only in identity, and that the generator's certificate digest is baked in at the path the proxy reads **and covered by the v1 signature**. Writes a sample APK for external verification. |

On a real emulator (API 34 and 35, KVM, `target: default` so `adb root` works),
`BridgePremiseTest` creates a secondary user, installs the runtime-generated proxy and this app
into it, plants known bytes as root, and asserts the premise:

* the proxy is reachable across the process boundary, so the `signature`-level BRIDGE permission
  really is granted between two separately built APKs;
* the proxy is **visible** to the app through `<queries><intent>` while the app holds no
  `QUERY_ALL_PACKAGES` — without visibility the platform will not even resolve the provider, and
  the failure is `Unknown authority`, indistinguishable from "not installed";
* it runs in the intended profile;
* bytes planted by root into `Android/data/<pkg>` and `Android/obb/<pkg>` are listed and streamed
  back through the bridge;
* a write through the bridge lands on the real filesystem, confirmed by the proxy's own
  `statPath` — the caller cannot check for itself, since the platform hides that directory from
  it, which is the point;
* the platform still hides other packages' private storage, so none of the above can pass
  vacuously;
* traversal is rejected and a forged authority cannot read another package.

`rootsAreNeverTheSharedAndroidDataParent` deserves a note. A root that resolves one level too
high — `Android/data` instead of `Android/data/<pkg>` — is *worse* than a missing root: it points
the bridge at the one directory scoped storage guarantees is unreadable, so the failure reads as
"the premise is false" rather than "the path is wrong". That is exactly what happened, and it
took six CI runs to find. Asserting the shape of the path catches it without an emulator.

The generated APK is additionally verified with the platform's own tooling:

```
apksigner verify --verbose --print-certs   →  Verifies · v2 scheme: true
aapt2 dump badging                         →  package: name='com.example.targetgame'
zipalign -c -p 4                           →  ALIGNMENT OK
keytool -printcert                         →  parses our hand-built PKCS#7
```

### Not covered

* **OEM behaviour.** Everything verified so far is AOSP `target: default` on API 34 and 35
  emulators. MIUI/HyperOS, ColorOS and One UI each add installer guards, background-kill rules
  and wireless-debugging timeouts of their own, and none of it has been exercised.
* **The installer UX from inside the app.** CI installs with `adb install --user`; the
  `PackageInstaller` session path, the `STATUS_PENDING_USER_ACTION` round trip and the per-profile
  `REQUEST_INSTALL_PACKAGES` grant flow are still unexercised on a device.
* **SAF writes** to a user-picked tree, and grant persistence across reboot.
* **`hasFragileUserData`** actually producing the "Keep app data" checkbox on a current build.
* **Recovery from process death** mid-transfer.

A second instrumented class, `CallerAuthPremiseTest`, covers the **production key layout** rather
than the test one. CI installs a proxy signed with a fresh random key that carries the SHA-256 of
the app's own certificate, alongside the same-key one, and asserts the app can still reach it:
`ping`, `mkdirs`, `openFile` in both directions, `list` and `statPath` — every gated provider
entry point. That install also proves two proxies coexist for one user, which they could not while
the proxy defined the bridge permission itself.

The premise itself is no longer on this list. It is verified on a real Android system, in a
secondary profile, on two API levels, in CI on every push: `PREMISE VERIFIED on API 34` /
`on API 35`, including that `pm uninstall -k` really does preserve the directories afterwards.

## Known gaps

* `:app` release signing needs an operator keystore. `keystore.properties` at the repo root
  (gitignored) supplies `storeFile`/`storePassword`/`keyAlias`/`keyPassword`; without it release
  assembly deliberately produces `app-release-unsigned.apk` rather than falling back to the
  committed test identity, which is public and must never sign a distributed build.

  The design problem this used to imply is solved, and solving it took two attempts because the
  first one broke something unrelated. See `research/06-milestone5-bridge-permission-ownership.md`.

  A `signature`-level permission is granted to packages signed like whichever package **defines**
  it, and the platform checks a provider's `android:permission` before any provider code runs. So
  with the proxy as the definer, and the proxy signed with a per-install key, Understudy could
  never hold `BRIDGE` in production and `enforceCaller()` was dead code. **`:app` now defines
  `BRIDGE` and the proxy only requires it** — requiring a permission defined by another package is
  ordinary Android. The generator bakes its own certificate's SHA-256 into the proxy as
  `assets/understudy-generator-cert.sha256`, and `ProxyFileBridge.enforceCaller()` checks every
  `query`/`openFile`/`call` against it, which is strictly stronger than a signature permission:
  "the exact install that generated me" rather than "some app signed with the release key". With
  no digest asset the proxy falls back to permission-only enforcement, and that fallback is an
  explicit test rather than an accident.

  **This is now device-verified.** `CallerAuthPremiseTest` runs on API 34 and API 35 emulators in
  CI against a proxy signed with a fresh random key, and reports `holdsBridge=GRANTED` plus an
  `ownCertificateSha256` equal to the digest baked into that proxy — gate 1 open because `:app`
  defines the permission, gate 2 satisfied by the digest. What remains unverified is a genuine
  *release* key: CI signs the app with the committed test keystore, so the digest is the test
  certificate's. The mechanism is key-agnostic, and that is a one-run check once a keystore exists.
* **Debug and release builds cannot be installed at the same time**, because both define
  `dev.understudy.permission.BRIDGE` and a permission name may be defined by only one package on
  the device. The same applies when upgrading over a build that left an *old* proxy installed —
  those still define the permission themselves, so uninstall them first.
  `InstallResultReceiver.describe` explains both. Namespacing the permission per `applicationId`
  would fix it, at the cost of a second substitution in `ManifestPatcher`; not worth it for a case
  that only affects developers.
* The only `ShellBackend` is the manual one. A self-pairing wireless-ADB backend is the intended
  upgrade; it is not stubbed in, because a backend that reports itself available and then cannot
  execute is worse than no backend.
* `syncProxyTemplate` is still manual, but no longer silent: `checkProxyTemplateFresh` (hooked
  into `check`, so it runs in CI) compares the committed asset against a fresh `:proxy` build and
  names the changed entry. It compares zip entry CRCs rather than file hashes because two builds
  of unchanged sources are not byte-identical — apksigner embeds a PKCS#7 signingTime, measured
  as 338 differing bytes, all inside the signature — so a hash check would fail on a clean
  checkout and get ignored.
* Proxy APK is ~680 KB, dominated by the Kotlin stdlib in `classes.dex`. `multiDexEnabled = false`
  would collapse it to one dex; writing the proxy in Java would shrink it to a few tens of KB but
  contradicts the project's Kotlin-only requirement.

## Security notes

* The signing key is generated per install and stored in app-private storage as PKCS#8. It is a
  software key rather than an Android Keystore key because our own v1/v2 signer needs the private
  material, and Keystore keys are non-exportable by design. It only ever signs throwaway proxies,
  whose sole privilege is to re-export their own app-specific storage back to the one install that
  generated them. `dataExtractionRules` excludes every backup domain on Android 12+ and
  `allowBackup="false"` covers earlier versions, because a restored key would let another device's
  proxies be accepted as this one's — and restoring one *over* an existing identity would
  silently break every proxy already installed here.
* The bridge provider is exported and guarded twice over: by
  `dev.understudy.permission.BRIDGE` at `protectionLevel="signature"`, and by
  `ProxyFileBridge.enforceCaller()`, which checks the calling uid's signing certificate against
  the SHA-256 digest of the *generator's* certificate that was baked into the APK at generation
  time. The second layer is what makes production work at all — a signature-level permission is
  granted to packages signed like its **definer**, and the definer is `:app`, so the permission
  alone would admit any build signed with the release key rather than the one install that made
  this proxy.

  **`BRIDGE` is defined by `:app` and never by a proxy.** That is load-bearing in both directions:
  a proxy that defined it could not be installed alongside another one
  (`INSTALL_FAILED_DUPLICATE_PERMISSION`), and the app could never hold it in production. If
  `:app` is not installed the permission is undefined and an undefined component permission fails
  **closed**, which is the safe direction.

  Positive caller verdicts are cached per uid and **negative ones never are** —
  `matchesGenerator` resolves callers through `PackageManager`, which is visibility-filtered, and
  a caller only becomes visible because it accessed our provider; that bookkeeping is posted to a
  handler, so the first call can race it. Caching the miss would lock the legitimate owner out for
  the lifetime of the process. `SYSTEM_UID` and the proxy's own uid are trusted, a malformed
  digest fails closed, and an absent one falls back to permission-only (the CI/debug
  configuration). Both sides validate paths independently — NUL bytes, absolute paths, `..`,
  backslashes, empty segments — and the proxy re-checks via canonical paths so a symlink planted
  inside the tree cannot redirect it.
* `QUERY_ALL_PACKAGES` is still not requested, and finding the proxies does not need it. Package
  visibility is filtered from API 30 and that filtering covers `ContentResolver` authority
  resolution, so an invisible provider fails with `Unknown authority` — identical to "not
  installed". Every proxy advertises `dev.understudy.action.PROXY_DISCOVERY` on an inert exported
  receiver, and `:app` declares `<queries><intent>` for it, which matches exactly the set of
  proxies and nothing else. The receiver is separate from `ProxyStatusActivity` on purpose:
  `KEEP_HIDDEN` teardown disables that activity, and visibility is computed from *enabled*
  components, so a filter there would make the proxy unreachable at the moment the app needs to
  reach it to un-hide itself.

  Note the threat model this does and does not cover. The digest proves the caller is the install
  that generated this proxy; it does not survive the generator being replaced by a
  differently-signed build, which invalidates every proxy it made. That is intentional — those
  proxies must stop trusting the new app — but it means a re-signed or sideloaded update to
  Understudy requires regenerating and reinstalling its proxies.
* Proxy **presence** is decided by calling the provider, never by asking `PackageManager`. A
  successful `ping` is strictly stronger evidence than a package query — it proves the provider is
  live, the permission is granted, the protocol matches and the proxy is in the profile we asked
  for — and a truthful "installed" would still be the wrong answer, because an installed proxy
  that fails any of those is not usable. `ProxyInstaller.isInstalled()` existed, was never called,
  and was wrong on API 30+ (`getPackageInfo` is visibility-filtered, and `runCatching` turns
  `NameNotFoundException` into `false`, indistinguishable from a real absence); it was removed with
  a comment explaining why, because it is the obvious thing to add back.
* **Teardown refuses to destroy data without an explicit `confirmDataLoss = true`**, and
  evacuate-then-uninstall is only offered once a completed pull has been recorded. Every failure
  path that could leave data ambiguous sets `dataAtRisk` and stops rather than proceeding.
