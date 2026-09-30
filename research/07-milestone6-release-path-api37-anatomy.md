# Milestone 6 — the release path made real, the API 37 anatomy completed, and three gaps closed honestly

Date: 2026-09-30 · Status: **the whole flow — premise, caller-auth and the in-app install
including its confirmation dialog — is device-verified on API 36** (run #52:
`INSTALLER-SESSION VERIFIED`, `PREMISE VERIFIED`, `CALLER-AUTH VERIFIED` in one job). Runs
#49–#52 were a four-run evidence chain: #49 proved the release path and exposed the
silent-green verdict class; #50 device-proved the 27 KB proxy and measured that no appop
makes a commit silent; #51 located API 37's blocker to `State: RUNNING_LOCKED` and the
dialog's to `Can't resume non-current user`; #52 passed on 36 and named the last two defects
(AOSP's `user setup not complete` suppression; a state-extraction bug that skipped the
unlock probe). Run #53 carries those fixes. The experimental API 37 job is documented in §3
with its blocker identified to a mechanism, not to a vibe.

This milestone picked up HANDOFF §3's list and worked it: items 1, 4, 6 and 8 are closed,
item 7 (API 37) advanced from "never boots" to "boots, mitigated graphics, one broken platform
layer left", item 5 got a probe that will answer it, and item 2 got the reconciliation it was
blocked on. Items 3 and 9 are unchanged (OEM hardware; accepted limitation).

---

## 1. The release path, on a device, with a key from nowhere in the repo (§3.1 — closed)

### What was untested

Every device-verified run until now installed the **debug** app signed with the **committed
test keystore**. That key is public. The production proxy carries the digest of whatever
certificate the app was signed with, and `enforceCaller()` compares against it — so every
green caller-auth run so far proved the mechanism with one specific, repo-committed number.
The R8-minified release artifact had never run anywhere, by anything.

### What the `premise-release` job now proves

A per-run **ephemeral** RSA-4096 identity is generated with `keytool` inside the workflow;
`keystore.properties` is written exactly as the README instructs an operator to write it (the
mechanism under test is the operator's mechanism — a private shortcut would prove nothing);
the app is built `-Punderstudy.testBuildType=release`, which makes AGP create the androidTest
variant *against* the release build type, signed with the same ephemeral key; the proxy
generation tests are pointed at the ephemeral keystore through new Gradle properties
(`understudy.appKeystore*`), so the prod-signed proxy carries **the release certificate's**
digest. Then the identical `run-premise-test.sh` orchestration runs the full premise +
caller-auth + installer suites against it.

Three equalities are asserted on the host **before anything boots**, so a harness regression
fails in seconds with a named cause instead of as a mystery on the emulator:

```
app signer == instrument signer        (or am instrument is refused outright)
app signer == digest baked in proxy    (or enforceCaller() correctly refuses everything)
app signer != committed test identity  (or the job proves nothing)
```

Run #49's numbers: app = instrument = ephemeral = `eed99923…`, committed-test = `bdbdca70…`,
baked digest = `eed99923…`. `OK: 2 proxies coexist`, `CALLER-AUTH VERIFIED`,
`PREMISE VERIFIED` — the production authorization path, with a genuine non-test key, on a
device. What remains for §3.1 is only the operator's *actual* distribution key, which is by
definition not something a repository can hold; the mechanism it plugs into is now measured.

### Two runtime discoveries the JVM could never have made

**a) AGP minifies the release instrument too.** `testBuildType=release` brings a
`minifyReleaseAndroidTestWithR8` task nobody had seen before. It failed on androidx.test's
compile-only errorprone references (R8's own `missing_rules.txt` named exactly two classes),
and it raised the naming question that decided `proguard-test-rules.pro`: the CI script
addresses tests by name (`-e class dev.understudy.instrumented.BridgePremiseTest`), and
AndroidJUnitRunner resolves that filter against real dex class names — **a renamed test class
is a silently empty test run**, which the verdict logic would then have called a pass. The
test package is kept by name; the app keeps `bridge.**`, `core.model.**` and `install.**`,
exactly the surface the instruments link against.

**b) The release instrument died before a single test, and CI called it green.** Run #49's
release job printed, for *both* suites:

```
INSTRUMENTATION_RESULT: shortMsg=Process crashed.
INSTRUMENTATION_CODE: 0
```

`Process crashed.` matches neither `FAILURES!!!` nor `INSTRUMENTATION_FAILED`, and
`am instrument` exited 0 — so the phase reported `CALLER-AUTH VERIFIED` having run zero
tests. The artifact told the truth: `NoClassDefFoundError: androidx.tracing.Trace` at
`AndroidJUnitRunner.onCreate`. Anatomy, measured with `dexdump` in the sandbox rather than
reasoned:

* the runner calls `Trace.beginSection` and resolves it through the **combined classloader** —
  whichever APK carries it;
* the DEBUG app packages `androidx.tracing` (unshaken transitive of compose/lifecycle,
  1.1.0→1.2.0) — that is where debug instruments have always found it, by accident;
* R8 strips it from the RELEASE app as unreachable *from app code* — correctly;
* no test APK carries it in either variant: AGP compiles androidTest with the app's runtime
  classpath as **provided**, so `androidTestImplementation(tracing)` *resolves* (visible on
  `debugAndroidTestRuntimeClasspath`) yet is never packaged (class absent from every test
  dex — scanned entry by entry).

Fix: `-keep class androidx.tracing.** { *; }` in the app's rules — restoring exactly the
runtime shape debug always had, for ~1.5 KB. The comment in `proguard-rules.pro` records the
measurement chain so the rule is never "cleaned up".

**The verdict hole is closed independently of this crash** (§2) — a fix to one silent-green
path without a fix to the class of silent green would have been the run #30 mistake again.

---

## 2. Verdicts became a whitelist: "no failures" is not "it ran"

Run #49 found the same lie twice: the release job above, and the API 37 job, where
`INSTRUMENTATION_ABORTED: System has crashed.` glided through the failure grep with exit
code 0. This is HANDOFF §5.3 (`am instrument` exits 0 when tests fail) wearing a third
costume — the project's own methodology section says a pass must be *asserted*, not
*not-contradicted*, and the script violated it in exactly the place the methodology was
written about.

All three instrument phases now share one verdict function:

* `FAILURES!!!|Error in |INSTRUMENTATION_FAILED` → **1, real test failures** (never retried —
  retrying an assertion is hiding a result);
* `INSTRUMENTATION_ABORTED|System has crashed` → **2, environment failure**;
* **absence of the runner's positive `OK (N tests)` line → 2 as well** — the whitelist. An
  empty, truncated or novel-shaped output can no longer pass, whatever the exit code says;
* verdict 2 is retried **once**, after `await_framework` and after restarting the profile if
  the crash stopped it (`ensure_user_running` — run #49's caller-auth phase tested a user the
  earlier crash had stopped, and reported bridge failures that were really "nobody home").

---

## 3. API 37: the anatomy is complete; one platform layer is left, named

The experimental job's history is a ladder, and run #50 climbed two more rungs.

### 3.1 Graphics — root cause closed, mitigation measured

The abort (`Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma`, guest
`mapper.ranchu.so`, called from surfaceflinger's `RegionSamplingThread`) is upstream's, and
upstream has spoken: issuetracker **546200928 — "Won't fix (Intended behavior)"** (557246813
duplicate). Waiting is not a strategy; the capability cannot be disabled from the host side
either — measured three ways: our own run #48 log (`[FeatureControl] Bad feature name:
'ReadColorBufferDma'` on canary 37.3.2.0 — the HEAD "fix" was a silent no-op), and
docker-android's measurements (`-GLDMA,-GLDMA2,-GLDirectMem`, `-HostComposition`: guest bit
stays set; GPU backend irrelevant to the assert's existence).

What *can* be removed is the trigger. `RegionSamplingThread` only reads back when someone
registers a `CompositionSamplingListener`, and on a headless CI guest there are exactly two
registrants: **SystemUI** (navbar luminance) and the **HOME app** (icon contrast). Run #49
measured that one is not enough — with SystemUI disabled the guest survived boot, the
framework restart, user creation and start-user, then surfaceflinger SIGABRT'd in
RegionSampling *again*, mid-suite. Run #50 ran the both-triggers version: SystemUI + the
resolved HOME package (`cmd package resolve-activity … category.HOME` — image-portable) are
disabled for user 0, the framework is restarted so the crash-looping instances die, and both
are disabled for the secondary user before `start-user`. Result: **no new aborts anywhere in
the run** (the two abort texts in the log are the same historical crash-buffer entry,
timestamp 16:18:29.699, printed by two dumps). Residual triggers — task snapshots on
activity transitions, screencap — are things this suite never does.

External corroboration for the mitigation class: docker-android (crash loop ~1/14 s → 0 with
SystemUI off) and LibreMediaConverter's `api-37-emulator-crash.md` (full instrumented suite
completes on API 37; also the renderer discriminator — host GLES never boots, ANGLE-family
boots with 1–2 aborts — kept here as context, not as a lever: CI's swiftshader boots).

### 3.2 Storage/components — located to one line: the user never unlocks

Run #51's storage block settled it. On API 37, user 10 reaches `running` — and stops there:

```
UserInfo{10:understudy-ci:400} serialNo=10 isPrimary=false
    State: RUNNING_LOCKED            <- API 37
    State: RUNNING_UNLOCKED          <- API 34 and 35, same block, same run: the control
```

Its credential-encrypted storage never unlocks. Everything else is downstream of that one
line, and each symptom previously looked like its own mystery:

* the FUSE daemon for the user dies — `/storage/emulated/10` reads `Transport endpoint is
  not connected` (this predates the mitigation: #45/#46 showed it with no disable/restart,
  so the restart is not the cause);
* components of *installed* packages are unavailable while the user is CE-locked —
  `am start` of the proxy's own exported activity answers `Error type 3: Activity class …
  does not exist` for a package `pm list` shows installed;
* the provider does not resolve **even from root** (`content query` → `Could not find
  provider` — not a visibility filter; uid 0 bypasses those), because non-`directBootAware`
  components do not exist for a locked user;
* in-suite: `getPackageInfo` succeeds (package-level data lives in DE storage) while
  `resolveContentProvider → null` and discovery → `[]`;
* flags stay `0x400` — the `0x010` INITIALIZED bit every healthy level carries never lands,
  because user init finishes *after* unlock.

The competing theory — an independent API 37 change excluding **stopped** packages from
provider resolution — died by measurement in the same block: on API 34/35 the cold,
never-launched, stopped proxy's provider **returns rows** to a shell `content query`. Nothing
needs to unstop a proxy on a healthy level.

**The probe now in the script**: becoming the current user re-drives `UserController`'s
unlock path, and a CE unlock is *sticky* — so when the state reads LOCKED (and only then),
the script switches to the user, polls for `RUNNING_UNLOCKED`, switches back to user 0, and
re-tries the provider. If the switch completes the stuck unlock, the API 37 suite runs
against a background-but-unlocked user, exactly like every other level. If it does not, the
blocker is proven to be the image's own user-unlock flow — filed beside the graphics one
Google already declared intended behaviour — and the honest options are re-testing when
Google ships a fixed `android-37.x` image, or deleting the job and keeping the measured
negative. What is no longer possible, thanks to §2, is a green tick over any of it.

Run #52 then taught the probe a humility lesson: it never fired. `user 10 state:
<unreadable>` — the extraction (`grep -A2` from the `UserInfo{N:}` line to its `State:`)
came back empty because the real `dumpsys user` interleaves other lines between them; the
filtered diagnostic two lines below *showed* `RUNNING_LOCKED` while the variable gating the
probe was blank. An instrument that reads empty is itself the finding — the same lesson as
the `ram-size` saga in HANDOFF §3.7, caught this time by the log line the same commit added.
The extraction is now an awk scan to the first `State:` after the right `UserInfo` (tested
locally against interleaved and CRLF output), and the gate is exact-match dispatch —
`RUNNING_UNLOCKED` *contains* the substring `LOCKED`, so the grep it replaced would have
"fixed" healthy levels the moment extraction started working.

Run #53 ran the probe, and the probe delivered: polls 1–3 `RUNNING_LOCKED`, poll 4 —
after the foreground switch — **`RUNNING_UNLOCKING`**. The mechanism is real; the unlock
*starts*. And then the framework crashes mid-unlock (native `crash_dump64` at t≈109 s,
system_server restarting, polls degrading to unreadable), the user never reaches
`RUNNING_UNLOCKED`, and the provider stays unresolvable. There is no third move from this
side of the glass: the image's own user-unlock flow dies while doing exactly what the
workaround asks of it.

**So API 37 is dispatch-only now.** Two upstream defects, both measured, both documented
(gfxstream: won't-fix 546200928, triggers removed here — no new aborts since #50;
user-unlock: the probe above). The project's own rule decided it: an indefinitely-red job
trains everyone to ignore red, and a measured negative result is worth keeping *as a
document*, not as a tick that never turns. The harness stays whole behind
`workflow_dispatch` — re-test the day Google ships a fixed `android-37.x` image; the
recovery conditions are the ones docker-android listed: a fixed image, a fixed gfxstream
backend, or an ATD image for 37.

---

## 4. The in-app install path, on a device (§3.4 — closed, and corrected by the device)

`InstallerSessionPremiseTest` walks the production path with the production classes:
`ApkGenerator` (committed template + per-install identity + baked digest) →
`ProxyInstaller.stage` (session, fsync, commit) → `InstallResultReceiver` broadcast →
`getPackageInfo` (installer-of-record visibility — a *different* AppsFilter path from the
`<queries>` discovery the premise suite pins) → `BridgeClient.ping` answers through the
session-installed proxy. The shell contributes only the per-user appop
(`appops set --user N … REQUEST_INSTALL_PACKAGES allow` — the automatable equivalent of the
Settings toggle), asserted first so a missing grant is named rather than surfacing as an
opaque commit failure.

The first version of this test asserted that the appop makes the commit silent. **Run #50:
measured false** — `canRequestInstalls=true`, and the platform answered
`STATUS_PENDING_USER_ACTION` with `CONFIRM_INSTALL` anyway. Per-install confirmation is not
skippable for non-privileged installers on current builds; that is exactly what
`SessionManager` already models (`Installing(awaitingUser=true)`, confirmation intent handed
to the UI). The test was wrong; the product was right; the device said so in one run. The
test now drives the user's real path — commit → PENDING_USER_ACTION → launch confirmation →
UiAutomator taps **Install** → SUCCESS → ping — and a platform that ever commits silently
still passes (first-outcome-Success short-circuits). A missing button dumps the window
hierarchy into the artifact before failing.

Run #51 found the *second* layer: the dialog launched, and never appeared.
`W/ActivityTaskManager: Can't resume non-current user … InstallStart` — the launch was
allowed (`BAL_ALLOW_PERMISSION`), the task was created, `isVisible=false` forever, and
UiAutomator correctly refused to tap a button that was never on a screen. `am start-user`
runs a profile in the **background**; UI draws only for the **current** user. The phase now
switches the profile to the foreground (`am switch-user`, polled via `am get-current-user`)
and switches back afterwards, so the FUSE/caller-auth phases and teardown keep testing the
background-user case they always tested. A product note fell out for free: on a real device
the user is *in* the profile when they install — the CI background-user configuration is
strictly harsher than production, which is the right direction for a test to err.

With the switch in place, run #52 split the field. **API 36 passed the whole thing**:
`NeedsConfirmation` (from `com.google.android.packageinstaller`) → dialog launched →
`Install` tapped → `Success` broadcast → `ping OK … roots=[RootStatus(root=DATA,
exists=true, …)]` → `INSTALLER-SESSION VERIFIED on API 36`. The production install path is
device-proven. AOSP 34/35 showed the *third* layer: `Displayed
com.android.packageinstaller/.PackageInstallerActivity for user 10: +544ms` — the dialog
really rendered — immediately followed by `ActivityTaskManager: Skipping, user setup not
complete`, and the hierarchy dump twenty seconds later contains nothing but the status bar.
A `pm create-user` profile never ran a setup wizard, so `USER_SETUP_COMPLETE` is 0 and the
platform declines to keep ordinary tasks resumed for it; the Google installer tolerates
that, the AOSP one does not. The phase now marks the profile provisioned
(`settings put --user N secure user_setup_complete 1`) after switching it to the
foreground — the standard emulator-CI remedy — and the test gained a tap-by-id fallback,
because the AOSP and Google installers share the `ok_button` id but not the package name.

Run #53 proved the remedy works and found the *fourth* layer in the same dump that proved
it: the dialog rendered in full on AOSP 35 — "Do you want to install this app?", title
"Understudy Proxy", buttons `text="INSTALL"` and `text="CANCEL"` — and the test still
reported no button, because `By.text("Install")` is case-sensitive and AOSP's alert dialog
shouts. The word is the invariant; the capitalisation is per-build. The selector is now a
case-insensitive `^install$` pattern with the id fallbacks behind it. Four layers deep,
every one found by a device in a single run each, every one invisible to 197 JVM tests —
which is not a criticism of the JVM tests; it is the job description of this suite.

Deliberately not covered: the uninstall half. `PackageInstaller.uninstall` always routes
through a dialog, and the proxy's `hasFragileUserData` guarantees one (the "Keep app data"
checkbox); there is no faithful headless tap for a data-retention choice. The data-keeping
uninstall stays asserted via `pm uninstall -k` in teardown, as before.

---

## 5. Process death mid-transfer (§3.6 — closed on the JVM; the device half is the resume UI)

`TransferEngine` was already *restartable* — completed files skip by size, stale `.part`
files die before the next attempt — but it could not *remember*: process death took the fact
of the transfer with it, and the restarted app showed a UI that had never heard of the
half-finished backup. `TransferJournal` is the missing persistence (SharedPreferences, one
entry — the app runs one transfer at a time — file-completion granularity).

The load-bearing design point is the outcome semantics: `RUNNING` is written at `begin` and
replaced **only by a deliberate terminal write**. Process death cannot run cleanup code, so
"still RUNNING at next launch" is precisely its fingerprint; a graceful coroutine
cancellation reaches its `CancellationException` handler and records `CANCELLED`. The resume
offer therefore appears exactly when the platform killed us and never after the user
cancelled — a resume offer after a deliberate cancel is a lie, and a missing offer after a
kill is the bug. Corrupt/schema-drifted records read as absent: losing the offer is
recoverable; a boot loop is not. Eleven Robolectric tests pin these semantics, including the
late-progress-callback resurrection guard and URI round-trip fidelity (the resume re-grants
SAF from that exact string).

`MainViewModel.resumeTransfer()` checks its preconditions instead of assuming them: Ready
session **for the same package and user the journal names** (or bytes flow to the wrong
proxy), and a SAF grant that still restores. Refusals name the remedy. A resumed pull is
cheap by construction; a resumed push re-copies the tree, because a size-skip in that
direction could leave a truncated file *inside the save directory* looking restored.

The **device half followed in the same session**: `TransferDeathPremiseTest`, two instrument
invocations, because a dead process cannot keep testing. Phase `die` builds a known tree in
the proxy's storage through the real bridge (three small files plus 64 MB), begins a
journaled pull into app-specific external storage, and — the moment the big file passes
8 MB of in-flight copy, checked inside the per-chunk progress callback so no external
polling race exists — `Process.killProcess(myPid())`: SIGKILL to self, no handlers, no
cancellation, no terminal journal write; exactly what the low-memory killer does to a
transfer in flight. Phase `resume` is a fresh process: the journal must say RUNNING for
that exact transfer; the re-run pull must land every file byte-exact (SHA-256 against the
deterministic generator); files completed before the kill must be *untouched* (mtime — the
on-device analogue of the JVM suite's write-counting); no `.part` may survive; the journal
must end terminal. The die phase's verdict is the journal XML read from disk as root, not
the instrument exit — a crash is its success signature, which is why this one phase cannot
use the whitelist helper. Running on 34/35/36 and the release job, where it additionally
proves an R8'd app writes a journal a fresh R8'd process can read.

---

## 6. The proxy is 27,760 bytes (§3.8 — closed, device-proved in the same session)

The template was 701,358 bytes, "dominated by the Kotlin stdlib" — and the HANDOFF's
options were cosmetic (multiDex) or contradicted the project's Kotlin requirement (rewrite in
Java). The old "deliberately NOT minified" comment conflated two things: predictable names
for *our* classes, and shipping 2.3 MB of stdlib the proxy never calls. R8 separates them.
`proxy/proguard-rules.pro` keeps `dev.understudy.proxytpl.**` verbatim (manifest component
names must resolve inside the extracted dex; support stack traces must mean something); AGP's
aapt rules keep manifest components; tree-shaking drops `classes.dex` from **2,323,652 to
46,048 bytes**; the `kotlin_builtins` resources (kotlin-reflect's models, ~51 KB — the proxy
never reflects) are excluded from packaging.

| | before | after |
|---|---|---|
| template APK | 701,358 B | **27,760 B** |
| `classes.dex` | 2,323,652 B | **46,048 B** |
| generated proxy (device-installed) | ~696 KB | **~26.6 KB** |

Sandbox verification before committing (per the house discipline): apksigner Verifies (v2),
aapt2 badging intact, zipalign OK, `syncProxyTemplate` + **197/197 unit tests green**
including `ManifestPatcherTest`'s `EXPECTED_IDENTITY_STRINGS` on the new binary and
`BridgePermissionOwnershipTest` parsing it, generated proxy verifies with the right signer,
`checkProxyTemplateFresh` green. Device proof followed one run later: **#50's premise +
caller-auth suites passed on API 34/35/36 running proxies generated from the 27 KB
template** — provider cold-start included. Cold start should also be *faster* (less dex to
verify), which nothing has measured yet.

---

## 7. SAF writes (§3.5): the question is now a probe, not a shrug

SAF coverage stayed fake-free-but-device-less because a persistable tree grant comes from
`ACTION_OPEN_DOCUMENT_TREE` — a human — and CI has none. But root bypasses
ActivityManagerService's grant check, so a *shell-side* grant might hand the app the same
URI. Nobody knew which candidate command exists on which level, and guessing is how this
project once spent six runs on a directory path. Every level now runs a strictly
informational probe: `content grant-uri-permission`, `pm grant-uri-permission`, an `am
broadcast` carrying `--grant-*-uri-permission`, then `dumpsys activity uri-permissions` to
show what actually landed.

**Run #53 answered it, and the answer is no** (API 35 AOSP; the other levels' logs agree):
`content` has no grant subcommand (it prints usage), `pm grant-uri-permission` is
`Unknown command`, and the broadcast "completes" with zero receivers — which confers
nothing; the subsequent `dumpsys activity uri-permissions` shows the app holding no tree
grant. So §3.5's honest label is now evidence-backed: **instrumented SAF coverage needs a
human, or a UI rig that drives the real picker** — technically possible with the UiAutomator
muscle this milestone built for the install dialog (launch the picker, tap through folder
selection and "Allow"), but every extra step is per-level wording and layout risk, and the
picker's grant flow is exactly what OEMs customise hardest. The probe stays in the script:
it costs four best-effort commands, and if any future level grows a shell grant path, the
log will say so on the first run that has it.

---

## 8. Wireless ADB (§3.2): the reconciliation, delivered

HANDOFF §3.2 blocked the backend on a contradiction and said to reconcile before writing it.
Reconciled, from milestone 4's device-verified findings:

| Runbook step | uid 2000 (self-paired wireless ADB) | root (`su`) |
|---|---|---|
| `pm list packages/users`, `dumpsys`, `content` probes | yes | yes |
| `pm install --user N`, `pm uninstall --user N` | yes | yes |
| `pm uninstall -k --user N` (keeps data, leaves the name **retained**) | yes | yes |
| `appops`, permission state | yes | yes |
| `mv` rename-aside inside `/data/media/N` | **no** — another user's emulated storage is unreachable below root (run #12), and root itself is refused the FUSE view (run #11) | yes, on the raw path |
| `chown` ownership repair (`restoreOwnership`) | **no** — needs CAP_CHOWN | yes |
| evacuating the conflicting user's data before its uninstall | **no** — that data is exactly what uid 2000 cannot read | yes |

So the contradiction resolves into a **capability-gated backend, not a universal one**: the
conflict case (target installed elsewhere under a different signature) has *no* data-safe
unprivileged path on any Android 11+ build — that is a platform fact, not a backend defect.
A wireless-ADB backend can honestly offer: the whole no-conflict flow, `-k` teardown, and the
rename-aside runbook **only when `su` answers** — with the data-destroying alternative
labelled as such, since `ShellCommands` already generates both texts. What it must never do
is report the runbook as available and then fail at `mv`: the original refusal-to-ship
standard stands, now with a precise line to hold it to.

Not built this session, deliberately: the ADB pairing + transport stack (LADB-style) is a
substantial implementation that **no CI in this project can exercise** — emulators do not
offer wireless-debugging self-pairing — and shipping an untestable protocol client is how
"claims available, cannot execute" gets born. The design above is the deliverable §3.2 asked
for; implementation waits for a device rig or a library decision.

---

## 9. What this milestone says about the method

Three times this session, a device corrected a confident claim inside a single run: the
feature-flag "fix" that was a `Bad feature name` no-op (#48's own log, found by reading
instead of re-running); the silent install that does not exist (#50); and the green verdict
over a crashed process (#49, twice). Each correction was cheap *because* the harness now
carries its own evidence — crash dumps, whitelisted verdicts, three-equality pre-boot
assertions, before/after discriminators. The pattern worth keeping: when a phase can pass
without proving anything, the phase is the bug.
