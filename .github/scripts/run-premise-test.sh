#!/usr/bin/env bash
# Orchestrates the FUSE-premise test on an emulator.
#
# Everything here needs uid 2000 (adb shell), which is precisely why it cannot be done from
# inside the app or even the instrumented test: creating a user and installing packages are both
# shell-only operations. The test itself only asserts.
#
# Usage: run-premise-test.sh [api-level]
set -euo pipefail

API="${1:-34}"
TARGET_PKG="${TARGET_PKG:-com.example.targetgame}"
# The package the *production-signed* proxy impersonates. Distinct from TARGET_PKG so the two
# proxies coexist for the same user: TARGET_PKG is signed with the app's key (permission path),
# PRODSIGN_PKG with a fresh random key carrying the app's certificate digest (production path).
PRODSIGN_PKG="${PRODSIGN_PKG:-com.example.prodgame}"
APP_PKG="${APP_PKG:-dev.understudy.debug}"
# Whether the run may proceed WITHOUT root, in a reduced mode that proves less. Default 0: the
# API 34/35 jobs must fail loudly if they lose root, because that is a real regression (run #30)
# and a silently reduced suite would look identical to a passing one.
#
# Set to 1 only for images where root is impossible. There is no AOSP (`target: default`) system
# image for API 36 or 37 — `sdkmanager --list` offers google_apis, google_apis_playstore and
# google_atd, and nothing else — and google_apis is production-signed, so `adb root` is refused.
# Since the project targets API 37, verifying nothing above 35 is worse than verifying a clearly
# labelled subset of it. See the banner below for exactly what the subset is.
ALLOW_NO_ROOT="${ALLOW_NO_ROOT:-0}"
TEST_RUNNER="${TEST_RUNNER:-androidx.test.runner.AndroidJUnitRunner}"
USER_NAME="understudy-ci"

LOGDIR="premise-logs"
mkdir -p "$LOGDIR"

log() { printf '\n=== %s ===\n' "$*" | tee -a "$LOGDIR/orchestration.log"; }

# Echo every command into the log too: when this fails in CI the shell transcript is the only
# evidence available, and `set -x` output is what makes an adb orchestration debuggable.
exec > >(tee -a "$LOGDIR/orchestration.log") 2>&1
set -x

log "environment"
adb wait-for-device
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.version.release
adb shell getprop ro.product.cpu.abi
echo "api=$API target=$TARGET_PKG app=$APP_PKG"

# `adb root` makes later steps easier but is unavailable on google_apis images; shell already
# runs as uid 2000 in the shell_data_file SELinux domain, which is what actually matters for
# writing inside another user's Android/data.
log "adb root (retried — it is not best effort, the suite needs it)"
# Root decides how we can plant fixture bytes. On a userdebug/eng image (`target: default`, i.e.
# AOSP) `adb root` succeeds and we can write to /data/media/<user> — the *raw* storage that sits
# underneath the FUSE view. On a production-signed google_apis image it genuinely cannot, and
# uid 2000 has no access to another user's emulated storage at all, which is what produced
# "mkdir: '/storage/emulated/10': Permission denied".
#
# RETRIED, because this is a flake source and not a rare one. Run #30's API 35 job lost the whole
# run here: `adb root` returned non-zero on the first and only attempt even though the image was
# `target: default` and runs #18 and #29 had rooted the same image fine. `adb root` restarts adbd,
# which drops the connection, and the emulator-runner's own `logcat -G` call in the same window
# reported "device 'emulator-5554' not found" — the device was mid-reconnect. One transient
# failure therefore looked exactly like a production-signed image, and the message said so.
#
# So: retry, wait for the device between attempts, and only then draw a conclusion. The wording
# below no longer asserts a cause it has not established.
HAVE_ROOT=0
for attempt in 1 2 3 4 5 6; do
  adb wait-for-device
  if out="$(adb root 2>&1)"; then
    adb wait-for-device
    # `adb root` exits 0 for "adbd is already running as root" too, so confirm rather than trust.
    if [ "$(adb shell id -u 2>/dev/null | tr -d '\r')" = "0" ]; then
      HAVE_ROOT=1
      echo "adb root succeeded on attempt $attempt: uid=0 ($out)"
      break
    fi
    echo "adb root returned 0 but id -u is $(adb shell id -u 2>&1 | tr -d '\r'); attempt $attempt"
  else
    echo "adb root attempt $attempt failed: $out"
  fi
  sleep 5
done
EXPECT_PLANTED=true
if [ "$HAVE_ROOT" != "1" ]; then
  echo "!! could not obtain root after 6 attempts."
  adb shell getprop ro.build.type
  adb shell getprop ro.build.tags
  adb shell getprop ro.debuggable
  adb shell id
  if [ "$ALLOW_NO_ROOT" != "1" ]; then
    echo "!! refusing to continue. Planting fixture bytes into another user's private storage"
    echo "   needs uid 0: uid 2000 is exempt from the FUSE filter only within its own mount"
    echo "   namespace, and even root is refused the FUSE view of another user (run #11), so"
    echo "   /data/media/$USER_ID is the only route. This job pins target: default, so a"
    echo "   failure here is an emulator that never settled, not a missing feature."
    exit 1
  fi
  EXPECT_PLANTED=false
  # A GitHub Actions annotation, not just log text: a reduced run that only mentioned it in the
  # transcript would still show a green tick with nothing on the summary page, which is how a
  # silently narrower suite survives for months. This puts it on the run's front page.
  echo "::warning title=Premise suite running REDUCED on API $API::adb root is unavailable, so fixture bytes could not be planted. Not proven by this job: reading data that PRE-DATES the proxy, and 'pm uninstall -k' preservation. Both remain covered by the rooted AOSP jobs."
  cat <<'BANNER'
--------------------------------------------------------------------------------------------
RUNNING IN REDUCED MODE: no root, so nothing was planted and nothing can be checked on the
raw filesystem. Set ALLOW_NO_ROOT=1 for this job, which means the image cannot be rooted.

STILL PROVEN here, all of it from inside a real secondary profile:
  * a runtime-generated proxy installs for user N and answers across Binder, so the
    signature-level BRIDGE permission really is granted between two separately built APKs;
  * the proxy runs in the intended profile, and reports its own two roots;
  * bytes written THROUGH THE BRIDGE land on the real filesystem, confirmed by the proxy's own
    statPath — the caller cannot check for itself, which is the point;
  * the platform still hides that storage from every other package, so none of the above can
    pass vacuously;
  * two proxies coexist for one user (neither defines BRIDGE);
  * the app reaches a proxy signed with a DIFFERENT key via the generator-certificate digest;
  * traversal is rejected, and a forged authority reads nobody;
  * <queries><intent> discovery resolves the proxies without QUERY_ALL_PACKAGES.

NOT PROVEN here, and still only proven on the rooted API 34/35 jobs:
  * that the proxy can read data that PRE-DATES it — the backup/restore case, which needs
    fixture bytes planted by root into /data/media/<user>;
  * that `pm uninstall -k` preserves Android/data afterwards, which can only be checked on the
    raw path.
--------------------------------------------------------------------------------------------
BANNER
fi
# RAW_BASE is computed after the user exists; see the planting step.

# --------------------------------------------------------------------------------------------
# Waiting for a framework that stays up.
#
# `sys.boot_completed=1` — what the emulator action waits for — is not the same claim as "the
# package service will still be there when the next command arrives". On API 37 (run #41) the guest
# reached system_server and then surfaceflinger crashed at t=72s, taking zygote and system_server
# with it; the script was already running and `pm create-user` came back with
# "cmd: Failure calling service package: Broken pipe (32)". Run #42 got past that with a retry and
# then hit `cmd: Can't find service: package` after `am start-user`, on a line that was only there
# to print something — under `set -e` an informational command killed the run.
#
# So: one helper, called before anything that talks to the framework, and no informational command
# is ever allowed to fail the script. This is not API-37-specific; a framework restart during boot
# is a property of a software-rendered emulator on a shared runner.
# --------------------------------------------------------------------------------------------
FRAMEWORK_TRANSIENT='Broken pipe|Can.t find service|device offline|device not found|Service package|no devices/emulators found'

framework_ready() {
  local out
  out="$(adb shell pm list users 2>&1 || true)"
  printf '%s' "$out" | grep -qE "$FRAMEWORK_TRANSIENT" && return 1
  printf '%s' "$out" | grep -q "UserInfo{" || return 1
  return 0
}

# await_framework <label> — waits up to ~5 min for the package service to answer. Returns non-zero
# only if it never does, and prints enough to say why.
await_framework() {
  local label="${1:-framework}" i
  for i in $(seq 1 60); do
    if framework_ready; then
      [ "$i" -gt 1 ] && echo "await_framework($label): answered after $i attempts"
      return 0
    fi
    sleep 5
  done
  echo "!! await_framework($label): the package manager never answered in 300 s."
  echo "-- what the framework said before it stopped answering --"
  adb logcat -d -b crash 2>/dev/null | tail -60 || true
  adb shell getprop sys.boot_completed 2>&1 || true
  adb shell getprop init.svc.zygote 2>&1 || true
  adb shell getprop init.svc.surfaceflinger 2>&1 || true
  adb shell dmesg 2>/dev/null | tail -30 || true
  return 1
}

# adb_fw <args...> — an adb shell command against the framework, retried across a restart.
# Informational callers should append `|| true`; this function does not swallow failures itself,
# because a command that genuinely cannot succeed must still be able to fail the run.
adb_fw() {
  local attempt out rc
  for attempt in 1 2 3 4 5 6; do
    # In an `if` condition, not a bare assignment: under `set -e` a failing command substitution
    # in an assignment propagates and can kill the caller before the retry logic runs.
    if out="$(adb shell "$@" 2>&1)"; then rc=0; else rc=$?; fi
    if ! printf '%s' "$out" | grep -qE "$FRAMEWORK_TRANSIENT"; then
      printf '%s\n' "$out"
      return $rc
    fi
    echo "adb_fw: transient framework failure on attempt $attempt ($out); waiting"
    await_framework "adb_fw retry" || return 1
  done
  printf '%s\n' "$out"
  return $rc
}

# dump_guest_crashes <label> — the guest's own account of why it is unhappy.
#
# Added because `-show-kernel` answered "is the kernel panicking?" (no) and "which service is
# dying?" (surfaceflinger, SIGABRT, in a loop) but not "why". The abort message lives in the crash
# logcat buffer and in a tombstone, neither of which reaches the serial console, and by the time a
# human reads the job log the emulator is gone. Both are cheap to capture and empty when nothing
# crashed, so this runs unconditionally rather than only on a failure path.
dump_guest_crashes() {
  local label="${1:-unlabelled}"
  echo "-- guest crash buffer ($label) --"
  adb logcat -d -b crash 2>/dev/null | tail -80 || echo "  (crash buffer unavailable)"
  echo "-- guest tombstones ($label) --"
  adb shell 'ls -1t /data/tombstones 2>/dev/null | head -3' 2>/dev/null || true
  adb shell 'T=$(ls -1t /data/tombstones/tombstone_* 2>/dev/null | head -1); [ -n "$T" ] && head -60 "$T"' 2>/dev/null \
    || echo "  (no tombstone readable)"
  echo "-- which services are up ($label) --"
  adb shell 'getprop | grep -E "^\[init\.svc\.(zygote|surfaceflinger|system_server|bootanim)\]"' 2>/dev/null || true
}

log "wait for the framework to be usable, not merely booted"
dump_guest_crashes "after boot"
if ! await_framework "initial"; then
  dump_guest_crashes "framework never came up"
  exit 1
fi
echo "framework answered:"
adb shell pm list users 2>&1 || true

# --------------------------------------------------------------------------------------------
# DISABLE_SYSTEM_UI=1 — the API 37 gfxstream mitigation.
#
# The API 37 guest image asserts in mapper.ranchu (`!rcEnc->featureInfo()->hasReadColorBufferDma`)
# on ANY host color-buffer readback, because the image's gralloc HAL was built expecting the host
# NOT to advertise ReadColorBufferDma while every current emulator build does. Upstream calls this
# intended behaviour (issuetracker 546200928, "Won't fix"; 557246813 is a duplicate), and the
# obvious knobs are measured dead ends: `-feature -ReadColorBufferDma` is rejected by canary
# 37.3.2.0 as "Bad feature name" (run #48's own log), and -GLDMA/-GLDMA2/-GLDirectMem do not
# clear the guest's hasReadColorBufferDma bit (docker-android's measurements, yeshen.blog 164109821).
#
# So the capability cannot be turned off — but the TRIGGER can. The readback that kills
# surfaceflinger comes from RegionSamplingThread, and on a headless CI guest exactly two apps
# register CompositionSamplingListeners: SystemUI (navbar luminance) and the HOME app (icon
# contrast). Run #49 is the evidence for needing BOTH: with SystemUI disabled the guest stayed
# up through boot, user creation and start-user — then surfaceflinger SIGABRT'd in
# RegionSampling again at 15:39:31, mid-suite, with only the launcher left to sample (run #48's
# cascade had already shown nexuslauncher's RegionSamplingHelper dying alongside SystemUI's).
# Two independent reports measured the SystemUI half of this mitigation: docker-android's crash
# loop (~1 abort/14 s) went to 0, and LibreMediaConverter's run-e2e.sh gets through a full
# instrumented suite on API 37 with it disabled (docs/api-37-emulator-crash.md). Residual risk:
# other readback callers (task snapshots on activity transitions, screencap) can still abort —
# this suite launches no activities and takes no screenshots, so nothing it does is a known
# trigger, and the instrument phases retry once across a guest crash (run_instrument_phase).
#
# The framework is restarted afterwards so the already-crash-looping instances die with it
# rather than respawning sampling listeners during the run. Off by default: on API 34/35/36
# SystemUI and the launcher are innocent and disabling them would change what those jobs measure.
if [ "${DISABLE_SYSTEM_UI:-0}" = "1" ]; then
  log "disable the RegionSampling triggers (SystemUI + launcher) and restart the framework"
  adb_fw pm disable-user --user 0 com.android.systemui || true
  # Resolve the HOME package rather than hardcoding it: google_apis ships nexuslauncher, AOSP
  # ships launcher3, and an image refresh can rename either. The fallback keeps the mitigation
  # from silently doing nothing if the resolver output ever changes shape.
  HOME_PKG="$(adb_fw cmd package resolve-activity -a android.intent.action.MAIN \
      -c android.intent.category.HOME 2>/dev/null | tr -d '\r' \
      | sed -n 's/.*packageName=\([A-Za-z0-9_.]*\).*/\1/p' | head -1 || true)"
  case "$HOME_PKG" in ''|*[!A-Za-z0-9_.]*) HOME_PKG="com.google.android.apps.nexuslauncher" ;; esac
  echo "home package resolved as: $HOME_PKG"
  adb_fw pm disable-user --user 0 "$HOME_PKG" || true
  adb_fw cmd package list packages -d || true
  # `stop; start` restarts zygote+system_server without touching the kernel or adb. Between the
  # two commands the device may vanish entirely, hence wait-for-device on both sides.
  adb shell stop || true
  adb wait-for-device
  adb shell start || true
  adb wait-for-device
  if ! await_framework "after trigger disable + framework restart"; then
    dump_guest_crashes "framework never came back after trigger disable"
    exit 1
  fi
  dump_guest_crashes "after trigger disable"
  echo "SystemUI + $HOME_PKG disabled for user 0; framework restarted and answering."
fi

log "create the secondary user profile"
# Retried because "the framework answers" and "the framework will still be up in a second" are
# different claims; runs #41 and #42 are the evidence, and #42 only got through on attempt 2.
CREATE_OUT=""
for attempt in 1 2 3 4 5 6; do
  CREATE_OUT="$(adb shell pm create-user "$USER_NAME" 2>&1 || true)"
  echo "attempt $attempt: $CREATE_OUT"
  case "$CREATE_OUT" in
    *"created user id"*) break ;;
    *"already exists"*)  CREATE_OUT="$(adb shell pm list users 2>&1)"; break ;;
  esac
  sleep 15
done
# `|| true` is load-bearing. Under `set -euo pipefail` a grep that matches nothing makes the whole
# command substitution fail, which kills the script HERE — before the diagnostic block below can
# print anything. Run #41 proved it: `pm create-user` returned "Broken pipe" and the script exited
# 224 having said nothing about why. A diagnostic that only runs when the thing it diagnoses did
# not happen is not a diagnostic.
USER_ID="$(printf '%s' "$CREATE_OUT" | grep -oE 'id [0-9]+' | grep -oE '[0-9]+' | head -1 || true)"
if [ -z "${USER_ID:-}" ]; then
  echo "!! could not parse a user id from: $CREATE_OUT"
  adb shell pm list users
  # Run #40 died here on API 37 with "Cannot add user. Not enough space on disk. (code 5)" and the
  # log said nothing about the disk, so the next step was a guess. UserManagerService checks
  # allocatable bytes on /data before allowing a secondary profile; print the numbers that decide
  # it, plus how many users this build permits at all, so a failure here names its own cause.
  echo "-- /data and the host's view of it --"
  adb shell df -h /data 2>&1 | head -5 || true
  adb shell df -h 2>&1 | head -12 || true
  echo "-- data partition size the AVD was created with --"
  adb shell getprop ro.data.largefs 2>&1 || true
  adb shell stat -f -c '%n blocks=%b free=%f size=%S' /data 2>&1 || true
  echo "-- user limits on this build --"
  adb shell pm get-max-users 2>&1 || true
  # Headless-system-user builds refuse a secondary profile for different reasons; say which one.
  adb shell getprop ro.fw.mu.headless 2>&1 || true
  exit 1
fi
echo "created user id $USER_ID"
# The raw storage path for this user, i.e. what the FUSE mount at /storage/emulated/$USER_ID
# is backed by. This is where the rename-aside trick really operates.
RAW_BASE="/data/media/$USER_ID"

# SystemUI and the launcher run PER USER, so disabling them for user 0 does not stop the new
# profile from starting its own instances — which would re-register RegionSampling listeners
# and re-arm the gfxstream abort mid-run. Disabled here, before start-user, so user $USER_ID
# never launches either. Best-effort: a failure here must not end the run, the await below is
# the load-bearing part.
if [ "${DISABLE_SYSTEM_UI:-0}" = "1" ]; then
  adb_fw pm disable-user --user "$USER_ID" com.android.systemui || true
  adb_fw pm disable-user --user "$USER_ID" "${HOME_PKG:-com.google.android.apps.nexuslauncher}" || true
fi

log "start the new profile"
# switch-user brings it to the foreground, which is how the app will normally be used. We also
# want the *unstarted* case to work, so the bridge is exercised after a start-user as well.
adb shell am start-user "$USER_ID" 2>&1 || true

# WAIT for the profile to actually be running. `sleep 5` was enough on API 34/35/36 and is not on
# 37, and run #46 is the evidence — this is the same mistake as assuming `sys.boot_completed` means
# the framework will stay up, one level down:
#
#   API 36:  UserInfo{10:understudy-ci:410} running
#   API 37:  UserInfo{11:understudy-ci:400}          <- no "running", and 0x400 lacks the
#                                                       0x010 FLAG_INITIALIZED bit that 0x410 has
#
# `am start-user` had already printed "Success: user started", and `await_framework` passed because
# `pm list users` answered — the package service is up, the *profile* is not. Everything after that
# ran against a user that was still coming up: installs went in, the instrumented suite started, and
# 7 of 11 tests failed with `Unknown authority` while both proxies were confirmed installed. That
# reads as "the bridge is broken on Android 17" and is actually "we did not wait".
await_user_running() {
  local want="$1" i line
  for i in $(seq 1 60); do
    line="$(adb shell pm list users 2>/dev/null | tr -d '\r' | grep -E "UserInfo\{$want:" || true)"
    case "$line" in
      *running*) echo "user $want running after $i poll(s): $line"; return 0 ;;
    esac
    sleep 3
  done
  echo "!! user $want never reached 'running' in 180 s:"
  adb shell pm list users 2>&1 || true
  adb shell dumpsys user 2>/dev/null | head -40 || true
  return 1
}

# ensure_user_running — a guest crash stops the secondary profile (run #49: user 11 lost its
# "running" flag and its FUSE daemon after surfaceflinger took the framework down mid-suite,
# so the NEXT phase tested a dead profile and reported bridge failures that were really
# "nobody home"). Before any retry, put the profile back the way the phase expects it.
ensure_user_running() {
  local line
  line="$(adb shell pm list users 2>/dev/null | tr -d '\r' | grep -E "UserInfo\{$USER_ID:" || true)"
  case "$line" in
    *running*) return 0 ;;
  esac
  echo "ensure_user_running: user $USER_ID is not running ($line); restarting it"
  adb shell am start-user "$USER_ID" 2>&1 || true
  await_user_running "$USER_ID" || return 1
  await_framework "after user restart" || return 1
  return 0
}

# instrument_verdict <logfile> — classifies one `am instrument` run.
#   0 = the suite ran and passed   1 = real test failures   2 = guest environment failure
#
# WHITELIST, not blacklist. Run #49 is why: the guest crashed mid-suite, `am instrument`
# printed "INSTRUMENTATION_ABORTED: System has crashed." — which the old
# FAILURES!!!/INSTRUMENTATION_FAILED grep did not match — AND EXITED 0, so the phase was
# silently green while nothing had been proven. That is the exact failure class this whole
# workflow exists to avoid (run #30's silent degradation, `am instrument`'s exit-code lie in
# HANDOFF §5.3), arriving through a new door. A pass now REQUIRES the runner's own positive
# result line: "OK (N tests)". Anything else — abort, empty output, a truncated log — is an
# environment failure, whatever the exit code says.
instrument_verdict() {
  local log="$1"
  if grep -qE "FAILURES!!!|Error in |INSTRUMENTATION_FAILED" "$log"; then
    return 1
  fi
  if grep -qE "INSTRUMENTATION_ABORTED|System has crashed" "$log"; then
    return 2
  fi
  if ! grep -qE "OK \([0-9]+ tests?" "$log"; then
    return 2
  fi
  return 0
}

# run_instrument_phase <logfile> <am-instrument-args...> — runs one phase with ONE retry across
# a guest crash (verdict 2). The retry is environmental, not evidential: test failures (1) are
# never retried, and the retry only counts if it produces its own positive "OK (N tests)".
# Retrying a crashed guest is legitimate; retrying a failed assertion would be hiding results.
run_instrument_phase() {
  local log="$1"; shift
  local attempt verdict exit_code
  for attempt in 1 2; do
    if [ "$attempt" -gt 1 ]; then
      echo "-- retrying the instrument phase after a guest crash (attempt $attempt) --"
      await_framework "instrument retry" || return 2
      ensure_user_running || return 2
      dump_guest_crashes "before instrument retry"
    fi
    set +e
    adb shell am instrument -w "$@" 2>&1 | tee "$log"
    exit_code="${PIPESTATUS[0]}"
    set -e
    echo "am instrument exit=$exit_code (attempt $attempt) log=$log"
    set +e
    instrument_verdict "$log"
    verdict=$?
    set -e
    case "$verdict" in
      0) return 0 ;;
      1) return 1 ;;
      *) echo "!! attempt $attempt: no positive result line — the guest crashed or the"
         echo "   runner printed nothing usable (exit=$exit_code). This is an environment"
         echo "   failure, not a pass; a crash mid-suite must never be silently green."
         dump_guest_crashes "instrument attempt $attempt" ;;
    esac
  done
  return 2
}
await_user_running "$USER_ID" || exit 1
# Starting a secondary profile restarts enough of the framework that the package service can be
# absent for a while. Run #42 died on the very next line — `pm list users`, which exists only to
# print something — because `set -e` does not care why a command failed.
await_framework "after start-user" || exit 1
adb shell pm list users 2>&1 || true


# INSTALL ORDER IS DELIBERATE, and it matches production rather than being convenient.
#
# The app goes in FIRST, because on a real device the app is what generates and installs the
# proxy — the reverse order is not a state a user can reach. It also matters technically: from
# API 30 the app can only reach a provider it is allowed to *see*, and visibility is computed by
# AOSP AppsFilter at package-scan time in both directions, so either order works — but only the
# production order is the one worth testing.
#
# Note what is NOT done here: `adb install -i $APP_PKG`, which would also record the app as the
# installer and grant visibility through AppsFilter's separate canQueryAsInstaller path. That
# would be more faithful to production and would make this suite pass even if the <queries>
# declaration were broken, hiding the regression. Installing via plain adb leaves <queries> as
# the ONLY visibility mechanism under test, which is the stricter configuration and the one that
# must hold for a proxy the user installed by hand.
log "install the app under test for user $USER_ID"
await_framework "install app" || exit 1
# `|| true` for the same reason: `find | head -1` can SIGPIPE, and an empty variable then fails at
# the `adb install` below with a message that names the file, which is the useful place to fail.
# Parameterised so the release-E2E job can drive the identical orchestration against a
# RELEASE-signed app (app-release.apk + app-release-androidTest.apk, applicationId without the
# .debug suffix). Defaults are the debug outputs every existing job has always used.
APP_APK_DIR="${APP_APK_DIR:-app/build/outputs/apk/debug}"
TEST_APK_DIR="${TEST_APK_DIR:-app/build/outputs/apk/androidTest/debug}"
APP_APK="$(find "$APP_APK_DIR" -name '*.apk' | head -1 || true)"
TEST_APK="$(find "$TEST_APK_DIR" -name '*.apk' | head -1 || true)"
echo "app=$APP_APK"; echo "test=$TEST_APK"
if [ -z "$APP_APK" ] || [ -z "$TEST_APK" ]; then
  echo "!! no APK found under $APP_APK_DIR or $TEST_APK_DIR — the build step did not produce them"
  ls -laR app/build/outputs/apk 2>/dev/null | head -30 || true
  exit 1
fi
adb install --user "$USER_ID" -r -t "$APP_APK" 2>&1 | tee premise-logs/install-app.log
adb install --user "$USER_ID" -r -t "$TEST_APK" 2>&1 | tee premise-logs/install-test.log
adb_fw pm list packages --user "$USER_ID" | grep -F "$APP_PKG" \
  || { echo "!! the app under test is not installed for user $USER_ID"; exit 1; }

log "install the proxy APK for user $USER_ID"
await_framework "install proxy" || exit 1
adb install --user "$USER_ID" -r /tmp/proxy.apk 2>&1 | tee premise-logs/install-proxy.log
# adb_fw, not adb shell: a framework restart here prints nothing and would be reported as
# "proxy is not installed", sending the next person after an install bug that does not exist.
adb_fw pm list packages --user "$USER_ID" | grep -F "$TARGET_PKG" \
  || { echo "!! proxy is not installed for user $USER_ID"; exit 1; }

log "install the production-signed proxy for user $USER_ID"
await_framework "install prod-signed proxy" || exit 1
# Signed with a DIFFERENT key than the app, carrying the app's certificate digest — the production
# key layout. If this file is missing the caller-auth phase below is skipped loudly rather than
# silently passing, so a harness regression cannot masquerade as coverage.
#
# This is also the artifact that proves two proxies can coexist at all. Both carry the same
# provider permission requirement; neither defines it. When the proxy used to define
# dev.understudy.permission.BRIDGE itself, this second install died with
# INSTALL_FAILED_DUPLICATE_PERMISSION in under ten seconds (run #28) — a device-wide collision
# between two packages that share no code and no user.
if [ -f /tmp/proxy-prodsign.apk ]; then
  adb install --user "$USER_ID" -r /tmp/proxy-prodsign.apk 2>&1 | tee premise-logs/install-proxy-prodsign.log
  grep -q "INSTALL_FAILED_DUPLICATE_PERMISSION" premise-logs/install-proxy-prodsign.log \
    && { echo "!! two proxies collided on a permission definition — the proxy must not define BRIDGE"; exit 1; }
  adb_fw pm list packages --user "$USER_ID" | grep -F "$PRODSIGN_PKG" \
    || { echo "!! production-signed proxy ($PRODSIGN_PKG) is not installed for user $USER_ID"; exit 1; }
else
  echo "!! /tmp/proxy-prodsign.apk missing — the 'Generate the proxy APKs' step did not produce it"
  exit 1
fi

log "confirm both proxies are installed side by side"
await_framework "confirm coexistence" || exit 1
# The whole point of moving the permission definition to :app. One proxy working was never enough:
# reaching several targets is the product.
adb shell pm list packages --user "$USER_ID" | grep -E "$TARGET_PKG|$PRODSIGN_PKG" | tee premise-logs/both-proxies.log
COUNT="$(grep -cE "$TARGET_PKG|$PRODSIGN_PKG" premise-logs/both-proxies.log || true)"
if [ "$COUNT" -lt 2 ]; then
  echo "!! only $COUNT of the two proxies is installed for user $USER_ID; coexistence is broken"
  exit 1
fi
echo "OK: $COUNT proxies coexist for user $USER_ID"

log "who defines the bridge permission, and what can the app see?"
# Run #29 failed with "Unknown authority" from the app while `content query` from the shell worked
# in the same log. That gap is package-visibility filtering, and it is invisible unless asked
# about directly. dumpsys is the ground truth for what AppsFilter computed.
adb shell dumpsys package permission dev.understudy.permission.BRIDGE 2>&1 | head -25 \
  | tee premise-logs/permission-owner.log || true
echo "-- AppsFilter state for the app (forceQueryable / queries) --"
adb shell dumpsys package queries 2>&1 | grep -A6 -E "$APP_PKG|$TARGET_PKG|$PRODSIGN_PKG" \
  | head -60 | tee premise-logs/queries.log || true
echo "-- does the discovery action resolve? (this is what <queries><intent> buys) --"
adb shell cmd package query-receivers --components -a dev.understudy.action.PROXY_DISCOVERY 2>&1 \
  | head -20 | tee premise-logs/discovery-query.log || true

log "storage + component state of user $USER_ID (diagnostic, all levels)"
# The API 37 premise failure (runs #45-#50) lives in this layer and nowhere else: user N's
# emulated storage reports "Transport endpoint is not connected" (the FUSE mount exists, its
# daemon does not), the user's flags stay 0x400 without the 0x010 INITIALIZED bit every
# working level shows, and then — everything downstream of a user whose CE storage never
# unlocked: the proxy's provider does not resolve even from ROOT (`content query` -> "Could not
# find provider", which is NOT a visibility filter, because root bypasses those), discovery
# returns [], and the instrumented suite reports ProxyUnreachable while getPackageInfo
# succeeds. One broken layer, three symptoms — OR a second, independent API 37 change:
# provider resolution excluding freshly installed (stopped) packages, which the explicit
# activity launch below discriminates between. These lines capture the platform's own account
# on every level, so the healthy ones serve as the control for the sick one. Informational:
# no command here may change any verdict (every one is best-effort), and the discriminator
# ends by force-stopping the proxy, so the suite still faces the exact cold state it would
# have faced without this block.
echo "-- user state --"
USER_STATE_LINE="$(adb shell "dumpsys user 2>/dev/null" | grep -A2 "UserInfo{$USER_ID:" | grep -oE "State: [A-Z_]+" | head -1 || true)"
adb shell "dumpsys user 2>/dev/null | grep -E 'UserInfo\{$USER_ID|State|running|initialized|unlocked' | head -12" 2>&1 || true
echo "user $USER_ID state: ${USER_STATE_LINE:-<unreadable>}"
# API 37's premise failure (runs #45-#51) reduces to this line: the secondary user reaches
# RUNNING_LOCKED and its CE storage NEVER unlocks — flags stay 0x400 without the 0x010
# INITIALIZED bit, the FUSE daemon for the user dies ("Transport endpoint is not connected"),
# every non-directBootAware component becomes unavailable ("Activity class ... does not exist"
# for an installed package; content query from ROOT: "Could not find provider"), and the suite
# reports ProxyUnreachable while getPackageInfo succeeds. API 34/35 show the healthy control in
# the same block: RUNNING_UNLOCKED, flags 0x410, cold provider resolvable while stopped — which
# also kills the stopped-state-filtering theory: on healthy levels nothing needs to unstop the
# proxy for its provider to resolve.
#
# The probe below tests whether a foreground switch completes the stuck unlock: startUser
# normally unlocks credential-less secondary users automatically, and becoming current re-drives
# UserController's unlock path. CE unlock is STICKY, so if it works we switch straight back and
# the suite still runs against a background user — the harder case every other level passes.
if printf '%s' "$USER_STATE_LINE" | grep -q "LOCKED"; then
  echo "!! user $USER_ID is RUNNING_LOCKED: CE storage never unlocked. Probing the"
  echo "   foreground-switch workaround (one variable, logged before and after)."
  adb shell am switch-user "$USER_ID" 2>&1 | head -3 || true
  for i in $(seq 1 20); do
    st="$(adb shell "dumpsys user 2>/dev/null" | grep -A2 "UserInfo{$USER_ID:" | grep -oE "State: [A-Z_]+" | head -1 || true)"
    echo "unlock poll $i: ${st:-<unreadable>}"
    printf '%s' "$st" | grep -q "RUNNING_UNLOCKED" && break
    sleep 3
  done
  echo "-- switching back to user 0; CE unlock is sticky if it happened --"
  adb shell am switch-user 0 2>&1 | head -3 || true
  sleep 3
  adb shell "dumpsys user 2>/dev/null | grep -A2 'UserInfo{$USER_ID:'" 2>&1 | head -6 || true
  echo "-- does the proxy's storage work now? --"
  adb shell content query --user "$USER_ID" --uri "content://$TARGET_PKG/data" 2>&1 | head -4 || true
fi
echo "-- volumes + mounts --"
adb shell sm list-volumes 2>&1 | head -8 || true
adb shell "mount 2>/dev/null | grep -E '/storage/emulated|/mnt/pass_through/$USER_ID|/mnt/runtime/[a-z]*/emulated/$USER_ID' | head -10" 2>&1 || true
echo "-- MediaProvider (the FUSE host process) --"
adb shell "pidof com.google.android.providers.media.module || pidof com.android.providers.media.module" 2>&1 || echo "  (media module process not running under either known name)"
adb shell "logcat -d -v time MediaProvider:V FuseDaemon:V vold:W StorageManagerService:W *:S 2>/dev/null | tail -30" 2>&1 || true
echo "-- can the platform reach the proxy's provider? (BEFORE any launch: cold, stopped state) --"
# `content query` from the shell is the functional probe; uid 0/2000 bypasses visibility
# filtering, so a failure here is platform state, not AppsFilter. (`cmd package
# resolve-content-provider` does not exist — measured "Unknown command" on 34, 35 and 37.)
adb shell content query --user "$USER_ID" --uri "content://$TARGET_PKG/data" 2>&1 | head -4 || true
echo "-- component availability probe: launch the proxy's own exported activity --"
# On a LOCKED user this answers "Error type 3: Activity class ... does not exist" for a package
# that is demonstrably installed (run #51, API 37) — components of a CE-locked user are simply
# not there yet. On healthy levels it starts, which also clears the stopped flag; the query
# before/after distinguishes the two stories, and the force-stop returns the package to the
# exact cold state the suite will face.
adb shell "am start --user $USER_ID -n $TARGET_PKG/dev.understudy.proxytpl.ProxyStatusActivity" 2>&1 | head -5 || true
sleep 3
adb shell content query --user "$USER_ID" --uri "content://$TARGET_PKG/data" 2>&1 | head -4 || true
adb shell "am force-stop --user $USER_ID $TARGET_PKG" 2>&1 || true
echo "-- (end of storage diagnostics) --"

log "plant known bytes in the proxy's private storage"
# These directories do not exist yet, and that is the point: the proxy must be able to see data
# it did not create itself, which is the backup/restore case the whole app exists for.
#
# Two candidate locations, because how we can write depends on whether we have root:
#   /data/media/<user>/...        the RAW storage under the FUSE mount. Needs root, but it is
#                                 exactly where the real rename-aside trick operates, and writing
#                                 here proves the data pre-dates the proxy.
#   /storage/emulated/<user>/...  the FUSE view. uid 2000 is normally exempt from the filter, but
#                                 only within its OWN mount namespace — another user's emulated
#                                 storage is not visible to it, so this needs root too.
DATA_DIR="/storage/emulated/$USER_ID/Android/data/$TARGET_PKG"
OBB_DIR="/storage/emulated/$USER_ID/Android/obb/$TARGET_PKG"
RAW_DATA="$RAW_BASE/Android/data/$TARGET_PKG"
RAW_OBB="$RAW_BASE/Android/obb/$TARGET_PKG"
PAYLOAD="understudy-fuse-premise-check"

# Resolve the proxy's PER-USER uid and leave it in $PROXY_UID.
#
# This cost two full CI cycles to get right, so here is every wrong answer:
#
#   WRONG  `dumpsys package <pkg> | grep userId=`   — Android 14 RENAMED that field to
#          `appId=` (AOSP Settings.java: `pw.print("  appId="); pw.println(ps.getAppId())`),
#          so on API 34/35 the grep matches nothing and the whole run dies here (run #11).
#   WRONG  chowning to the number dumpsys prints, whatever the field is called — it is the
#          APP ID, shared by every user. User 10's processes run as userId*100000 + appId
#          (u10_a148 == 1010148), and FUSE attributes Android/data/<pkg> by that per-user
#          uid. Chowning to the bare appId leaves user 10's files owned by a user-0 uid
#          and the proxy still cannot read them.
#
# Right: `pm list packages -U --user <user>` prints `package:<name> uid:<uid>` where the uid
# is ALREADY the per-user one (AOSP PackageInfoUtils.initForUser:
# `output.uid = UserHandle.getUid(userId, UserHandle.getAppId(...))`). Note `--user` is
# mandatory: the default queries user 0, where the proxy is NOT installed. Cross-checked
# against the ground truth: the CE data dir /data/user/<user>/<pkg>, which installd creates
# owned by exactly that uid (needs root, which this script has; if stat fails we keep the
# pm answer).
resolve_proxy_uid() {
  local pm_uid stat_uid
  pm_uid=$(adb shell pm list packages -U --user "$USER_ID" "$TARGET_PKG" 2>/dev/null | tr -d '\r' \
            | grep -F "package:$TARGET_PKG uid:" | head -1 \
            | sed -E 's/.*uid:([0-9]+).*/\1/')
  case "$pm_uid" in ''|*[!0-9]*) pm_uid="" ;; esac
  stat_uid=$(adb shell stat -c %u "/data/user/$USER_ID/$TARGET_PKG" 2>/dev/null | tr -d '\r')
  case "$stat_uid" in ''|*[!0-9]*) stat_uid="" ;; esac
  echo "resolve_proxy_uid: pm='--user $USER_ID' -> '${pm_uid:-?}'   stat(/data/user/$USER_ID/$TARGET_PKG) -> '${stat_uid:-?}'"
  if [ -n "$pm_uid" ] && [ -n "$stat_uid" ] && [ "$pm_uid" != "$stat_uid" ]; then
    echo "resolve_proxy_uid: WARNING pm($pm_uid) != stat($stat_uid); trusting stat"
  fi
  PROXY_UID="${stat_uid:-$pm_uid}"
  [ -n "$PROXY_UID" ]
}

plant() {
  local base="$1"
  adb shell "
    mkdir -p '$base/Android/data/$TARGET_PKG/planted' || exit 1
    mkdir -p '$base/Android/obb/$TARGET_PKG' || exit 1
    echo -n '$PAYLOAD' > '$base/Android/data/$TARGET_PKG/planted/save.dat' || exit 1
    echo -n '$PAYLOAD' > '$base/Android/obb/$TARGET_PKG/main.1.com.example.planted.obb' || exit 1
  " || return 1

  # OWNERSHIP MATTERS, and this cost a full CI cycle to find (run #10).
  #
  # Writing as root leaves the files `root:ext_data_rw` mode 660/2770, and the proxy — which
  # runs as u10_aNNN — then gets EACCES from FUSE on its *own* app-specific directory,
  # surfacing as listFiles() == null and "cannot list: ... (permission denied)". Run #10's
  # `ls -laR` is the evidence: the `files/` dir the proxy created through FUSE is
  # `u10_a148 ext_data_rw`, the root-planted `planted/` next to it stayed `root ext_data_rw`
  # and was invisible to the proxy.
  #
  # The FUSE layer attributes app-specific external storage by owning uid, not merely by
  # path. The platform creates these directories as the app's per-user uid; anything
  # restoring data from a backup has to do the same or the real app (and our proxy) cannot
  # read it back.
  if ! resolve_proxy_uid; then
    echo "!! could not determine the uid of $TARGET_PKG; leaving files root-owned"
    return 1
  fi
  echo "proxy per-user uid = $PROXY_UID; chowning planted data to it"
  # OWNER-ONLY chown (no `:group`): the setgid'd parent dirs already gave the planted dirs
  # the platform's own group (ext_data_rw / ext_obb_rw) and mode (2770 dirs / 660 files).
  # Do NOT chmod them — `chmod 771` clears the setgid bit the platform relies on, and the
  # platform's mode is 770, not 771.
  adb shell "
    chown -R '$PROXY_UID' '$base/Android/data/$TARGET_PKG' || exit 1
    chown -R '$PROXY_UID' '$base/Android/obb/$TARGET_PKG'  || exit 1
    ls -lan '$base/Android/data/$TARGET_PKG' '$base/Android/data/$TARGET_PKG/planted' '$base/Android/obb/$TARGET_PKG'
    stat -c '%u %g %a %n' '$base/Android/data/$TARGET_PKG' '$base/Android/data/$TARGET_PKG/planted/save.dat' '$base/Android/obb/$TARGET_PKG'
  "
}

PLANTED_BASE=""
if [ "$HAVE_ROOT" = "1" ]; then
  echo "-- trying the raw lower filesystem ($RAW_BASE) --"
  if plant "$RAW_BASE"; then PLANTED_BASE="$RAW_BASE"; fi
fi
if [ -z "$PLANTED_BASE" ]; then
  # Attempted even though it has never worked, because "never worked" is an observation and not a
  # guarantee: if some image ever does let uid 2000 or uid 0 reach another user's FUSE view, this
  # is what would find out, and the suite would keep working instead of dying on a missing root.
  echo "-- trying the FUSE view (/storage/emulated/$USER_ID) --"
  if plant "/storage/emulated/$USER_ID"; then PLANTED_BASE="/storage/emulated/$USER_ID"; fi
fi

if [ -z "$PLANTED_BASE" ]; then
  if [ "$HAVE_ROOT" = "1" ]; then
    echo "!! had root and still could not plant fixtures by either route — that is a real"
    echo "   failure, not an environment limitation."
    adb shell id
    adb shell ls -ld /storage/emulated/$USER_ID /data/media/$USER_ID 2>&1 || true
    exit 1
  fi
  echo "-- no root, so nothing was planted; the two planted* tests will skip themselves --"
fi
if [ -n "$PLANTED_BASE" ]; then echo "planted under $PLANTED_BASE"; fi

log "confirm the app itself CANNOT read those bytes (the restriction is real)"
# Run as the app's own uid inside user 10 for a control: this is the failure mode the whole
# project works around. It must go through the FUSE path — an app uid cannot read the raw
# /data/media lower fs at all, and note the shell's mount namespace may not even carry user
# 10's FUSE view, so treat this as informational. The authoritative control is the
# instrumented `thePlatformStillHidesOtherPackagesPrivateStorageFromUs`, which runs in a
# proper user-10 process. Expected to fail; `|| true` keeps the script going.
adb shell "run-as $APP_PKG --user $USER_ID ls '/storage/emulated/$USER_ID/Android/data/$TARGET_PKG/planted' 2>&1 || true" | head -5 || true

log "clear logcat and start capturing"
adb logcat -c 2>/dev/null || true
adb logcat -v time > premise-logs/logcat.log 2>&1 &
LOGCAT_PID=$!
sleep 1

log "probe the provider directly from the shell (bypasses our client)"
# INFORMATIONAL, and it does not work everywhere. On API 34/35/36 a root shell resolves
# `content://<target>/data` and returns rows, which is useful because it exercises the provider
# through the platform rather than through BridgeClient. On API 37 (run #45) the same command, from
# the same root shell, with both proxies confirmed installed, returns:
#   java.lang.IllegalStateException: Could not find provider: com.example.targetgame
# The authoritative path is the instrumented suite, which runs as the app inside user 10 — that is
# what the premise rests on and what the verdict is read from. A stack trace here is not a failure
# of the product, and this note exists so the next reader does not spend a run finding that out.
# `content query` exercises the same provider through the platform, so if this also returns
# nothing the problem is in the proxy; if it works, the problem is in our client or in how the
# app resolves the authority.
adb shell content query --user "$USER_ID" --uri "content://$TARGET_PKG/data" 2>&1 | head -20 || true
adb shell content query --user "$USER_ID" --uri "content://$TARGET_PKG/data/planted" 2>&1 | head -20 || true
# Discriminator: `files/` was created by the proxy ITSELF through its own view of the data
# root; `planted/` was created by root on the lower fs. If files lists but planted does not,
# the proxy's data view only contains what went through its own access path.
adb shell content query --user "$USER_ID" --uri "content://$TARGET_PKG/data/files" 2>&1 | head -8 || true
echo "-- retry the data root listing (rules out a first-touch race) --"
adb shell content query --user "$USER_ID" --uri "content://$TARGET_PKG/data" 2>&1 | head -8 || true
echo "-- call ping --"
adb shell content call --user "$USER_ID" --uri "content://$TARGET_PKG" --method ping 2>&1 | head -20 || true
echo "-- what the proxy actually sees on disk --"
adb shell ls -laR "/data/media/$USER_ID/Android/data/$TARGET_PKG" 2>&1 | head -30 || true

# Run #12 narrowed the failure to the DATA root only: with the whole tree chowned to the
# proxy's per-user uid, `obb` became fully readable through the bridge while `data` still
# reported "cannot list (permission denied)" for its root and "no such entry" for a child
# that provably exists on the lower fs. Since Android 11 an app's own Android/data/<pkg> is
# supposed to reach it through a *bind mount of the lower fs* that zygote installs at fork
# (Zygote.cpp BindMountStorageDirs: tmpfs over Android/{data,obb}, then a per-package bind
# mount from /mnt/pass_through), while the FUSE daemon outright refuses to serve those paths
# ("Emulated storage bind-mounts app-private data directories, and so these should not be
# accessible through FUSE anyway"). Two candidate explanations remain, and they have
# opposite fixes, so measure instead of guessing:
#   (a) the data bind mount is missing or points somewhere unexpected, so the proxy is
#       talking to the FUSE daemon, which refuses it;
#   (b) the mount is right but something about the pre-existing directory differs from obb's
#       (data was mode 2770 group ext_data_rw, obb 2771 group ext_obb_rw).
# Everything below is diagnostic and must never change the verdict.
log "diagnose the proxy's view of its own data root"
# `|| true` is load-bearing, and this is the THIRD time this class of bug has ended a run.
# `pidof` exits 1 when the process is not running, which is a normal and expected answer here —
# on API 37 (run #45) the shell's `content query` probe could not resolve the provider, so nothing
# had started the proxy process yet. Under `set -euo pipefail` the non-zero exit inside a command
# substitution propagates to the assignment and kills the script, two lines below a comment saying
# "Everything below is diagnostic and must never change the verdict". The same thing happened with
# the `grep | head` that parses create-user (run #41) and with an informational `pm list users`
# (run #42). Rule for this file: a command whose only job is to print something gets `|| true`.
PROXY_PID="$(adb shell pidof "$TARGET_PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}' || true)"
echo "proxy pid: ${PROXY_PID:-<not running>}"
if [ -n "${PROXY_PID:-}" ]; then
  echo "-- storage-related properties --"
  adb shell getprop | grep -iE "fuse|sdcardfs|vold.*isolation|storage" | head -20 || true

  echo "-- mount namespace of the proxy: what is mounted over Android/{data,obb} --"
  # A tmpfs over Android/data plus a bind mount of the package dir is the expected shape.
  # If the bind mount is absent for data but present for obb, explanation (a) is confirmed.
  adb shell "cat /proc/$PROXY_PID/mountinfo" 2>&1 \
    | grep -E "Android/(data|obb)|/storage/emulated|pass_through" | head -30 || true

  echo "-- the data root as the PROXY sees it (via /proc/pid/root) vs the lower fs --"
  adb shell "ls -lan '/proc/$PROXY_PID/root/storage/emulated/$USER_ID/Android/data/$TARGET_PKG' 2>&1 | head -12" || true
  adb shell "ls -lan '/data/media/$USER_ID/Android/data/$TARGET_PKG' 2>&1 | head -12" || true

  echo "-- inode identity: same directory or a different one? --"
  # %d:%i differing between the two views proves the proxy is NOT looking at the lower fs
  # directory we chowned, which is explanation (a).
  adb shell "stat -c '%d:%i %U:%G %a %n' '/proc/$PROXY_PID/root/storage/emulated/$USER_ID/Android/data/$TARGET_PKG' 2>&1" || true
  adb shell "stat -c '%d:%i %U:%G %a %n' '/data/media/$USER_ID/Android/data/$TARGET_PKG' 2>&1" || true
  adb shell "stat -c '%d:%i %U:%G %a %n' '/proc/$PROXY_PID/root/storage/emulated/$USER_ID/Android/obb/$TARGET_PKG' 2>&1" || true
  adb shell "stat -c '%d:%i %U:%G %a %n' '/data/media/$USER_ID/Android/obb/$TARGET_PKG' 2>&1" || true

  echo "-- can the proxy's own uid read it? (run-as the proxy, inside its namespace) --"
  # run-as cannot target a non-debuggable package, so this is best-effort; the uid check that
  # matters is whether the owning uid itself is refused, which the instrumented test covers.
  adb shell "cat '/proc/$PROXY_PID/root/storage/emulated/$USER_ID/Android/data/$TARGET_PKG/planted/save.dat' 2>&1" || true
  adb shell "cat '/proc/$PROXY_PID/root/storage/emulated/$USER_ID/Android/obb/$TARGET_PKG/main.1.com.example.planted.obb' 2>&1" || true

  echo "-- what the bridge's own root resolution produced --"
  adb shell content call --user "$USER_ID" --uri "content://$TARGET_PKG" --method ping 2>&1 | head -5 || true

  # The picture so far (run #13): no bind mount over Android/{data,obb} in the proxy's
  # namespace — only the plain FUSE mount at /storage/emulated — and ro.fuse.bpf.is_running=true.
  # Root can stat AND read the planted bytes through /proc/<pid>/root, i.e. the proxy's own
  # mount namespace resolves the path to a perfectly good, correctly-owned directory. Yet the
  # proxy's own listFiles() on that same path returns null for Android/data while working for
  # Android/obb. Root has CAP_DAC_OVERRIDE, so root succeeding does not prove the app uid can
  # open it; and the data/obb asymmetry has no DAC explanation (the proxy owns both).
  #
  # That leaves the FUSE daemon's per-path policy as the only thing that can differ between the
  # two roots, and it is decided in MediaProvider's Java layer. Its denials are logged at W by
  # "Invalid other package file access from <uid>" — but the default logcat buffer here never
  # showed them. So raise the priority and capture the MediaProvider/FUSE side explicitly while
  # re-triggering both listings.
  echo "-- MediaProvider/FUSE side: capture denials while re-listing both roots --"
  adb shell "logcat -c" 2>/dev/null || true
  MP_LOG=/tmp/mp-probe.log
  adb shell "logcat -v threadtime MediaProvider:V FuseDaemon:V FuseUtils:V MediaProviderForFuse:V *:S" > "$MP_LOG" 2>&1 &
  MP_PID=$!
  sleep 1
  # Re-trigger through the provider, which is the proxy process doing the File calls.
  adb shell content query --user "$USER_ID" --uri "content://$TARGET_PKG/data" 2>&1 | head -3 || true
  adb shell content query --user "$USER_ID" --uri "content://$TARGET_PKG/obb" 2>&1 | head -3 || true
  sleep 1
  kill "$MP_PID" 2>/dev/null || true
  wait "$MP_PID" 2>/dev/null || true
  echo "-- MediaProvider log for that window (filtered) --"
  grep -iE "Invalid other package|access|denied|Android/(data|obb)|$TARGET_PKG|uid" "$MP_LOG" 2>/dev/null | head -40 || cat "$MP_LOG" 2>/dev/null | head -40
  cp "$MP_LOG" premise-logs/mediaprovider-probe.log 2>/dev/null || true

  echo "-- errno: the instrumented diagnoseDataRootVisibility test reports it --"
  # A C probe would be the direct way to get errno, but there is no NDK on the runner and no
  # system compiler for a static Android binary, so the errno question is answered from inside
  # the app instead: BridgePremiseTest.diagnoseDataRootVisibility lists both roots through
  # java.io.File AND java.nio.file.Files, whose exception types distinguish AccessDenied from
  # NoSuchFile — exactly the distinction listFiles() discards. Its DIAG lines are in
  # premise-logs/instrument.log, printed below and grepped after the run.

  echo "-- does the app uid itself differ from root here? (no CAP_DAC_OVERRIDE) --"
  # If run-as works for the proxy (it is not debuggable, so this is expected to fail) we would
  # see the app's own view. Either way, record the attempt: it distinguishes "FUSE policy
  # refuses the uid" from "DAC refuses the uid".
  adb shell "run-as $TARGET_PKG ls -la . 2>&1 || true" | head -4 || true

  echo "-- full unfiltered logcat around the listing, for the record --"
  adb shell "logcat -d -v threadtime -t 200" 2>&1 | grep -iE "media|fuse|targetgame|EACCES|denied" | head -40 || true
fi

log "run the instrumented premise test as user $USER_ID"
await_framework "premise instrument" || exit 1
# --user is what puts the test process inside the secondary profile, so the app and the proxy
# share a uid space and the signature-level permission grant applies.
PREMISE_VERDICT=0
run_instrument_phase premise-logs/instrument.log \
  --user "$USER_ID" \
  -e targetPackage "$TARGET_PKG" \
  -e userId "$USER_ID" \
  -e expectPlanted "$EXPECT_PLANTED" \
  -e class dev.understudy.instrumented.BridgePremiseTest \
  "$APP_PKG.test/$TEST_RUNNER" || PREMISE_VERDICT=$?

log "premise instrument verdict=$PREMISE_VERDICT (0=pass, 1=test failures, 2=guest crash/no result)"
sleep 2
kill "$LOGCAT_PID" 2>/dev/null || true
wait "$LOGCAT_PID" 2>/dev/null || true

log "proxy-side logcat (bridge, provider, crashes)"
grep -iE "UnderstudyBridge|ProxyFileBridge|AndroidRuntime|FATAL|ActivityManager.*$TARGET_PKG|ContentProvider|SecurityException|FileNotFound" \
  premise-logs/logcat.log | head -60 || echo "(no matching logcat lines)"

log "probe: what can a plain uid-2000 adb shell see? (runbook feasibility, informational)"
# The rename-aside runbook the app generates assumes a plain `adb shell` (uid 2000) can mv
# inside /storage/emulated/<user>. Everything above ran as ROOT, and root was DENIED on the
# FUSE view of user 10 (run #11: `mkdir /storage/emulated/10` -> Permission denied), so the
# uid-2000 case has never actually been observed anywhere. Settle it with evidence: unroot,
# look, re-root. Purely informational — it must never change the suite's verdict, so every
# command is best-effort and timeboxed.
set +e
if [ "$HAVE_ROOT" = "1" ]; then
  timeout 60 adb unroot >/dev/null 2>&1
  timeout 120 adb wait-for-device
  sleep 3
  echo "-- identity now: $(adb shell id 2>/dev/null | head -1 | tr -d '\r') --"
  adb shell "ls -ld '/storage/emulated/$USER_ID' 2>&1"
  adb shell "ls -la '/storage/emulated/$USER_ID/Android/data/$TARGET_PKG' 2>&1 | head -8"
  adb shell "ls -la '/storage/emulated/$USER_ID/Android/data/$TARGET_PKG/planted' 2>&1 | head -5"
  adb shell "cat '/storage/emulated/$USER_ID/Android/data/$TARGET_PKG/planted/save.dat' 2>&1"
  adb shell "ls -ld '/data/media/$USER_ID' 2>&1"
  echo "-- restoring root --"
  timeout 60 adb root >/dev/null 2>&1
  timeout 120 adb wait-for-device
  sleep 3
  echo "-- identity restored: $(adb shell id 2>/dev/null | head -1 | tr -d '\r') --"
else
  echo "(no root in this run; shell was already uid 2000)"
  adb shell "ls -la '/storage/emulated/$USER_ID/Android/data/$TARGET_PKG' 2>&1 | head -8"
fi
set -e

log "SAF feasibility probe: can a shell grant a tree URI to the app? (informational)"
# HANDOFF §3.5: SAF writes are verified only against fakes because a persistable tree grant
# normally comes from the ACTION_OPEN_DOCUMENT_TREE picker — a human. If the shell could grant
# the same URI (root bypasses the grant check in ActivityManagerService), CI could instrument
# SafDestination without one. This probe settles which, if any, of the candidate commands
# exists on each API level; it changes nothing and must never affect the verdict. Everything
# is best-effort, timeboxed, and the outputs are the deliverable.
SAF_TREE_URI="content://com.android.externalstorage.documents/tree/primary%3ADocuments"
echo "-- candidate 1: content grant-uri-permission --"
timeout 30 adb shell content grant-uri-permission --user "$USER_ID" --uri "$SAF_TREE_URI" --permission 67 2>&1 | head -4 || true
echo "-- candidate 2: pm grant-uri-permission (positional) --"
timeout 30 adb shell pm grant-uri-permission "$APP_PKG" "$SAF_TREE_URI" 67 2>&1 | head -4 || true
echo "-- candidate 3: am broadcast with a URI grant (explicit, to our own receiver-less package) --"
timeout 30 adb shell am broadcast -a dev.understudy.action.SAF_GRANT_PROBE -p "$APP_PKG" \
  --user "$USER_ID" -d "$SAF_TREE_URI" --grant-read-uri-permission --grant-write-uri-permission 2>&1 | head -4 || true
echo "-- what grants does the app hold afterwards? --"
timeout 30 adb shell dumpsys activity uri-permissions 2>&1 | grep -A4 -B2 "$APP_PKG" | head -30 || true
echo "-- (end of SAF probe; a working candidate here would unlock instrumented SAF coverage) --"

log "diagnostic output from inside the app's own mount namespace"
# The test app's view. Note this is evidence about the RESTRICTION, not about the mechanism:
# the test app is a different uid from the proxy, so Android/data/<target> is supposed to be
# invisible to it. Printed whether or not the suite passed.
grep -hE "DIAG " premise-logs/instrument.log premise-logs/logcat.log 2>/dev/null \
  | sed -E 's/.*System\.out\( *[0-9]+\): //' | head -40 || echo "(no DIAG lines found)"

log "the PROXY's own report of its two roots (the evidence that matters)"
# Only the proxy can say whether it can read its own Android/data. This is the answer to the
# question runs #10-#15 kept circling: it reports both java.io.File and java.nio.file views, so
# AccessDeniedException and NoSuchFileException are distinguishable, and it creates a scratch
# directory to separate "the mount is broken" from "this entry pre-dates the process".
grep -hE "SELF-DIAG" premise-logs/instrument.log premise-logs/logcat.log 2>/dev/null \
  | sed -E 's/.*System\.out\( *[0-9]+\): //; s/.*UnderstudyBridge\( *[0-9]+\): //' \
  | head -60 || echo "(no SELF-DIAG lines found)"

# The verdict is the whitelist from instrument_verdict, not a failure grep: run #49 proved
# `am instrument` exits 0 through "INSTRUMENTATION_ABORTED: System has crashed." with no
# failure line in sight. A pass requires the runner's own "OK (N tests)".
if [ "$PREMISE_VERDICT" -ne 0 ]; then
  echo "!! premise suite verdict=$PREMISE_VERDICT (1 = test failures, 2 = guest crashed or produced no result)"
  grep -A20 -E "FAILURES!!!|Error in |INSTRUMENTATION_ABORTED" premise-logs/instrument.log | head -60 || true
  exit 1
fi

log "run the PRODUCTION caller-auth premise test as user $USER_ID"
await_framework "caller-auth instrument" || exit 1
# The FUSE premise above installs a proxy signed with the SAME key as the app, so it only ever
# exercises the signature-permission path. This phase installs one signed with a DIFFERENT key
# (a fresh per-install key) carrying the app's certificate digest — the production layout, where
# the app cannot sign the proxy with its own build-time key. A successful call proves the digest
# path works AND that BRIDGE is defined by the app (so the differently-signed caller can hold it);
# a permission SecurityException here means the proxy still defines BRIDGE and the platform gate
# fires before the proxy's own digest check ever runs.
# Restart logcat for this phase. The capture above was killed after the FUSE premise ran, and
# `CALLERAUTH-DIAG` is printed with System.out, which lands in logcat rather than in the
# `am instrument` transcript. Run #30 passed this phase and still reported
# "(no CALLERAUTH-DIAG lines found)" — so the diagnostics that exist precisely to explain a
# failure would have been absent in the one run where they mattered.
adb logcat -c 2>/dev/null || true
adb logcat -v time > premise-logs/callerauth-logcat.log 2>&1 &
CALLERAUTH_LOGCAT_PID=$!
sleep 1

CALLERAUTH_VERDICT=0
run_instrument_phase premise-logs/callerauth-instrument.log \
  --user "$USER_ID" \
  -e prodTargetPackage "$PRODSIGN_PKG" \
  -e userId "$USER_ID" \
  -e class dev.understudy.instrumented.CallerAuthPremiseTest \
  "$APP_PKG.test/$TEST_RUNNER" || CALLERAUTH_VERDICT=$?
log "caller-auth instrument verdict=$CALLERAUTH_VERDICT (0=pass, 1=test failures, 2=guest crash/no result)"
sleep 2
kill "$CALLERAUTH_LOGCAT_PID" 2>/dev/null || true
wait "$CALLERAUTH_LOGCAT_PID" 2>/dev/null || true

log "caller-auth diagnostics (which gate produced any failure)"
grep -hE "CALLERAUTH-DIAG|UnderstudyBridge" \
  premise-logs/callerauth-instrument.log premise-logs/callerauth-logcat.log 2>/dev/null \
  | sed -E 's/.*System\.out\( *[0-9]+\): //' | head -60 || echo "(no CALLERAUTH-DIAG lines found)"

log "confirm both proxies are still installed after the caller-auth phase"
# A phase that ends by breaking the other proxy would be a very confusing way to fail later.
adb shell pm list packages --user "$USER_ID" | grep -E "$TARGET_PKG|$PRODSIGN_PKG" || true

if [ "$CALLERAUTH_VERDICT" -ne 0 ]; then
  echo "!! production caller-auth verdict=$CALLERAUTH_VERDICT (1 = test failures, 2 = guest crashed or produced no result)"
  grep -A25 -E "FAILURES!!!|Error in |INSTRUMENTATION_ABORTED" premise-logs/callerauth-instrument.log | head -70 || true
  echo "!! CALLER-AUTH FAILED: the app could not reach a proxy signed with a different key that"
  echo "!!   carries the app's certificate digest. In production that is every bridge call."
  exit 1
fi
echo "CALLER-AUTH VERIFIED on API $API: the app reached a proxy signed with a DIFFERENT key"
echo "  ($PRODSIGN_PKG) via the generator-certificate digest path."

# --------------------------------------------------------------------------------------------
# The in-app install path (HANDOFF §3.4). Everything above installed with `adb install --user`,
# which proves the ARTIFACTS install; nothing before this point proved the APP can install —
# the PackageInstaller session flow, the per-user REQUEST_INSTALL_PACKAGES appop, the
# commit→broadcast round trip, and the bridge answering afterwards. The instrumented test does
# all of it with the production classes; the shell's only job is the appop grant, which on a
# real device is the user toggling "install unknown apps" in this profile's own Settings.
# RUN_INSTALLER_PHASE=0 skips it — used on the API 37 job, which is still fighting for a
# stable boot and should not grow new phases until it has survived its existing ones.
# --------------------------------------------------------------------------------------------
if [ "${RUN_INSTALLER_PHASE:-1}" = "1" ]; then
  INSTALLER_PKG="${INSTALLER_PKG:-com.example.installtarget}"
  log "grant REQUEST_INSTALL_PACKAGES to the app for user $USER_ID (per-user appop)"
  # appops is per-user and this grant is exactly what the Settings toggle writes. Shell can set
  # it without root; on builds where it cannot, the test's canRequestInstalls() assertion names
  # the cause rather than failing mysteriously at commit.
  adb_fw appops set --user "$USER_ID" "$APP_PKG" REQUEST_INSTALL_PACKAGES allow || true
  adb_fw appops get --user "$USER_ID" "$APP_PKG" REQUEST_INSTALL_PACKAGES || true

  # The confirmation dialog is UI, and UI only draws for the CURRENT user: run #51's logcat is
  # the evidence — "W/ActivityTaskManager: Can't resume non-current user …
  # com.android.packageinstaller/.InstallStart", launched with BAL_ALLOW_PERMISSION, task
  # created, isVisible=false forever, and UiAutomator correctly found no Install button on a
  # dialog that was never allowed on screen. So: switch the profile to the foreground for this
  # phase only, and switch back afterwards — the FUSE/caller-auth phases above and the teardown
  # below keep running against a background user, the harder case they have always tested.
  log "switch user $USER_ID to the foreground (the confirmation dialog needs a current user)"
  adb shell am switch-user "$USER_ID" 2>&1 | head -3 || true
  for i in $(seq 1 20); do
    cur="$(adb shell am get-current-user 2>/dev/null | tr -d '\r' || true)"
    [ "$cur" = "$USER_ID" ] && break
    sleep 2
  done
  echo "current user: $(adb shell am get-current-user 2>&1 | tr -d '\r' || true)"

  log "run the installer-session premise test as user $USER_ID"
  adb logcat -c 2>/dev/null || true
  adb logcat -v time > premise-logs/installer-logcat.log 2>&1 &
  INSTALLER_LOGCAT_PID=$!
  sleep 1
  INSTALLER_VERDICT=0
  run_instrument_phase premise-logs/installer-instrument.log \
    --user "$USER_ID" \
    -e installerTargetPackage "$INSTALLER_PKG" \
    -e userId "$USER_ID" \
    -e class dev.understudy.instrumented.InstallerSessionPremiseTest \
    "$APP_PKG.test/$TEST_RUNNER" || INSTALLER_VERDICT=$?
  log "installer instrument verdict=$INSTALLER_VERDICT (0=pass, 1=test failures, 2=guest crash/no result)"
  sleep 2
  kill "$INSTALLER_LOGCAT_PID" 2>/dev/null || true
  wait "$INSTALLER_LOGCAT_PID" 2>/dev/null || true

  log "installer diagnostics"
  grep -hE "INSTALLER-DIAG|InstallResult|PackageInstaller" \
    premise-logs/installer-instrument.log premise-logs/installer-logcat.log 2>/dev/null \
    | sed -E 's/.*System\.out\( *[0-9]+\): //' | head -40 || echo "(no INSTALLER-DIAG lines found)"

  if [ "$INSTALLER_VERDICT" -ne 0 ]; then
    echo "!! installer-session verdict=$INSTALLER_VERDICT (1 = test failures, 2 = guest crashed or produced no result)"
    grep -A25 -E "FAILURES!!!|Error in |INSTRUMENTATION_ABORTED" premise-logs/installer-instrument.log | head -70 || true
    echo "!! INSTALLER-SESSION FAILED: the app could not install its own proxy through a"
    echo "!!   PackageInstaller session in this profile. This is the production install path."
    exit 1
  fi
  echo "INSTALLER-SESSION VERIFIED on API $API: the app staged, committed and received the"
  echo "  result of its own proxy install ($INSTALLER_PKG) for user $USER_ID, and the bridge"
  echo "  answered through the session-installed proxy."

  log "switch back to user 0 (leave teardown the background-user state it expects)"
  adb shell am switch-user 0 2>&1 | head -3 || true
  sleep 2
else
  echo "-- installer-session phase SKIPPED (RUN_INSTALLER_PHASE=${RUN_INSTALLER_PHASE:-1}) --"
fi

log "teardown: uninstall the proxy KEEPING its data"
await_framework "teardown" || exit 1
# The data-preserving teardown the app cannot do unprivileged. Confirms `-k` really does leave
# Android/data behind, which is what the app's teardown strategy depends on.
adb shell pm uninstall -k --user "$USER_ID" "$TARGET_PKG" 2>&1 || true
if [ "$HAVE_ROOT" = "1" ]; then
  # Verify on the RAW path: root gets EACCES on the FUSE view of another user (run #11 proved
  # it), so checking $DATA_DIR here would report a false "-k did not preserve it".
  adb shell "ls -la '$RAW_DATA' 2>&1 || echo '  (data dir gone — -k did NOT preserve it!)'" \
    | tee premise-logs/after-uninstall.log

  if adb shell "[ -d '$RAW_DATA/planted' ]" 2>/dev/null; then
    echo "OK: 'pm uninstall -k' preserved the data directory"
    adb shell "ls -lan '$RAW_DATA/planted'; cat '$RAW_DATA/planted/save.dat'; echo" || true
  else
    echo "!! 'pm uninstall -k' did NOT preserve $RAW_DATA/planted"
    exit 1
  fi
else
  echo "-- NOT asserting that -k preserved the data: the only place to look is $RAW_DATA,"
  echo "   which needs root, and the proxy that could have answered for it is now uninstalled."
  echo "   Claiming it was preserved here would be exactly the kind of unfalsifiable pass"
  echo "   this suite exists to avoid. The rooted API 34/35 jobs assert it."
fi

log "remove the test user"
# The installer-phase proxy has served its purpose; remove it explicitly so a failed
# remove-user does not leave a third package lingering on a snapshot someone reuses.
if [ "${RUN_INSTALLER_PHASE:-1}" = "1" ]; then
  adb shell pm uninstall --user "$USER_ID" "${INSTALLER_PKG:-com.example.installtarget}" 2>&1 || true
fi
adb shell pm remove-user "$USER_ID" 2>&1 || true

echo
if [ "$EXPECT_PLANTED" = "true" ]; then
  echo "PREMISE VERIFIED on API $API: a proxy installed for user $USER_ID could read and write"
  echo "  $DATA_DIR"
  echo "  $OBB_DIR"
  echo "including data that pre-dated the proxy, and 'pm uninstall -k' preserved it afterwards."
else
  echo "PREMISE VERIFIED (REDUCED, no root) on API $API: a proxy installed for user $USER_ID"
  echo "could read and write"
  echo "  $DATA_DIR"
  echo "  $OBB_DIR"
  echo "NOT verified here: reading data that pre-dates the proxy, and -k preservation."
  echo "Both need root and remain covered by the rooted API 34/35 jobs."
fi
