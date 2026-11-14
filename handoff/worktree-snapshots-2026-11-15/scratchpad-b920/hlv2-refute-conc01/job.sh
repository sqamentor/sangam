#!/bin/bash
# Job A: the README:105-108 bash runner pattern verbatim, except the timings: --lease 3 and a heartbeat every 8 s
# (instead of the default 1800 s lease and 600 s beat), so one heartbeat interval outlasts the lease. That gap stands
# in for a sleep/hibernate or stalled heartbeat loop while the job keeps running. Instrumentation lines start "A:".
# In this sandbox /usr/bin/sleep and /usr/bin/date exit 127, so sleep is emulated with python and times use bash's
# $EPOCHREALTIME (ms).
sleep() { python -I -c "import sys,time; time.sleep(float(sys.argv[1]))" "$1"; }
ts() { echo "${EPOCHREALTIME%???}"; }
LOCK="$1"; MODE="$2"
if [ "$MODE" = winpid ]; then P=$(cat /proc/$$/winpid); else P=$$; fi
echo "A: \$\$=$$ winpid=$(cat /proc/$$/winpid) passing --pid $P"
python -I "$LOCK" acquire sangam "Trial 2 retest" --pid $P --lease 3 || exit 1
( while sleep 8; do python -I "$LOCK" heartbeat sangam >/dev/null; echo "A: heartbeat rc=$? at $(ts)"; done ) & HB=$!
trap 'kill $HB 2>/dev/null; python -I "$LOCK" release sangam; echo "A: release rc=$? at $(ts)"' EXIT
echo "A: heavy step starts $(ts)"
sleep 14
echo "A: heavy step ends $(ts)"
