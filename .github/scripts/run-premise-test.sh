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

plant() {
  local base="$1"
  adb shell "
    mkdir -p '$base/Android/data/$TARGET_PKG/planted' || exit 1
    mkdir -p '$base/Android/obb/$TARGET_PKG' || exit 1
    echo -n '$PAYLOAD' > '$base/Android/data/$TARGET_PKG/planted/save.dat' || exit 1
    echo -n '$PAYLOAD' > '$base/Android/obb/$TARGET_PKG/main.1.com.example.planted.obb' || exit 1
    ls -laR '$base/Android/data/$TARGET_PKG' '$base/Android/obb/$TARGET_PKG'
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
# Run as the app's own uid in user 0 for a control: this is the failure mode the whole project
# works around. Expected to fail; `|| true` keeps the script going.
adb shell "run-as $APP_PKG ls '$PLANTED_BASE/Android/data/$TARGET_PKG/planted' 2>&1 || true" | head -5 || true

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
adb shell "ls -la '$DATA_DIR' 2>&1 || echo '  (data dir gone — -k did NOT preserve it!)'" \
  | tee premise-logs/after-uninstall.log

if adb shell "[ -d '$DATA_DIR/planted' ]" 2>/dev/null; then
  echo "OK: 'pm uninstall -k' preserved the data directory"
else
  echo "!! 'pm uninstall -k' did NOT preserve $DATA_DIR/planted"
  exit 1
fi

log "remove the test user"
adb shell pm remove-user "$USER_ID" 2>&1 || true

echo
echo "PREMISE VERIFIED on API $API: a proxy installed for user $USER_ID could read and write"
echo "  $DATA_DIR"
echo "  $OBB_DIR"
echo "and 'pm uninstall -k' preserved the data afterwards."
