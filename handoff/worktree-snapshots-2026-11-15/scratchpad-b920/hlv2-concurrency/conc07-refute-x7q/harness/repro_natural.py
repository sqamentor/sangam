"""CONC-07 refutation attempt, experiment 1: natural race against the UNMODIFIED v2 code (byte-identical copy).

The parent starts `heavy_lock.py acquire` on a free lock (own temp SHARED_LOCK_DIR per trial), spins until the lock
file appears, then TerminateProcess-es the acquirer at once (Popen.kill on Windows == taskkill /F). No hooks, no
patches: whatever the lock file holds afterwards is what a hard kill at the grant leaves behind.
Then, on the first 0-byte lock left with --pid (holder dead, lease expired), it runs the follow-up checks.
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


def env_for(lock_dir, **extra):
    env = os.environ.copy()
    for k in list(env):
        if k.startswith("SHARED_LOCK_") or k == "HEAVY_LOCK_PID":
            del env[k]
    env["SHARED_LOCK_DIR"] = lock_dir
    env["SHARED_LOCK_POLL_SECONDS"] = "0.2"
    env.update(extra)
    return env


def classify(lock):
    if not os.path.exists(lock):
        return "absent", None
    size = os.path.getsize(lock)
    if size == 0:
        return "empty", 0
    try:
        with open(lock, encoding="utf-8") as f:
            json.load(f)
        return "full", size
    except ValueError:
        return "partial", size


def trial(tag, i, holder_pid):
    d = os.path.join(RUNS, f"nat-{tag}-{i:03d}")
    os.makedirs(d)
    lock = os.path.join(d, LOCK)
    args = [PY, "-I", HEAVY, "acquire", "A", "job A"]
    if holder_pid is not None:
        args += ["--pid", str(holder_pid), "--lease", "1"]
    p = subprocess.Popen(args, env=env_for(d), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    seen = False
    deadline = time.perf_counter() + 15
    while time.perf_counter() < deadline:
        if os.path.exists(lock):
            seen = True
            break
    p.kill()                                   # TerminateProcess
    p.wait()
    kind, size = classify(lock) if seen else ("never-created", None)
    return d, kind, size, p.returncode


def run(cmd_args, lock_dir, timeout=30):
    return subprocess.run([PY, "-I", HEAVY, *cmd_args], capture_output=True, text=True, env=env_for(lock_dir),
                          timeout=timeout)


def follow_up(d, holder):
    print(f"\n=== follow-up on {d} (holder pid {holder.pid}) ===")
    lock = os.path.join(d, LOCK)
    print(f"lock size: {os.path.getsize(lock)} bytes; holder alive before kill: {holder.poll() is None}")
    holder.kill()
    holder.wait()
    time.sleep(1.5)                            # --lease 1 would have expired by now, holder is dead
    st = run(["status"], d)
    print("status rc=%d stdout:\n%s" % (st.returncode, st.stdout.rstrip()))
    r = run(["release", "A"], d)
    print(f"release A rc={r.returncode} stdout={r.stdout.strip()!r} stderr={r.stderr.strip()!r}")
    out_p, err_p = os.path.join(d, "B.stdout"), os.path.join(d, "B.stderr")
    with open(out_p, "w") as fo, open(err_p, "w") as fe:
        b = subprocess.Popen([PY, "-I", HEAVY, "acquire", "B", "job B"], env=env_for(d), stdout=fo, stderr=fe)
        time.sleep(8)
        print(f"after 8 s: waiter B running={b.poll() is None}; lock size={os.path.getsize(lock)}")
        print(f"B stderr: {open(err_p).read().strip()!r}")
        served = os.path.join(d, LOCK + ".served.log")
        print(f"served.log exists={os.path.exists(served)} (no grant line, no auto-release line)")
        r = run(["release", "?"], d)
        print(f"release '?' rc={r.returncode} stdout={r.stdout.strip()!r} stderr={r.stderr.strip()!r}")
        done = True
        try:
            b.wait(10)
        except subprocess.TimeoutExpired:
            done = False
        print(f"after release '?': B finished={done} rc={b.returncode} stdout={open(out_p).read().strip()!r}")
        if b.poll() is None:
            b.kill()
            b.wait()
    r = run(["release", "B"], d)
    print(f"release B rc={r.returncode} stdout={r.stdout.strip()!r}")


def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 60
    results = {}
    keep = {}
    holders = {}
    for tag in ("old", "pid"):
        counts = {}
        for i in range(n):
            holder = None
            if tag == "pid":
                holder = subprocess.Popen([PY, "-I", "-c", "import time; time.sleep(120)"])
            d, kind, size, rc = trial(tag, i, holder.pid if holder else None)
            counts[kind] = counts.get(kind, 0) + 1
            if kind in ("empty", "partial") and tag not in keep:
                keep[tag] = d
                holders[tag] = holder
            else:
                if holder is not None:
                    holder.kill()
                    holder.wait()
                shutil.rmtree(d, ignore_errors=True)
        results[tag] = counts
        print(f"{tag}: {n} trials (kill as soon as the lock file appears) -> {counts}", flush=True)
    if "pid" in keep:
        follow_up(keep["pid"], holders["pid"])
    else:
        print("no 0-byte lock left in the pid variant; follow-up skipped")
    for tag, d in keep.items():
        if tag != "pid":
            print(f"kept {tag} example: {d} size={os.path.getsize(os.path.join(d, LOCK))}")
            if holders.get(tag) is not None:
                holders[tag].kill()


if __name__ == "__main__":
    main()
