"""Uninstrumented race for CONC-04: three processes call the UNMODIFIED functions of the copied shared_lock.py
(no hooks, no pauses) against a temp lock folder:
    hb : heartbeat(cfg, "A") in a loop               (an in-flight heartbeat of A next to A's release)
    a  : acquire A (--pid holderA) -> release A      (A's job, repeatedly)
    b  : acquire B (--pid holderB) -> check record -> release B
Detection (in b): B's acquire returned 0 but, a moment later, the lock file says another owner or cannot be parsed.
Only the poll interval is shortened (SHARED_LOCK_POLL_SECONDS) so handoffs happen often.

usage: stress.py run <seconds>        (orchestrator)
"""
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
CODE = os.path.join(HERE, "code")
LOCKS = os.path.join(HERE, "locks-stress")
STOP = os.path.join(LOCKS, "STOP")
RESULT = os.path.join(HERE, "stress-result.txt")
LONG_WHAT = "job A: mvn -T1 verify -pl sangam-engine,sangam-flowable-ports,sangam-integration (lane 3)"


def load():
    spec = importlib.util.spec_from_file_location("shared_lock", os.path.join(CODE, "shared_lock.py"))
    sl = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(sl)
    return sl, sl.Config("heavy-build.lock", LOCKS)


def log(text):
    with open(RESULT, "a", encoding="utf-8") as f:
        f.write(text + "\n")


def stopped():
    return os.path.exists(STOP)


def worker(role, pid_a, pid_b):
    sl, cfg = load()
    sys.stdout = open(os.devnull, "w")
    sys.stderr = open(os.devnull, "w")
    n = 0
    if role == "hb":
        ok = 0
        while not stopped():
            if sl.heartbeat(cfg, "A") == 0:
                ok += 1
            n += 1
        log(f"hb: {n} heartbeat calls, {ok} wrote a record")
    elif role == "a":
        while not stopped():
            sl.acquire(cfg, "A", LONG_WHAT, int(pid_a), 600)
            n += 1
            time.sleep(0.001)
            rc = 1
            while rc == 1 and not stopped():
                rc = sl.release(cfg, "A")
            if rc == 2:
                log(f"a: release A refused after grant #{n}")
                break
        log(f"a: {n} grants to A")
    elif role == "b":
        while not stopped():
            sl.acquire(cfg, "B", "job B", int(pid_b), 600)
            n += 1
            time.sleep(0.001)
            try:
                with open(cfg.lock, "rb") as f:
                    data = f.read()
            except FileNotFoundError:
                data = None
            try:
                owner = json.loads(data.decode("utf-8")).get("owner") if data is not None else None
            except ValueError as exc:
                owner = f"<unparseable: {exc}>"
            if owner != "B":
                log(f"b: DETECTED at grant #{n} to B: acquire B returned 0, lock file now says owner={owner!r}; "
                    f"{len(data) if data else 0} bytes: {data!r}")
                rc = sl.release(cfg, "B")
                log(f"b: release B after detection -> rc={rc}")
                open(STOP, "w").close()
                break
            rc = 1
            while rc == 1 and not stopped():
                rc = sl.release(cfg, "B")
            if rc == 2:
                log(f"b: release B refused at grant #{n}")
                open(STOP, "w").close()
                break
        log(f"b: {n} grants to B")


def orchestrate(seconds):
    shutil.rmtree(LOCKS, ignore_errors=True)
    os.makedirs(LOCKS)
    if os.path.exists(RESULT):
        os.remove(RESULT)
    env = os.environ.copy()
    for key in list(env):
        if key.startswith("SHARED_LOCK_") or key == "HEAVY_LOCK_PID":
            del env[key]
    env["SHARED_LOCK_DIR"] = LOCKS
    env["SHARED_LOCK_POLL_SECONDS"] = "0.0005"
    holder_a = subprocess.Popen([sys.executable, "-I", "-c", "import time; time.sleep(900)"])
    holder_b = subprocess.Popen([sys.executable, "-I", "-c", "import time; time.sleep(900)"])
    me = os.path.abspath(__file__)
    procs = [subprocess.Popen([sys.executable, "-I", me, "worker", role, str(holder_a.pid), str(holder_b.pid)], env=env)
             for role in ("a", "b", "hb")]
    t0 = time.time()
    while time.time() - t0 < seconds and not os.path.exists(STOP):
        time.sleep(0.2)
    if not os.path.exists(STOP):
        open(STOP, "w").close()
    for p in procs:
        try:
            p.wait(3)
        except subprocess.TimeoutExpired:
            p.kill()                       # 'a' can be stuck waiting behind the hijacked lock
    for h in (holder_a, holder_b):
        h.kill()
    print(f"elapsed {time.time() - t0:.1f}s")
    with open(RESULT, encoding="utf-8") as f:
        print(f.read())


if __name__ == "__main__":
    if sys.argv[1] == "worker":
        worker(*sys.argv[2:5])
    else:
        orchestrate(float(sys.argv[2]))
