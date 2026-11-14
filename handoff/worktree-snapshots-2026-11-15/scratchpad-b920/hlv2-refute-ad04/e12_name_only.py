"""Review-only (HLV2-AD-04 refutation). Runs a COPY of heavy_lock v2 (./code) against temp lock folders under ./locks.
Never touches the live ff9f2caf folder.
 e1  release by owner name from an unrelated process while the pid holder is alive; next acquire is granted
 e1c control: release by a different name is refused
 e2  dead holder + 3 s lease; a same-name heartbeat loop (1 s) from an unrelated process keeps the waiter blocked
 e2c control: same setup with no heartbeat loop; waiter gets the lock about 3 s after the kill
"""
import json, os, shutil, subprocess, sys, time

BASE = os.path.dirname(os.path.abspath(__file__))
PY = sys.executable
CODE = os.path.join(BASE, "code", "heavy_lock.py")
assert "ff9f2caf" not in BASE and "ff9f2caf" not in CODE


def lockdir(tag):
    d = os.path.join(BASE, "locks", tag)
    assert "ff9f2caf" not in d
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(d)
    return d


def env_for(d):
    e = {k: v for k, v in os.environ.items() if not k.startswith("SHARED_LOCK_") and k != "HEAVY_LOCK_PID"}
    e.update(SHARED_LOCK_DIR=d, SHARED_LOCK_POLL_SECONDS="0.2")
    return e


def tool(d, *args, bg=False):
    cmd = [PY, "-I", CODE, *args]
    if bg:
        return subprocess.Popen(cmd, env=env_for(d), stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    r = subprocess.run(cmd, env=env_for(d), capture_output=True, text=True, timeout=60)
    return r.returncode, " | ".join(x for x in (r.stdout.strip(), r.stderr.strip()) if x)


def record(d):
    try:
        with open(os.path.join(d, "heavy-build.lock"), encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return None


def sleeper(sec=120):
    return subprocess.Popen([PY, "-I", "-c", f"import time; time.sleep({sec})"])


def e1():
    print("=== e1 release by name from an unrelated process frees a LIVE pid lock")
    d = lockdir("e1")
    s = sleeper()
    print("  acquire X --pid <live sleeper>:", tool(d, "acquire", "X", "job x", "--pid", str(s.pid)))
    r = record(d)
    print(f"  record: owner={r['owner']} holder_pid={r['holder_pid']} (driver pid {os.getpid()}, release runs in a new pid)")
    print("  release X from an unrelated process:", tool(d, "release", "X"))
    t0 = time.time()
    print("  acquire Z (no pid):", tool(d, "acquire", "Z", "job z"), f"after {time.time() - t0:.1f} s")
    print("  holder X's pid still alive:", s.poll() is None, "| lock owner now:", (record(d) or {}).get("owner"))
    s.kill(); s.wait()
    tool(d, "release", "Z")


def e1c():
    print("=== e1c control: release by a different name is refused")
    d = lockdir("e1c")
    s = sleeper()
    print("  acquire X --pid <live sleeper>:", tool(d, "acquire", "X", "job x", "--pid", str(s.pid)))
    print("  release Q:", tool(d, "release", "Q"))
    print("  lock owner now:", (record(d) or {}).get("owner"))
    s.kill(); s.wait()
    tool(d, "release", "X")


def stray_loop(d, owner, beats, interval):
    src = ("import subprocess,sys,time\n"
           f"for i in range({beats}):\n"
           f"    time.sleep({interval})\n"
           f"    subprocess.run([r'{PY}','-I',r'{CODE}','heartbeat','{owner}'],capture_output=True)\n")
    return subprocess.Popen([PY, "-I", "-c", src], env=env_for(d))


def e2(with_loop=True):
    tag = "e2" if with_loop else "e2c"
    print(f"=== {tag} dead holder, 3 s lease, {'a same-name heartbeat loop every 1 s for 12 s' if with_loop else 'no heartbeat loop (control)'}")
    d = lockdir(tag)
    s = sleeper()
    print("  acquire X --pid <sleeper> --lease 3:", tool(d, "acquire", "X", "job x", "--pid", str(s.pid), "--lease", "3"))
    s.kill(); s.wait()
    t_kill = time.time()
    print(f"  holder pid {s.pid} killed")
    loop = stray_loop(d, "X", 12, 1) if with_loop else None
    w = tool(d, "acquire", "W", "next job", bg=True)
    leases = []
    while time.time() - t_kill < 10 and w.poll() is None:
        r = record(d)
        if r and r.get("owner") == "X":
            leases.append(round(r["lease_until"] - t_kill, 1))
        time.sleep(1)
    print(f"  after {time.time() - t_kill:.1f} s: waiter {'STILL WAITING' if w.poll() is None else 'exit %s' % w.returncode}; "
          f"X lease_until seen (s after kill): {sorted(set(leases))}")
    if loop:
        loop.wait()
        t_stop = time.time()
        print(f"  heartbeat loop ended {t_stop - t_kill:.1f} s after the kill")
    else:
        t_stop = t_kill
    try:
        out, err = w.communicate(timeout=15)
        print(f"  waiter: rc={w.returncode} {out.strip()} after {time.time() - t_stop:.1f} s from "
              f"{'loop end' if loop else 'kill'}; stderr tail: {err.strip().splitlines()[-1:]}")
    except subprocess.TimeoutExpired:
        w.kill(); print("  waiter still waiting 15 s later")
    tool(d, "release", "W")


if __name__ == "__main__":
    for name in sys.argv[1:] or ["e1", "e1c", "e2", "e2c"]:
        if name == "e2c":
            e2(with_loop=False)
        else:
            globals()[name]()
