# Milestone 5 — who owns the bridge permission, and who can see whom

Date: 2026-09-30 · Status: **green — CI run #31, all three jobs, on API 34 and API 35**

Milestone 4 proved the premise. This one is about the two things that stop the premise from being
*usable*: a signed release build that cannot talk to the proxy it generated, and a device on which
only one proxy can exist at a time.

Both turned out to be the same underlying subject — Android's package-identity and
package-visibility rules — and both were found by a device, not by reading code.

---

## 1. The two defects

### 1.1 No two proxies could ever coexist

CI run #28 installed a second proxy and died in under ten seconds, before a single test ran:

```
adb: failed to install /tmp/proxy-prodsign.apk: Failure [INSTALL_FAILED_DUPLICATE_PERMISSION:
  Package com.example.prodgame attempting to redeclare permission
  dev.understudy.permission.BRIDGE already owned by com.example.targetgame]
```

`proxy/src/main/AndroidManifest.xml` declared
`<permission android:name="dev.understudy.permission.BRIDGE" android:protectionLevel="signature"/>`.
Every generated proxy inherits that declaration, and a given permission name may be defined by only
**one** package on the whole device. Permission definitions are device-wide exactly like package
identity is — so this is the same class of constraint that produced
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, arriving from a different direction.

That is a product bug, not a harness bug. Reaching several targets' save data *is* the product; a
user with two games in a profile would hit this on the second one, and the error names a permission
they have never heard of.

### 1.2 In production the app could never hold the permission

The deeper one. A `signature`-level permission is granted to packages signed like whichever package
**defines** it. In production the proxy is signed with a per-install `SigningIdentity` key generated
on the device; Understudy is signed at build time with whatever key distributed it. They can never
match, because the app does not have its own release private key on the device.

So with the proxy as the definer:

1. the platform checks the provider's `android:permission` **before any provider code runs**;
2. Understudy does not hold `BRIDGE` and cannot be granted it;
3. the call is refused at the gate;
4. `ProxyFileBridge.enforceCaller()` — the generator-certificate digest check written
   specifically to make production work — **never executes**.

A signed release build would install a proxy that refuses every call from the app that made it. The
app would look broken for a reason no user could diagnose, and the fix that was supposed to address
it was dead code. This was `HANDOFF.md`'s "single largest gap".

### 1.3 One change closes both

**`:app` defines `BRIDGE`; the proxy only requires it.** Requiring a permission defined by another
package is ordinary Android — the platform resolves permissions device-wide by name, and a provider
has no obligation to define the permission guarding it.

| | Before | After |
|---|---|---|
| Who defines `BRIDGE` | each proxy | `:app` |
| Two proxies on one device | `INSTALL_FAILED_DUPLICATE_PERMISSION` | fine |
| Does `:app` hold it in production | no (different signer from the definer) | yes (it matches its own certificate) |
| What actually authorises a caller | the permission, which cannot work | `enforceCaller()`'s generator-certificate digest |
| `:app` absent, proxy installed | provider guarded by an undefined permission | same — and an undefined component permission fails **closed** |

The digest check is *strictly stronger* than a signature permission. A signature permission says
"same signer as the definer", i.e. any build signed with Understudy's release key. The digest says
"the exact install that generated me". Re-signing or sideloading a different build of Understudy
correctly invalidates every proxy the old one made.

The permission is kept as a first gate anyway: it costs nothing, it keeps the provider closed on
test artifacts that carry no digest, and defence in depth beats elegance here.

---

## 2. The thing nobody expected: fixing 1.2 broke the bridge entirely

CI run #29, with the permission moved, failed **5 of 10** `BridgePremiseTest` cases — including the
basic `ping` that had passed on six consecutive green runs:

```
dev.understudy.bridge.BridgeError$ProxyUnreachable: No proxy responds at authority
  'com.example.targetgame'. Is it installed in this user profile?
Caused by: java.lang.IllegalArgumentException: Unknown authority com.example.targetgame
	at android.content.ContentResolver.call(ContentResolver.java:2463)
```

And in the *same log*, seconds earlier, from the shell:

```
+ adb shell content query --user 10 --uri content://com.example.targetgame/data
Row: 0 name=files,   isDir=1, size=0,  …, readable=1, writable=1, path=files
Row: 1 name=planted, isDir=1, size=0,  …, readable=1, writable=1, path=planted
+ adb shell content call --user 10 --uri content://com.example.targetgame --method ping
Result: Bundle[{protocol=1, package=com.example.targetgame, ok=true, user=10, …}]
```

The provider was alive, serving rows, running as user 10. The proxy was fine. **The app could not
see it.**

### 2.1 Cause: package-visibility filtering applies to provider resolution

From API 30 the platform filters which packages an app may see, and the filter covers
`ContentResolver` authority resolution, not just the `getPackageInfo` / `queryIntent*` family. An
app that cannot see a provider gets `Unknown authority` — which is *character-for-character
identical* to "nothing is installed under that name". That ambiguity is what makes this failure
expensive: it reads as a missing install, not as a missing declaration.

The shell (`adb shell content query`, uid 2000/0) is not filtered, which is why the same log
contains a working provider and a failing client.

### 2.2 Why it had worked for six runs: an undocumented AOSP rule

Nothing in the project ever declared `<queries>`. Visibility came from the permission itself.
`frameworks/base` `services/core/java/com/android/server/pm/AppsFilter.java`,
`addPackageInternal` (android14-release):

```java
if (!newPkg.getUsesPermissions().isEmpty()) {
    for (ParsedUsesPermission usesPermission : newPkg.getUsesPermissions()) {
        // Lookup in the mPermissionToUids cache if installed packages have
        // defined this permission.
        if (mPermissionToUids.containsKey(usesPermissionName)) {
            … mQueryableViaUsesPermission.add(newPkgSetting.getAppId(), targetAppId);
        }
        …
    }
}
if (!newPkg.getPermissions().isEmpty()) {
    // newPkg defines some permissions
    for (ParsedPermission permission : newPkg.getPermissions()) {
        // Lookup in the mUsesPermissionToUids cache if installed packages have
        // requested this permission.
        … mQueryableViaUsesPermission.add(queryingAppId, newPkgSetting.getAppId());
    }
}
```

**A package becomes visible to anything that requests a permission it defines** — in both
directions, so install order does not matter, and with an explicit `targetAppId != newAppId` guard
so defining and requesting your own permission buys nothing.

That is the whole reason the bridge ever resolved. The proxy defined `BRIDGE`, the app requested it,
so the proxy was visible. Moving the definition to `:app` made the app its own definer, which the
guard excludes — and visibility vanished.

This rule is **not in the published package-visibility documentation.** The "visible automatically"
page lists own app, certain system packages, the installer, `startActivityForResult` callers,
service binders, provider accessors, URI-grant providers and IMEs. Not permission definers. It was
found by reading AOSP after a device contradicted the model.

### 2.3 What replaces it, and why nothing simpler works

The replacement has to be a **static** declaration, because the target package name is chosen when
the APK is generated:

| Mechanism | Viable? |
|---|---|
| `<queries><package android:name="…">` | No — the name does not exist at build time |
| `<queries><provider android:authorities="…">` | No — the authority *is* the package name |
| proxy defines a **fixed** permission, app requests it | No — two proxies collide again (defect 1.1) |
| proxy defines a **per-target** permission, app requests it | No — the app cannot request a name it does not know |
| `QUERY_ALL_PACKAGES` | Works, but Play-restricted and orders of magnitude broader than needed |
| rely on `canQueryAsInstaller` (the app installs the proxy) | Works in production, invisible in CI, and fails for any proxy the user installed another way |
| **`<queries><intent>` on a custom action** | **Yes** — matches every proxy and nothing else |

So: every proxy advertises `dev.understudy.action.PROXY_DISCOVERY`, and `:app` declares

```xml
<queries>
    <intent>
        <action android:name="dev.understudy.action.PROXY_DISCOVERY" />
    </intent>
</queries>
```

`<queries><intent>` requires exactly one `<action>` and no `<data>` path attributes; a bare custom
action is the canonical use, and it is the only `<queries>` form that can match a set of packages
whose names are unknown at build time.

### 2.4 Why the action is on a *new* component and not on the existing activity

`ProxyStatusActivity` already has an intent filter, and adding a second one there is the obvious
move. It is also a trap.

`KEEP_HIDDEN` — the default and safest teardown — hides the proxy by disabling its launcher
activity, which an app may always do to its own components. Visibility is computed from *enabled*
components. So a filter on that activity stops matching the moment the proxy hides itself, and the
app loses the ability to reach the bridge at precisely the moment it needs it, to call
`showLauncher` and undo the hiding. The result is a permanently unreachable proxy with no launcher
icon and no UI: the worst state this app can produce, and one it would create by itself.

Hence `ProxyDiscoveryReceiver`: exported, inert, guarded by `BRIDGE` so a stranger cannot broadcast
to it and wake the process, and never disabled by anything.

Guarding it with a permission is safe for discoverability: `canQueryViaComponents` matches against
*parsed* intent filters on `AndroidPackage` objects, not through the resolver, so component
permissions are not consulted — and even if some build did consult them, the only caller that
matters holds `BRIDGE` by construction.

---

## 3. A related trap: `enforceCaller()` must not remember a "no"

`matchesGenerator()` authenticates a caller via `PackageManager`, which is itself visibility-
filtered. The caller does become visible — "any app that accesses a content provider in your app"
*is* on the published automatic list, which is the reverse direction of §2.2 and is what makes the
digest check possible at all — but that bookkeeping is posted to a handler in
`ActivityManagerService`, so the **first** call can race it and resolve no packages.

The original code cached that:

```kotlin
val ok = runCatching { matchesGenerator(uid, expected) }.getOrDefault(false)
callerVerdicts[uid] = ok          // ← a transient miss, remembered forever
```

One racing first call would then lock the legitimate owner out for the lifetime of the proxy
process: `SecurityException` on every subsequent call, unrecoverable short of reinstalling the
proxy, with a UI that can say nothing more specific than "bridge unavailable".

`CallerVerdictCache` now stores **positive verdicts only**. The cost asymmetry is what makes that
free: a false negative costs one extra certificate walk on the next call from an authorised uid,
while caching a false *positive* would be a security hole. `matchesGenerator` also distinguishes
its two failure modes in logcat — "resolves to no visible packages" (transient) versus "presented a
certificate that is not the generator's" (permanent) — because from a support report those are
indistinguishable otherwise.

---

## 3b. The same conflation on the other side of the boundary

Written while testing the transfer engine, and the same class of bug as §3: two different failures
that cross Binder looking identical, with the difference deciding whether work continues.

`ProxyFileBridge` throws `SecurityException` in two unrelated situations:

* `enforceCaller()` — the calling uid is not the install that generated this proxy. **Fatal**: every
  subsequent call fails too, so a transfer should stop.
* `BridgePaths` via `requireOk()` — the *path* asked for is not acceptable. **Not fatal**: the next
  file may be perfectly fine.

`BridgeClient` mapped both to `BridgeError.PermissionDenied`, and `TransferEngine` treats that as
fatal. Measured, with one symlink inside the save tree pointing at a real file outside it:

```
failure path=live-link fatal=true reason=The proxy at '…' refused access: this is not the
  Understudy install that generated it. … Regenerate and reinstall the proxy.
```

So a save directory containing one link — which games and launchers create routinely, for shared
asset directories — aborted the entire backup and told the user their Understudy install was broken
and that they should uninstall and regenerate every proxy they had. The security property was fine:
the bytes outside the root were never served. Only the *classification* was wrong, and the
classification is what the user acts on.

`BridgeContract.PATH_REJECTION_MARKER` separates them. It has to be a string prefix rather than a
distinct exception type because the two sides cannot share types: `:app` has `:proxy-core` on its
**test** classpath only, so a custom exception thrown by the proxy would not resolve in the app's
classloader. `call()` reports errors inside a Bundle rather than by throwing, so it gets the
structured equivalent, `KEY_PATH_REJECTED`. Both are stripped before a reason is shown to a user.

`PROTOCOL_VERSION` is deliberately **not** bumped: an old proxy without the marker degrades to the
previous behaviour, and an old app seeing the marker shows it verbatim. Neither breaks, and bumping
would force every existing proxy to be regenerated for a classification fix.

A footnote worth keeping: the same experiment showed a **dangling** symlink behaves differently and
was already correct. `File.exists()` follows the link and returns false, so `openFile` throws
`FileNotFoundException` before the canonical-path check ever matters, and the client reports
`NotFound` — non-fatal. Two symlink shapes, two different code paths, only one of them broken. That
is why the suite tests both.

## 4. What is now pinned, and where

| Invariant | Pinned by | Needs a device? |
|---|---|---|
| `:app` defines `BRIDGE` at signature level and requests it | `BridgePermissionOwnershipTest` | no |
| the proxy defines **no** permission | `BridgePermissionOwnershipTest`, on the sources *and* on the committed binary template | no |
| `protectionLevel` is a valid discriminator for "defines a permission" | `BridgePermissionOwnershipTest.protectionLevelReallyIsTheDiscriminator…` | no |
| retargeting preserves the permission name, the discovery action and every component class name | `BridgePermissionOwnershipTest`, `ManifestPatcherTest` | no |
| both proxies advertise discovery; `:app` queries for it | `BridgePermissionOwnershipTest` | no |
| negative caller verdicts are never cached | `CallerVerdictCacheTest` (8) | no |
| the two `BridgeContract` copies agree, `DISCOVERY_ACTION` included | `BridgeIntegrationTest.theTwoCopiesOfTheContractAgree` (reflection over every String/int constant) | no |
| install failures are explained, including `INSTALL_FAILED_DUPLICATE_PERMISSION` | `InstallFailureDescriptionTest` (12) | no |
| a **path** refusal is never reported as a **caller** refusal | `TransferEngineTest` (22), both symlink shapes | no |
| the signing identity loads rather than regenerates, and survives corruption | `SigningIdentityPersistenceTest` (17) | no |
| write-then-swap, resume, cancellation and per-file isolation | `TransferEngineTest` (22) | no |
| **two proxies really do coexist on a device** | `run-premise-test.sh`, "confirm both proxies are installed side by side" | **yes** |
| **the app can really see and reach a proxy** | `BridgePremiseTest.theProxyIsVisibleThroughTheDiscoveryIntentWithoutQueryAllPackages` | **yes** |
| **the production digest path works against a differently-signed proxy** | `CallerAuthPremiseTest` (2) | **yes** |

The artifact-level checks read the committed `proxy-template.apk` with the project's own AXML
parser. `protectionLevel` appears in an AXML string pool only as a `<permission>` element's
attribute, so its absence proves the template defines nothing while `BRIDGE`'s presence proves the
provider still requires it. Element and attribute names share one pool, which is why `permission`
alone cannot discriminate — and why a test exists to check that the discriminator still means what
it says.

CI also changed shape:

* the app is installed **before** the proxies, which is the production order (the app generates and
  installs them) and the reverse of what CI did before;
* `adb install -i dev.understudy.debug` is deliberately **not** used. It would record the app as
  the installer and grant visibility through `canQueryAsInstaller` as well, making the suite pass
  even if `<queries>` were broken. Plain `adb install` leaves `<queries>` as the only mechanism
  under test, which is the stricter configuration and the one that must hold for a proxy the user
  installed by hand;
* `dumpsys package permission`, `dumpsys package queries` and a `cmd package query-receivers` probe
  are captured every run, so the next visibility failure arrives with the platform's own answer
  instead of a guess;
* an explicit `INSTALL_FAILED_DUPLICATE_PERMISSION` grep fails the run with a message naming the
  cause, rather than letting it surface as a missing package three steps later.

---

## 5. Known limitation this introduces

With `:app` defining a fixed permission name, a debug build (`dev.understudy.debug`) and a release
build (`dev.understudy`) **cannot be installed at the same time** — both define
`dev.understudy.permission.BRIDGE`, so the second install fails with
`INSTALL_FAILED_DUPLICATE_PERMISSION`.

The proper fix would be to namespace the permission per `applicationId` and teach `ManifestPatcher`
a second substitution so each generated proxy requires its generator's specific name. That means
touching the exact-match rule that milestone 4 hardened, plus `EXPECTED_IDENTITY_STRINGS`, for a
case that only affects someone running a debug build alongside a release one. Not worth the risk;
documented instead, and `InstallResultReceiver.describe` now says what to do when it happens.

The mirror-image case is also worth knowing: upgrading Understudy while a proxy generated by an
*older* build is still installed fails the same way, because that old proxy still defines `BRIDGE`.
Uninstall the old proxies first. Nothing shipped before this, so it is a CI and developer concern
rather than a user one.

---

## 5b. Verified — CI run #31, both API levels

```
OK: 2 proxies coexist for user 10                                  API 34 and API 35
OK (11 tests)                                                      BridgePremiseTest
OK (2 tests)                                                       CallerAuthPremiseTest
CALLER-AUTH VERIFIED on API 34: the app reached a proxy signed with a DIFFERENT key
CALLER-AUTH VERIFIED on API 35: the app reached a proxy signed with a DIFFERENT key
PREMISE VERIFIED on API 34 / on API 35
OK: 'pm uninstall -k' preserved the data directory
```

Both defects from §1 are closed on a real Android system, and the visibility mechanism from §2.3 is
confirmed to be the one doing the work:

```
PREMISE-DIAG   queryIntentReceivers(dev.understudy.action.PROXY_DISCOVERY)
                 = OK ([com.example.prodgame, com.example.targetgame])
PREMISE-DIAG   getPackageInfo         = OK (PackageInfo{… com.example.targetgame})
PREMISE-DIAG   resolveContentProvider = OK (ContentProviderInfo{name=com.example.targetgame
                                    className=dev.understudy.proxytpl.bridge.ProxyFileBridge})
PREMISE-DIAG   getLaunchIntentForPackage = OK (… cmp=com.example.targetgame/…ProxyStatusActivity)
```

`<queries><intent>` resolves **both** proxies from an app holding no `QUERY_ALL_PACKAGES`, and once
the package is visible every other query API follows — including `resolveContentProvider`, which is
the one that was failing. So the fix is not merely sufficient; it restores the whole surface.

### The two gates, with the actual numbers

`CallerAuthPremiseTest` prints which gate produced any failure. On a green run it prints this
instead, and the two digests are the whole proof:

```
CALLERAUTH-DIAG target=com.example.prodgame uid=1010141 userId=10
CALLERAUTH-DIAG holdsBridge=GRANTED (dev.understudy.permission.BRIDGE)
CALLERAUTH-DIAG ownCertificateSha256=bdbdca70b0d38d4a23d2193af23e5f1fd1355155ef2dfd5bf06342be248545fa
CALLERAUTH-DIAG ping OK: package=com.example.prodgame user=10 protocol=1
                 roots=[DATA exists=true /storage/emulated/10/Android/data/com.example.prodgame, …]
CALLERAUTH-DIAG mkdirs+query+openFile round trip OK
                 (PathStat(exists=true, isDirectory=false, sizeBytes=47,
                  canonicalPath=/storage/emulated/10/Android/data/com.example.prodgame/
                                callerauth-probe/round-trip.txt))
```

* **Gate 1 — `holdsBridge=GRANTED`** while the proxy is signed with a *different* key
  (`apksigner` reports `O=Understudy, CN=Understudy PerInstall`, certificate SHA-256
  `79b6131e…`, against the app's `bdbdca70…`). Before §1.3 that was impossible: the proxy defined
  the permission, so only packages sharing its throwaway key could hold it. This line is the
  entire point of moving the definition.
* **Gate 2 — `ownCertificateSha256=bdbdca70…`** equals the digest `ProdSignedProxyTest` baked into
  the proxy's `assets/understudy-generator-cert.sha256`. The proxy compared the calling uid's
  certificate against it and let the call through, which is the production authorisation path
  running on a device for the first time.
* The round trip covers `call(mkdirs)`, `openFile` both ways, `query` and `call(statPath)` — every
  gated entry point — and `statPath`'s canonical path plus byte count confirm the write reached
  the real filesystem, reported by the only process that can see it.

What this still does not cover: a genuine *release* signing key. CI signs the app with the
committed test keystore, so `bdbdca70…` is the test certificate. The mechanism is key-agnostic —
the generator bakes whatever its own certificate hashes to — but the end-to-end pass with a real
distribution key is still outstanding, and needs one run once `keystore.properties` exists.

Also worth recording: `adb root succeeded on attempt 1` on both jobs. Run #30's API 35 failure was
a single un-retried `adb root` losing a race with adbd's restart, not an image problem; §4 of the
commit that fixed it has the detail.

---

## 6. Lessons

1. **A green run does not tell you which mechanism made it green.** Six consecutive runs proved the
   bridge worked and said nothing about *why*. The reason was an undocumented AOSP visibility rule
   riding along with a permission declaration that was there for a completely different purpose.
   When a change to one thing breaks an unrelated thing, the first question is "what was the old
   thing silently also doing?"
2. **`Unknown authority` is not "not installed".** It is the filtered-visibility error, and it is
   indistinguishable in the message from a genuinely absent provider. Any diagnostic that cannot
   tell those apart will send you looking in the wrong place.
3. **Read the platform source when the published docs are silent.** The visibility rule in §2.2 is
   not on the package-visibility pages. It is twelve lines of `AppsFilter.java`, and finding it
   took less time than the CI run that proved something was wrong.
4. **Prefer the stricter test configuration.** Not passing `-i` to `adb install` costs nothing and
   removes a second mechanism that could have masked a broken first one.
5. **Never cache a negative derived from a filtered or asynchronous lookup.** The value is not
   "no", it is "not yet".
