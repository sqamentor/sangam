"""Order-of-magnitude timing of the two windows CONC-08 needs, on this machine, in a temp folder.
window 1: after the oldest-ticket snapshot (shared_lock.py:334) up to the os.replace call (:340)
window 2: lock path empty, from os.replace returning (:340) to the put-back rename (:355)"""
import importlib.util
import json
import os
import statistics
import sys
import time

ROOT = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("shared_lock", os.path.join(ROOT, "code", "shared_lock.py"))
sl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sl)
d = os.path.join(ROOT, "locks-timing")
os.makedirs(d, exist_ok=True)
assert "ff9f2caf" not in d
lock = os.path.join(d, "heavy-build.lock")
rec_y = {"owner": "Y", "what": "job Y", "since": "x", "epoch": time.time(), "holder_pid": 1}
rec_a = {"owner": "A", "what": "job A", "since": "y", "epoch": 1.0, "holder_pid": 2}
w1, w2 = [], []
for i in range(300):
    with open(lock, "w", encoding="utf-8") as f:
        json.dump(rec_y, f)
    live, ticket = ["0001-X-1"], os.path.join(d, "0001-X-1")
    t0 = time.perf_counter()
    if not live or live[0] != os.path.basename(ticket):
        raise SystemExit("unexpected")
    aside = f"{lock}.stale-{os.getpid()}-{time.time_ns()}"
    t1 = time.perf_counter()
    os.replace(lock, aside)
    t2 = time.perf_counter()
    with open(aside, encoding="utf-8") as f:
        taken = json.load(f)
    same = sl._same_record(taken, rec_a)
    t3 = time.perf_counter()
    sl._rename_noreplace(aside, lock)
    w1.append(t1 - t0)
    w2.append(t3 - t2)
os.remove(lock)
us = lambda xs: f"median {statistics.median(xs) * 1e6:.1f} us, p99 {sorted(xs)[int(len(xs) * .99)] * 1e6:.1f} us"
print(f"window 1 (snapshot check -> os.replace call): {us(w1)}")
print(f"window 2 (lock path empty: os.replace done -> put-back rename call): {us(w2)}")
