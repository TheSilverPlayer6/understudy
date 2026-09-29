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
| Target installed or retained anywhere | Needs a shell for the rename-aside runbook (see below). |

The rename-aside runbook moves the private directories to a `.understudy-bak` name, does a full
uninstall to clear the device-wide record, installs the proxy, does the work, uninstalls with
`-k`, then moves the directories back. The **Shell** tab generates every command with the real
user id and paths filled in.

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
| `install` | `ApkGenerator`, `ProxyInstaller`, `InstallResultReceiver` |
| `core` | `SessionState` machine and `SessionManager` |
| `shell` | `ShellBackend` and the `ShellCommands` runbook generator |
| `transfer` | `TransferEngine` — streamed, resumable, write-then-swap |
| `storage` | `SafDestination` — SAF tree with persisted grants |
| `ui` | Compose UI |

**`:proxy-core`** — the proxy's actual code, as an Android *library*, so `:app`'s tests can
compile against it. **Zero dependencies**, framework APIs only: this code ends up inside the
generated APK's `classes.dex`, which is installed standalone.

Two constraints that look contradictory but are not:
* the **class package must stay `dev.understudy.proxytpl`**, because `ManifestPatcher`
  prefix-substitutes that string to re-target fully-qualified component names;
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

106 JVM tests, all passing, plus an instrumented suite that runs on a real Android emulator in
CI (`.github/workflows/emulator.yml`) — because the central claim is about the kernel's FUSE
layer and no JVM test can reach it.


| Suite | Covers |
|---|---|
| `BridgeIntegrationTest` (30) | **Robolectric.** Registers the *real* `ProxyFileBridge` from `:proxy-core` and drives it with the *real* `BridgeClient` through a real `ContentResolver`: identity handshake, cursor schema, streaming both ways, truncate vs append, mkdirs/delete/rename/statTree/statPath, wipe, launcher hiding, and path-safety from both sides — including a hand-crafted traversal URI that bypasses the client. Also asserts both roots end in the package name (see below), and that the two copies of `BridgeContract` agree on every constant that crosses the process boundary. |
| `SessionManagerTest` (34) | **Robolectric.** The transition guards the app's safety rests on, against the real `ProxyFileBridge`, real `ProxyInstaller` (on Robolectric's `PackageInstaller` shadow) and real `ApkGenerator`. Mostly about *refusals*: `DESTROY_DATA` without confirmation never even asks the system to uninstall; a failed wipe or shell command sets `dataAtRisk` instead of proceeding; a recorded pull for one package does not unlock evacuation of another; a dead bridge is not treated as a successful wipe. |
| `ShellCommandsTest` (12) | The runbook's **ordering** — data moved aside before anything can delete it — plus per-user paths, idempotence guards, quoting, that the destructive form carries no `-k`, and that ownership repair targets the *per-user* uid via `pm list packages -U` rather than dumpsys. |
| `TransferModelTest` (11) | Progress arithmetic, clamping, empty-transfer edge cases, and the success predicate that must not report success on a partial copy. |
| `ManifestPatcherTest` (10) | Parses the **real AGP-produced manifest** committed as a fixture. Asserts a no-op re-encode is byte-identical, that only the bare package string is rewritten, that the permission is preserved, that the chunk chain still lands exactly on EOF, and that invalid package names are rejected. |
| `V2SignerStructureTest` (6) | Re-parses our own signer block with an independent reader mirroring apksig's field order; verifies the signature over exactly the embedded `signedData`; checks the signing-block framing and the `0xa5`/`0x5a` chunked-digest rules. |
| `ProxyApkFactoryTest` (3) | End-to-end generation: zip CRC/size integrity, required and dropped entries, determinism, and that two targets differ only in identity. Writes a sample APK for external verification. |

On a real emulator (API 34 and 35, KVM, `target: default` so `adb root` works),
`BridgePremiseTest` creates a secondary user, installs the runtime-generated proxy and this app
into it, plants known bytes as root, and asserts the premise:

* the proxy is reachable across the process boundary, so the `signature`-level BRIDGE permission
  really is granted between two separately built APKs;
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

The premise itself is no longer on this list. It is verified on a real Android system, in a
secondary profile, on two API levels, in CI on every push: `OK (10 tests)` and
`PREMISE VERIFIED on API 34` / `on API 35`, including that `pm uninstall -k` really does preserve
the directories afterwards.

## Known gaps

* `:app` release signing needs an operator keystore. `keystore.properties` at the repo root
  (gitignored) supplies `storeFile`/`storePassword`/`keyAlias`/`keyPassword`; without it release
  assembly deliberately produces `app-release-unsigned.apk` rather than falling back to the
  committed test identity, which is public and must never sign a distributed build.

  The design problem this used to imply is solved. A `signature`-level permission requires caller
  and provider to share a certificate, which is impossible in production: the proxy is signed with
  a per-install key generated on the device, the app with its build-time key. So the generator now
  bakes its own certificate's SHA-256 into the proxy as
  `assets/understudy-generator-cert.sha256`, and `ProxyFileBridge.enforceCaller()` checks every
  `query`/`openFile`/`call` against it. The permission is kept as a second layer — it costs
  nothing and covers the CI/debug configuration, where both APKs genuinely do share the committed
  test key. With no digest asset the proxy falls back to permission-only enforcement, which is
  what the test suite and CI rely on, and that fallback is an explicit test rather than an
  accident.

  What remains unverified about this is the production shape end to end: CI signs both APKs with
  the test keystore, so it exercises the *permission* path, not the digest path. The digest path
  is unit-tested (asset present at the right path, covered by the v1 signature, malformed digests
  rejected) but has not run on a device against a differently-signed app.
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
  time. The second layer is what makes production work at all — a signature-level permission
  needs caller and provider to share a certificate, which they cannot when the proxy is signed
  with a per-install device key and the app with a build-time key. Verdicts are cached per uid,
  `SYSTEM_UID` and the proxy's own uid are trusted, a malformed digest fails closed, and an
  absent one falls back to permission-only (the CI/debug configuration). Both sides validate
  paths independently — NUL bytes, absolute paths, `..`, backslashes, empty segments — and the
  proxy re-checks via canonical paths so a symlink planted inside the tree cannot redirect it.

  Note the threat model this does and does not cover. The digest proves the caller is the install
  that generated this proxy; it does not survive the generator being replaced by a
  differently-signed build, which invalidates every proxy it made. That is intentional — those
  proxies must stop trusting the new app — but it means a re-signed or sideloaded update to
  Understudy requires regenerating and reinstalling its proxies.
* `QUERY_ALL_PACKAGES` is not requested. Proxy detection is done by calling the provider, which is
  stronger evidence than a package query and needs no permission.
* **Teardown refuses to destroy data without an explicit `confirmDataLoss = true`**, and
  evacuate-then-uninstall is only offered once a completed pull has been recorded. Every failure
  path that could leave data ambiguous sets `dataAtRisk` and stops rather than proceeding.
