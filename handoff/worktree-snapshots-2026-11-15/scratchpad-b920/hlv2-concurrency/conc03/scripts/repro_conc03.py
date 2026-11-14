"""CONC-03 reproduction against a TEMP COPY of heavy_lock v2. Never touches the live lock folder.

parts: unit | grant_none | grant_after | grant_before | alias
"""
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import time

BASE = os.path.abspath(r"C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812"
                       r"\scratchpad\hlv2-concurrency\conc03")
CODE = os.path.join(BASE, "code")
HEAVY = os.path.join(CODE, "heavy_lock.py")
PY = sys.executable
PING = os.path.join(os.environ.get("SystemRoot", r"C:\Windows"), "System32", "PING.EXE")
DETACHED = 0x00000008
LOCKFILE = "heavy-build.lock"


def lockdir(part):
    d = os.path.join(BASE, "locks", part)
    assert "ff9f2caf" not in d.lower() and os.path.normcase(d).startswith(os.path.normcase(BASE)), d
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(d)
    return d


def env(d, **extra):
    e = os.environ.copy()
    for k in list(e):
        if k.startswith("SHARED_LOCK_") or k == "HEAVY_LOCK_PID":
            del e[k]
    e["SHARED_LOCK_DIR"] = d
    e["SHARED_LOCK_POLL_SECONDS"] = "0.2"
    e.update(extra)
    return e


def run(d, *args, timeout=30):
    r = subprocess.run([PY, "-I", HEAVY, *args], capture_output=True, text=True, env=env(d), timeout=timeout, cwd=CODE)
    return {"rc": r.returncode, "stdout": r.stdout.strip(), "stderr": r.stderr.strip()}


class Bg:
    def __init__(self, d, tag, *args):
        self.out = os.path.join(d, tag + ".stdout")
        self.err = os.path.join(d, tag + ".stderr")
        self._o = open(self.out, "w", encoding="utf-8")
        self._e = open(self.err, "w", encoding="utf-8")
        self.p = subprocess.Popen([PY, "-I", HEAVY, *args], stdout=self._o, stderr=self._e, env=env(d), cwd=CODE)

    @staticmethod
    def _text(path):
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read().strip()

    def stdout(self):
        return self._text(self.out)

    def stderr(self):
        return self._text(self.err)

    def running(self):
        return self.p.poll() is None

    def stop(self):
        if self.running():
            self.p.kill()
            self.p.wait()
        self._o.close()
        self._e.close()


def record(d):
    """Test-side reader; tolerant of the transient empty file between O_EXCL create and the JSON write."""
    for _ in range(40):
        try:
            with open(os.path.join(d, LOCKFILE), encoding="utf-8") as f:
                return json.load(f)
        except FileNotFoundError:
            return None
        except (ValueError, OSError):
            time.sleep(0.05)
    return {"owner": "<unreadable>"}


def owner(d):
    return (record(d) or {}).get("owner")


def served(d):
    try:
        with open(os.path.join(d, LOCKFILE + ".served.log"), encoding="utf-8") as f:
            return f.read().splitlines()
    except FileNotFoundError:
        return []


def tickets(d):
    try:
        return sorted(os.listdir(os.path.join(d, LOCKFILE + ".queue")))
    except FileNotFoundError:
        return []


def wait_until(pred, timeout, step=0.05):
    end = time.time() + timeout
    while time.time() < end:
        if pred():
            return True
        time.sleep(step)
    return pred()


def load_sl(d):
    os.environ["SHARED_LOCK_DIR"] = d          # this process only; Config reads it at construction
    os.environ.pop("SHARED_LOCK_NO_AUTORELEASE", None)
    sys.dont_write_bytecode = True
    spec = importlib.util.spec_from_file_location("shared_lock_copy", os.path.join(CODE, "shared_lock.py"))
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    cfg = m.Config(LOCKFILE, CODE)
    assert os.path.normcase(cfg.dir) == os.path.normcase(os.path.abspath(d)), cfg.dir
    return m, cfg


def sleeper(seconds=600):
    return subprocess.Popen([PY, "-I", "-c", f"import time; time.sleep({seconds})"],
                            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def kill_and_free(p):
    try:
        p.kill()
    except OSError:
        pass
    p.wait()
    try:
        p._handle.Close()                       # drop our handle so the process object (and its pid) is freed
    except Exception:
        pass


def find_reuse(sl, pid, budget_s=300, max_spawn=12000, batch=16):
    """Spawn detached PING.EXE in small batches (each killed at once) until one is given `pid`, or until another
    process on the machine takes it. Returns (info, popen-or-None)."""
    t0 = time.time()
    spawned = 0
    while time.time() - t0 < budget_s and spawned < max_spawn:
        alive, st, det = sl.probe_process(pid)
        if alive or alive is None:
            return {"by": "another process on the machine", "probe": [alive, st, det], "spawned": spawned,
                    "secs": round(time.time() - t0, 1)}, None
        procs = []
        for _ in range(batch):
            procs.append(subprocess.Popen([PING, "-n", "900", "127.0.0.1"], stdin=subprocess.DEVNULL,
                                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                          creationflags=DETACHED))
            spawned += 1
        hit = None
        for q in procs:
            if q.pid == pid and hit is None:
                hit = q
            else:
                kill_and_free(q)
        if hit is not None:
            return {"by": "our PING.EXE (unrelated to the lock)", "probe": list(sl.probe_process(pid)),
                    "spawned": spawned, "secs": round(time.time() - t0, 1)}, hit
    return {"by": None, "spawned": spawned, "secs": round(time.time() - t0, 1)}, None


def part_unit():
    d = lockdir("unit")
    sl, cfg = load_sl(d)
    s = sleeper(120)
    alive, start, det = sl.probe_process(s.pid)
    now = time.time()
    base = {"owner": "A", "what": "job", "since": "t", "epoch": now - 100, "holder_pid": s.pid,
            "lease_until": now - 5, "lease_seconds": 1}
    out = {"live_probe": [alive, start, det]}
    for name, hs in (("live_pid_null_start", None), ("live_pid_matching_start", start),
                     ("live_pid_wrong_start", start - 3600)):
        rec = dict(base, holder_start=hs)
        out[name] = {"liveness": sl.liveness(rec), "evaluate": sl.evaluate(cfg, rec, at=now)}
    kill_and_free(s)
    dead = dict(base, holder_start=None)
    out["dead_pid_null_start"] = {"probe": sl.probe_process(s.pid), "liveness": sl.liveness(dead),
                                  "evaluate": sl.evaluate(cfg, dead, at=time.time())}
    return out


def part_grant(mode):
    """mode none: holder dies while its acquire waits -> grant-time re-probe records holder_start=null.
    mode after: same, then the pid is reused after the grant -> 'yes', waiter never auto-releases.
    mode before: the pid is reused before the grant -> the reuser's start time is recorded as the holder's."""
    d = lockdir("grant_" + mode)
    sl, cfg = load_sl(d)
    log = {"lock_dir": d}
    reuser = None
    B = W = None
    try:
        log["H_acquire"] = run(d, "acquire", "H", "current holder (old-style)")
        s = sleeper(600)
        P = s.pid
        req = sl.probe_process(P)
        log["runner_pid"] = P
        log["request_time_probe"] = req
        W = Bg(d, "W", "acquire", "W", "acquire of a runner killed while waiting", "--pid", str(P), "--lease", "2")
        log["W_ticket_seen"] = wait_until(lambda: any("-W-" in t for t in tickets(d)), 10)
        time.sleep(0.5)
        log["W_waiting_before_kill"] = W.running()
        kill_and_free(s)                        # the runner dies; its python acquire keeps waiting
        time.sleep(0.3)
        log["probe_after_runner_killed"] = sl.probe_process(P)
        log["W_still_waiting_after_runner_killed"] = W.running()
        if mode == "before":
            info, reuser = find_reuse(sl, P)
            log["reuse"] = info
            if info.get("by") is None:
                return log
            log["probe_after_reuse"] = sl.probe_process(P)
        log["H_release"] = run(d, "release", "H")
        log["W_granted"] = wait_until(lambda: owner(d) == "W", 15)
        W.p.wait(15)
        rec = record(d)
        log["record_after_grant"] = rec
        log["W_stdout"] = W.stdout()
        log["W_stderr"] = W.stderr()
        log["request_time_start_vs_recorded_holder_start"] = [req[1], (rec or {}).get("holder_start")]
        if mode == "after":
            info, reuser = find_reuse(sl, P)
            log["reuse"] = info
            if info.get("by") is None:
                return log
        if mode in ("after", "before"):
            time.sleep(2.5)                     # the 2 s lease has certainly expired
            rec = record(d)
            log["lease_left_s"] = round(rec["lease_until"] - time.time(), 1)
            log["liveness_of_record"] = sl.liveness(rec)
            log["liveness_if_request_time_start_had_been_kept"] = sl.liveness(dict(rec, holder_start=req[1]))
            log["evaluate_of_record"] = sl.evaluate(cfg, rec)
            B = Bg(d, "B", "acquire", "B", "next job in the queue")
            time.sleep(8)
            log["B_after_8s"] = {"B_running": B.running(), "lock_owner": owner(d),
                                 "auto_release_lines": [l for l in served(d) if " auto-released " in l],
                                 "B_stderr": B.stderr()}
            if reuser is not None:
                kill_and_free(reuser)           # the unrelated process that took the pid exits
                reuser = None
                log["B_after_unrelated_process_exits"] = {
                    "B_granted_within_15s": wait_until(lambda: owner(d) == "B", 15),
                    "auto_release_lines": [l for l in served(d) if " auto-released " in l],
                    "B_stdout": B.stdout(), "B_stderr_tail": B.stderr().splitlines()[-2:]}
        return log
    finally:
        if reuser is not None:
            kill_and_free(reuser)
        for bg in (B, W):
            if bg is not None:
                bg.stop()
        o = owner(d)
        if o:
            log["cleanup_release"] = run(d, "release", o)


def part_alias():
    d = lockdir("alias")
    sl, _ = load_sl(d)
    me = os.getpid()
    return {"my_pid": me, "probes": {f"pid+{k}": sl.probe_process(me + k) for k in range(0, 5)}}


if __name__ == "__main__":
    part = sys.argv[1]
    t0 = time.time()
    if part == "unit":
        res = part_unit()
    elif part.startswith("grant_"):
        res = part_grant(part.split("_", 1)[1])
    elif part == "alias":
        res = part_alias()
    else:
        raise SystemExit(f"unknown part {part}")
    res = {"part": part, "elapsed_s": round(time.time() - t0, 1), "result": res}
    text = json.dumps(res, indent=1, default=str)
    with open(os.path.join(BASE, f"result_{part}.json"), "w", encoding="utf-8") as f:
        f.write(text)
    print(text)
