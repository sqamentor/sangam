"""CONC-11 refutation probe: an owner name containing ':' (NTFS alternate data stream in the ticket path).

Runs ONLY against private copies of the code in this folder and private lock folders in this folder.
The live lock folder (ff9f2caf scratchpad) is never referenced.

    python -I conc11_probe.py primitives
    python -I conc11_probe.py scenario <v2|v1> <tag> <first_owner> <poll|default> <stall_seconds> <kill:0|1>
"""
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
SRC_V2 = r"D:\GITHUB  Project\IFMS\00_MASTER_INDEX\session_tools\heavy_lock_v2"
SRC_V1 = r"D:\GITHUB  Project\IFMS\00_MASTER_INDEX\session_tools\scratch_A"
PY = sys.executable
LIVE_MARK = "ff9f2caf"


def sha(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def guard(path):
    full = os.path.abspath(path)
    assert full.startswith(HERE), f"refusing path outside the probe folder: {full}"
    assert LIVE_MARK not in full, f"refusing live path: {full}"
    return full


def streams(path):
    """Alternate data streams of a file, via PowerShell (evidence only)."""
    try:
        r = subprocess.run(["powershell", "-NoProfile", "-Command",
                            f"Get-Item -LiteralPath '{path}' -Stream * | ForEach-Object {{ $_.Stream + '=' + $_.Length }}"],
                           capture_output=True, text=True, timeout=30)
        return r.stdout.strip().splitlines()
    except Exception as exc:  # evidence helper only
        return [f"<streams unavailable: {exc}>"]


def primitives():
    d = guard(os.path.join(HERE, "primitives"))
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(d)
    ticket = os.path.join(d, f"{time.time_ns():020d}-sangam:trial2-{os.getpid()}")
    with open(ticket, "w", encoding="utf-8") as f:
        f.write("colon owner")
    names = os.listdir(d)
    print("listdir after open(<ns>-sangam:trial2-<pid>, 'w'):", names)
    base = os.path.join(d, names[0])
    print("streams on the base file:", streams(base))
    print("basename(ticket) =", os.path.basename(ticket), "| equals listed name:", os.path.basename(ticket) == names[0])
    print("base main-stream size:", os.path.getsize(base))
    m0 = os.path.getmtime(base)
    time.sleep(3.0)
    os.utime(ticket)                       # what acquire() does every poll
    m1 = os.path.getmtime(base)
    print(f"os.utime(stream path) moved getmtime(base) by {m1 - m0:+.3f} s (sleep was 3.0 s)")
    time.sleep(2.0)
    os.utime(ticket)
    m2 = os.path.getmtime(base)
    print(f"second os.utime(stream path) moved getmtime(base) by {m2 - m1:+.3f} s (sleep was 2.0 s)")
    os.remove(ticket)                      # what acquire()'s finally does
    print("after os.remove(stream path): base exists =", os.path.exists(base), "| listdir =", os.listdir(d),
          "| streams =", streams(base))
    os.remove(base)
    print("after os.remove(base): listdir =", os.listdir(d))


def setup(variant, tag):
    root = guard(os.path.join(HERE, f"{variant}-{tag}"))
    shutil.rmtree(root, ignore_errors=True)
    os.makedirs(root)
    if variant == "v2":
        code = os.path.join(root, "code")
        os.makedirs(code)
        for name in ("shared_lock.py", "heavy_lock.py"):
            shutil.copyfile(os.path.join(SRC_V2, name), os.path.join(code, name))
            assert sha(os.path.join(SRC_V2, name)) == sha(os.path.join(code, name))
        locks = os.path.join(root, "locks")
        os.makedirs(locks)
    else:
        # v1's lock path is fixed to the folder of the script, so the copy's folder is the private lock folder
        code = os.path.join(root, "code_and_locks")
        os.makedirs(code)
        shutil.copyfile(os.path.join(SRC_V1, "heavy_lock.py"), os.path.join(code, "heavy_lock.py"))
        with open(os.path.join(code, "heavy_lock.py"), encoding="utf-8") as f:
            assert 'LOCK = os.path.join(os.path.dirname(os.path.abspath(__file__)), "heavy-build.lock")' in f.read()
        locks = code
    return root, guard(locks), guard(os.path.join(code, "heavy_lock.py"))


def scenario(variant, tag, first_owner, poll, stall_seconds, kill):
    stall_seconds = float(stall_seconds)
    root, locks, tool = setup(variant, tag)
    env = {k: v for k, v in os.environ.items() if not k.startswith(("SHARED_LOCK_", "HEAVY_LOCK_"))}
    env["SHARED_LOCK_DIR"] = locks
    if poll != "default":
        env["SHARED_LOCK_POLL_SECONDS"] = poll
    queue = os.path.join(locks, "heavy-build.lock.queue")
    lockfile = os.path.join(locks, "heavy-build.lock")
    t0 = time.time()

    def log(msg):
        print(f"[{variant}/{tag} +{time.time() - t0:6.1f}s] {msg}", flush=True)

    def run(*args):
        r = subprocess.run([PY, "-I", tool, *args], capture_output=True, text=True, env=env, timeout=60)
        log(f"$ {' '.join(args)} -> rc={r.returncode} out={r.stdout.strip()!r} err={r.stderr.strip()[:200]!r}")
        return r

    def tickets():
        try:
            names = sorted(os.listdir(queue))
        except FileNotFoundError:
            return []
        out = []
        for n in names:
            try:
                out.append(f"{n} (mtime age {time.time() - os.path.getmtime(os.path.join(queue, n)):.1f}s)")
            except OSError:
                out.append(f"{n} (gone)")
        return out

    def owner():
        try:
            with open(lockfile, encoding="utf-8") as f:
                return json.load(f).get("owner")
        except FileNotFoundError:
            return None
        except (OSError, ValueError):
            return "?"

    def start(owner_name, what, label):
        out = open(os.path.join(root, f"{label}.out"), "w", encoding="utf-8")
        p = subprocess.Popen([PY, "-I", tool, "acquire", owner_name, what], stdout=out, stderr=subprocess.STDOUT,
                             env=env)
        return p, out

    def text(label):
        with open(os.path.join(root, f"{label}.out"), encoding="utf-8", errors="replace") as f:
            return f.read().strip()

    log(f"tool={tool}")
    log(f"lock folder={locks} poll={poll}")
    run("acquire", "H", "holder")
    w1, o1 = start(first_owner, "first waiter", "w1")
    deadline = time.time() + 15
    while time.time() < deadline and len(tickets()) < 1:
        time.sleep(0.1)
    time.sleep(0.5)
    w2, o2 = start("ifms", "second waiter", "w2")
    deadline = time.time() + 15
    while time.time() < deadline and len(tickets()) < 2:
        time.sleep(0.1)
    q = tickets()
    log(f"queue: {q}")
    if q:
        log(f"streams of {q[0].split(' ')[0]}: {streams(os.path.join(queue, q[0].split(' ')[0]))}")
    run("status")
    run("release", "H")
    t_rel = time.time()
    w1_done_at = w2_done_at = None
    while time.time() - t_rel < stall_seconds:
        time.sleep(1.0)
        if w1_done_at is None and w1.poll() is not None:
            w1_done_at = time.time()
            log(f"w1 ({first_owner}) exited rc={w1.returncode} {time.time() - t_rel:.1f}s after release; out={text('w1')!r}")
            if owner() == first_owner:
                run("release", first_owner)
        if w2_done_at is None and w2.poll() is not None:
            w2_done_at = time.time()
            log(f"w2 (ifms) exited rc={w2.returncode} {time.time() - t_rel:.1f}s after release; out={text('w2')!r}")
            if owner() == "ifms":
                run("release", "ifms")
        if int(time.time() - t_rel) % 15 == 0:
            log(f"sample: w1 waiting={w1.poll() is None} w2 waiting={w2.poll() is None} lock owner={owner()!r} "
                f"queue={tickets()}")
        if w1_done_at is not None and w2_done_at is not None:
            break
    log(f"END OF OBSERVATION ({stall_seconds}s after release): w1 waiting={w1.poll() is None} "
        f"w2 waiting={w2.poll() is None} lock owner={owner()!r} lock file present={os.path.exists(lockfile)} "
        f"queue={tickets()}")
    if kill == "1" and w1.poll() is None:
        w1.kill()
        w1.wait()
        t_kill = time.time()
        log(f"killed w1 ({first_owner}) with TerminateProcess; queue={tickets()}")
        while time.time() - t_kill < 150 and w2.poll() is None:
            time.sleep(1.0)
            if int(time.time() - t_kill) % 15 == 0:
                log(f"after kill: w2 waiting={w2.poll() is None} lock owner={owner()!r} queue={tickets()}")
        if w2.poll() is not None:
            log(f"w2 (ifms) exited rc={w2.returncode} {time.time() - t_kill:.1f}s after the kill; out={text('w2')!r}; "
                f"lock owner={owner()!r}")
            if owner() == "ifms":
                run("release", "ifms")
        else:
            log("w2 still waiting 150 s after the kill")
    for p in (w1, w2):
        if p.poll() is None:
            p.kill()
            p.wait()
    for f in (o1, o2):
        f.close()
    log(f"w1 output: {text('w1')!r}")
    log(f"w2 output: {text('w2')!r}")
    try:
        with open(lockfile + ".served.log", encoding="utf-8") as f:
            log("served log: " + " | ".join(line.strip() for line in f))
    except OSError:
        log("served log: none")
    log(f"final: lock owner={owner()!r} queue={tickets()}")


if __name__ == "__main__":
    if sys.argv[1] == "primitives":
        primitives()
    else:
        scenario(*sys.argv[2:8])
