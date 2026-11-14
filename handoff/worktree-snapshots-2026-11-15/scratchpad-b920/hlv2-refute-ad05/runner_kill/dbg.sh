d="/c/Users/Lokendra Singh/AppData/Local/Temp/claude/b920001b93f0ae3b/6e98c2a7-cdac-4783-9098-832546572812/scratchpad/hlv2-refute-ad05/runner_kill"
rm -rf "$d/bash-markers"; mkdir -p "$d/bash-markers"
echo "t0 $(date +%s.%N)"
( echo "sub start $(date +%s.%N)"; python -I "$d/slow_hb.py" "$d/bash-markers"; echo "A rc=$? at $(date +%s.%N)" ) > "$d/bg.log" 2>&1 &
p=$!
sleep 1.5; echo "t1.5 $(date +%s.%N) jobs: $(jobs -l)"; ls -la "$d/bash-markers"
wait $p; echo "waited $(date +%s.%N)"; cat "$d/bg.log"; ls "$d/bash-markers"; rm -f "$d/bg.log"
