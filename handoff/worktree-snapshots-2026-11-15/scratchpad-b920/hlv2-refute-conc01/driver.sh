#!/bin/bash
# usage: driver.sh <codedir (mixed path)> <lockdir (mixed path)> <msys|winpid>
# Runs job A (job.sh) and, 1.5 s later, a waiter B from another owner; prints both transcripts and the served log.
sleep() { python -I -c "import sys,time; time.sleep(float(sys.argv[1]))" "$1"; }
ts() { echo "${EPOCHREALTIME%???}"; }
CODE="$1"; LOCKDIR="$2"; MODE="$3"; HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$LOCKDIR"
export SHARED_LOCK_DIR="$LOCKDIR" SHARED_LOCK_POLL_SECONDS=0.5
LOCK="$CODE/heavy_lock.py"
echo "T0 $(ts)"
bash "$HERE/job.sh" "$LOCK" "$MODE" > "$LOCKDIR/A.out" 2>&1 & JOB=$!
sleep 1.5
echo "B: waiting from $(ts)" > "$LOCKDIR/B.out"
python -I "$LOCK" acquire ifms "B heavy step" --pid "$(cat /proc/$$/winpid)" --lease 60 >> "$LOCKDIR/B.out" 2>&1
echo "B: acquired, heavy step B starts $(ts)" >> "$LOCKDIR/B.out"
python -I "$LOCK" status 2>&1 | head -2 >> "$LOCKDIR/B.out"
wait $JOB
python -I "$LOCK" release ifms >> "$LOCKDIR/B.out" 2>&1
echo "=== job A ($MODE)"; cat "$LOCKDIR/A.out"
echo "=== waiter B"; cat "$LOCKDIR/B.out"
echo "=== served log"; cat "$LOCKDIR/heavy-build.lock.served.log"
