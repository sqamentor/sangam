#!/usr/bin/bash
# Review-only copy of the README.md:104-108 bash recipe (HLV2-AD-04 refutation), against a COPY of heavy_lock v2 and a temp
# lock folder. Differences from the README, test plumbing only: SHARED_LOCK_DIR = temp folder, beat 1 s instead of 600 s,
# --lease 3, owner name, the runner and the loop record their Windows pids, sleep stands in for the heavy step.
export SHARED_LOCK_DIR="$1"; export SHARED_LOCK_POLL_SECONDS=0.2      # TEST ONLY
LOCK="$2"
case "$SHARED_LOCK_DIR$LOCK" in *ff9f2caf*) echo "refusing the live lock"; exit 9;; esac
/usr/bin/cat /proc/$$/winpid > "$SHARED_LOCK_DIR/runner.winpid"
# /usr/bin/sleep exits 127 in this Git install (environment), so a python sleep stands in for `sleep` (test plumbing)
sleep() { python -I -c "import time; time.sleep($1)"; }
PID_ARG=$$; [ -n "$USE_WINPID" ] && PID_ARG=$(/usr/bin/cat /proc/$$/winpid)   # USE_WINPID=1 isolates the loop question
python -I "$LOCK" acquire readme-owner "Trial 2 retest" --pid $PID_ARG --lease ${LEASE:-3} 2> "$SHARED_LOCK_DIR/runner.acquire.err" || exit 1
( /usr/bin/cat /proc/$BASHPID/winpid > "$SHARED_LOCK_DIR/hb.winpid"; while sleep 1; do python -I "$LOCK" heartbeat readme-owner >/dev/null; done ) & HB=$!
trap 'kill $HB 2>/dev/null; python -I "$LOCK" release readme-owner' EXIT
sleep ${STEP:-300}   # ... heavy step ...
