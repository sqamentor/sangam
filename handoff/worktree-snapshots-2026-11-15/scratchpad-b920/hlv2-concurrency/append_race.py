"""Do concurrent appends through open(path, 'a') -- the way _append_served writes the audit line -- lose lines on
Windows? Writes only to a file in this folder."""
import os
import subprocess
import sys

BASE = os.path.dirname(os.path.abspath(__file__))
LOG = os.path.join(BASE, "append_race.log")
if len(sys.argv) > 1:
    tag = sys.argv[1]
    for i in range(3000):
        with open(LOG, "a", encoding="utf-8") as log:      # same pattern as shared_lock._append_served
            log.write(f"{tag} {i:05d} auto-released stale lock owner=x pid=1 since=y reason=dead-holder+expired-lease\n")
    sys.exit(0)
if os.path.exists(LOG):
    os.remove(LOG)
procs = [subprocess.Popen([sys.executable, "-I", __file__, f"w{k}"]) for k in range(6)]
for p in procs:
    p.wait()
with open(LOG, encoding="utf-8", errors="replace") as f:
    lines = f.read().splitlines()
import re
pat = re.compile(r"^w\d \d{5} auto-released stale lock owner=x pid=1 since=y reason=dead-holder\+expired-lease$")
good = [l for l in lines if pat.match(l)]
print(f"expected {6 * 3000} lines; file has {len(lines)} lines, {len(good)} intact, {len(lines) - len(good)} damaged, "
      f"{6 * 3000 - len(good)} audit lines lost or damaged")
