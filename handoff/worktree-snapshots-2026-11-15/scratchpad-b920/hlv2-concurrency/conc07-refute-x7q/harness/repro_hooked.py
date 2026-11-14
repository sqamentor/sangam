"""CONC-07 experiment 2: pin the window with an in-process hook (code under test unmodified)."""
import json
import os
import subprocess
import sys
import time

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CODE = os.path.join(BASE, "code")
RUNS = os.path.join(BASE, "runs")
HOOK = os.path.join(BASE, "harness", "hook_acquire.py")
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


def lock_state(d):
    lock = os.path.join(d, LOCK)
    if not os.path.exists(lock):
        return "absent"
    size = os.path.getsize(lock)
    if size == 0:
        return "0 bytes"
    try:
        with open(lock, encoding="utf-8") as f:
            rec = json.load(f)
        return f"{size} bytes, full record owner={rec.get('owner')} holder_pid={rec.get('holder_pid')}"
    except ValueError:
        return f"{size} bytes, PARTIAL"


def waiter_check(d, seconds=3.0):
    err_p = os.path.join(d, "B.stderr")
    with open(err_p, "w") as fe:
        b = subprocess.Popen([PY, "-I", HEAVY, "acquire", "B", "job B"], env=env_for(d), stdout=subprocess.DEVNULL,
                             stderr=fe)
        time.sleep(seconds)
        running = b.poll() is None
        b.kill()
        b.wait()
    return running, open(err_p).read().strip()


def case(name, where, mode, with_pid):
    d = os.path.join(RUNS, f"hook-{name}")
    os.makedirs(d)
    marker = os.path.join(d, "marker.json")
    holder = subprocess.Popen([PY, "-I", "-c", "import time; time.sleep(120)"]) if with_pid else None
    args = [PY, "-I", HOOK, CODE, where, mode, marker, "acquire", "A", "job A"]
    if with_pid:
        args += ["--pid", str(holder.pid), "--lease", "1"]
    p = subprocess.Popen(args, env=env_for(d), stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    deadline = time.time() + 20
    while time.time() < deadline and not os.path.exists(marker):
        time.sleep(0.01)
    time.sleep(0.05)
    if mode == "sleep":
        p.kill()                       # TerminateProcess while parked inside the window
    out, err = p.communicate(timeout=30)
    snap = json.load(open(marker)) if os.path.exists(marker) else None
    if holder is not None:
        holder.kill()
        holder.wait()
    time.sleep(1.2)                    # lease (1 s) expired, holder dead
    final = lock_state(d)
    print(f"[{name}] hook at {snap and snap['at']}: lock_exists={snap and snap['lock_exists']} "
          f"lock_size={snap and snap['lock_size']} tickets={snap and snap['tickets']}")
    print(f"[{name}] acquirer rc={p.returncode} stdout={out.strip()!r} stderr_tail={err.strip().splitlines()[-1:] }")
    print(f"[{name}] lock afterwards: {final}")
    if final == "0 bytes":
        running, berr = waiter_check(d)
        print(f"[{name}] v2 waiter B after 3 s: still waiting={running}; stderr={berr!r}")
    print()


def main():
    case("H1-probe-snapshot", "probe", "snapshot", True)
    case("H2-probe-kill", "probe", "sleep", True)
    case("H3-probe-ctrlc", "probe", "kbint", True)
    case("H4-fdopen-snapshot-oldstyle", "fdopen", "snapshot", False)
    case("H5-fdopen-ctrlc-oldstyle", "fdopen", "kbint", False)


if __name__ == "__main__":
    main()
