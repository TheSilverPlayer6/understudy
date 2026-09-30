# Milestone 4 — the premise, verified on real Android

Date: 2026-09-29 · Status: **green** (CI run #18, reproduced on every run since)

Milestones 1–3 built the thing. This one found out whether it is true. Everything before it was
JVM tests and external verifiers over artifacts; nothing had ever executed on an Android kernel,
and the project's entire reason to exist is a claim about that kernel.

---

## 1. The claim, stated so it can be falsified

> A proxy APK carrying package name `P`, installed for user `N`, can read and write
> `/storage/emulated/N/Android/{data,obb}/P` — including data that existed **before** the proxy
> was installed — and can stream those bytes to a different app over Binder.
>
> And no other app can, including one holding `MANAGE_EXTERNAL_STORAGE`.

The second half is not a formality. A test suite that only asserts "our proxy can read the
directory" passes vacuously on any build where the restriction is absent, and would then be
measuring nothing. `BridgePremiseTest.thePlatformStillHidesOtherPackagesPrivateStorageFromUs`
exists so the suite cannot pass for the wrong reason.

## 2. What runs, and where

| | |
|---|---|
| Where | GitHub Actions, `reactivecircus/android-emulator-runner@v2` |
| Images | `system-images;android-34;default;x86_64` and `…android-35;default;x86_64` |
| Why `default` and not `google_apis` | `google_apis` is production-signed and **refuses `adb root`**. Planting fixture bytes into a *secondary* user's private storage needs uid 0 — see §5. |
| KVM | Enabled by a udev rule; GitHub's Linux runners expose `/dev/kvm` but leave it root-only. |
| Orchestration | `.github/scripts/run-premise-test.sh` (shell, because creating a user and installing packages are shell-only operations no app can perform) |
| Assertions | `BridgePremiseTest` (10 instrumented cases), run with `am instrument --user 10` |

The Daytona build sandbox cannot do any of this: `/dev/kvm` exists but is `nobody:nogroup` mode
660, `sudo chmod` is denied for lack of `CAP_FOWNER` over a host device node, and `kvm=True` at
creation returns `DaytonaForbiddenError: KVM sandboxes are not enabled for this organization`.
That is why device testing lives in CI and the build lives in Daytona.

## 3. Verified end state

```
OK (10 tests)                                        API 34 and API 35
PREMISE VERIFIED on API 34: a proxy installed for user 10 could read and write
  /storage/emulated/10/Android/data/com.example.targetgame
  /storage/emulated/10/Android/obb/com.example.targetgame
and 'pm uninstall -k' preserved the data afterwards.
```

The proxy APK under test is not a checked-in binary. It is produced by `ProxyApkFactoryTest`
during `:app:testDebugUnitTest` — the same code path the app uses at runtime — copied to
`/tmp/proxy.apk`, and installed with `adb install --user 10`. So CI exercises the real generator,
including the AXML retargeting and the hand-rolled v1/v2 signer.

### The decisive evidence, from run #18's log

The control and the result, four lines apart:

```
+ adb shell 'run-as dev.understudy.debug --user 10 ls \
    /storage/emulated/10/Android/data/com.example.targetgame/planted'
ls: /storage/emulated/10/Android/data/com.example.targetgame/planted: Permission denied

+ adb shell content query --user 10 --uri content://com.example.targetgame/data/planted
Row: 0 name=save.dat, isDir=0, size=29, modified=1790699339931, readable=1, writable=1,
     path=planted/save.dat
```

The app running as user 10 is **denied** the directory directly. The proxy, reached over Binder
through `content query` (the platform's own client, not ours), **returns the file** — the same 29
bytes root planted before the proxy existed. `content query` bypassing our client matters: it
proves the provider works through the platform, not merely through `BridgeClient`.

And the handshake that makes it possible:

```
Result: Bundle[{protocol=1, package=com.example.targetgame, ok=true, user=10, roots=Bundle[…]}]
```

`ping` succeeding across two separately built APKs is itself a result — it means the
`signature`-level `BRIDGE` permission was genuinely granted between them, and that the proxy
process really is running as user 10 rather than as the owner.

## 4. Three defects were stacked, and each one hid the next

This took runs #10–#18. Every fix exposed the next failure, which is why it took nine runs rather
than three.

### 4.1 The uid lookup could never have worked

`dumpsys package <pkg> | grep userId=` matches nothing on API 34+. Android 14 **renamed the
field** to `appId=` (AOSP `Settings.java`: `pw.print("  appId="); pw.println(ps.getAppId())`).
Run #11 died here, printing `!! could not determine the uid of com.example.targetgame`.

The rename was the shallow half. The deeper problem is that *either* field gives the **app id**,
which is shared by every user, while FUSE attributes app-specific storage by the **per-user** uid:

```
per-user uid = userId * 100000 + appId          u10_a141 == 1010141
```

Chowning to the bare appId leaves user 10's files owned by a user-0-range uid and the app still
cannot read them — the fix would have appeared to work and changed nothing.

The reliable source is `pm list packages -U --user N <pkg>`, which prints
`applicationInfo.uid` — computed by `PackageInfoUtils.initForUser` as
`UserHandle.getUid(userId, appId)`, i.e. already per-user. Stable format, every API level, no
root needed, and `-u` also covers the retained state `pm uninstall -k` leaves behind. `--user` is
**mandatory**; without it the query defaults to user 0 and returns a uid in the wrong range. CI
cross-checks it against `stat -c %u /data/user/N/<pkg>`, the ground truth installd creates.

Two more traps in the same command, both of which cost a cycle:

* `UID` is **read-only in bash**. `UID=$(…)` aborts the operator's shell before any chown runs.
* `chmod 771` clears the setgid bit the platform relies on, and the platform's mode is **2770**,
  not 771. Ownership repair must chown the **owner only** — the setgid'd parent directories
  already gave the planted entries the platform's own group (`ext_data_rw` / `ext_obb_rw`) and
  mode.

### 4.2 The data root was one directory too high

```kotlin
// before
getExternalFilesDir(null).parentFile.parentFile
```

That assumes the platform returns `…/Android/data/<pkg>/files`. On API 34 and 35 it returns
`…/Android/data/<pkg>` with **no** `files` segment, so two levels up landed on `…/Android/data` —
the shared parent of every package's private storage, and precisely the path scoped storage hides
from all apps.

Listing it returned `AccessDeniedException`, which reads as *"the premise is false"* when the
restriction was working correctly on a directory the bridge should never have been pointed at.
Meanwhile `obb` worked, because its path was built with the package segment intact. That
asymmetry is what made this look like a platform difference between `data` and `obb` rather than
a bug in one of two sibling expressions, and it sent six runs after MediaProvider and mount
namespaces.

`dataDirFor` now locates the `/Android/data/<pkg>` marker in the platform's own path instead of
counting levels, so both shapes resolve identically. The generalisable rule:

> **A root that is one level wrong is worse than a root that is missing.** A missing root fails
> loudly. A wrong one points at a directory the platform guarantees is unreadable, so the failure
> reads as "the premise is false" instead of "the path is wrong".

`rootsAreNeverTheSharedAndroidDataParent` now asserts the *shape* of both roots, which catches
this class of bug without an emulator.

### 4.3 The write test contradicted the suite it lived in

It confirmed a bridge write by stat'ing the path **from the test app's uid**:

```kotlin
File("/storage/emulated/$user/Android/data/$target/$path").isFile
```

which can never be true, because the platform hides that directory from every other package — the
very thing `thePlatformStillHidesOtherPackagesPrivateStorageFromUs` asserts two tests later. Only
one of the two could ever pass.

"Did my write reach the real filesystem?" is answerable **only from inside the proxy**, so the
bridge gained `statPath`, reporting exists / isDirectory / size plus the canonical path the
platform resolved. The canonical path is stronger evidence than a local stat would have been: a
local stat could only ever prove the file was visible to *us*.

## 5. Two findings that changed the product, not just the tests

### 5.1 FUSE attributes app-specific storage by owning uid

Run #10's `ls -laR` is the evidence. After the proxy had touched its own root through FUSE:

```
drwxrws--- 4 u10_a141 ext_data_rw 4096  .
drwxrws--- 2 u10_a141 ext_data_rw 4096  files      <- created by the proxy through FUSE
drwxrws--- 2 root     ext_data_rw 4096  planted    <- created by adb shell as root
```

`planted/` sat next to `files/`, in the directory the proxy owns, and was **invisible to it**.
`File.listFiles()` returned `null`, which surfaces as "cannot list: … (permission denied)" and is
indistinguishable from "the data is gone".

So restoring save data via adb as root leaves it **unreadable by the app that owns it**. Anyone
who has ever pushed a backup into `Android/data/<pkg>` from a rooted shell has hit this and
probably concluded the restore failed. It is now `ShellCommands.restoreOwnership(userId, pkg)`,
step 6b of the rename-aside runbook; the original design had no ownership repair at all, because
nobody knew it was needed.

### 5.2 A plain `adb shell` cannot reach another user's storage

Run #12 dropped to uid 2000 (`u:r:shell:s0`) and was denied everywhere:

| Path | Result |
|---|---|
| `/storage/emulated/10` | `Permission denied` |
| `/storage/emulated/10/Android/data/<pkg>` | `Permission denied` |
| a file inside it | `Permission denied` |
| `/data/media/10` | `Permission denied` |

Root is refused the FUSE view too — run #11: even uid 0 gets EACCES on `/storage/emulated/10`,
because a secondary user's emulated storage is not mounted in the root adb shell's mount
namespace. Root *can* reach the raw lower filesystem `/data/media/10`.

**This contradicts the research the project started from**, which assumed uid 2000 is exempt from
the FUSE filter for every user's tree. On API 34/35 AOSP it is exempt only within its own user.
Consequences:

* the runbook's `mv` steps need **root**, not merely adb, and must use the raw
  `/data/media/N/…` paths, since root is denied the FUSE view;
* `chown` needs `CAP_CHOWN` regardless, so ownership repair was always root-or-su-only;
* **on a production device without root there is no shell path into another user's private
  storage.** The unprivileged flows — install into one's own profile, `hasFragileUserData`'s
  "Keep app data" checkbox, keep-installed-and-hidden — are the whole product for those users,
  not a subset of it.

Still unverified: whether OEM builds differ. Everything here is AOSP `target: default`.

## 6. The methodology lesson, which is worth more than any of the fixes

For a claim about what a process can see, the only admissible evidence is **that process's own
report**.

Five runs of increasingly subtle external observation — mount namespaces, inode identity, logcat
filters, reading MediaProvider source — built a correct and complete picture of everything except
the one number that mattered, because none of them asked the proxy what it thought its own root
directory was. `ProxyFileBridge.selfDiagnostic` exists so that question can always be asked, in
CI and from a support request on a real device.

Second lesson: **`File.listFiles()` returns `null` for both EACCES and ENOENT and never surfaces
errno.** Every "permission denied" string in this project was an *inference* until the
diagnostics switched to `java.nio.file`, whose `AccessDeniedException` versus
`NoSuchFileException` is the distinction that mattered. A C probe would have been better still,
but there is no NDK on the CI runner and no compiler for a static Android binary — verified, not
assumed.

Third: **`am instrument` exits 0 even when tests fail.** The CI script greps the output for
`FAILURES!!!` / `Error in ` rather than trusting the exit code. Related, and equally load-bearing:
Gradle serves tests from cache, so a test whose *side effect* is an artifact a later CI step
verifies must actually run — `org.gradle.caching=false` plus `--rerun-tasks`.

## 7. What milestone 4 did **not** verify

Stated plainly, because the difference is the whole point of this document:

1. **The production caller-authentication path.** Run #18's proxy is signed with the *same* test
   key as the app, so it exercises the `signature`-permission path only. In production the proxy
   is signed with a per-install key. See `06-milestone5-bridge-permission-ownership.md`.
2. **The installer UX.** CI installs with `adb install --user`. The `PackageInstaller` session
   path, `STATUS_PENDING_USER_ACTION`, and the per-profile `REQUEST_INSTALL_PACKAGES` grant flow
   are unexercised.
3. **SAF writes** to a user-picked tree, and grant persistence across reboot.
4. **`hasFragileUserData`** actually producing the "Keep app data" checkbox on a current build.
5. **OEM behaviour** — MIUI/HyperOS, ColorOS, One UI.
6. **Behaviour under process death** mid-transfer, and session recovery.
7. **API 36/37.** Verified on 34 and 35; the project targets 37.
