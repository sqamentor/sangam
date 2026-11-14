"""CONC-06 without any hook in the code under test: a child runs UNMODIFIED shared_lock.heartbeat in a tight loop and
the parent kills it (TerminateProcess) at a random moment. Counts how often the lock file is left unparseable.
Private copy + private lock folders only."""
import importlib.util
import io
import contextlib
import json
import os
import random
import shutil
import subprocess
import sys
import threading
import time

BASE = os.path.dirname(os.path.abspath(__file__))
CODE = os.path.join(BASE, "code")
PY = sys.executable
BUDGET_S = float(sys.argv[1]) if len(sys.argv) > 1 else 150.0
LANES = 4

CHILD = r'''
import importlib.util, os, sys
code_dir = sys.argv[1]
spec = importlib.util.spec_from_file_location("shared_lock", os.path.join(code_dir, "shared_lock.py"))
sl = importlib.util.module_from_spec(spec); spec.loader.exec_module(sl)
cfg = sl.Config("heavy-build.lock", code_dir)
out = sys.stdout
sys.stdout = open(os.devnull, "w")
out.write("READY\n"); out.flush()
while True:
    sl.heartbeat(cfg, "A")
'''

spec = importlib.util.spec_from_file_location("shared_lock", os.path.join(CODE, "shared_lock.py"))
sl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sl)
results = []
lock_mu = threading.Lock()


def make_lock(lock_dir):
    cfg = sl.Config.__new__(sl.Config)
    cfg.dir = lock_dir
    cfg.lock = os.path.join(lock_dir, "heavy-build.lock")
    cfg.queue = cfg.lock + ".queue"
    cfg.served_log = cfg.lock + ".served.log"
    cfg.stale_seconds, cfg.poll, cfg.no_autorelease = 10800, 0.2, False
    with lock_mu, contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        sl.acquire(cfg, "A", "bruteforce", holder_pid=os.getpid(), lease_seconds=1800)
    return cfg


def lane(n):
    d = os.path.join(BASE, "locks", f"brute-{n}")
    assert "ff9f2caf" not in d
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(d)
    env = dict(os.environ)
    env["SHARED_LOCK_DIR"] = d
    cfg = make_lock(d)
    attempts = hits = 0
    samples = []
    deadline = time.time() + BUDGET_S
    while time.time() < deadline:
        p = subprocess.Popen([PY, "-I", "-c", CHILD, CODE], env=env, stdout=subprocess.PIPE,
                             stderr=subprocess.DEVNULL, text=True)
        p.stdout.readline()
        time.sleep(random.uniform(0.02, 0.12))
        p.kill()
        p.wait()
        attempts += 1
        raw = open(cfg.lock, "rb").read()
        try:
            json.loads(raw)
        except ValueError as exc:
            hits += 1
            if len(samples) < 2:
                samples.append(f"{len(raw)} bytes, tail={raw[-40:]!r}, error={exc}")
            shutil.copyfile(cfg.lock, os.path.join(d, f"corrupt-{hits}.json"))
            os.remove(cfg.lock)
            cfg = make_lock(d)
    with lock_mu:
        results.append((n, attempts, hits, samples))


threads = [threading.Thread(target=lane, args=(i,)) for i in range(LANES)]
for t in threads:
    t.start()
for t in threads:
    t.join()
total_a = sum(r[1] for r in results)
total_h = sum(r[2] for r in results)
print(f"unhooked heartbeat loop killed at random moments: {total_a} kills, {total_h} left an unparseable lock file "
      f"({100 * total_h / max(total_a, 1):.1f}%)")
for n, a, h, s in sorted(results):
    for line in s:
        print(f"  lane {n}: {line}")
