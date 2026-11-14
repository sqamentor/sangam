"""Inaccessible (v2 probe alive=None) processes alive right now, by creation time relative to boot.
Shows how many long-lived inaccessible processes appear after boot (candidates to sit on a reused holder pid)."""
import collections
import importlib.util
import os
import time

BASE = os.path.dirname(os.path.abspath(__file__))


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


sl = load("shared_lock_copy", os.path.join(BASE, "code", "shared_lock.py"))
nt = load("ntproc", os.path.join(BASE, "ntproc.py"))
table = nt.nt_table()
now = time.time()
boot = min(c for c, _, _ in table.values() if c)
print(f"boot (earliest process creation): {sl.iso(boot)}; uptime {(now - boot) / 3600:.2f} h; {len(table)} processes")
late = []
for pid, (create, image, session) in table.items():
    if pid in (0, 4) or not create:
        continue
    if sl.probe_process(pid)[0] is None and create - boot > 600:
        late.append((create, pid, image, session))
late.sort()
print(f"inaccessible processes created more than 10 min after boot and still alive: {len(late)}")
per_hour = collections.Counter(int((c - boot) // 3600) for c, *_ in late)
print("   by hour after boot:", dict(sorted(per_hour.items())))
for create, pid, image, session in late:
    print(f"   +{(create - boot) / 60:6.1f} min  age {(now - create) / 60:6.1f} min  {image}:{pid} session {session}")
