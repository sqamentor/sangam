"""Operational failure-mode probes for heavy_lock v2 -- runs ONLY against temp lock dirs under this folder.

Never touches the live lock (ff9f2caf scratchpad). Every subprocess gets SHARED_LOCK_DIR=<this folder>/locks/<test>.
Usage: python -I ops_tests.py T1 T2 ...   (no args = all python-only tests)
"""
import json
import os
import shutil
import subprocess
import sys
import time

BASE = os.path.dirname(os.path.abspath(__file__))
CODE = os.path.join(BASE, "code")
PATCHED = os.path.join(BASE, "patched")
PY = r"C:\Python313\python.exe"
LIVE_MARK = "ff9f2caf"


def lock_dir(name):
    d = os.path.join(BASE, "locks", name)
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(d)
    assert d.startswith(BASE) and LIVE_MARK not in d, d
    return d


def env_for(d, **extra):
    env = os.environ.copy()
    for k in list(env):
        if k.startswith("SHARED_LOCK_") or k == "HEAVY_LOCK_PID":
            del env[k]
    env["SHARED_LOCK_DIR"] = d
    env["SHARED_LOCK_POLL_SECONDS"] = "0.2"
    env.update(extra)
    assert LIVE_MARK not in env["SHARED_LOCK_DIR"]
    return env


def tool(code_dir=CODE):
    return os.path.join(code_dir, "heavy_lock.py")


def run(d, args, code_dir=CODE, timeout=30, **extra):
    r = subprocess.run([PY, "-I", tool(code_dir), *args], capture_output=True, text=True, env=env_for(d, **extra),
                       timeout=timeout, cwd=BASE)
    return r.returncode, r.stdout.strip(), r.stderr.strip()


def bg(d, args, tag, code_dir=CODE, **extra):
    out = open(os.path.join(d, tag + ".out"), "w")
    err = open(os.path.join(d, tag + ".err"), "w")
    p = subprocess.Popen([PY, "-I", tool(code_dir), *args], stdin=subprocess.DEVNULL, stdout=out, stderr=err,
                         env=env_for(d, **extra), cwd=BASE)
    p._files = (out, err)
    SPAWNED.append(p)
    return p


def bg_text(d, tag):
    res = []
    for ext in ("out", "err"):
        try:
            with open(os.path.join(d, f"{tag}.{ext}"), encoding="utf-8", errors="replace") as f:
                res.append(f.read().strip())
        except OSError:
            res.append("")
    return res


SPAWNED = []


def sleeper(seconds=300):
    p = subprocess.Popen([PY, "-I", "-c", f"import time; time.sleep({seconds})"], stdin=subprocess.DEVNULL,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    SPAWNED.append(p)
    return p


def record(d):
    try:
        with open(os.path.join(d, "heavy-build.lock"), encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return None
    except ValueError as exc:
        return {"_corrupt": str(exc)}


def served(d):
    try:
        with open(os.path.join(d, "heavy-build.lock.served.log"), encoding="utf-8") as f:
            return [l.rstrip("\n") for l in f]
    except FileNotFoundError:
        return []


def say(*a):
    print(*a, flush=True)


def stop(*procs):
    for p in procs:
        if p is None:
            continue
        if p.poll() is None:
            p.kill()
            p.wait()
        for f in getattr(p, "_files", ()):
            f.close()


# ------------------------------------------------------------------------------------------------ tests

def T1():
    """Orphaned heartbeat loop keeps a DEAD holder's lock alive indefinitely (heartbeat never checks liveness)."""
    d = lock_dir("T1")
    s = sleeper()
    say("T1 acquire A:", run(d, ["acquire", "A", "job A", "--pid", str(s.pid), "--lease", "4"]))
    stop(s)
    say(f"T1 holder pid {s.pid} killed; poll()={s.poll()}")
    w = bg(d, ["acquire", "B", "job B"], "B")
    t0 = time.time()
    beats = []
    while time.time() - t0 < 16:
        rc, out, err = run(d, ["heartbeat", "A"])
        beats.append(rc)
        time.sleep(2)
    st = run(d, ["status"])[1].splitlines()
    say("T1 heartbeats rc while holder dead:", beats)
    say("T1 after 16 s of heartbeats (lease 4 s): waiter B running =", w.poll() is None, "| owner =", (record(d) or {}).get("owner"))
    say("T1 status:", st[1:3])
    t1 = time.time()
    while w.poll() is None and time.time() - t1 < 15:
        time.sleep(0.1)
    say(f"T1 heartbeats stopped -> B finished={w.poll() is not None} after {time.time() - t1:.1f} s; owner={(record(d) or {}).get('owner')}")
    say("T1 served:", [l for l in served(d) if "auto-released" in l])
    say("T1 B stderr:", bg_text(d, "B")[1].splitlines()[:2])
    stop(w)
    run(d, ["release", "B"])


def T2():
    """release compares only the owner name: a stray `release X` frees a LIVE holder's lock; another job then acquires."""
    d = lock_dir("T2")
    s1, s2 = sleeper(), sleeper()
    say("T2 runner-1 acquire X --pid live:", run(d, ["acquire", "X", "run 1 (live)", "--pid", str(s1.pid)]))
    say("T2 stray release X from another process:", run(d, ["release", "X"]))
    say("T2 job Y acquire:", run(d, ["acquire", "Y", "other heavy job", "--pid", str(s2.pid)], timeout=10))
    say("T2 runner-1 still alive:", s1.poll() is None, "| runner-1 heartbeat:", run(d, ["heartbeat", "X"]))
    say("T2 runner-1 release in its finally:", run(d, ["release", "X"]))
    stop(s1, s2)
    run(d, ["release", "Y"])


def T3():
    """Heartbeat TOCTOU (read() ... os.open(O_WRONLY)) overwrites a SUCCESSOR's lock; then a third job auto-releases it
    while the successor is still running. Delay injected only in the patched copy to make the window deterministic."""
    d = lock_dir("T3")
    sa, sb = sleeper(), sleeper()
    say("T3 A acquire:", run(d, ["acquire", "A", "job A", "--pid", str(sa.pid), "--lease", "3"]))
    hb = bg(d, ["heartbeat", "A"], "hbA", code_dir=PATCHED, HLV2_TEST_HB_DELAY="6.0")
    t0 = time.time()
    while "TEST: heartbeat read done" not in bg_text(d, "hbA")[1] and time.time() - t0 < 15:
        time.sleep(0.02)                  # wait until the heartbeat has read A's record (it is now inside the window)
    say(f"T3 heartbeat A has read the record ({time.time() - t0:.2f} s after start); now A releases and B acquires")
    say("T3 A release (job done):", run(d, ["release", "A"]))
    say("T3 B acquire:", run(d, ["acquire", "B", "job B", "--pid", str(sb.pid), "--lease", "600"]))
    say("T3 record right after B acquired:", {k: (record(d) or {}).get(k) for k in ("owner", "holder_pid", "lease_seconds")})
    hb.wait(10)
    say("T3 A heartbeat finished rc", hb.returncode, bg_text(d, "hbA"))
    rec = record(d) or {}
    say("T3 record after the late heartbeat:", {k: rec.get(k) for k in ("owner", "holder_pid", "lease_seconds", "what")})
    say("T3 B heartbeat:", run(d, ["heartbeat", "B"]))
    stop(sa)                              # A's runner exits after its release
    c = bg(d, ["acquire", "C", "job C"], "C")
    try:
        c.wait(20)
    except subprocess.TimeoutExpired:
        pass
    say("T3 C acquire finished:", c.returncode, bg_text(d, "C")[0], "| B's job still running:", sb.poll() is None)
    say("T3 served auto-release:", [l for l in served(d) if "auto-released" in l])
    say("T3 B release at end of its job:", run(d, ["release", "B"]))
    stop(sb, c)
    run(d, ["release", "C"])


def T6():
    """Non-finite --lease values are accepted."""
    for val in ("nan", "inf", "1e400"):
        d = lock_dir("T6_" + val)
        s = sleeper()
        rc, out, err = run(d, ["acquire", "A", "job", "--pid", str(s.pid), "--lease", val])
        rec = record(d) or {}
        say(f"T6 --lease {val}: acquire rc={rc}; lease_until={rec.get('lease_until')!r} lease_seconds={rec.get('lease_seconds')!r}")
        with open(os.path.join(d, "heavy-build.lock"), encoding="utf-8") as f:
            say("    raw record tail:", f.read()[-60:])
        stop(s)
        rc, out, err = run(d, ["status"])
        say(f"    status rc={rc}; tail stderr={err.splitlines()[-1] if err else ''!r}; lines={out.splitlines()[1:3]}")
        w = bg(d, ["acquire", "B", "job B"], "B")
        try:
            w.wait(8)
        except subprocess.TimeoutExpired:
            pass
        o, e = bg_text(d, "B")
        say(f"    waiter (holder dead): finished={w.poll() is not None} rc={w.returncode} last stderr={e.splitlines()[-1] if e else ''!r}")
        stop(w)


def T7():
    """Empty / corrupt lock file (e.g. acquirer killed between O_EXCL create and the JSON write)."""
    d = lock_dir("T7")
    open(os.path.join(d, "heavy-build.lock"), "w").close()
    say("T7 owner release of an empty lock:", run(d, ["release", "A"]))
    say("T7 owner heartbeat:", run(d, ["heartbeat", "A"]))
    w = bg(d, ["acquire", "B", "job B"], "B")
    time.sleep(3)
    say("T7 waiter still waiting:", w.poll() is None, "| stderr:", bg_text(d, "B")[1].splitlines()[:1])
    say("T7 status first lines:", run(d, ["status"])[1].splitlines()[:2])
    stop(w)


def T8():
    """CLI contract and exit codes."""
    d = lock_dir("T8")
    cases = [
        ["acquire"], ["acquire", "A"], ["acquire", "A", "w", "--pid", "abc"], ["acquire", "A", "w", "--pid", "0"],
        ["acquire", "A", "w", "--lease", "-5"], ["acquire", "A", "w", "--bogus"], ["acquire", "A", "--fast run"],
        ["heartbeat"], ["release"], ["heartbeat", "A"], ["release", "A"], ["status"],
    ]
    for args in cases:
        rc, out, err = run(d, args)
        first_err = err.splitlines()[0] if err else ""
        say(f"T8 {args!r:55} rc={rc} out={out.splitlines()[0] if out else ''!r} err0={first_err[:70]!r}")
    say("T8 old-style acquire:", run(d, ["acquire", "A", "w", "extra1", "extra2"]))
    say("T8 heartbeat old-style:", run(d, ["heartbeat", "A"]))
    say("T8 record:", record(d))
    say("T8 release:", run(d, ["release", "A"]))
    s = sleeper()
    say("T8 pid acquire stderr:", run(d, ["acquire", "A", "w", f"--pid={s.pid}", "--lease=900"])[2])
    say("T8 status:", run(d, ["status"])[1].splitlines())
    say("T8 heartbeat:", run(d, ["heartbeat", "A"]))
    stop(s)
    say("T8 heartbeat after holder died:", run(d, ["heartbeat", "A"]))
    say("T8 release:", run(d, ["release", "A"]))
    say("T8 served:", served(d))


def T9():
    """--pid beyond the DWORD range."""
    sys.path.insert(0, CODE)
    import importlib.util
    spec = importlib.util.spec_from_file_location("sl_probe", os.path.join(CODE, "shared_lock.py"))
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    for pid in (4, 2 ** 32 + 4, 2 ** 40, 999999999):
        say(f"T9 probe_process({pid}) ->", m.probe_process(pid))
    d = lock_dir("T9")
    say("T9 acquire --pid 4294967300:", run(d, ["acquire", "A", "w", "--pid", "4294967300", "--lease", "1"]))
    time.sleep(1.5)
    say("T9 status:", run(d, ["status"])[1].splitlines()[1:3])
    run(d, ["release", "A"])


def T12():
    """A head-of-queue waiter that inherited SHARED_LOCK_NO_AUTORELEASE=1 blocks auto-release for everyone."""
    d = lock_dir("T12")
    s = sleeper()
    run(d, ["acquire", "A", "job A", "--pid", str(s.pid), "--lease", "1"])
    stop(s)
    time.sleep(1.5)
    w1 = bg(d, ["acquire", "W1", "first"], "W1", SHARED_LOCK_NO_AUTORELEASE="1")
    time.sleep(0.5)
    w2 = bg(d, ["acquire", "W2", "second"], "W2")
    time.sleep(6)
    say("T12 after 6 s: owner", (record(d) or {}).get("owner"), "| W1 waiting", w1.poll() is None, "| W2 waiting", w2.poll() is None)
    stop(w1)
    try:
        w2.wait(8)
    except subprocess.TimeoutExpired:
        pass
    say("T12 after W1 stopped: W2 rc", w2.returncode, bg_text(d, "W2")[0])
    stop(w2)
    run(d, ["release", "W2"])


def T13():
    """holder_start null (pid not running at grant) + later pid reuse: judged alive forever, never auto-released."""
    d = lock_dir("T13")
    imp = sleeper()                                    # stands in for an unrelated process that reused the pid number
    now = time.time()
    rec = {"owner": "R", "what": "orphan grant", "since": "2026-11-09T19:00:00+00:00", "epoch": now - 3600,
           "held_by": "owner name until release (not a pid)", "acquirer_pid": 1, "acquirer_pid_note": "x",
           "holder_pid": imp.pid, "holder_start": None, "lease_until": now - 60, "lease_seconds": 1800}
    with open(os.path.join(d, "heavy-build.lock"), "w", encoding="utf-8") as f:
        json.dump(rec, f)
    say("T13 status:", run(d, ["status"])[1].splitlines()[1:3])
    w = bg(d, ["acquire", "W", "next job"], "W")
    time.sleep(4)
    say("T13 waiter after 4 s: waiting =", w.poll() is None, "| stderr:", bg_text(d, "W")[1].splitlines()[:1])
    stop(w, imp)


if __name__ == "__main__":
    names = sys.argv[1:] or ["T8", "T1", "T2", "T3", "T6", "T7", "T9", "T12"]
    for n in names:
        say(f"===== {n}: {globals()[n].__doc__.strip().splitlines()[0]}")
        try:
            globals()[n]()
        except Exception as exc:
            say(f"{n} EXCEPTION {exc.__class__.__name__}: {exc}")
        finally:
            for p in SPAWNED:
                stop(p)
            SPAWNED.clear()
