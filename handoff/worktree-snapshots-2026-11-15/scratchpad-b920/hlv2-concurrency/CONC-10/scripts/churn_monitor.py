"""Read-only. For DURATION seconds, watch every process birth/exit on this machine (NtQuerySystemInformation every
0.25 s). For each new process: does the COPIED v2 probe call it 'unknown' (cannot open)? Was its pid freed by an
exited process during the window (an observed pid reuse)? Writes only stdout."""
import collections
import importlib.util
import os
import sys
import time

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


sl = load("shared_lock", os.path.join(BASE, "code", "shared_lock.py"))
nq = load("ntqsi", os.path.join(BASE, "scripts", "ntqsi.py"))
DURATION = float(sys.argv[1]) if len(sys.argv) > 1 else 300

t0 = time.time()
prev, prev_tids = nq.snapshot(with_threads=True)
freed = {}          # pid -> (time freed, name of the process that exited)
births = []         # dicts
by_key = {}
thread_reuse = 0
while time.time() - t0 < DURATION:
    time.sleep(0.25)
    cur, tids = nq.snapshot(with_threads=True)
    now = time.time()
    for pid, (start, name, _pp) in prev.items():
        c = cur.get(pid)
        if c is None or c[0] != start:
            freed[pid] = (now, name)
            b = by_key.get((pid, start))
            if b is not None:
                b["exited"] = now
    for pid in list(freed):
        if pid in tids and pid not in prev_tids:
            thread_reuse += 1           # a freed process id came back as a thread id ('no such process' to the probe)
            del freed[pid]
    for pid, (start, name, ppid) in cur.items():
        p = prev.get(pid)
        if p is None or p[0] != start:
            alive, pstart, detail = sl.probe_process(pid)
            b = {"pid": pid, "name": name, "start": start, "seen": now, "unknown": alive is None, "detail": detail,
                 "reuse_of": freed.pop(pid, None), "exited": None}
            births.append(b)
            by_key[(pid, start)] = b
    prev, prev_tids = cur, tids

end = time.time()
n = len(births)
unk = [b for b in births if b["unknown"]]
print(f"window {end - t0:.0f} s: {n} process births, {len(unk)} of them 'unknown' to the copied v2 probe "
      f"({100.0 * len(unk) / max(n, 1):.1f} %)")
print("births by name (top 20):", collections.Counter(b["name"] for b in births).most_common(20))
print("'unknown' births:", [(b["name"], b["pid"], b["detail"], "alive at end" if b["exited"] is None
                             else f"lived {b['exited'] - b['seen']:.0f}s") for b in unk])
reused = [b for b in births if b["reuse_of"]]
print(f"observed pid reuses (pid freed by an exit inside the window, then given to a new process): {len(reused)}; "
      f"'unknown' among them: {sum(1 for b in reused if b['unknown'])}")
for b in reused[:40]:
    t_free, old = b["reuse_of"]
    print(f"   pid {b['pid']}: {old} exited, {b['name']} got the pid {b['seen'] - t_free:.1f}s later; "
          f"unknown={b['unknown']}")
print(f"freed process ids seen coming back as thread ids: {thread_reuse}; freed pids not reused by the end: {len(freed)}")
