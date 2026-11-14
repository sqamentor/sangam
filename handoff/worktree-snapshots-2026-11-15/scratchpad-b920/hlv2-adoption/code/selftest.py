"""selftest.py -- planted-error proof of the shared machine lock v2 (IFMS P-458; I-44/I-45/I-59).

Black-box: drives heavy_lock.py and compile_lock.py as subprocesses against a temporary lock folder
(SHARED_LOCK_DIR) with a fast poll (SHARED_LOCK_POLL_SECONDS=0.2), and spawns real child processes wherever a live or
dead pid is needed. One PASS/FAIL line per case; exit code 1 on any failing case.

    python -I selftest.py                      # cases (a)..(h), (w) wrappers, and the built-in negative control (nc)
    python -I selftest.py --negative-control   # case (a) alone with SHARED_LOCK_NO_AUTORELEASE=1: it must come out
                                               # failing (exit 1), which proves the test detects the auto-release
"""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
HEAVY = os.path.join(HERE, "heavy_lock.py")
COMPILE = os.path.join(HERE, "compile_lock.py")
PY = sys.executable
POLL = "0.2"
LOCK = "heavy-build.lock"
AUTO_RELEASE_MARK = " auto-released stale lock "


def base_env(lock_dir, **extra):
    env = os.environ.copy()
    for key in list(env):
        if key.startswith("SHARED_LOCK_") or key == "HEAVY_LOCK_PID":
            del env[key]
    env["SHARED_LOCK_DIR"] = lock_dir
    env["SHARED_LOCK_POLL_SECONDS"] = POLL
    env.update(extra)
    return env


def run(tool, args, env, timeout=30):
    return subprocess.run([PY, "-I", tool, *args], capture_output=True, text=True, encoding="utf-8",
                          errors="replace", env=env, timeout=timeout, cwd=HERE)


def wait_until(predicate, timeout, step=0.05):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if predicate():
            return True
        time.sleep(step)
    return predicate()


class Background:
    """A tool invocation left running (a waiter); stdout/stderr go to files so they can be read while it runs."""

    def __init__(self, tool, args, env, lock_dir, tag):
        self.out_path = os.path.join(lock_dir, f"{tag}.stdout")
        self.err_path = os.path.join(lock_dir, f"{tag}.stderr")
        self._out = open(self.out_path, "w", encoding="utf-8")
        self._err = open(self.err_path, "w", encoding="utf-8")
        self.proc = subprocess.Popen([PY, "-I", tool, *args], stdout=self._out, stderr=self._err, env=env, cwd=HERE)

    def running(self):
        return self.proc.poll() is None

    def wait(self, timeout):
        try:
            self.proc.wait(timeout)
            return True
        except subprocess.TimeoutExpired:
            return False

    @property
    def returncode(self):
        return self.proc.returncode

    @staticmethod
    def _read(path):
        try:
            with open(path, encoding="utf-8", errors="replace") as f:
                return f.read()
        except OSError:
            return ""

    def stdout(self):
        return self._read(self.out_path)

    def stderr(self):
        return self._read(self.err_path)

    def first_stderr_line(self):
        lines = self.stderr().strip().splitlines()
        return lines[0] if lines else "<no stderr>"

    def stop(self):
        if self.running():
            self.proc.kill()
            self.proc.wait()
        for handle in (self._out, self._err):
            try:
                handle.close()
            except OSError:
                pass


class Case:
    def __init__(self, key, title):
        self.key, self.title = key, title
        self.dir = tempfile.mkdtemp(prefix=f"shared_lock_selftest_{key}_")
        self.children = []
        self.checks = []
        self.notes = []
        self.started = time.time()
        self.elapsed = None

    # -- environment and processes
    def env(self, **extra):
        return base_env(self.dir, **extra)

    def child(self, code):
        proc = subprocess.Popen([PY, "-I", "-c", code], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.children.append(proc)      # the Popen handle is kept, so a finished child's pid is not reused meanwhile
        return proc

    def sleeper(self, seconds):
        return self.child(f"import time; time.sleep({seconds})")

    def start(self, tool, args, tag, env=None):
        bg = Background(tool, args, env or self.env(), self.dir, tag)
        self.children.append(bg)
        return bg

    # -- lock state
    def record(self, name=LOCK):
        try:
            with open(os.path.join(self.dir, name), encoding="utf-8") as f:
                return json.load(f)
        except FileNotFoundError:
            return None

    def owner(self, name=LOCK):
        return (self.record(name) or {}).get("owner")

    def served(self, name=LOCK):
        try:
            with open(os.path.join(self.dir, name + ".served.log"), encoding="utf-8") as f:
                return [line.rstrip("\n") for line in f]
        except FileNotFoundError:
            return []

    def auto_released(self, name=LOCK):
        return [line for line in self.served(name) if AUTO_RELEASE_MARK in line]

    def tickets(self, name=LOCK):
        try:
            return sorted(os.listdir(os.path.join(self.dir, name + ".queue")))
        except FileNotFoundError:
            return []

    # -- verdict
    def check(self, ok, text):
        self.checks.append((bool(ok), text))
        return bool(ok)

    def note(self, text):
        self.notes.append(text)

    def passed(self):
        return bool(self.checks) and all(ok for ok, _ in self.checks)

    def close(self):
        for child in self.children:
            if isinstance(child, Background):
                child.stop()
            elif child.poll() is None:
                child.kill()
                child.wait()
        self.elapsed = time.time() - self.started
        shutil.rmtree(self.dir, ignore_errors=True)


# ------------------------------------------------------------------------------------------------- cases

TITLE_A = "dead pid + expired lease: auto-released, served-log line present, second acquire succeeds"


def case_a(c, extra_env=None):
    env = c.env(**(extra_env or {}))
    holder = c.sleeper(60)
    r = run(HEAVY, ["acquire", "A", "job A", "--pid", str(holder.pid), "--lease", "1"], env)
    c.check(r.returncode == 0 and r.stdout.strip() == "acquired by A: job A",
            f"acquire A: rc={r.returncode} stdout={r.stdout.strip()!r}")
    rec = c.record() or {}
    for key in ("owner", "what", "since", "epoch", "held_by", "acquirer_pid", "acquirer_pid_note"):
        c.check(key in rec, f"v1 field {key} present in the record")
    c.check(rec.get("holder_pid") == holder.pid, f"record holder_pid={rec.get('holder_pid')}, expected {holder.pid}")
    c.check(rec.get("holder_start") is not None, f"record holder_start={rec.get('holder_start')} (a creation time)")
    c.check(rec.get("lease_seconds") == 1 and abs(rec.get("lease_until", 0) - (rec.get("epoch", 0) + 1)) < 0.01,
            f"lease fields: lease_seconds={rec.get('lease_seconds')}, "
            f"lease_until-epoch={rec.get('lease_until', 0) - rec.get('epoch', 0):.3f}")
    holder.kill()
    holder.wait()
    time.sleep(1.5)                           # the 1 s lease has expired and the holder is dead
    waiter = c.start(HEAVY, ["acquire", "B", "job B"], "B", env)
    done = waiter.wait(10)
    c.check(done and waiter.returncode == 0,
            f"second acquire finished within 10 s with rc {waiter.returncode}" if done
            else f"second acquire still waiting after 10 s; lock owner={c.owner()!r}; auto-release lines={len(c.auto_released())}")
    out = waiter.stdout()
    c.check("acquired by B: job B" in out, f"second acquire stdout={out.strip()!r}")
    lines = c.auto_released()
    pattern = (rf"^\S+ auto-released stale lock owner=A pid={holder.pid} since={re.escape(str(rec.get('since')))} "
               r"reason=dead-holder\+expired-lease$")
    c.check(any(re.match(pattern, line) for line in lines),
            f"served-log auto-release line in the contract format: {lines[-1] if lines else 'none'}")
    if lines:
        c.note(f"served log: {lines[-1]}")
    c.check(c.owner() == "B", f"lock now held by {c.owner()!r}")
    r = run(HEAVY, ["release", "B"], env)
    c.check(r.returncode == 0 and r.stdout.strip() == "released by B", f"release B: {r.stdout.strip()!r}")


def case_b(c):
    env = c.env()
    holder = c.sleeper(60)
    r = run(HEAVY, ["acquire", "A", "job A", "--pid", str(holder.pid), "--lease", "3"], env)
    c.check(r.returncode == 0 and "acquired by A: job A" in r.stdout, f"acquire A with a 3 s lease: rc={r.returncode}")
    lease_until = (c.record() or {}).get("lease_until", 0)
    holder.kill()
    holder.wait()
    waiter = c.start(HEAVY, ["acquire", "B", "job B"], "B", env)
    time.sleep(1.5)
    c.check(waiter.running(), "before lease expiry: B still waiting 1.5 s after the holder died")
    c.check(c.owner() == "A", f"before lease expiry: lock still held by {c.owner()!r}")
    c.check(not c.auto_released(), "before lease expiry: no auto-release line")
    err = waiter.stderr()
    c.check("is not running" in err and "lease expires in" in err,
            f"waiter reported the dead holder and the pending lease: {waiter.first_stderr_line()!r}")
    granted_at = [None]

    def granted():
        if c.owner() == "B":
            granted_at[0] = time.time()
            return True
        return False

    c.check(wait_until(granted, 15), "after lease expiry: B acquired within 15 s")
    if granted_at[0] is not None:
        c.check(granted_at[0] >= lease_until,
                f"grant observed {granted_at[0] - lease_until:+.2f} s relative to lease_until (must not be negative)")
        c.note(f"grant observed {granted_at[0] - lease_until:+.2f} s after lease_until")
    c.check(waiter.wait(5) and waiter.returncode == 0 and "acquired by B: job B" in waiter.stdout(),
            "B's acquire exited 0 with the v1 wording")
    lines = c.auto_released()
    c.check(len(lines) == 1 and f"owner=A pid={holder.pid}" in lines[0], f"exactly one auto-release line: {lines}")
    run(HEAVY, ["release", "B"], env)


def case_c(c):
    env = c.env()
    holder = c.sleeper(30)                    # python -c "import time; time.sleep(30)"
    r = run(HEAVY, ["acquire", "A", "job A", "--pid", str(holder.pid), "--lease", "1"], env)
    c.check(r.returncode == 0, f"acquire A with a live pid and a 1 s lease: rc={r.returncode}")
    time.sleep(1.5)                           # lease expired, holder alive
    waiter = c.start(HEAVY, ["acquire", "B", "job B"], "B", env)
    time.sleep(2.0)
    c.check(waiter.running(), "B still waiting after 2 s")
    c.check(holder.poll() is None, "holder process still alive")
    c.check(c.owner() == "A", f"lock still held by {c.owner()!r} (live pid, expired lease)")
    c.check(not c.auto_released(), "no auto-release line")
    err = waiter.stderr()
    c.check("MISSED HEARTBEAT" in err and f"holder pid {holder.pid} alive=yes" in err,
            f"waiter printed the MISSED HEARTBEAT warning: {waiter.first_stderr_line()!r}")
    c.note(f"warning: {waiter.first_stderr_line()}")
    st = run(HEAVY, ["status"], env)
    c.check("alive=yes" in st.stdout and re.search(r"lease remaining: -\d+ s", st.stdout) is not None,
            f"status shows alive=yes and a negative lease remaining: {st.stdout.strip().splitlines()[1:3]}")
    waiter.stop()
    r = run(HEAVY, ["release", "A"], env)
    c.check(r.returncode == 0 and r.stdout.strip() == "released by A", "owner can still release")


def case_d(c):
    env = c.env(SHARED_LOCK_STALE_SECONDS="1")
    r = run(HEAVY, ["acquire", "A", "old style"], env)
    rec = c.record() or {}
    c.check(r.returncode == 0 and r.stdout.strip() == "acquired by A: old style",
            f"old-style acquire: rc={r.returncode} stdout={r.stdout.strip()!r}")
    c.check(all(key not in rec for key in ("holder_pid", "holder_start", "lease_until", "lease_seconds")),
            f"old-style record has no liveness fields: {sorted(rec)}")
    time.sleep(1.5)                           # older than the (overridden) 1 s stale threshold
    waiter = c.start(HEAVY, ["acquire", "B", "job B"], "B", env)
    time.sleep(2.0)
    c.check(waiter.running(), "B still waiting after 2 s")
    c.check(c.owner() == "A", f"lock still held by {c.owner()!r} (old-style, never auto-released)")
    c.check(not c.auto_released(), "no auto-release line")
    expected = f"STALE lock held by A since {rec.get('since')} (old style); report to the main session, do not delete"
    c.check(expected in waiter.stderr(), f"v1 STALE wording on stderr: {waiter.first_stderr_line()!r}")
    c.note(f"warning: {waiter.first_stderr_line()}")
    st = run(HEAVY, ["status"], env)
    c.check("holder pid: none (old-style lock, no liveness fields)" in st.stdout, "status reports the old-style lock")
    waiter.stop()
    r = run(HEAVY, ["release", "A"], env)
    c.check(r.returncode == 0 and r.stdout.strip() == "released by A", "owner releases the old-style lock")


def case_e(c):
    env = c.env()
    holder = c.sleeper(60)
    r = run(HEAVY, ["acquire", "A", "job A", "--pid", str(holder.pid), "--lease", "1"], env)
    rec = c.record() or {}
    c.check(r.returncode == 0 and rec.get("holder_start") is not None,
            f"acquire A recorded holder_start={rec.get('holder_start')}")
    planted = dict(rec)
    planted["holder_start"] = float(rec.get("holder_start") or 0) - 3600.0   # wrong start: "another process, same pid"
    planted["lease_until"] = time.time() - 5                                  # expired lease
    with open(os.path.join(c.dir, LOCK), "w", encoding="utf-8") as f:
        json.dump(planted, f)
    st = run(HEAVY, ["status"], env)
    status_line = next((line for line in st.stdout.splitlines() if line.startswith("holder pid:")), "")
    c.check("alive=no" in status_line and "pid reused" in status_line,
            f"status treats the mismatched start time as dead: {status_line!r}")
    c.note(f"status: {status_line}")
    c.check(holder.poll() is None, "the process with that pid is still running, so only the start-time guard can say dead")
    r = run(HEAVY, ["acquire", "B", "job B"], env, timeout=15)
    c.check(r.returncode == 0 and "acquired by B: job B" in r.stdout,
            f"second acquire auto-released the reused-pid lock: rc={r.returncode} stdout={r.stdout.strip()!r}")
    lines = c.auto_released()
    c.check(len(lines) == 1 and f"owner=A pid={holder.pid}" in lines[0], f"one auto-release line: {lines}")
    c.check(c.owner() == "B", f"lock now held by {c.owner()!r}")
    run(HEAVY, ["release", "B"], env)


def case_f(c):
    env = c.env()
    r = run(HEAVY, ["acquire", "H", "holder"], env)
    c.check(r.returncode == 0, "H holds the lock")
    w1 = c.start(HEAVY, ["acquire", "W1", "first in line"], "W1", env)
    c.check(wait_until(lambda: any("-W1-" in n for n in c.tickets()), 5), "W1 ticket appeared")
    w2 = c.start(HEAVY, ["acquire", "W2", "second in line"], "W2", env)
    c.check(wait_until(lambda: any("-W2-" in n for n in c.tickets()), 5), "W2 ticket appeared")
    tickets = c.tickets()
    c.check(len(tickets) == 2 and "-W1-" in tickets[0] and "-W2-" in tickets[1], f"queue order by arrival: {tickets}")
    time.sleep(0.6)
    c.check(w1.running() and w2.running(), "both waiters blocked while H holds")
    run(HEAVY, ["release", "H"], env)
    c.check(w1.wait(10) and w1.returncode == 0 and "acquired by W1: first in line" in w1.stdout(),
            "W1 (older ticket) acquired first")
    time.sleep(0.6)                           # more than one poll: W2 has tried and must still be waiting
    c.check(w2.running(), "W2 still waiting while W1 holds")
    c.check(c.owner() == "W1", f"lock held by {c.owner()!r}")
    run(HEAVY, ["release", "W1"], env)
    c.check(w2.wait(10) and w2.returncode == 0 and "acquired by W2: second in line" in w2.stdout(),
            "W2 acquired after W1 released")
    served = [m.group(1) for m in (re.search(r" served (\S+) arrived=", line) for line in c.served()) if m]
    c.check(served == ["H", "W1", "W2"], f"served-log order: {served}")
    c.note(f"served order: {served}")
    run(HEAVY, ["release", "W2"], env)


def case_g(c):
    env = c.env()
    holder = c.sleeper(60)
    r = run(HEAVY, ["acquire", "A", "job A", "--pid", str(holder.pid), "--lease", "5"], env)
    rec0 = c.record() or {}
    c.check(r.returncode == 0 and rec0.get("lease_seconds") == 5, "acquire A with a 5 s lease")
    time.sleep(1.0)
    at = time.time()
    r = run(HEAVY, ["heartbeat", "A"], env)
    rec1 = c.record() or {}
    c.check(r.returncode == 0 and r.stdout.startswith("heartbeat by A: lease extended to "),
            f"heartbeat by the owner: rc={r.returncode} stdout={r.stdout.strip()!r}")
    c.check(rec1.get("lease_until", 0) > rec0.get("lease_until", 0),
            f"lease_until moved by {rec1.get('lease_until', 0) - rec0.get('lease_until', 0):+.2f} s")
    c.check(abs(rec1.get("lease_until", 0) - (at + 5)) < 1.5,
            f"new lease_until = now + lease_seconds (off by {rec1.get('lease_until', 0) - (at + 5):+.2f} s)")
    c.check(all(rec1.get(k) == rec0.get(k) for k in
                ("owner", "what", "since", "epoch", "holder_pid", "holder_start", "lease_seconds")),
            "heartbeat changed only lease_until")
    c.note(f"lease_until moved {rec1.get('lease_until', 0) - rec0.get('lease_until', 0):+.2f} s")
    r = run(HEAVY, ["heartbeat", "B"], env)
    c.check(r.returncode == 2 and r.stderr.strip() == "refused: lock is held by A, not B",
            f"heartbeat by a non-owner: rc={r.returncode} stderr={r.stderr.strip()!r}")
    c.check((c.record() or {}).get("lease_until") == rec1.get("lease_until"), "non-owner heartbeat left the lease unchanged")
    run(HEAVY, ["release", "A"], env)
    r = run(HEAVY, ["heartbeat", "A"], env)
    c.check(r.returncode == 2 and r.stderr.strip() == "refused: no lock held",
            f"heartbeat with no lock held: rc={r.returncode} stderr={r.stderr.strip()!r}")


def case_h(c):
    env = c.env()
    r = run(HEAVY, ["acquire", "A", "job A"], env)
    c.check(r.returncode == 0, "A holds the lock")
    r = run(HEAVY, ["release", "B"], env)
    c.check(r.returncode == 2 and r.stderr.strip() == "refused: lock is held by A, not B" and r.stdout == "",
            f"release by a non-owner: rc={r.returncode} stderr={r.stderr.strip()!r}")
    c.check(c.owner() == "A", f"lock still held by {c.owner()!r}")
    r = run(HEAVY, ["release", "A"], env)
    c.check(r.returncode == 0 and r.stdout.strip() == "released by A", f"release by the owner: {r.stdout.strip()!r}")
    c.check(c.record() is None, "lock file removed")
    r = run(HEAVY, ["release", "A"], env)
    c.check(r.returncode == 0 and r.stdout.strip() == "no lock held", f"release with no lock: {r.stdout.strip()!r}")


def case_w(c):
    env = c.env()
    r = run(COMPILE, ["acquire", "C", "compile job", "--pid", str(os.getpid())], env)
    c.check(r.returncode == 0 and r.stdout.strip() == "acquired by C: compile job", f"compile_lock acquire: {r.stdout.strip()!r}")
    c.check(os.path.isfile(os.path.join(c.dir, "compile.lock")) and not os.path.isfile(os.path.join(c.dir, LOCK)),
            "compile_lock.py writes compile.lock and leaves heavy-build.lock alone")
    rec = c.record("compile.lock") or {}
    c.check(rec.get("holder_pid") == os.getpid() and rec.get("lease_seconds") == 1800, f"default lease 1800 s: {rec.get('lease_seconds')}")
    st = run(HEAVY, ["status"], env)
    c.check(st.stdout.splitlines()[0] == "null", "heavy_lock.py status prints null while only compile.lock is held")
    st = run(COMPILE, ["status"], env)
    c.check(json.loads(st.stdout.splitlines()[0]).get("owner") == "C", "compile_lock.py status: first line is the JSON record")
    r = run(COMPILE, ["release", "C"], env)
    c.check(r.returncode == 0 and r.stdout.strip() == "released by C", "compile_lock release wording")
    r = run(HEAVY, ["acquire", "E", "env pid"], c.env(HEAVY_LOCK_PID=str(os.getpid())))
    c.check(r.returncode == 0 and (c.record() or {}).get("holder_pid") == os.getpid(), "HEAVY_LOCK_PID supplies the holder pid")
    run(HEAVY, ["release", "E"], env)
    r = run(HEAVY, ["bogus"], env)
    c.check(r.returncode == 64 and r.stdout == "", f"bad usage exits 64 as v1: rc={r.returncode}")
    r = run(HEAVY, ["acquire", "X", "y", "--pid", "abc"], env)
    c.check(r.returncode == 64 and c.record() is None, f"non-numeric --pid exits 64 without taking the lock: rc={r.returncode}")


def run_case(key, title, fn, **kwargs):
    case = Case(key, title)
    try:
        fn(case, **kwargs)
    except Exception as exc:  # a crash is a failing case, never a silent one
        case.check(False, f"exception {exc.__class__.__name__}: {exc}")
    finally:
        case.close()
    return case


def negative_control():
    inner = run_case("a", TITLE_A, case_a, extra_env={"SHARED_LOCK_NO_AUTORELEASE": "1"})
    meta = Case("nc", "negative control: case (a) comes out failing when the stale rule is disabled "
                      "(SHARED_LOCK_NO_AUTORELEASE=1), so the test detects the auto-release")
    meta.check(not inner.passed(), "case (a) under the control did not pass")
    failing = [text for ok, text in inner.checks if not ok]
    meta.note(f"case (a) under the control: {len(failing)} check(s) not ok; first: {failing[0] if failing else 'none'}")
    meta.close()
    meta.elapsed = (meta.elapsed or 0) + (inner.elapsed or 0)
    return meta


def report(case):
    print(f"{'PASS' if case.passed() else 'FAIL'} ({case.key}) {case.title} [{case.elapsed or 0:.1f} s]", flush=True)
    for ok, text in case.checks:
        if not ok:
            print(f"      not ok: {text}", flush=True)
    for note in case.notes:
        print(f"      {note}", flush=True)


def main(argv):
    print(f"shared_lock v2 self-test  python={sys.version.split()[0]} platform={sys.platform} "
          f"poll={POLL}s  folder={HERE}", flush=True)
    print(f"each case runs in its own temporary lock folder under {tempfile.gettempdir()}", flush=True)
    if "--negative-control" in argv:
        case = run_case("a", TITLE_A, case_a, extra_env={"SHARED_LOCK_NO_AUTORELEASE": "1"})
        report(case)
        failed = 0 if case.passed() else 1
        print(f"RESULT: {1 - failed} passed, {failed} failed  (negative control: SHARED_LOCK_NO_AUTORELEASE=1 was set; "
              "a failing case (a) is the expected outcome)", flush=True)
        return 1 if failed else 0
    plan = [
        ("a", TITLE_A, case_a),
        ("b", "dead pid + unexpired lease (3 s): not released before the lease, released after it", case_b),
        ("c", "live pid + expired lease: not released, MISSED HEARTBEAT warning printed", case_c),
        ("d", "old-style lock (no pid): not released, v1 STALE warning (SHARED_LOCK_STALE_SECONDS=1)", case_d),
        ("e", "pid-reuse guard: live pid with a wrong holder_start is treated as dead", case_e),
        ("f", "FIFO: two waiters, the older ticket wins", case_f),
        ("g", "heartbeat extends the lease; refused for a non-owner and when no lock is held", case_g),
        ("h", "release by a non-owner refused", case_h),
        ("w", "wrappers: compile_lock.py uses compile.lock, HEAVY_LOCK_PID, usage exit 64", case_w),
    ]
    results = []
    for key, title, fn in plan:
        case = run_case(key, title, fn)
        report(case)
        results.append(case)
    meta = negative_control()
    report(meta)
    results.append(meta)
    failed = sum(1 for case in results if not case.passed())
    print(f"RESULT: {len(results) - failed} passed, {failed} failed", flush=True)
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
