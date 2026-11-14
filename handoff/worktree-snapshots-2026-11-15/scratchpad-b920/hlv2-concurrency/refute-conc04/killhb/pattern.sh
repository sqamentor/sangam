# The README.md:106-107 heartbeat pattern with the heartbeat command replaced by a ticker (no lock involved).
# MSYS child binaries (sleep, ls) do not start in this environment, so `sleep N` is a native python sleeper; the
# structure (background subshell loop, HB=$!, kill $HB) is the README's.
D="$(cd "$(dirname "$0")" && pwd)"
LOG="$D/log"
pysleep() { python -I -c "import time,sys; time.sleep(float(sys.argv[1]))" "$1"; }
stamp() { python -I -c "import time; print(f'{time.time():.2f}')"; }
: > "$LOG"
( while pysleep 1; do python -I "$D/tick.py" "$LOG"; done ) & HB=$!
pysleep 2.6                     # the subshell is now running its first python "heartbeat"
echo "$(stamp) main: kill \$HB ($HB); a release in the trap would run now" >> "$LOG"
kill $HB 2>/dev/null
echo "$(stamp) main: kill rc=$?" >> "$LOG"
pysleep 4.5
cat "$LOG"
