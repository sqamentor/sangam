"""CONC-07 experiment 1b: steady state of a 0-byte lock left by a hard kill (UNMODIFIED v2 copy, no hooks).

Loops natural trials (holder = live sleeper, --pid, --lease 1; kill the acquirer as soon as the lock file appears)
until one leaves a 0-byte lock, records whether the dead acquirer's FIFO ticket survived, then: kills the holder,
lets the lease expire, and watches a v2 waiter for >90 s (past TICKET_DEAD_SECONDS) to show the steady state.
"""
import json
import os
import shutil
import subprocess
import sys
import time

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CODE = os.path.join(BASE, "code")
RUNS = os.path.join(BASE, "runs")
HEAVY = os.path.join(CODE, "heavy_lock.py")
PY = sys.executable
LOCK = "heavy-build.lock"


def env_for(lock_dir):
    env = os.environ.copy()
    for k in list(env):
        if k.startswith("SHARED_LOCK_") or k == "HEAVY_LOCK_PID":
            del env[k]
    env["SHARED_LOCK_DIR"] = lock_dir
    env["SHARED_LOCK_POLL_SECONDS"] = "0.2"
    return env


def run(cmd_args, lock_dir, timeout=30):
    return subprocess.run([PY, "-I", HEAVY, *cmd_args], capture_output=True, text=True, env=env_for(lock_dir),
                          timeout=timeout)


def tickets(d):
    try:
        return sorted(os.listdir(os.path.join(d, LOCK + ".queue")))
    except FileNotFoundError:
        return []


def main():
    stats = {"empty+ticket-left": 0, "empty+ticket-gone": 0, "full": 0}
    chosen = None
    for i in range(200):
        d = os.path.join(RUNS, f"steady-{i:03d}")
        os.makedirs(d)
        lock = os.path.join(d, LOCK)
        holder = subprocess.Popen([PY, "-I", "-c", "import time; time.sleep(300)"])
        p = subprocess.Popen([PY, "-I", HEAVY, "acquire", "A", "job A", "--pid", str(holder.pid), "--lease", "1"],
                             env=env_for(d), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        deadline = time.perf_counter() + 15
        while time.perf_counter() < deadline and not os.path.exists(lock):
            pass
        p.kill()
        p.wait()
        size = os.path.getsize(lock) if os.path.exists(lock) else None
        if size == 0:
            key = "empty+ticket-left" if tickets(d) else "empty+ticket-gone"
        else:
            key = "full"
        stats[key] += 1
        if key == "empty+ticket-gone" and chosen is None:
            chosen = (d, holder)
            break
        holder.kill()
        holder.wait()
        shutil.rmtree(d, ignore_errors=True)
    print(f"trials until a 0-byte lock with the ticket already removed (kill landed in :421-:435): {stats}")
    if chosen is None:
        print("none found")
        return
    d, holder = chosen
    lock = os.path.join(d, LOCK)
    print(f"dir={d}\nlock size={os.path.getsize(lock)} tickets={tickets(d)}")
    holder.kill()
    holder.wait()
    print(f"holder pid {holder.pid} killed; waiting 1.5 s so the 1 s lease would have expired")
    time.sleep(1.5)
    st = run(["status"], d)
    print("status:\n" + st.stdout.rstrip())
    r = run(["release", "A"], d)
    print(f"release A: rc={r.returncode} stderr={r.stderr.strip()!r}")
    r = run(["heartbeat", "A"], d)
    print(f"heartbeat A: rc={r.returncode} stdout={r.stdout.strip()!r} stderr={r.stderr.strip()!r}")
    out_p, err_p = os.path.join(d, "B.stdout"), os.path.join(d, "B.stderr")
    fo, fe = open(out_p, "w"), open(err_p, "w")
    b = subprocess.Popen([PY, "-I", HEAVY, "acquire", "B", "job B"], env=env_for(d), stdout=fo, stderr=fe)
    t0 = time.time()
    first_err_at = None
    while time.time() - t0 < 20:
        if open(err_p).read().strip() and first_err_at is None:
            first_err_at = time.time() - t0
            break
        time.sleep(0.1)
    time.sleep(6)
    print(f"waiter B: running={b.poll() is None} after {time.time() - t0:.1f} s; first stderr after "
          f"{first_err_at if first_err_at is None else round(first_err_at, 1)} s")
    print(f"B stderr: {open(err_p).read().strip()!r}")
    print(f"lock size now={os.path.getsize(lock)}; served.log exists={os.path.exists(lock + '.served.log')}; "
          f"tickets={tickets(d)}")
    r = run(["release", "?"], d)
    print(f"release '?': rc={r.returncode} stdout={r.stdout.strip()!r}")
    try:
        b.wait(10)
    except subprocess.TimeoutExpired:
        pass
    print(f"after release '?': B rc={b.returncode} stdout={open(out_p).read().strip()!r}")
    if b.poll() is None:
        b.kill()
        b.wait()
    fo.close()
    fe.close()
    r = run(["release", "B"], d)
    print(f"release B: rc={r.returncode} stdout={r.stdout.strip()!r}")


if __name__ == "__main__":
    main()
