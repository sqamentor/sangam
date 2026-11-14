"""Read-only. Age profile of the running processes the copied v2 probe calls 'unknown' versus all processes: how many
unopenable processes were born after boot (i.e. took a pid that could have been a dead holder's)?"""
import collections
import importlib.util
import os
import time

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


sl = load("shared_lock", os.path.join(BASE, "code", "shared_lock.py"))
nq = load("ntqsi", os.path.join(BASE, "scripts", "ntqsi.py"))
procs = nq.snapshot()
now = time.time()
boot = min(s for p, (s, n, pp) in procs.items() if s and p not in (0,))
print(f"earliest process start (boot): {sl.iso(boot)}  ({(now - boot) / 3600:.1f} h ago)")
buckets = [("<10 min", 600), ("<1 h", 3600), ("<6 h", 6 * 3600), ("<24 h", 86400), (">=24 h", float("inf"))]
cnt = {"unknown": collections.Counter(), "openable": collections.Counter()}
late_unknown = collections.Counter()
for pid, (start, name, _pp) in procs.items():
    if pid == 0 or start is None:
        continue
    alive, _, _ = sl.probe_process(pid)
    kind = "unknown" if alive is None else "openable"
    age = now - start
    for label, lim in buckets:
        if age < lim:
            cnt[kind][label] += 1
            break
    if kind == "unknown" and start - boot > 600:      # born more than 10 min after boot
        late_unknown[name] += 1
for kind in cnt:
    print(kind, [(label, cnt[kind][label]) for label, _ in buckets])
print("unknown processes born > 10 min after boot, by name:", late_unknown.most_common(30),
      "total", sum(late_unknown.values()))
