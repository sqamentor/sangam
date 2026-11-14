"""CONC-07 experiment 3: how long the lock file exists with 0 bytes on a normal grant (v1 vs v2, in-process).

created = os.open(..., O_EXCL) returned (v2 :407 / v1 :73); written = the `with os.fdopen(...)` block exited, i.e.
the JSON reached the file (v2 :434-435 / v1 :89-93). Window = written - created. Code under test is unmodified;
only os.open / os.fdopen are wrapped to take timestamps.
"""
import contextlib
import importlib.util
import io
import os
import statistics
import subprocess
import sys
import time

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
T = {}
real_open, real_fdopen = os.open, os.fdopen


def timed_open(path, flags, *a, **k):
    fd = real_open(path, flags, *a, **k)
    if flags & os.O_EXCL:
        T["created"] = time.perf_counter()
    return fd


class TimedFile:
    def __init__(self, f):
        self._f = f

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        r = self._f.__exit__(*exc)
        T["written"] = time.perf_counter()
        return r

    def write(self, s):
        return self._f.write(s)

    def __getattr__(self, name):
        return getattr(self._f, name)


def timed_fdopen(fd, *a, **k):
    return TimedFile(real_fdopen(fd, *a, **k))


os.open, os.fdopen = timed_open, timed_fdopen


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def summarize(label, xs):
    xs = sorted(x * 1e3 for x in xs)
    q = lambda p: xs[min(len(xs) - 1, int(p * len(xs)))]
    print(f"{label:34s} n={len(xs)} median={statistics.median(xs):.3f} ms p90={q(0.90):.3f} p99={q(0.99):.3f} "
          f"max={xs[-1]:.3f} ms", flush=True)


def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 300
    lock_dir = os.path.join(BASE, "runs", "measure")
    os.makedirs(lock_dir, exist_ok=True)
    os.environ["SHARED_LOCK_DIR"] = lock_dir
    os.environ["SHARED_LOCK_POLL_SECONDS"] = "0.2"
    v2 = load("shared_lock", os.path.join(BASE, "code", "shared_lock.py"))
    v1 = load("heavy_lock_v1", os.path.join(BASE, "v1", "heavy_lock.py"))
    assert os.path.dirname(v1.LOCK) == os.path.join(BASE, "v1"), v1.LOCK
    sleeper = subprocess.Popen([sys.executable, "-I", "-c", "import time; time.sleep(600)"])
    cfg = v2.Config("heavy-build.lock", os.path.join(BASE, "code"))
    assert cfg.dir == lock_dir, cfg.dir
    sink = io.StringIO()
    results = {"v1 (heavy_lock.py:73 -> :89-93)": [], "v2 old-style (no --pid)": [], "v2 with --pid (probe at :430)": []}
    for i in range(n):
        for label in results:
            T.clear()
            with contextlib.redirect_stdout(sink), contextlib.redirect_stderr(sink):
                if label.startswith("v1"):
                    v1.acquire("A", "job A")
                    v1.release("A")
                elif "old-style" in label:
                    v2.acquire(cfg, "A", "job A")
                    v2.release(cfg, "A")
                else:
                    v2.acquire(cfg, "A", "job A", holder_pid=sleeper.pid, lease_seconds=60.0)
                    v2.release(cfg, "A")
            results[label].append(T["written"] - T["created"])
    for label, xs in results.items():
        summarize(label, xs)
    probes = []
    for _ in range(2000):
        t0 = time.perf_counter()
        v2.probe_process(sleeper.pid)
        probes.append(time.perf_counter() - t0)
    summarize("probe_process(live pid) alone", probes)
    sleeper.kill()
    sleeper.wait()


if __name__ == "__main__":
    main()
