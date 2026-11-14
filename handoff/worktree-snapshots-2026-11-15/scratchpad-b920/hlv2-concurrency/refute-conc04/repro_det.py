"""Deterministic interleavings for CONC-04 against a COPY of heavy_lock v2 in this folder and a temp lock folder.
All lock commands are the unmodified copy (code/heavy_lock.py -> code/shared_lock.py), run as subprocesses.
Only the heartbeat (hb_paused.py) and, in s1b, B's acquire (b_paused.py) carry a pause hook (no logic change).
"""
import json
import os
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
CODE = os.path.join(HERE, "code")
HEAVY = os.path.join(CODE, "heavy_lock.py")
PY = sys.executable
LOCK = "heavy-build.lock"


def env_for(lock_dir):
    env = os.environ.copy()
    for key in list(env):
        if key.startswith("SHARED_LOCK_") or key == "HEAVY_LOCK_PID":
            del env[key]
    env["SHARED_LOCK_DIR"] = lock_dir
    env["SHARED_LOCK_POLL_SECONDS"] = "0.1"
    return env


def run(args, env, timeout=30):
    r = subprocess.run([PY, "-I", HEAVY, *args], capture_output=True, text=True, env=env, timeout=timeout, cwd=HERE)
    return r.returncode, r.stdout.strip(), r.stderr.strip()


def raw(lock_dir):
    try:
        with open(os.path.join(lock_dir, LOCK), "rb") as f:
            return f.read()
    except FileNotFoundError:
        return None


def owner_of(lock_dir):
    data = raw(lock_dir)
    if data is None:
        return None
    try:
        return json.loads(data.decode("utf-8")).get("owner")
    except ValueError as exc:
        return f"<unparseable: {exc}>"


def wait_for(pred, timeout, step=0.02):
    end = time.time() + timeout
    while time.time() < end:
        if pred():
            return True
        time.sleep(step)
    return pred()


def sleeper():
    return subprocess.Popen([PY, "-I", "-c", "import time; time.sleep(600)"])


def fresh(name):
    d = os.path.join(HERE, name)
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(d)
    return d


def tickets(lock_dir):
    try:
        return sorted(os.listdir(os.path.join(lock_dir, LOCK + ".queue")))
    except FileNotFoundError:
        return []


def s1():
    print("=== s1: heartbeat A read A's record, A released, B acquired and wrote its record, heartbeat A resumes")
    d = fresh("locks-s1")
    sync = fresh("sync-s1")
    env = env_for(d)
    ha, hb_holder = sleeper(), sleeper()
    procs = [ha, hb_holder]
    try:
        print("acquire A:", run(["acquire", "A", "job A", "--pid", str(ha.pid), "--lease", "3"], env))
        marker, go = os.path.join(sync, "hb_read_done"), os.path.join(sync, "hb_go")
        hb = subprocess.Popen([PY, "-I", os.path.join(HERE, "hb_paused.py"), CODE, d, "A", marker, go],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=env)
        procs.append(hb)
        assert wait_for(lambda: os.path.exists(marker), 15), "heartbeat never reached its read"
        print("heartbeat A has read:", open(marker, encoding="utf-8").read()[:80], "...")
        wb = subprocess.Popen([PY, "-I", HEAVY, "acquire", "B", "job B", "--pid", str(hb_holder.pid)],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=env)
        procs.append(wb)
        assert wait_for(lambda: any("-B-" in t for t in tickets(d)), 10), "B never queued"
        print("release A:", run(["release", "A"], env))
        out, err = wb.communicate(timeout=15)
        print(f"B's acquire rc={wb.returncode} stdout={out.strip()!r}")
        print("lock owner now:", owner_of(d))
        rec_b = json.loads(raw(d))
        open(go, "w").close()
        out, err = hb.communicate(timeout=15)
        print(f"heartbeat A resumed rc={hb.returncode} stdout={out.strip()!r} stderr={err.strip()!r}")
        rec = json.loads(raw(d))
        print("lock owner after the heartbeat:", rec.get("owner"), "holder_pid:", rec.get("holder_pid"),
              f"(A's holder {ha.pid}, B's holder {hb_holder.pid}); B's record before: owner={rec_b.get('owner')} "
              f"holder_pid={rec_b.get('holder_pid')}")
        print("heartbeat B:", run(["heartbeat", "B"], env))
        print("release B:", run(["release", "B"], env))
        ha.kill(); ha.wait()
        lease_left = rec.get("lease_until", 0) - time.time()
        print(f"A's holder killed; lease re-armed by A's heartbeat, {lease_left:.1f}s left; waiting it out")
        time.sleep(max(0.0, lease_left) + 0.5)
        rc, so, se = run(["acquire", "C", "job C"], env, timeout=20)
        print(f"acquire C: rc={rc} stdout={so!r} stderr={se!r}")
        print(f"B's holder (pid {hb_holder.pid}) still alive: {hb_holder.poll() is None}; B never released "
              f"-> holders now: B (believes it holds since its acquire) and {owner_of(d)}")
        with open(os.path.join(d, LOCK + ".served.log"), encoding="utf-8") as f:
            print("served log:\n  " + "  ".join(f.readlines()))
    finally:
        for p in procs:
            if p.poll() is None:
                p.kill()
                p.wait()


def s1b():
    print("=== s1b: heartbeat A writes into B's freshly created, still empty lock file; then B writes its record")
    d = fresh("locks-s1b")
    sync = fresh("sync-s1b")
    env = env_for(d)
    ha, hb_holder = sleeper(), sleeper()
    procs = [ha, hb_holder]
    try:
        long_what = "job A: mvn -T1 verify -pl sangam-engine,sangam-flowable-ports,sangam-integration (lane 3)"
        print("acquire A:", run(["acquire", "A", long_what, "--pid", str(ha.pid), "--lease", "3"], env))
        m_hb, go_hb = os.path.join(sync, "hb_read_done"), os.path.join(sync, "hb_go")
        m_b, go_b = os.path.join(sync, "b_created"), os.path.join(sync, "b_go")
        hb = subprocess.Popen([PY, "-I", os.path.join(HERE, "hb_paused.py"), CODE, d, "A", m_hb, go_hb],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=env)
        procs.append(hb)
        assert wait_for(lambda: os.path.exists(m_hb), 15), "heartbeat never reached its read"
        wb = subprocess.Popen([PY, "-I", os.path.join(HERE, "b_paused.py"), CODE, d, "B", "job B", str(hb_holder.pid),
                               m_b, go_b], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=env)
        procs.append(wb)
        assert wait_for(lambda: any("-B-" in t for t in tickets(d)), 10), "B never queued"
        print("release A:", run(["release", "A"], env))
        assert wait_for(lambda: os.path.exists(m_b), 15), "B never created the lock"
        print("B created the lock file (O_EXCL); size now:", len(raw(d)))
        open(go_hb, "w").close()
        out, err = hb.communicate(timeout=15)
        print(f"heartbeat A resumed rc={hb.returncode} stdout={out.strip()!r} stderr={err.strip()!r}")
        print("lock size after the heartbeat:", len(raw(d)), "owner:", owner_of(d))
        open(go_b, "w").close()
        out, err = wb.communicate(timeout=15)
        print(f"B's acquire rc={wb.returncode} stdout={out.strip()!r}")
        data = raw(d)
        print(f"lock size after B's write: {len(data)} bytes; parse: {owner_of(d)}")
        print("raw tail:", data[-120:])
        rc, so, se = run(["status"], env)
        print("status first lines:", so.splitlines()[:2])
        print("release B:", run(["release", "B"], env))
        hb_holder.kill(); hb_holder.wait()
        ha.kill(); ha.wait()
        print("both holders now dead; waiter C tries for 8 s (default rules, no test override of the stale rule)")
        wc = subprocess.Popen([PY, "-I", HEAVY, "acquire", "C", "job C"], stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, text=True, env=env)
        procs.append(wc)
        try:
            out, err = wc.communicate(timeout=8)
            print(f"C finished rc={wc.returncode} stdout={out.strip()!r}")
        except subprocess.TimeoutExpired:
            print(f"C still waiting after 8 s with both holders dead; lock parse: {owner_of(d)}")
    finally:
        for p in procs:
            if p.poll() is None:
                p.kill()
                p.wait()


if __name__ == "__main__":
    which = sys.argv[1:] or ["s1", "s1b"]
    for name in which:
        globals()[name]()
        print()
