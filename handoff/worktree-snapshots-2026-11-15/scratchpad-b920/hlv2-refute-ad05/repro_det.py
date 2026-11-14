"""Independent deterministic reproduction of HLV2-AD-05 on a COPY of heavy_lock v2, temp lock folders only.

E1  in-flight heartbeat overwrites the NEXT holder's complete record (A's record replaces B's), then a waiter C
    auto-releases A's re-armed record and is granted while B still runs.
E2  in-flight heartbeat lands between B's O_EXCL create and B's json.dump: B's shorter record is written over A's
    longer one without truncation -> torn JSON -> placeholder owner '?' -> never auto-released.

Patched copy: the ONLY changes are env-controlled sleeps (default 0) that widen existing gaps:
  HB_GAP_DELAY   immediately before heartbeat's by-name os.open (shared_lock.py line 496)
  ACQ_WRITE_DELAY immediately before acquire's os.fdopen/json.dump (shared_lock.py line 434) -- E2 only
"""
import json, os, shutil, subprocess, sys, time

ROOT = os.path.dirname(os.path.abspath(__file__))
PY = sys.executable
CODE = os.path.join(ROOT, "code")
PATCHED = os.path.join(ROOT, "patched_gap")
LOCKS = os.path.join(ROOT, "locks")
LIVE_MARK = "ff9f2caf"


def make_patched():
    shutil.rmtree(PATCHED, ignore_errors=True)
    os.makedirs(PATCHED)
    shutil.copyfile(os.path.join(CODE, "heavy_lock.py"), os.path.join(PATCHED, "heavy_lock.py"))
    src = open(os.path.join(CODE, "shared_lock.py"), encoding="utf-8").read()
    hb_needle = '        fd = os.open(cfg.lock, os.O_WRONLY | getattr(os, "O_BINARY", 0))\n'
    acq_needle = '    with os.fdopen(fd, "w", encoding="utf-8") as f:\n        json.dump(record, f)\n'
    assert src.count(hb_needle) == 1 and src.count(acq_needle) == 1
    src = src.replace(hb_needle, '        time.sleep(float(os.environ.get("HB_GAP_DELAY", "0")))  # REVIEW INJECTION\n' + hb_needle)
    src = src.replace(acq_needle, '    time.sleep(float(os.environ.get("ACQ_WRITE_DELAY", "0")))  # REVIEW INJECTION\n' + acq_needle)
    open(os.path.join(PATCHED, "shared_lock.py"), "w", encoding="utf-8").write(src)


def lockdir(tag):
    d = os.path.join(LOCKS, tag)
    assert LIVE_MARK not in d
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(d)
    return d


def env_for(d, **extra):
    e = {k: v for k, v in os.environ.items()
         if not k.startswith("SHARED_LOCK_") and k not in ("HEAVY_LOCK_PID", "HB_GAP_DELAY", "ACQ_WRITE_DELAY")}
    e.update(SHARED_LOCK_DIR=d, SHARED_LOCK_POLL_SECONDS="0.2", **extra)
    return e


def tool(d, *args, bg=False, **extra):
    cmd = [PY, "-I", os.path.join(PATCHED, "heavy_lock.py"), *args]
    assert LIVE_MARK not in cmd[2] and LIVE_MARK not in d
    if bg:
        return subprocess.Popen(cmd, env=env_for(d, **extra), stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    r = subprocess.run(cmd, env=env_for(d, **extra), capture_output=True, text=True, timeout=60)
    return r.returncode, " | ".join(x for x in (r.stdout.strip(), r.stderr.strip()) if x)


def rec(d):
    p = os.path.join(d, "heavy-build.lock")
    try:
        raw = open(p, "rb").read()
    except FileNotFoundError:
        return None
    try:
        r = json.loads(raw)
        return {"owner": r.get("owner"), "holder_pid": r.get("holder_pid"), "what": r.get("what"), "len": len(raw)}
    except ValueError:
        return {"TORN": raw.decode("utf-8", "replace"), "len": len(raw)}


def sleeper(sec=120):
    return subprocess.Popen([PY, "-I", "-c", f"import time; time.sleep({sec})"])


def e1():
    print("=== E1: heartbeat read A's record, A released, B granted, heartbeat opens B's file by name")
    d = lockdir("e1")
    sa, sb = sleeper(), sleeper()
    print("A acquire:", tool(d, "acquire", "customwf-a", "job A", "--pid", str(sa.pid), "--lease", "4"))
    hb = tool(d, "heartbeat", "customwf-a", bg=True, HB_GAP_DELAY="2")
    time.sleep(0.8)
    print("A release:", tool(d, "release", "customwf-a"))
    print("B acquire:", tool(d, "acquire", "customwf-b", "job B", "--pid", str(sb.pid), "--lease", "60"))
    print("record right after B's grant:", rec(d))
    out, err = hb.communicate(timeout=10)
    print(f"A's in-flight heartbeat: rc={hb.returncode} {out.strip()} {err.strip()}")
    print("record now:", rec(d), "(B's runner believes it holds the lock)")
    print("B heartbeat:", tool(d, "heartbeat", "customwf-b"))
    sa.kill(); sa.wait()
    print(f"A's runner pid {sa.pid} exited; waiting for A's re-armed 4 s lease")
    time.sleep(4.5)
    c = tool(d, "acquire", "customwf-c", "job C", bg=True)
    try:
        out, err = c.communicate(timeout=15)
        print(f"C acquire: rc={c.returncode} {out.strip()} | stderr: {err.strip()}")
    except subprocess.TimeoutExpired:
        c.kill(); print("C still waiting")
    print("B still running:", sb.poll() is None, "| record:", rec(d))
    print("B release (its finally):", tool(d, "release", "customwf-b"))
    print("served.log tail:", open(os.path.join(d, "heavy-build.lock.served.log"), encoding="utf-8").read().strip().splitlines()[-3:])
    sb.kill(); sb.wait()


def e2():
    print("=== E2: heartbeat write lands between B's O_EXCL create and B's record write (B's record shorter)")
    d = lockdir("e2")
    sa, sb = sleeper(), sleeper()
    print("A acquire:", tool(d, "acquire", "customwf-a", "job A: maven verify all lanes, long description", "--pid", str(sa.pid)))
    lenA = rec(d)["len"]
    hb = tool(d, "heartbeat", "customwf-a", bg=True, HB_GAP_DELAY="1.5")
    time.sleep(0.6)
    print("A release:", tool(d, "release", "customwf-a"))
    b = tool(d, "acquire", "customwf-b", "B", "--pid", str(sb.pid), bg=True, ACQ_WRITE_DELAY="2.5")
    out, err = hb.communicate(timeout=10)
    print(f"A's in-flight heartbeat: rc={hb.returncode} {out.strip()} {err.strip()}")
    out, err = b.communicate(timeout=15)
    print(f"B acquire: rc={b.returncode} {out.strip()}")
    print(f"record now (A's record was {lenA} bytes):", rec(d))
    print("status:", tool(d, "status"))
    print("B heartbeat:", tool(d, "heartbeat", "customwf-b"))
    print("B release:", tool(d, "release", "customwf-b"))
    sa.kill(); sa.wait(); sb.kill(); sb.wait()
    c = tool(d, "acquire", "customwf-c", "job C", bg=True, SHARED_LOCK_STALE_SECONDS="1")
    time.sleep(6)
    alive = c.poll() is None
    if alive:
        c.kill()
    out, err = c.communicate()
    print(f"C after 6 s with both holders dead: {'STILL WAITING' if alive else 'exited'}; stderr: {err.strip()[:300]}")


if __name__ == "__main__":
    make_patched()
    for name in sys.argv[1:] or ["e1", "e2"]:
        globals()[name]()
