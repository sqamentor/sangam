"""UNPATCHED stress for HLV2-AD-05 (no injected sleeps). Two lock users hand the lock back and forth through the
unmodified tool functions (acquire / heartbeat / release from code/shared_lock.py, byte-identical to the live v2
source) while one heartbeat loop per owner runs concurrently, as a runner's heartbeat job would. Only the documented
test tunable SHARED_LOCK_POLL_SECONDS is changed, to compress time. Temp lock folder only.

A violation is recorded when a user that was GRANTED the lock finds, before its own release, a record that is not
its own (overwrite by the other owner's in-flight heartbeat) or an unreadable one (torn), or when its release is
refused. Each violation ends the trial; a new trial starts in a fresh lock folder.

usage: python -I stress.py [seconds=25] [poll=0.001]
"""
import importlib.util, json, multiprocessing as mp, os, random, shutil, subprocess, sys, time

ROOT = os.path.dirname(os.path.abspath(__file__))
CODE = os.path.join(ROOT, "code")
LOCK_NAME = "heavy-build.lock"


def load(lockdir, poll):
    assert "ff9f2caf" not in lockdir
    os.environ["SHARED_LOCK_DIR"] = lockdir
    os.environ["SHARED_LOCK_POLL_SECONDS"] = str(poll)
    for k in ("SHARED_LOCK_NO_AUTORELEASE", "SHARED_LOCK_STALE_SECONDS", "HEAVY_LOCK_PID"):
        os.environ.pop(k, None)
    sys.dont_write_bytecode = True
    spec = importlib.util.spec_from_file_location("shared_lock", os.path.join(CODE, "shared_lock.py"))
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m, m.Config(LOCK_NAME, lockdir)


def quiet():
    devnull = open(os.devnull, "w")
    sys.stdout = devnull
    sys.stderr = devnull


def hb_loop(lockdir, owner, stop, out):
    sl, cfg = load(lockdir, 0.001)
    quiet()
    ok = refused = 0
    while not stop.is_set():
        if sl.heartbeat(cfg, owner) == 0:
            ok += 1
        else:
            refused += 1
    out.put(("hb", owner, ok, refused))


def user(lockdir, owner, holder_pid, poll, stop, out):
    sl, cfg = load(lockdir, poll)
    quiet()
    grants = 0
    while not stop.is_set():
        sl.acquire(cfg, owner, "job " + owner, holder_pid=holder_pid, lease_seconds=600)
        grants += 1
        time.sleep(random.uniform(0.0, 0.004))              # the "heavy step"
        seen = sl.read(cfg)                                  # what this holder's own heartbeat/release would read
        if seen is None or seen.get("owner") != owner:
            try:
                raw = open(cfg.lock, "rb").read().decode("utf-8", "replace")
            except OSError as exc:
                raw = repr(exc)
            kind = "torn" if seen and seen.get("owner") == "?" else ("gone" if seen is None else "overwritten")
            out.put(("violation", owner, grants, kind, raw))
            stop.set()
            break
        while True:
            rc = sl.release(cfg, owner)
            if rc == 0:
                break
            if rc == 2:
                out.put(("violation", owner, grants, "release-refused", json.dumps(sl.read(cfg))))
                stop.set()
                break
        if stop.is_set():
            break
    out.put(("grants", owner, grants))


def trial(idx, budget, poll, sleepers):
    lockdir = os.path.join(ROOT, "locks", f"stress-{idx}")
    shutil.rmtree(lockdir, ignore_errors=True)
    os.makedirs(lockdir)
    ctx = mp.get_context("spawn")
    stop, out = ctx.Event(), ctx.Queue()
    procs = [ctx.Process(target=hb_loop, args=(lockdir, "customwf-a", stop, out)),
             ctx.Process(target=hb_loop, args=(lockdir, "customwf-b", stop, out)),
             ctx.Process(target=user, args=(lockdir, "customwf-a", sleepers[0].pid, poll, stop, out)),
             ctx.Process(target=user, args=(lockdir, "customwf-b", sleepers[1].pid, poll, stop, out))]
    for p in procs:
        p.start()
    t0 = time.time()
    while time.time() - t0 < budget and not stop.is_set():
        time.sleep(0.05)
    stop.set()
    for p in procs:
        p.join(3)
    for p in procs:
        if p.is_alive():
            p.terminate()
            p.join(2)
    msgs = []
    while True:
        try:
            msgs.append(out.get(timeout=0.3))
        except Exception:
            break
    return time.time() - t0, msgs


if __name__ == "__main__":
    total = float(sys.argv[1]) if len(sys.argv) > 1 else 25.0
    poll = float(sys.argv[2]) if len(sys.argv) > 2 else 0.001
    sleepers = [subprocess.Popen([sys.executable, "-I", "-c", "import time; time.sleep(900)"]) for _ in range(2)]
    t_start, idx, grants, violations = time.time(), 0, 0, []
    try:
        while time.time() - t_start < total:
            idx += 1
            elapsed, msgs = trial(idx, total - (time.time() - t_start), poll, sleepers)
            for m in msgs:
                if m[0] == "grants":
                    grants += m[2]
                elif m[0] == "violation":
                    violations.append(m)
                    print(f"trial {idx} after {elapsed:.1f}s: VIOLATION {m[3]} seen by {m[1]} at its grant #{m[2]}: {m[4][:420]}")
                elif m[0] == "hb":
                    pass
    finally:
        for s in sleepers:
            s.kill()
    print(f"SUMMARY poll={poll}s wall={time.time() - t_start:.1f}s trials={idx} grants={grants} violations={len(violations)} "
          f"kinds={sorted({v[3] for v in violations})}")
