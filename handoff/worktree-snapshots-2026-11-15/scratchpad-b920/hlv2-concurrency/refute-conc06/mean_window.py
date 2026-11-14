import importlib.util, io, contextlib, os, sys, time, statistics
BASE = sys.argv[1]; CODE = os.path.join(BASE, "code"); D = os.path.join(BASE, "locks", "mean")
assert "ff9f2caf" not in D
os.makedirs(D, exist_ok=True)
for n in os.listdir(D):
    p = os.path.join(D, n)
    if os.path.isfile(p): os.remove(p)
os.environ["SHARED_LOCK_DIR"] = D
spec = importlib.util.spec_from_file_location("shared_lock", os.path.join(CODE, "shared_lock.py"))
sl = importlib.util.module_from_spec(spec); spec.loader.exec_module(sl)
cfg = sl.Config("heavy-build.lock", CODE)
with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
    sl.acquire(cfg, "A", "mean", holder_pid=os.getpid(), lease_seconds=1800)
rw, rt = os.write, os.ftruncate
t = {}
def w(fd, data):
    t["0"] = time.perf_counter_ns(); return rw(fd, data)
def f(fd, n):
    r = rt(fd, n); t["1"] = time.perf_counter_ns(); return r
os.write, os.ftruncate = w, f
xs = []
with contextlib.redirect_stdout(io.StringIO()):
    for _ in range(3000):
        sl.heartbeat(cfg, "A"); xs.append((t["1"] - t["0"]) / 1000)
os.write, os.ftruncate = rw, rt
m = statistics.mean(xs)
print(f"mean write-start -> ftruncate-return window: {m:.1f} us over {len(xs)} heartbeats")
print(f"P(corrupting kill per uniformly-timed job end, 600 s cadence, 18% shorter): {0.18 * m / 1e6 / 600:.2e}")
with contextlib.redirect_stdout(io.StringIO()):
    sl.release(cfg, "A")
