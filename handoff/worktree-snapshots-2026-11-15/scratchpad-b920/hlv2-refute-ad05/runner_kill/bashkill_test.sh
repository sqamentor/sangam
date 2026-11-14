#!/usr/bin/env bash
# README bash runner (lines 104-108): ( while sleep 600; do python heartbeat; done ) & HB=$!; trap 'kill $HB; release' EXIT
# Question: when a python heartbeat is in flight inside the subshell, does `kill $HB` stop it before release runs?
# Builtins only for checks ($EPOCHREALTIME, glob + [ -e ]); slow_hb.py records its own start/done times.
here="/c/Users/Lokendra Singh/AppData/Local/Temp/claude/b920001b93f0ae3b/6e98c2a7-cdac-4783-9098-832546572812/scratchpad/hlv2-refute-ad05/runner_kill"
d="$here/bash-markers"
rm -rf "$d"; mkdir -p "$d"
( while sleep 0.2; do python -I "$here/slow_hb.py" "$d" >/dev/null; done ) & HB=$!
start=""; i=0
while [ $i -lt 600 ]; do
  for f in "$d"/start-*; do [ -e "$f" ] && start="$f"; done
  [ -n "$start" ] && break
  sleep 0.05; i=$((i+1))
done
[ -z "$start" ] && { echo "no heartbeat started"; kill $HB; exit 1; }
winpid="${start##*start-}"
sleep 0.3
kill_t=$EPOCHREALTIME
kill $HB 2>/dev/null
echo "bash $BASH_VERSION: in-flight python pid $winpid started at $(cat "$start"); subshell killed at $kill_t"
sleep 4.5
if [ -e "$d/done-$winpid" ]; then
  echo "done marker written at $(cat "$d/done-$winpid") -> the in-flight heartbeat kept running after 'kill \$HB' (release would run in between)"
else
  echo "no done marker -> the in-flight heartbeat was stopped by 'kill \$HB'"
fi
