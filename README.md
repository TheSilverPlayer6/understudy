# Understudy

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
2. rewrite the string pool so `dev.understudy.proxytpl` → the target package. This is a **prefix**
   substitution, which also retargets `authorities` and the fully-qualified component names
   (aapt resolved those against the manifest package at build time, so they do not follow the
   package on their own). `dev.understudy.permission.BRIDGE` is deliberately preserved;
3. repack `AndroidManifest.xml` + `classes*.dex` into an aligned ZIP, dropping `resources.arsc`,
   `META-INF/` and the Kotlin metadata;
4. sign with v1 + v2 using a key generated on first run and persisted thereafter.

No `apksigner`, no `zipalign`, no BouncyCastle at runtime.

## Testing

65 JVM tests, all passing:

| Suite | Covers |
|---|---|
| `ManifestPatcherTest` (9) | Parses the **real AGP-produced manifest** committed as a fixture. Asserts a no-op re-encode is byte-identical, that all three identity sites move, that the permission does not, that the chunk chain still lands exactly on EOF, and that invalid package names are rejected. |
| `V2SignerStructureTest` (6) | Re-parses our own signer block with an independent reader mirroring apksig's field order; verifies the signature over exactly the embedded `signedData`; checks the signing-block framing and the `0xa5`/`0x5a` chunked-digest rules. |
| `ProxyApkFactoryTest` (3) | End-to-end generation: zip CRC/size integrity, required and dropped entries, determinism, and that two targets differ only in identity. Writes a sample APK for external verification. |
| `ShellCommandsTest` (10) | The runbook's **ordering** — data moved aside before anything can delete it — plus per-user paths, idempotence guards, quoting, and that the destructive form carries no `-k`. |
| `TransferModelTest` (11) | Progress arithmetic, clamping, empty-transfer edge cases, and the success predicate that must not report success on a partial copy. |
| `BridgeIntegrationTest` (26) | **Robolectric.** Registers the *real* `ProxyFileBridge` from `:proxy-core` and drives it with the *real* `BridgeClient` through a real `ContentResolver`: identity handshake, cursor schema, streaming both ways, truncate vs append, mkdirs/delete/rename/statTree, wipe, launcher hiding, and path-safety from both sides — including a hand-crafted traversal URI that bypasses the client. |

The generated APK is additionally verified with the platform's own tooling:

```
apksigner verify --verbose --print-certs   →  Verifies · v2 scheme: true
aapt2 dump badging                         →  package: name='com.example.targetgame'
zipalign -c -p 4                           →  ALIGNMENT OK
keytool -printcert                         →  parses our hand-built PKCS#7
```

### Not covered

* **Nothing has run on a device or emulator**, so the FUSE grant itself — the premise the whole
  app rests on — is untested. Robolectric has no FUSE layer, so `BridgeIntegrationTest` proves
  the *protocol* is correct, not that the platform will *grant* the access.
  Attempted and blocked: `/dev/kvm` exists in the build sandbox and the host CPU supports `svm`,
  but the node is `nobody:nogroup` 660 and `sudo chmod` is denied (no `CAP_FOWNER`); `kvm=True`
  returns `KVM sandboxes are not enabled for this organization`, and the API key cannot change
  org features. GitHub Actions runners do expose `/dev/kvm`, but there are no git credentials
  anywhere in the environment to push a workflow. See `research/04-milestone3-running-it.md`.
* The installer UX, SAF writes, grant persistence, `hasFragileUserData` dialog behaviour, OEM
  variations and recovery from process death mid-transfer are all unexercised.
* `SessionManager`'s transition guards have no test yet — they are the next highest-value target
  after device access.

## Known gaps

* `:app` has no release `signingConfigs`, so release assembly produces an unsigned APK.
* The only `ShellBackend` is the manual one. A self-pairing wireless-ADB backend is the intended
  upgrade; it is not stubbed in, because a backend that reports itself available and then cannot
  execute is worse than no backend.
* `syncProxyTemplate` is manual. Nothing fails if you edit `:proxy` and forget to run it — the app
  would ship a stale template. A hash-comparison task hooked into `check` is the obvious fix.
* Proxy APK is ~680 KB, dominated by the Kotlin stdlib in `classes.dex`. `multiDexEnabled = false`
  would collapse it to one dex; writing the proxy in Java would shrink it to a few tens of KB but
  contradicts the project's Kotlin-only requirement.

## Security notes

* The signing key is generated per install and stored in app-private storage as PKCS#8. It is a
  software key rather than an Android Keystore key because our own v1/v2 signer needs the private
  material, and Keystore keys are non-exportable by design. It only ever signs throwaway proxies
  whose sole privilege is a `signature`-level permission back into Understudy.
* The bridge provider is exported but guarded by `dev.understudy.permission.BRIDGE` at
  `protectionLevel="signature"`. Both sides validate paths independently — NUL bytes, absolute
  paths, `..`, backslashes, empty segments — and the proxy re-checks via canonical paths so a
  symlink planted inside the tree cannot redirect it.
* `QUERY_ALL_PACKAGES` is not requested. Proxy detection is done by calling the provider, which is
  stronger evidence than a package query and needs no permission.
* **Teardown refuses to destroy data without an explicit `confirmDataLoss = true`**, and
  evacuate-then-uninstall is only offered once a completed pull has been recorded. Every failure
  path that could leave data ambiguous sets `dataAtRisk` and stops rather than proceeding.
