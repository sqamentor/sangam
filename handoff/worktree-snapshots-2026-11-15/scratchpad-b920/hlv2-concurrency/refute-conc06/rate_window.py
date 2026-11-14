"""CONC-06 likelihood: how often a heartbeat record is shorter than the previous one, how long the
write->ftruncate window is, and how long one heartbeat process lives. Private copy + private lock folder only."""
import importlib.util
import io
import os
import contextlib
import statistics
import subprocess
import sys
import time

BASE = os.path.dirname(os.path.abspath(__file__))
CODE = os.path.join(BASE, "code")
LOCK_DIR = os.path.join(BASE, "locks", "rate")
assert "ff9f2caf" not in LOCK_DIR
os.makedirs(LOCK_DIR, exist_ok=True)
for name in os.listdir(LOCK_DIR):
    p = os.path.join(LOCK_DIR, name)
    if os.path.isfile(p):
        os.remove(p)
os.environ["SHARED_LOCK_DIR"] = LOCK_DIR
os.environ["SHARED_LOCK_POLL_SECONDS"] = "0.2"

spec = importlib.util.spec_from_file_location("shared_lock", os.path.join(CODE, "shared_lock.py"))
sl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sl)
cfg = sl.Config("heavy-build.lock", CODE)
assert cfg.dir == os.path.abspath(LOCK_DIR), cfg.dir

# --- acquire window (create with O_EXCL -> record written), the v1-inherited path, for comparison
real_open = os.open
marks = {}


def open_hook(path, flags, *a, **k):
    fd = real_open(path, flags, *a, **k)
    if flags & os.O_EXCL:
        marks["created"] = time.perf_counter_ns()
    return fd


real_append = sl._append_served


def append_hook(c, line):
    marks.setdefault("written", time.perf_counter_ns())
    return real_append(c, line)


os.open = open_hook
sl._append_served = append_hook
acq_windows = []
for i in range(30):
    marks.clear()
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        sl.acquire(cfg, "A", "rate", holder_pid=os.getpid(), lease_seconds=1800)
    acq_windows.append((marks["written"] - marks["created"]) / 1000)
    if i < 29:
        with contextlib.redirect_stdout(io.StringIO()):
            sl.release(cfg, "A")
os.open = real_open
sl._append_served = real_append

# --- heartbeat: shorter-record rate and write->ftruncate window
real_write, real_ftruncate = os.write, os.ftruncate
hb = {}


def write_hook(fd, data):
    hb["old"] = os.fstat(fd).st_size
    hb["new"] = len(data)
    hb["t0"] = time.perf_counter_ns()
    return real_write(fd, data)


def ftruncate_hook(fd, length):
    hb["t1"] = time.perf_counter_ns()
    r = real_ftruncate(fd, length)
    hb["t2"] = time.perf_counter_ns()
    return r


os.write, os.ftruncate = write_hook, ftruncate_hook
N = 5000
shorter = 0
core, full = [], []
with contextlib.redirect_stdout(io.StringIO()):
    for _ in range(N):
        hb.clear()
        assert sl.heartbeat(cfg, "A") == 0
        shorter += hb["new"] < hb["old"]
        core.append((hb["t1"] - hb["t0"]) / 1000)
        full.append((hb["t2"] - hb["t0"]) / 1000)
os.write, os.ftruncate = real_write, real_ftruncate


def q(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(p * len(xs)))]


print(f"heartbeats: {N}; new record shorter than the file it overwrites: {shorter} ({100 * shorter / N:.1f}%)")
print(f"window write-start -> ftruncate-call (us): median {statistics.median(core):.1f}, p99 {q(core, 0.99):.1f}")
print(f"window write-start -> ftruncate-return (us): median {statistics.median(full):.1f}, p99 {q(full, 0.99):.1f}")
print(f"acquire empty-file window O_EXCL-create -> record written (us): median {statistics.median(acq_windows):.1f}, "
      f"p99/max {max(acq_windows):.1f}")

# --- one heartbeat process, end to end (what Stop-Job can catch in flight)
env = dict(os.environ)
walls = []
for _ in range(20):
    t = time.perf_counter()
    r = subprocess.run([sys.executable, "-I", os.path.join(CODE, "heavy_lock.py"), "heartbeat", "A"], env=env,
                       capture_output=True, text=True)
    walls.append((time.perf_counter() - t) * 1000)
    assert r.returncode == 0, r.stderr
print(f"heartbeat process wall time (ms): median {statistics.median(walls):.1f}, max {max(walls):.1f}")

w_us = q(full, 0.99)
p_short = shorter / N
p_inflight = statistics.median(walls) / 1000 / 600
p_window = w_us / 1e6 / 600
print(f"P(heartbeat process in flight at a random Stop-Job, 600 s cadence) ~ {p_inflight:.2e}")
print(f"P(random Stop-Job lands in the write->ftruncate window, p99 window) ~ {p_window:.2e}")
print(f"P(corrupting kill per job end) ~ {p_window * p_short:.2e}  (~1 in {1 / (p_window * p_short):,.0f} job ends)")
with contextlib.redirect_stdout(io.StringIO()):
    sl.release(cfg, "A")
