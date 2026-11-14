"""Review experiments on a COPY with temp lock folders:
 t7a  a heartbeat loop from ANY process (same owner name) keeps a dead holder's lock alive indefinitely
 t7b  release by name from ANY process frees a live pid lock (no pid check)
 t8   heartbeat read->open gap: a heartbeat in flight across release+re-acquire overwrites the NEW holder's record
      (patched copy: the only change is an env-controlled sleep inside that gap, to make the interleaving deterministic)
"""
import json, os, shutil, subprocess, sys, time

BASE = os.path.dirname(os.path.abspath(__file__))
PY = sys.executable
ORIG = os.path.join(BASE, "code")
PATCHED = os.path.join(BASE, "patched")


def lockdir(tag):
    d = os.path.join(BASE, "locks", tag)
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(d)
    return d


def env_for(d, **extra):
    e = {k: v for k, v in os.environ.items() if not k.startswith("SHARED_LOCK_") and k != "HEAVY_LOCK_PID"}
    e.update(SHARED_LOCK_DIR=d, SHARED_LOCK_POLL_SECONDS="0.2", **extra)
    return e


def tool(code_dir, d, *args, bg=False, **extra):
    cmd = [PY, "-I", os.path.join(code_dir, "heavy_lock.py"), *args]
    assert "ff9f2caf" not in cmd[2]
    if bg:
        return subprocess.Popen(cmd, env=env_for(d, **extra), stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    r = subprocess.run(cmd, env=env_for(d, **extra), capture_output=True, text=True, timeout=30)
    return r.returncode, (r.stdout.strip() + " " + r.stderr.strip()).strip()


def sleeper(sec=120):
    return subprocess.Popen([PY, "-I", "-c", f"import time; time.sleep({sec})"])


def owner_pid(d):
    try:
        r = json.load(open(os.path.join(d, "heavy-build.lock"), encoding="utf-8"))
        return r.get("owner"), r.get("holder_pid")
    except FileNotFoundError:
        return None, None


def t7a():
    print("=== t7a stray same-name heartbeat keeps a DEAD holder's lock")
    d = lockdir("t7a")
    s = sleeper()
    print("acquire:", tool(ORIG, d, "acquire", "customwf-x", "job", "--pid", str(s.pid), "--lease", "3"))
    s.kill(); s.wait()
    print(f"holder pid {s.pid} killed (runner died)")
    stray = subprocess.Popen([PY, "-I", "-c",
                              "import subprocess,sys,time\n"
                              f"for i in range(14):\n time.sleep(1); subprocess.run([r'{PY}','-I',r'{os.path.join(ORIG, 'heavy_lock.py')}','heartbeat','customwf-x'],capture_output=True)"],
                             env=env_for(d))
    w = tool(ORIG, d, "acquire", "W", "next job", bg=True)
    time.sleep(12)
    print("waiter after 12 s with a 3 s lease and a dead holder:", "STILL WAITING" if w.poll() is None else f"exit {w.returncode}")
    stray.wait()
    t0 = time.time()
    try:
        w.wait(10)
    except subprocess.TimeoutExpired:
        pass
    print(f"stray heartbeat loop stopped -> waiter {'exit %s after %.1f s' % (w.returncode, time.time() - t0) if w.poll() is not None else 'still waiting'}")
    if w.poll() is None:
        w.kill()


def t7b():
    print("=== t7b foreign release by name frees a LIVE pid lock")
    d = lockdir("t7b")
    s = sleeper()
    print("acquire customwf-y --pid live:", tool(ORIG, d, "acquire", "customwf-y", "job y", "--pid", str(s.pid)))
    print("release customwf-y from an unrelated process:", tool(ORIG, d, "release", "customwf-y"))
    print("next acquire while the holder still runs:", tool(ORIG, d, "acquire", "customwf-z", "job z"))
    print("holder still alive:", s.poll() is None)
    s.kill(); s.wait()


def t8():
    print("=== t8 heartbeat in flight across release + re-acquire (patched copy, HB_DELAY gap)")
    shutil.rmtree(PATCHED, ignore_errors=True)
    shutil.copytree(ORIG, PATCHED, ignore=shutil.ignore_patterns("selftest.py", "__pycache__"))
    p = os.path.join(PATCHED, "shared_lock.py")
    src = open(p, encoding="utf-8").read()
    needle = "    held = read(cfg)\n    if held is None:\n        print(\"refused: no lock held\", file=sys.stderr, flush=True)\n        return 2\n"
    assert src.count(needle) == 1, "heartbeat anchor not found"
    src = src.replace(needle, needle + "    time.sleep(float(os.environ.get('HB_DELAY', '0')))   # REVIEW INJECTION: widen the read->open gap\n")
    open(p, "w", encoding="utf-8").write(src)
    d = lockdir("t8")
    sa, sb = sleeper(), sleeper()
    print("A acquire:", tool(PATCHED, d, "acquire", "customwf-a", "job A", "--pid", str(sa.pid), "--lease", "4"))
    hb = tool(PATCHED, d, "heartbeat", "customwf-a", bg=True, HB_DELAY="2")
    time.sleep(0.8)                       # A's heartbeat has read A's record and is inside the gap
    print("A release:", tool(PATCHED, d, "release", "customwf-a"))
    print("B acquire:", tool(PATCHED, d, "acquire", "customwf-b", "job B", "--pid", str(sb.pid), "--lease", "60"))
    print("lock owner/pid right after B's grant:", owner_pid(d))
    out, err = hb.communicate(timeout=10)
    print(f"A's in-flight heartbeat finished: rc={hb.returncode} {out.strip()} {err.strip()}")
    print("lock owner/pid now:", owner_pid(d), " (B believes it holds the lock)")
    print("B heartbeat:", tool(PATCHED, d, "heartbeat", "customwf-b"))
    sa.kill(); sa.wait()                  # A's runner exits normally after its release
    time.sleep(4.5)                       # A's re-armed 4 s lease runs out
    c = tool(PATCHED, d, "acquire", "customwf-c", "job C", bg=True)
    try:
        out, err = c.communicate(timeout=10)
        print(f"C acquire: rc={c.returncode} {out.strip()} | {err.strip().splitlines()[-1:]}")
    except subprocess.TimeoutExpired:
        c.kill(); print("C still waiting")
    print("B's job still running:", sb.poll() is None, "| lock owner now:", owner_pid(d))
    print("B release in its finally:", tool(PATCHED, d, "release", "customwf-b"))
    sb.kill(); sb.wait()


if __name__ == "__main__":
    for name in sys.argv[1:] or ["t7a", "t7b", "t8"]:
        globals()[name]()
