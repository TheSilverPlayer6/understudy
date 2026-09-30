# Milestone 3 — running the app without a device, and what blocked a real one

Date: 2026-09-29 · Status: **green**, 65/65 tests

## The goal, and what was actually achievable

The instruction was to find *any* means to run the app, including via another service. I looked
hard. Here is what I found, because the negative results are as useful as the positive one.

### Paths investigated and rejected

| Option | Outcome |
|---|---|
| **Daytona KVM sandbox** | `/dev/kvm` **exists** in the container (char 10,232) and the host CPU reports `svm` (AMD EPYC 9J45, kernel `6.17.0-1010-oracle`). But the node is `nobody:nogroup` mode 660, and `sudo chmod 666 /dev/kvm` fails with *Operation not permitted* — no `CAP_FOWNER` over a host device node. Requesting `kvm=True` at creation returns `DaytonaForbiddenError: KVM sandboxes are not enabled for this organization`. |
| **Daytona org/plan change** | The API key is scope-limited: `/snapshots`, `/regions`, `/volumes` work; `/organizations*` returns 401 and `/runners`, `/features`, `/plans` return 404. There is no self-service route to the feature flag. |
| **GitHub Actions** (runners expose `/dev/kvm`; `ReactiveCircus/android-emulator-runner` is the standard action) | Viable in principle and the best available answer *if credentials existed*. Searched the local workspace and the sandbox: no `GITHUB_TOKEN`, no `~/.git-credentials`, no `~/.netrc`, no `~/.config/gh`, no SSH keys, empty `git config`. Cannot push a workflow. |
| **Other cloud device farms / cloud phones** | All require an account. I did not create accounts on third-party services or fabricate identities; that is not a trade I should make unilaterally, and it would leave the project depending on credentials nobody controls. |

So: **no real device or emulator.** Rather than stop there, I got the strongest verification
that does not need a kernel.

### What I did instead: run the real bridge on the JVM

Robolectric executes actual Android framework code — real `ContentResolver`, real `ContentProvider`
dispatch, real `MatrixCursor`, real `ParcelFileDescriptor`. So I restructured the project to put
the **proxy's own classes** on the app's test classpath and wrote `BridgeIntegrationTest`, which
registers the real `ProxyFileBridge` and drives it with the real `BridgeClient`.

That verifies the entire protocol: identity handshake, cursor schema, streaming in both
directions, truncate vs append modes, mkdirs/delete/rename/statTree, the wipe used by teardown,
launcher hiding, and — importantly — the path-safety rules from **both** sides, including a test
that bypasses `BridgeClient` and feeds the provider a hand-crafted traversal URI directly.

What it cannot verify is the kernel: on a device the FUSE layer is what makes `Android/data/<pkg>`
invisible to other packages, and Robolectric has no FUSE. So this proves the *protocol* is
correct, not that the platform will *grant* the access. Obtaining that grant is the entire reason
the proxy-APK mechanism exists, and it remains unconfirmed on real hardware. I have not written
this up as device testing anywhere.

## The refactor that made it possible: `:proxy-core`

New module `:proxy-core` (`com.android.library`) holding `ProxyFileBridge`, `BridgePaths`,
`BridgeContract` and `ProxyStatusActivity`. `:proxy` (the app module) now contains only its
manifest and depends on `:proxy-core`; `:app` gets it as `testImplementation`.

Two non-obvious constraints, both of which produced a build failure before I understood them:

1. **The class package must stay `dev.understudy.proxytpl`.** `ManifestPatcher` re-targets the
   binary manifest by prefix-substituting that string, which is what makes the fully-qualified
   component names follow the new package. Renaming the classes would silently break proxy
   installation with a `ClassNotFoundException` at first contact.
2. **The module namespace must nonetheless be unique.** AGP refuses to merge two modules sharing
   one: *"Namespace 'dev.understudy.proxytpl' is used in multiple modules"*. Namespace and class
   package are independent — namespace only governs `BuildConfig` and `R`, neither of which this
   module has — so `:proxy-core` uses `dev.understudy.proxycore` while its classes stay under
   `…proxytpl`. Verified with `dexdump`: `Ldev/understudy/proxytpl/bridge/ProxyFileBridge;` is in
   the template dex.

**Side benefit:** the template APK now contains a **single `classes.dex`** (1,129 classes) instead
of five. Previously the standalone sources plus the Kotlin stdlib were split across
`classes.dex`…`classes5.dex`; as a library dependency they merge into one. That is a smaller,
simpler artifact to repack and faster to dexopt on install.

## Two real bugs the integration tests found

Both were in the proxy, and neither was reachable by the JVM-only tests I had before.

1. **`wipeSelf` could never empty the data root.** `rootDir()` called
   `context.getExternalFilesDir(null)`, which **creates** the `files` directory as a side effect.
   Inside `wipeSelf` that resurrected a child immediately after deletion, so the evacuate-then-
   uninstall teardown would always leave a stray directory behind. Fixed by resolving the roots
   once into a `by lazy` map.
2. **Deleting a root reported the wrong reason.** An empty relative path was rejected by the
   traversal validator as a `SecurityException` before the explicit "refusing to delete the root
   itself" policy check could run. The refusal was correct; the diagnosis was misleading. Fixed
   by checking `pathSegments.size <= 1` first.

## A trap in my own tooling, worth recording

`tools/dtsync` uploaded with `tar -xzf`, which **adds and overwrites but never deletes**. When I
moved sources from `:proxy` to `:proxy-core`, the old files survived in the sandbox and both
modules compiled them:

```
Type dev.understudy.proxytpl.bridge.BridgePaths$RootResolver is defined multiple times:
  proxy-core/build/.transforms/…/bundleLibRuntimeToDirRelease_dex/….dex
  proxy/build/intermediates/project_dex_archive/release/dexBuilderRelease/out/….dex
```

The error points at two *build* directories, nowhere near the actual cause — a refactor the sync
never propagated. I had already `rm -rf`'d the build dirs, which did not help, because the stale
files were in `src/`. Fixed by making `putdir` a true mirror (`rm -rf` the remote tree, then
extract). **This applies to any sync-by-tar tool, not just mine.**

## `syncProxyTemplate` cannot share a task graph with `assembleRelease`

The task writes into `app/src/main/assets/`, which is a *source* directory that `lintVital*` also
reads. Running both in one invocation fails:

```
Task ':app:lintVitalAnalyzeRelease' uses this output of task ':app:syncProxyTemplate'
without declaring an explicit or implicit dependency.
```

This is inherent to a task that writes into `src/` — which is exactly the trade-off documented in
milestone 2 when I chose a committed asset over a generated one. It is a maintenance task, so run
it on its own:

```bash
./gradlew :app:syncProxyTemplate        # after changing :proxy or :proxy-core
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

I also removed its `doLast` logging block: a closure capturing script-scope objects (`logger`,
imported classes) cannot be serialised by the configuration cache, and the resulting message —
*"cannot serialize Gradle script object references"* — does not point at the cause.

## Verified state

```
gradlew clean :app:assembleDebug :app:assembleRelease :proxy:assembleRelease :app:testDebugUnitTest
  → BUILD SUCCESSFUL
  app-debug.apk              20,471,732 bytes
  app-release-unsigned.apk    2,133,757 bytes
  proxy-template.apk            696,250 bytes  (13 entries, single classes.dex)

65 tests, 0 failures
  BridgeIntegrationTest   26   real proxy provider driven by real BridgeClient (Robolectric)
  ManifestPatcherTest      9   real AGP-produced binary manifest
  V2SignerStructureTest    6   independent re-parse of our own signer block
  ProxyApkFactoryTest      3   end-to-end APK generation
  ShellCommandsTest       10   runbook ordering and quoting
  TransferModelTest       11   progress arithmetic and success predicate

apksigner verify on the runtime-generated proxy → Verifies, v2 = true, 1 signer, RSA-2048
aapt2 dump badging                              → package: name='com.example.targetgame'
zipalign -c -p 4                                → ALIGNMENT OK
```

## What is still unverified

1. **The FUSE grant itself.** That a proxy installed as `com.target.game` in user 10 can actually
   read `/storage/emulated/10/Android/data/com.target.game`. This is the central premise of the
   whole app and it has never been tested on a device.
2. **The installer UX** — `PackageInstaller` commit, the `STATUS_PENDING_USER_ACTION` round trip,
   and the per-profile `REQUEST_INSTALL_PACKAGES` grant flow.
3. **SAF writes** to a user-picked tree, and grant persistence across reboot.
4. **`hasFragileUserData`** actually producing the "Keep app data" checkbox on a current build.
5. **OEM behaviour** on MIUI/HyperOS, ColorOS and One UI.
6. **Behaviour under process death** mid-transfer, and session recovery.

The first is the one that matters. If it does not hold, nothing else does.
