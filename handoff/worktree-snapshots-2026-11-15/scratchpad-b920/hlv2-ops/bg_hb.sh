#!/usr/bin/env bash
# Test-only: the README bash recipe shape -- heartbeat subshell + trap ... EXIT -- with a 1 s "beat" that only writes a file.
export PATH="/usr/bin:/bin:$PATH"
OUT="$1"
( while sleep 1; do date +%T >> "$OUT"; done ) & HB=$!
trap 'kill $HB 2>/dev/null' EXIT
echo "parent=$$ hb=$HB" >> "$OUT.parent"
sleep 300
