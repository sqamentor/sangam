#!/bin/bash
# README.md:105-107 bash pattern; differences: beat $BEAT s (README 600), --lease $LEASE (README default 1800),
# heartbeat output appended to beats.log instead of /dev/null (to count beats), heavy step = sleep 6001.
export PATH="/usr/bin:$PATH"
# On this machine /usr/bin/sleep exits 127 (STATUS_DLL_INIT_FAILED: no exploit-mitigation override for sleep.exe),
# so a working sleep is substituted; the README loop lines below are otherwise unchanged.
sleep() { python -I -c "import sys, time; time.sleep(float(sys.argv[1]))" "$1"; }
LOCK="$1"; BEAT="$2"; LEASE="$3"; OUT="$4"; PIDMODE="$5"
WINPID=$(cat /proc/$$/winpid)
if [ "$PIDMODE" = winpid ]; then HP=$WINPID; else HP=$$; fi
echo "job_msys_pid=$$ job_winpid=$WINPID holder_pid=$HP" > "$OUT/job.info"
python -I "$LOCK" acquire sangam "Trial 2 retest" --pid $HP --lease $LEASE >> "$OUT/acquire.log" 2>&1 || exit 1
( while sleep $BEAT; do python -I "$LOCK" heartbeat sangam >> "$OUT/beats.log" 2>&1; done ) & HB=$!
echo "hb_msys_pid=$HB hb_winpid=$(cat /proc/$HB/winpid)" >> "$OUT/job.info"
trap 'kill $HB 2>/dev/null; python -I "$LOCK" release sangam >> "$OUT/trap.log" 2>&1' EXIT
sleep 6001
