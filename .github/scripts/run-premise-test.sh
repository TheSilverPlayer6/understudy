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
APP_PKG="${APP_PKG:-dev.understudy.debug}"
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
log "adb root (best effort)"
# Root decides how we can plant fixture bytes. On a userdebug/eng image (`target: default`, i.e.
# AOSP) `adb root` succeeds and we can write to /data/media/<user> — the *raw* storage that sits
# underneath the FUSE view. On a production-signed google_apis image it fails, and uid 2000 has
# no access to another user's emulated storage at all, which is what produced
# "mkdir: '/storage/emulated/10': Permission denied".
if adb root >/dev/null 2>&1; then
  adb wait-for-device
  HAVE_ROOT=1
  echo "adb root succeeded: uid=$(adb shell id -u 2>/dev/null || echo '?')"
else
  HAVE_ROOT=0
  adb wait-for-device
  echo "adb root unavailable (production-signed image); planting will need a fallback"
fi
# RAW_BASE is computed after the user exists; see the planting step.

log "create the secondary user profile"
CREATE_OUT="$(adb shell pm create-user "$USER_NAME" 2>&1)"
echo "$CREATE_OUT"
USER_ID="$(printf '%s' "$CREATE_OUT" | grep -oE 'id [0-9]+' | grep -oE '[0-9]+' | head -1)"
if [ -z "${USER_ID:-}" ]; then
  echo "!! could not parse a user id from: $CREATE_OUT"
  adb shell pm list users
  exit 1
fi
echo "created user id $USER_ID"
# The raw storage path for this user, i.e. what the FUSE mount at /storage/emulated/$USER_ID
# is backed by. This is where the rename-aside trick really operates.
RAW_BASE="/data/media/$USER_ID"

log "start the new profile"
# switch-user brings it to the foreground, which is how the app will normally be used. We also
# want the *unstarted* case to work, so the bridge is exercised after a start-user as well.
adb shell am start-user "$USER_ID" 2>&1 || true
sleep 5
adb shell pm list users

log "install the proxy APK for user $USER_ID"
adb install --user "$USER_ID" -r /tmp/proxy.apk 2>&1 | tee premise-logs/install-proxy.log
adb shell pm list packages --user "$USER_ID" | grep -F "$TARGET_PKG" \
  || { echo "!! proxy is not installed for user $USER_ID"; exit 1; }

log "install the app under test for user $USER_ID"
APP_APK="$(find app/build/outputs/apk/debug -name '*.apk' | head -1)"
TEST_APK="$(find app/build/outputs/apk/androidTest/debug -name '*.apk' | head -1)"
echo "app=$APP_APK"; echo "test=$TEST_APK"
adb install --user "$USER_ID" -r -t "$APP_APK" 2>&1 | tee premise-logs/install-app.log
adb install --user "$USER_ID" -r -t "$TEST_APK" 2>&1 | tee premise-logs/install-test.log

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
  echo "-- trying the FUSE view (/storage/emulated/$USER_ID) --"
  if plant "/storage/emulated/$USER_ID"; then PLANTED_BASE="/storage/emulated/$USER_ID"; fi
fi

if [ -z "$PLANTED_BASE" ]; then
  echo "!! could not plant fixtures by either route."
  echo "   This is an environment limitation, not a product failure: without root, uid 2000"
  echo "   cannot reach another user's emulated storage. Re-run with target: default (AOSP)."
  adb shell id
  adb shell ls -ld /storage/emulated/$USER_ID /data/media/$USER_ID 2>&1 || true
  exit 1
fi
echo "planted under $PLANTED_BASE"

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
PROXY_PID="$(adb shell pidof "$TARGET_PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}')"
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
fi

log "run the instrumented premise test as user $USER_ID"
# --user is what puts the test process inside the secondary profile, so the app and the proxy
# share a uid space and the signature-level permission grant applies.
set +e
adb shell am instrument -w --user "$USER_ID" \
  -e targetPackage "$TARGET_PKG" \
  -e userId "$USER_ID" \
  -e expectPlanted true \
  -e class dev.understudy.instrumented.BridgePremiseTest \
  "$APP_PKG.test/$TEST_RUNNER" 2>&1 | tee premise-logs/instrument.log
INSTRUMENT_EXIT="${PIPESTATUS[0]}"
set -e

log "instrument exit=$INSTRUMENT_EXIT"
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

# `am instrument` returns 0 even when tests fail; the authoritative signal is in the output.
if grep -qE "FAILURES!!!|Error in |INSTRUMENTATION_FAILED" premise-logs/instrument.log; then
  echo "!! instrumented tests reported failures"
  grep -A20 -E "FAILURES!!!|Error in " premise-logs/instrument.log | head -60 || true
  exit 1
fi
if [ "$INSTRUMENT_EXIT" -ne 0 ]; then
  echo "!! am instrument exited $INSTRUMENT_EXIT"
  exit "$INSTRUMENT_EXIT"
fi

log "teardown: uninstall the proxy KEEPING its data"
# The data-preserving teardown the app cannot do unprivileged. Confirms `-k` really does leave
# Android/data behind, which is what the app's teardown strategy depends on.
adb shell pm uninstall -k --user "$USER_ID" "$TARGET_PKG" 2>&1 || true
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

log "remove the test user"
adb shell pm remove-user "$USER_ID" 2>&1 || true

echo
echo "PREMISE VERIFIED on API $API: a proxy installed for user $USER_ID could read and write"
echo "  $DATA_DIR"
echo "  $OBB_DIR"
echo "and 'pm uninstall -k' preserved the data afterwards."
