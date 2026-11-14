#!/bin/bash
# README bash runner pattern, verbatim except: lease 3 s and no heartbeat loop (stands in for a heartbeat gap,
# e.g. sleep/hibernate), and a 10 s "heavy step".
LOCK="$1"; MODE="$2"
if [ "$MODE" = winpid ]; then P=$(cat /proc/$$/winpid); else P=$$; fi
echo "job: \$\$=$$ winpid=$(cat /proc/$$/winpid) passing --pid $P"
python -I "$LOCK" acquire sangam "Trial 2 retest" --pid $P --lease 3 || exit 1
echo "job: heavy step starts $(date +%T)"
sleep 10
echo "job: heavy step ends $(date +%T); lock owner now: $(python -I "$LOCK" status | head -1 | cut -c1-60)"
python -I "$LOCK" release sangam
