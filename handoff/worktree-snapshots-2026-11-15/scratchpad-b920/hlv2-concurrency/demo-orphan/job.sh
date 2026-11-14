#!/bin/bash
# README bash pattern shape: background heartbeat subshell + EXIT trap. The "heartbeat" appends a line every second.
OUT="$1"
nap() { python -I -c "import time,sys; time.sleep(float(sys.argv[1]))" "$1"; }
( while nap 1; do echo beat >> "$OUT"; done ) & HB=$!
trap 'kill $HB 2>/dev/null' EXIT
cat /proc/$$/winpid > "$OUT.pid"
nap 600
