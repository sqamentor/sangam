"""CONC-08 refutation attempt: drive the unmodified v2 copy (code/) with real processes in a temp lock folder.

    python -I repro.py s3     # X stalls inside _auto_release; Y and Z are plain heavy_lock.py waiters
    python -I repro.py s3r    # same stall, no Z; Y releases while its record is displaced
    python -I repro.py ctrl   # negative control: identical setup, hooks are pass-throughs (no stall)

Every lock path is under this folder; the live ff9f2caf scratchpad is never named or touched.
"""
import json
import os
import shutil
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.abspath(__file__))
CODE = os.path.join(ROOT, "code")
HEAVY = os.path.join(CODE, "heavy_lock.py")
XH = os.path.join(ROOT, "x_hooked.py")
PY = sys.executable
LOCK = "heavy-build.lock"
T0 = time.time()
KIDS = []


def say(msg):
    print(f"[{time.time() - T0:7.2f}s] {msg}", flush=True)


def env_for(lockdir):
    env = os.environ.copy()
    for key in list(env):
        if key.startswith("SHARED_LOCK_") or key == "HEAVY_LOCK_PID":
            del env[key]
    env["SHARED_LOCK_DIR"] = lockdir
    env["SHARED_LOCK_POLL_SECONDS"] = "0.2"
    return env


def sleeper(seconds):
    p = subprocess.Popen([PY, "-I", "-c", f"import time; time.sleep({seconds})"],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    KIDS.append(p)
    return p


class Bg:
    def __init__(self, argv, env, outdir, tag):
        self.out = os.path.join(outdir, f"{tag}.stdout")
        self.err = os.path.join(outdir, f"{tag}.stderr")
        self._o = open(self.out, "w", encoding="utf-8")
        self._e = open(self.err, "w", encoding="utf-8")
        self.p = subprocess.Popen(argv, stdout=self._o, stderr=self._e, env=env, cwd=ROOT)
        KIDS.append(self.p)

    def text(self, which):
        try:
            with open(self.out if which == "out" else self.err, encoding="utf-8", errors="replace") as f:
                return f.read()
        except OSError:
            return ""


def wait_until(pred, timeout, step=0.1):
    end = time.time() + timeout
    while time.time() < end:
        if pred():
            return True
        time.sleep(step)
    return pred()


def record(lockdir, name=LOCK):
    for _ in range(20):
        try:
            with open(os.path.join(lockdir, name), encoding="utf-8") as f:
                return json.load(f)
        except FileNotFoundError:
            return None
        except (OSError, ValueError):
            time.sleep(0.05)
    return {"owner": "<unreadable>"}


def tickets(lockdir):
    try:
        return sorted(os.listdir(os.path.join(lockdir, LOCK + ".queue")))
    except FileNotFoundError:
        return []


def hooklog(ctrl):
    try:
        with open(os.path.join(ctrl, "x_hook.log"), encoding="utf-8") as f:
            return f.read()
    except OSError:
        return ""


def run(argv, env):
    return subprocess.run(argv, capture_output=True, text=True, encoding="utf-8", errors="replace", env=env,
                          cwd=ROOT, timeout=60)


def alive(p):
    return p.poll() is None


def served(lockdir):
    try:
        with open(os.path.join(lockdir, LOCK + ".served.log"), encoding="utf-8") as f:
            return [line.rstrip("\n") for line in f]
    except OSError:
        return []


def setup(name):
    lockdir = os.path.join(ROOT, f"locks-{name}")
    ctrl = os.path.join(ROOT, f"ctrl-{name}")
    for d in (lockdir, ctrl):
        assert d.startswith(ROOT) and "ff9f2caf" not in d
        shutil.rmtree(d, ignore_errors=True)
        os.makedirs(d)
    return lockdir, ctrl, env_for(lockdir)


def common_start(name, mode, with_z, y_lease=None):
    lockdir, ctrl, env = setup(name)
    say(f"scenario {name}: lock folder {lockdir}")
    a_holder = sleeper(600)
    r = run([PY, "-I", HEAVY, "acquire", "A", "job A", "--pid", str(a_holder.pid), "--lease", "2"], env)
    say(f"acquire A --pid {a_holder.pid} --lease 2 -> rc={r.returncode} {r.stdout.strip()!r}")
    x = Bg([PY, "-I", XH, CODE, lockdir, ctrl, mode, "acquire", "X", "job X"], env, ctrl, "X")
    assert wait_until(lambda: any("-X-" in t for t in tickets(lockdir)), 10), "X ticket missing"
    y_job = sleeper(900)
    y_args = [PY, "-I", HEAVY, "acquire", "Y", "job Y", "--pid", str(y_job.pid)]
    if y_lease:
        y_args += ["--lease", str(y_lease)]
    y = Bg(y_args, env, ctrl, "Y")
    assert wait_until(lambda: any("-Y-" in t for t in tickets(lockdir)), 10), "Y ticket missing"
    z = z_job = None
    if with_z:
        z_job = sleeper(900)
        z = Bg([PY, "-I", HEAVY, "acquire", "Z", "job Z", "--pid", str(z_job.pid)], env, ctrl, "Z")
        assert wait_until(lambda: any("-Z-" in t for t in tickets(lockdir)), 10), "Z ticket missing"
    say(f"queue (oldest first): {tickets(lockdir)}")
    a_holder.kill()
    a_holder.wait()
    say("A's holder pid killed; A's 2 s lease runs out -> A is stale (dead holder + expired lease)")
    return lockdir, ctrl, env, x, y, y_job, z, z_job


def scenario_s3():
    lockdir, ctrl, env, x, y, y_job, z, z_job = common_start("s3", "race", with_z=True)
    assert wait_until(lambda: "STALL-START" in hooklog(ctrl), 20), "X never reached _auto_release"
    t_stall = time.time()
    say("X judged A stale, passed the oldest-ticket snapshot (:334) and is now stalled before os.replace (:340)")
    ok = wait_until(lambda: "acquired by Y: job Y" in y.text("out"), 200, 0.25)
    say(f"Y acquired={ok} after X had stalled {time.time() - t_stall:.1f} s (natural 90 s ticket expiry, no back-dating)")
    say(f"   Y stderr: {y.text('err').strip()!r}")
    say(f"   lock owner now: {(record(lockdir) or {}).get('owner')}; queue: {tickets(lockdir)}")
    open(os.path.join(ctrl, "GO1"), "w").close()
    assert wait_until(lambda: "PUTBACK-PENDING" in hooklog(ctrl), 20), "X did not reach the put-back"
    asides = [n for n in os.listdir(lockdir) if n.startswith(LOCK + ".stale-")]
    say(f"X resumed: lock file present={record(lockdir) is not None}; displaced file(s): {asides}; "
        f"displaced owner={record(lockdir, asides[0]).get('owner') if asides else None}; Y's job alive={alive(y_job)}")
    ok = wait_until(lambda: "acquired by Z: job Z" in z.text("out"), 20)
    say(f"Z (oldest ticket now) acquired={ok}: {z.text('out').strip()!r}; lock owner={(record(lockdir) or {}).get('owner')}")
    open(os.path.join(ctrl, "GO2"), "w").close()
    ok = wait_until(lambda: "WARNING: a new lock appeared" in x.text("err"), 20)
    time.sleep(0.5)
    say(f"X put-back failed with the WARNING={ok}: {[l for l in x.text('err').splitlines() if 'WARNING' in l]}")
    say(f"hook log:\n{hooklog(ctrl)}")
    y.p.wait(5)
    z.p.wait(5)
    say(f"RESULT: Y acquire rc={y.p.returncode} (Y's job alive={alive(y_job)}), Z acquire rc={z.p.returncode} "
        f"(Z's job alive={alive(z_job)}), lock owner={(record(lockdir) or {}).get('owner')}")
    rel = run([PY, "-I", HEAVY, "release", "Y"], env)
    say(f"release Y -> rc={rel.returncode} stdout={rel.stdout.strip()!r} stderr={rel.stderr.strip()!r}")
    hb = run([PY, "-I", HEAVY, "heartbeat", "Y"], env)
    say(f"heartbeat Y -> rc={hb.returncode} stderr={hb.stderr.strip()!r}")
    asides = [n for n in os.listdir(lockdir) if n.startswith(LOCK + ".stale-")]
    say(f"displaced Y record still on disk: {asides} -> {record(lockdir, asides[0]) if asides else None}")
    say("served log:\n  " + "\n  ".join(served(lockdir)))
    say(f"X stderr:\n{x.text('err')}")
    two = alive(y_job) and alive(z_job) and y.p.returncode == 0 and z.p.returncode == 0 \
        and (record(lockdir) or {}).get("owner") == "Z" and rel.returncode == 2
    say("VERDICT s3: " + ("TWO HOLDERS (Y and Z both granted, both jobs running)" if two else "no double grant"))


def scenario_s3r():
    lockdir, ctrl, env, x, y, y_job, _, _ = common_start("s3r", "race", with_z=False, y_lease=3)
    assert wait_until(lambda: "STALL-START" in hooklog(ctrl), 20), "X never reached _auto_release"
    t_stall = time.time()
    ok = wait_until(lambda: "acquired by Y: job Y" in y.text("out"), 200, 0.25)
    say(f"Y acquired={ok} after X had stalled {time.time() - t_stall:.1f} s; owner={(record(lockdir) or {}).get('owner')}")
    open(os.path.join(ctrl, "GO1"), "w").close()
    assert wait_until(lambda: "PUTBACK-PENDING" in hooklog(ctrl), 20), "X did not reach the put-back"
    say(f"X displaced Y's live record; lock file present={record(lockdir) is not None}")
    rel = run([PY, "-I", HEAVY, "release", "Y"], env)
    say(f"Y finishes its step and releases -> rc={rel.returncode} stdout={rel.stdout.strip()!r}")
    open(os.path.join(ctrl, "GO2"), "w").close()
    assert wait_until(lambda: "PUTBACK-RESULT" in hooklog(ctrl), 20)
    say(f"X put-back: {[l for l in hooklog(ctrl).splitlines() if 'PUTBACK-RESULT' in l]}")
    say(f"lock after Y's successful release: {record(lockdir)}")
    time.sleep(6)
    st = run([PY, "-I", HEAVY, "status"], env)
    say("status:\n  " + "\n  ".join(st.stdout.strip().splitlines()[:3]))
    say(f"X stderr:\n{x.text('err')}")
    stuck = (record(lockdir) or {}).get("owner") == "Y" and rel.stdout.strip() == "no lock held"
    say("VERDICT s3r: " + ("RELEASED LOCK RESURRECTED (Y released, record back, live pid -> never auto-released)"
                           if stuck else "no resurrection"))


def scenario_ctrl():
    lockdir, ctrl, env, x, y, y_job, z, z_job = common_start("ctrl", "control", with_z=True)
    ok = wait_until(lambda: "acquired by X: job X" in x.text("out"), 20)
    time.sleep(2.0)
    say(f"control: X acquired={ok}; lock owner={(record(lockdir) or {}).get('owner')}; "
        f"Y waiting={alive(y.p)}; Z waiting={alive(z.p)}; queue={tickets(lockdir)}")
    say("served log:\n  " + "\n  ".join(served(lockdir)))
    single = ok and alive(y.p) and alive(z.p) and (record(lockdir) or {}).get("owner") == "X"
    say("VERDICT ctrl: " + ("single holder (X), Y and Z still waiting" if single else "UNEXPECTED"))


if __name__ == "__main__":
    try:
        {"s3": scenario_s3, "s3r": scenario_s3r, "ctrl": scenario_ctrl}[sys.argv[1]]()
    finally:
        for k in KIDS:
            if k.poll() is None:
                k.kill()
                k.wait()
