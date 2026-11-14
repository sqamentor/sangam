"""Deterministic interleavings against my private copy of shared lock v2 (never the live scratchpad).

Each scenario uses its own lock folder under this script's folder (SHARED_LOCK_DIR), plain CLI calls of the copied
heavy_lock.py, and hooked.py where one process must be held at an exact line. Prints what happened; no asserts.
"""
import json
import os
import shutil
import subprocess
import sys
import time

BASE = os.path.dirname(os.path.abspath(__file__))
CODE = os.path.join(BASE, "code")
HEAVY = os.path.join(CODE, "heavy_lock.py")
HOOKED = os.path.join(BASE, "hooked.py")
PY = sys.executable
LIVE_SCRATCH = "ff9f2caf-1246-4228-ae70-82a16cf209ae"


class Scenario:
    def __init__(self, name):
        self.name = name
        self.dir = os.path.join(BASE, "locks-" + name)
        self.sync = os.path.join(BASE, "sync-" + name)
        for d in (self.dir, self.sync):
            assert LIVE_SCRATCH not in os.path.abspath(d)
            shutil.rmtree(d, ignore_errors=True)
            os.makedirs(d)
        self.procs = []
        print(f"\n==================== {name} ====================", flush=True)

    def env(self, **extra):
        env = {k: v for k, v in os.environ.items() if not k.startswith(("SHARED_LOCK_", "HEAVY_LOCK_", "HL_"))}
        env.update(SHARED_LOCK_DIR=self.dir, SHARED_LOCK_POLL_SECONDS="0.2", HL_CODE=CODE, HL_SYNC=self.sync)
        env.update(extra)
        return env

    def sleeper(self, seconds=120):
        p = subprocess.Popen([PY, "-I", "-c", f"import time; time.sleep({seconds})"])
        self.procs.append(p)
        return p

    def run(self, *args, **extra):
        r = subprocess.run([PY, "-I", HEAVY, *args], capture_output=True, text=True, env=self.env(**extra), timeout=60)
        out = (r.stdout.strip() + (" | stderr: " + r.stderr.strip().replace("\n", " / ") if r.stderr.strip() else ""))
        print(f"  $ {' '.join(args)}  -> rc={r.returncode}: {out[:400]}", flush=True)
        return r

    def start(self, tag, *args, hook=None, **extra):
        out = open(os.path.join(self.dir, tag + ".out"), "w")
        cmd = [PY, "-I", HOOKED, "heavy-build.lock", *args] if hook else [PY, "-I", HEAVY, *args]
        env = self.env(HL_HOOK=hook or "", **extra)
        p = subprocess.Popen(cmd, stdout=out, stderr=subprocess.STDOUT, env=env)
        p._out_path = out.name
        self.procs.append(p)
        return p

    @staticmethod
    def output(p):
        with open(p._out_path, encoding="utf-8", errors="replace") as f:
            return f.read().strip().replace("\n", " / ")

    def wait_paused(self, name, timeout=30):
        path = os.path.join(self.sync, name + ".paused")
        deadline = time.time() + timeout
        while not os.path.exists(path):
            if time.time() > deadline:
                raise RuntimeError(f"{name} never paused")
            time.sleep(0.01)

    def go(self, name):
        open(os.path.join(self.sync, name + ".go"), "w").close()

    def raw(self):
        for _ in range(100):
            try:
                with open(os.path.join(self.dir, "heavy-build.lock"), "rb") as f:
                    return f.read()
            except FileNotFoundError:
                return None
            except PermissionError:          # Windows: another process is renaming/deleting it right now
                self.sharing_violations = getattr(self, "sharing_violations", 0) + 1
                time.sleep(0.01)
        raise RuntimeError("lock file stayed unreadable")

    def owner(self):
        raw = self.raw()
        if raw is None:
            return None
        try:
            return json.loads(raw).get("owner")
        except ValueError:
            return f"<unparseable {len(raw)} bytes>"

    def tickets(self):
        q = os.path.join(self.dir, "heavy-build.lock.queue")
        return sorted(os.listdir(q)) if os.path.isdir(q) else []

    def served(self):
        try:
            with open(os.path.join(self.dir, "heavy-build.lock.served.log"), encoding="utf-8") as f:
                return [l.rstrip() for l in f]
        except FileNotFoundError:
            return []

    def close(self):
        for p in self.procs:
            if p.poll() is None:
                p.kill()
                p.wait()


def wait_until(pred, timeout, step=0.05):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if pred():
            return True
        time.sleep(step)
    return pred()


# ------------------------------------------------------------------------------------------------ scenarios

def s1_heartbeat_hijack():
    s = Scenario("s1-heartbeat-hijack")
    try:
        a = s.sleeper()
        s.run("acquire", "A", "job A", "--pid", str(a.pid), "--lease", "2")
        hb = s.start("hbA", "heartbeat", "A", hook="pause_after_first_read")
        s.wait_paused("p1")
        print("  [heartbeat A has read A's record and passed the owner check; it is now preempted]")
        s.run("release", "A")
        b = s.sleeper()
        s.run("acquire", "B", "job B", "--pid", str(b.pid))
        print(f"  lock owner now: {s.owner()}  (B's job starts its heavy step)")
        s.go("p1")
        hb.wait(20)
        print(f"  heartbeat A resumed -> rc={hb.returncode}: {s.output(hb)}")
        print(f"  lock owner after the heartbeat: {s.owner()}   raw={s.raw()[:160]!r}")
        s.run("release", "B")
        s.run("heartbeat", "B")
        a.kill(); a.wait()
        print("  [A's job has ended (holder pid dead); B's job is still running]")
        c = s.start("C", "acquire", "C", "job C")
        got = wait_until(lambda: s.owner() == "C", 15)
        print(f"  waiter C acquired: {got}; B's holder still alive: {b.poll() is None}  -> "
              f"{'TWO HOLDERS (B and C)' if got and b.poll() is None else 'no double holding'}")
        print(f"  C output: {s.output(c)[:300]}")
    finally:
        s.close()


def s1b_heartbeat_into_new_empty_lock():
    s = Scenario("s1b-heartbeat-into-new-empty-lock")
    try:
        a = s.sleeper()
        s.run("acquire", "A", "job A with a fairly long description of the heavy step", "--pid", str(a.pid))
        hb = s.start("hbA", "heartbeat", "A", hook="pause_after_first_read")
        s.wait_paused("p1")
        s.run("release", "A")
        wb = s.start("B", "acquire", "B", "job B", hook="pause_before_record_write")
        s.wait_paused("p2")
        print(f"  [B created the lock with O_EXCL, record not yet written; size={len(s.raw())}]")
        s.go("p1")
        hb.wait(20)
        print(f"  heartbeat A resumed -> rc={hb.returncode}: {s.output(hb)}")
        s.go("p2")
        wb.wait(20)
        print(f"  B -> rc={wb.returncode}: {s.output(wb)}")
        raw = s.raw()
        print(f"  lock file now {len(raw)} bytes, owner={s.owner()}; tail={raw[-60:]!r}")
        s.run("release", "B")
        s.run("status")
    finally:
        s.close()


def s2_release_toctou():
    s = Scenario("s2-release-toctou")
    try:
        a = s.sleeper()
        s.run("acquire", "A", "job A", "--pid", str(a.pid), "--lease", "1")
        a.kill(); a.wait()
        time.sleep(1.5)
        print("  [A's holder pid is dead and its lease expired, but A's runner is still alive and now runs release]")
        rel = s.start("relA", "release", "A", hook="pause_after_first_read")
        s.wait_paused("p1")
        b = s.sleeper()
        wb = s.start("B", "acquire", "B", "job B", "--pid", str(b.pid))
        wb.wait(20)
        print(f"  waiter B -> rc={wb.returncode}: {s.output(wb)[:300]}")
        print(f"  lock owner now: {s.owner()}  (B's job starts)")
        s.go("p1")
        rel.wait(20)
        print(f"  release A resumed -> rc={rel.returncode}: {s.output(rel)}")
        print(f"  lock owner after A's release: {s.owner()}")
        s.run("acquire", "C", "job C")
        print(f"  B's holder alive: {b.poll() is None}; lock owner: {s.owner()} -> "
              f"{'TWO HOLDERS (B and C)' if b.poll() is None and s.owner() == 'C' else 'no double holding'}")
        s.run("release", "B")
    finally:
        s.close()


def s3_auto_release_putback_gap():
    s = Scenario("s3-auto-release-putback")
    try:
        a = s.sleeper()
        s.run("acquire", "A", "job A", "--pid", str(a.pid), "--lease", "1")
        a.kill(); a.wait()
        time.sleep(1.5)
        x = s.start("X", "acquire", "X", "job X", hook="pause_auto_release")
        s.wait_paused("p1")
        print(f"  [X judged A stale and stands before os.replace; queue={s.tickets()}]")
        # X stalls > TICKET_DEAD_SECONDS (or the wall clock jumps forward, e.g. resume from sleep): emulated by
        # back-dating X's ticket mtime by 100 s, which is exactly what _live_tickets measures.
        q = os.path.join(s.dir, "heavy-build.lock.queue")
        for t in s.tickets():
            then = time.time() - 100
            os.utime(os.path.join(q, t), (then, then))
        y_holder = s.sleeper()
        y = s.start("Y", "acquire", "Y", "job Y", "--pid", str(y_holder.pid))
        y.wait(20)
        print(f"  waiter Y -> rc={y.returncode}: {s.output(y)[:260]}")
        print(f"  lock owner: {s.owner()} (Y's job starts its heavy step); queue={s.tickets()}")
        s.go("p1")
        s.wait_paused("p2")
        print(f"  [X moved Y's live lock aside, saw it is not A's record, and stands before the put-back; "
              f"lock file present: {s.raw() is not None}]")
        z = s.start("Z", "acquire", "Z", "job Z")
        z.wait(20)
        print(f"  waiter Z -> rc={z.returncode}: {s.output(z)[:200]}")
        s.go("p2")
        time.sleep(1.0)
        print(f"  X output: {s.output(x)[:400]}")
        print(f"  lock owner: {s.owner()}; Y's holder alive: {y_holder.poll() is None}; aside files: "
              f"{[n for n in os.listdir(s.dir) if '.stale-' in n]} -> "
              f"{'TWO HOLDERS (Y and Z)' if s.owner() == 'Z' and y_holder.poll() is None else 'no double holding'}")
        s.run("release", "Y")
    finally:
        s.close()


def s3b_two_judges_same_stale_lock():
    """Control: two waiters that both judged the same stale record and both reach os.replace."""
    s = Scenario("s3b-two-judges")
    try:
        a = s.sleeper()
        s.run("acquire", "A", "job A", "--pid", str(a.pid), "--lease", "1")
        a.kill(); a.wait()
        time.sleep(1.5)
        sync1, sync2 = os.path.join(s.sync, "x1"), os.path.join(s.sync, "x2")
        os.makedirs(sync1); os.makedirs(sync2)
        x1h, x2h = s.sleeper(), s.sleeper()
        x1 = s.start("X1", "acquire", "X1", "job X1", "--pid", str(x1h.pid), hook="pause_auto_release", HL_SYNC=sync1)
        deadline = time.time() + 30
        while not os.path.exists(os.path.join(sync1, "p1.paused")) and time.time() < deadline:
            time.sleep(0.01)
        q = os.path.join(s.dir, "heavy-build.lock.queue")
        for t in s.tickets():
            then = time.time() - 100
            os.utime(os.path.join(q, t), (then, then))
        x2 = s.start("X2", "acquire", "X2", "job X2", "--pid", str(x2h.pid), hook="pause_auto_release", HL_SYNC=sync2)
        while not os.path.exists(os.path.join(sync2, "p1.paused")) and time.time() < deadline:
            time.sleep(0.01)
        print("  [X1 and X2 both judged A stale and both stand before os.replace]")
        open(os.path.join(sync1, "p1.go"), "w").close()
        time.sleep(0.5)
        open(os.path.join(sync2, "p1.go"), "w").close()
        open(os.path.join(sync1, "p2.go"), "w").close(); open(os.path.join(sync2, "p2.go"), "w").close()
        got = wait_until(lambda: s.owner() in ("X1", "X2"), 15)
        time.sleep(1.5)
        print(f"  lock owner: {s.owner()}; X1 exited={x1.poll() is not None} X2 exited={x2.poll() is not None}; "
              f"auto-release lines={sum(' auto-released ' in l for l in s.served())}")
        print(f"  X1: {s.output(x1)[:220]}")
        print(f"  X2: {s.output(x2)[:220]}")
    finally:
        s.close()


def s4_heartbeat_keeps_dead_holder():
    s = Scenario("s4-heartbeat-keeps-dead-holder")
    try:
        a = s.sleeper()
        s.run("acquire", "A", "job A", "--pid", str(a.pid), "--lease", "3")
        a.kill(); a.wait()
        print("  [A's holder pid is dead; an orphaned heartbeat loop (README bash pattern after the job shell was "
              "killed) keeps calling heartbeat A every 1 s]")
        loop = subprocess.Popen([PY, "-I", "-c",
                                 "import subprocess,sys,time\n"
                                 "end=time.time()+14\n"
                                 "while time.time()<end:\n"
                                 "    subprocess.run([sys.executable,'-I',sys.argv[1],'heartbeat','A'],capture_output=True)\n"
                                 "    time.sleep(1)\n", HEAVY], env=s.env())
        s.procs.append(loop)
        b = s.start("B", "acquire", "B", "job B")
        time.sleep(12)
        print(f"  after 12 s (4 leases): owner={s.owner()}, B still waiting={b.poll() is None}, "
              f"auto-release lines={sum(' auto-released ' in l for l in s.served())}")
        st = s.run("status")
        loop.wait(10)
        got = wait_until(lambda: s.owner() == "B", 10)
        print(f"  heartbeat loop stopped -> B acquired within 10 s: {got}")
    finally:
        s.close()


def s5_lease_renewed_before_removal():
    s = Scenario("s5-lease-renewed-before-removal")
    try:
        a = s.sleeper()
        s.run("acquire", "A", "job A", "--pid", str(a.pid), "--lease", "2")
        a.kill(); a.wait()
        time.sleep(2.5)
        x = s.start("X", "acquire", "X", "job X", hook="pause_auto_release")
        s.wait_paused("p1")
        s.run("heartbeat", "A")
        lease_until = json.loads(s.raw())["lease_until"]
        print(f"  [A's lease renewed by a heartbeat after X judged it: lease_until is {lease_until - time.time():+.2f} s "
              f"from now]")
        s.go("p1")
        x.wait(20)
        removed_at = time.time()
        print(f"  X -> rc={x.returncode}: {s.output(x)[:300]}")
        print(f"  lock removed while lease_until was still {lease_until - removed_at:+.2f} s in the future "
              f"(contract: only when the lease has expired)")
    finally:
        s.close()


def s6_killed_between_create_and_write():
    s = Scenario("s6-killed-between-create-and-write")
    try:
        a = s.sleeper()
        acq = s.start("A", "acquire", "A", "job A", "--pid", str(a.pid), hook="pause_after_create")
        s.wait_paused("p1")
        print(f"  [A's acquire created the lock with O_EXCL and is between create and write; size={len(s.raw())} "
              "bytes; the process is now killed (Ctrl+C / taskkill / tool timeout)]")
        acq.kill(); acq.wait()
        a.kill(); a.wait()
        s.run("status")
        s.run("release", "A")
        b = s.start("B", "acquire", "B", "job B", SHARED_LOCK_STALE_SECONDS="1")
        time.sleep(6)
        print(f"  after 6 s: B still waiting={b.poll() is None}; owner={s.owner()}; B: {s.output(b)[:250]}")
    finally:
        s.close()


def s7_heartbeat_killed_before_ftruncate():
    s = Scenario("s7-heartbeat-killed-before-ftruncate")
    try:
        a = s.sleeper()
        s.run("acquire", "A", "job A", "--pid", str(a.pid))
        before = s.raw()
        hb = s.start("hbA", "heartbeat", "A", hook="pause_before_ftruncate", HL_ROUND_TIME="1")
        s.wait_paused("p1")
        hb.kill(); hb.wait()
        after = s.raw()
        print(f"  record before heartbeat: {len(before)} bytes; after heartbeat killed between write and ftruncate: "
              f"{len(after)} bytes; tail={after[-40:]!r}")
        s.run("status")
        s.run("release", "A")
        a.kill(); a.wait()
        b = s.start("B", "acquire", "B", "job B")
        time.sleep(4)
        print(f"  holder now dead; after 4 s B still waiting={b.poll() is None}; owner={s.owner()}")
    finally:
        s.close()


def natural_shrink_rate(samples=200000):
    """How often a heartbeat's JSON is shorter than the record it overwrites (only lease_until changes)."""
    import random
    shorter = 0
    now_ticks = time.time_ns() // 100                    # Windows time.time(): 100 ns ticks, returned as ns / 1e9
    for _ in range(samples):
        k_old = now_ticks + random.randrange(10 ** 10)
        k_new = k_old + random.randrange(1, 6 * 10 ** 9)   # next heartbeat within 600 s
        old = (k_old * 100) / 1e9 + 1800                 # lease_until as written by acquire / previous heartbeat
        new = (k_new * 100) / 1e9 + 1800.0               # lease_until as written by this heartbeat
        if len(json.dumps(new)) < len(json.dumps(old)):
            shorter += 1
    print(f"\nnatural rate: heartbeat JSON shorter than the previous record in {100.0 * shorter / samples:.1f}% of "
          f"heartbeats (repr length of lease_until)", flush=True)


if __name__ == "__main__":
    which = sys.argv[1:] or ["s1", "s2", "s3", "s4", "s5", "s6", "s7", "rate"]
    table = {"s1": s1_heartbeat_hijack, "s1b": s1b_heartbeat_into_new_empty_lock, "s2": s2_release_toctou, "s3": s3_auto_release_putback_gap,
             "s3b": s3b_two_judges_same_stale_lock, "s4": s4_heartbeat_keeps_dead_holder, "s5": s5_lease_renewed_before_removal,
             "s6": s6_killed_between_create_and_write, "s7": s7_heartbeat_killed_before_ftruncate,
             "rate": natural_shrink_rate}
    for key in which:
        table[key]()
