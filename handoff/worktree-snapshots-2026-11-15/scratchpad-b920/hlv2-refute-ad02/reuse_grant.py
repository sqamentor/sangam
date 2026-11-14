"""Refutation experiment for HLV2-AD-02. COPY of heavy_lock v2 (./code) and private lock folders (./locks) only;
never the live ff9f2caf scratchpad.

N independent lock folders. In each: H holds an old-style lock; a runner stand-in R (live process) is started; a
queued acquire Q --pid R --lease 5 waits behind H; R is killed while Q is queued (Q keeps running, as after a plain
Stop-Process of a runner). Group B (even i): H is released only after R's pid is reused, so Q's grant-time probe
(shared_lock.py:430) sees the reuser. Group C (odd i): H is released at once, so Q takes the lock while the pid is
dead (holder_start null), and the reuse happens afterwards. A spin loop creates unrelated processes (cmd.exe,
suspended, no console) until one of them is handed a target pid, then a waiter W shows what the next job sees.
"""
import json, os, shutil, subprocess, sys, time

BASE = os.path.dirname(os.path.abspath(__file__))
CODE = os.path.join(BASE, "code")
HEAVY = os.path.join(CODE, "heavy_lock.py")
LOCKS = os.path.join(BASE, "locks")
PY = sys.executable
assert "ff9f2caf" not in BASE.lower() and "ff9f2caf" not in HEAVY.lower()
sys.dont_write_bytecode = True
sys.path.insert(0, CODE)
import shared_lock as SL  # the copy; used only for probe_process / iso diagnostics

CMD = os.path.join(os.environ["SystemRoot"], "System32", "cmd.exe")
CREATE_SUSPENDED, DETACHED_PROCESS = 0x4, 0x8
N = int(sys.argv[1]) if len(sys.argv) > 1 else 20
BUDGET = float(sys.argv[2]) if len(sys.argv) > 2 else 150.0
LEASE = "5"


def env_for(d):
    e = {k: v for k, v in os.environ.items() if not k.startswith("SHARED_LOCK_") and k != "HEAVY_LOCK_PID"}
    e.update(SHARED_LOCK_DIR=d, SHARED_LOCK_POLL_SECONDS="0.2")
    return e


def tool(d, *args):
    r = subprocess.run([PY, "-I", HEAVY, *args], capture_output=True, text=True, env=env_for(d), timeout=30)
    return r.returncode, r.stdout.strip(), r.stderr.strip()


def bg(d, name, *args):
    out = open(os.path.join(d, name + ".out"), "w")
    err = open(os.path.join(d, name + ".err"), "w")
    p = subprocess.Popen([PY, "-I", HEAVY, *args], env=env_for(d), stdout=out, stderr=err)
    out.close(); err.close()
    return p


def spawn():
    """An unrelated live process that costs nothing: cmd.exe created suspended, without a console."""
    return subprocess.Popen([CMD], creationflags=CREATE_SUSPENDED | DETACHED_PROCESS, close_fds=True)


def free(p):
    try:
        p.kill()
    except OSError:
        pass
    try:
        p.wait(5)
    except Exception:
        pass
    try:
        p._handle.Close()   # drop our handle so the pid can really be freed and reused
    except Exception:
        pass


def rec(d):
    try:
        with open(os.path.join(d, "heavy-build.lock"), encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return None


def readf(d, name):
    try:
        with open(os.path.join(d, name), encoding="utf-8", errors="replace") as f:
            return f.read().strip()
    except OSError:
        return ""


def t():
    return time.strftime("%H:%M:%S", time.gmtime()) + f".{int(time.time() * 1000) % 1000:03d}"


live = []          # every process we started, for cleanup
try:
    shutil.rmtree(LOCKS, ignore_errors=True)
    dirs = []
    for i in range(N):
        d = os.path.join(LOCKS, f"r{i:02d}")
        os.makedirs(d)
        dirs.append(d)
        rc, out, err = tool(d, "acquire", "H", "blocking holder (old-style)")
        assert rc == 0, (i, rc, out, err)
    runners, rstart, Q = {}, {}, {}
    for i, d in enumerate(dirs):
        r = spawn(); live.append(r)
        runners[i] = r
        rstart[i] = SL.probe_process(r.pid)[1]
        q = bg(d, "Q", "acquire", f"customwf-q{i:02d}", "queued job", "--pid", str(r.pid), "--lease", LEASE)
        live.append(q); Q[i] = q
    time.sleep(2.0)
    for i, d in enumerate(dirs):
        tickets = os.listdir(os.path.join(d, "heavy-build.lock.queue"))
        assert Q[i].poll() is None and any(f"customwf-q{i:02d}" in n for n in tickets), (i, tickets, Q[i].poll())
    print(f"{t()} {N} queued acquires waiting behind H; each --pid is a live runner stand-in "
          f"(pids {sorted(r.pid for r in runners.values())})")
    pid_of = {i: runners[i].pid for i in runners}
    for i in runners:
        free(runners[i])
    time.sleep(0.3)
    gone = {i: SL.probe_process(pid_of[i]) for i in runners}
    print(f"{t()} runner stand-ins killed + handles closed; probe after kill: "
          + ", ".join(sorted({f'{a}/{dt}' for (a, _, dt) in gone.values()})))
    print(f"{t()} queued acquires still alive after their runner died: {sum(Q[i].poll() is None for i in Q)}/{N}")
    groupB = [i for i in range(N) if i % 2 == 0]
    groupC = [i for i in range(N) if i % 2 == 1]
    for i in groupC:
        tool(dirs[i], "release", "H")
    for i in groupC:
        try:
            Q[i].wait(10)
        except subprocess.TimeoutExpired:
            pass
    c_null = [i for i in groupC if (rec(dirs[i]) or {}).get("holder_start", "x") is None]
    print(f"{t()} group C: H released while the runner pid is dead -> Q exit codes "
          f"{sorted({Q[i].returncode for i in groupC}, key=str)}; records with holder_pid=<dead pid> and "
          f"holder_start=null: {len(c_null)}/{len(groupC)}")
    ex = rec(dirs[groupC[0]])
    print(f"      example record (r{groupC[0]:02d}): {json.dumps(ex)}")

    # ---- spin: unrelated processes until a target pid is handed out again
    targets = {pid_of[i]: i for i in range(N)}
    hits, spawned, t0 = {}, 0, time.time()
    while time.time() - t0 < BUDGET:
        p = spawn(); spawned += 1
        i = targets.get(p.pid)
        if i is not None and i not in hits:
            live.append(p)
            hits[i] = p
            print(f"{t()} pid {p.pid} (runner of r{i:02d}, group {'B' if i % 2 == 0 else 'C'}) handed to an unrelated "
                  f"suspended cmd.exe after {spawned} spawns / {time.time() - t0:.1f} s")
            if any(k % 2 == 0 for k in hits) and any(k % 2 == 1 for k in hits):
                break
        else:
            free(p)
    print(f"{t()} spin done: {spawned} spawns in {time.time() - t0:.1f} s, hits {sorted(hits)}")

    # ---- what the lock does with each reused pid
    W = {}
    for i, reuser in sorted(hits.items()):
        d = dirs[i]
        if i % 2 == 0:  # group B: grant happens now, while the pid belongs to the reuser
            tool(d, "release", "H")
            try:
                Q[i].wait(10)
            except subprocess.TimeoutExpired:
                pass
        r = rec(d)
        rs = SL.probe_process(reuser.pid)[1]
        print(f"--- r{i:02d} group {'B' if i % 2 == 0 else 'C'}: Q exit {Q[i].returncode}; record holder_pid={r.get('holder_pid')} "
              f"holder_start={r.get('holder_start')} ({SL.iso(r.get('holder_start')) if r.get('holder_start') else 'null'}); "
              f"dead runner started {SL.iso(rstart[i])} ({rstart[i]}); reuser started {SL.iso(rs)} ({rs})")
        st = tool(d, "status")[1].splitlines()
        print("      status| " + st[1])
        print("      status| " + st[2])
        W[i] = bg(d, "W", "acquire", "W", "next heavy job")
        live.append(W[i])
    # controls: the other targets (pid not reused by the spin)
    ctrl = [i for i in range(N) if i not in hits]
    for i in ctrl:
        if i % 2 == 0:
            tool(dirs[i], "release", "H")
    for i in ctrl:
        if i % 2 == 0:
            try:
                Q[i].wait(10)
            except subprocess.TimeoutExpired:
                pass
    cW, cstart = {}, time.time()
    for i in ctrl:
        cW[i] = bg(dirs[i], "W", "acquire", "W", "next heavy job")
        live.append(cW[i])
    time.sleep(14)
    for i in sorted(W):
        state = "STILL WAITING" if W[i].poll() is None else f"exit {W[i].returncode}"
        print(f"{t()} r{i:02d}: waiter W 14 s after the lease (5 s) expired, real holder long dead: {state}")
        for line in readf(dirs[i], "W.err").splitlines()[:1]:
            print("      W.err| " + line)
    done = [i for i in ctrl if cW[i].poll() is not None]
    stuck = [i for i in ctrl if cW[i].poll() is None]
    print(f"{t()} controls (pid not reused by the spin): {len(done)}/{len(ctrl)} waiters got the lock "
          f"(auto-release after the lease); still waiting: {stuck}")
    for i in stuck:
        print(f"      r{i:02d} stuck: {readf(dirs[i], 'W.err').splitlines()[:1]} probe now: {SL.probe_process(pid_of[i])}")
    # the lock is only freed when the unrelated process ends
    for i, reuser in sorted(hits.items()):
        free(reuser)
    time.sleep(3)
    for i in sorted(W):
        state = "still waiting" if W[i].poll() is None else f"exit {W[i].returncode} ({readf(dirs[i], 'W.out')})"
        print(f"{t()} r{i:02d}: after the unrelated process was killed, waiter W: {state}")
        print("      served| " + readf(dirs[i], "heavy-build.lock.served.log").splitlines()[-2])
finally:
    for p in live:
        try:
            running = p.returncode is None and p.poll() is None
        except Exception:
            running = False
        if running:
            free(p)
        else:
            try:
                p._handle.Close()
            except Exception:
                pass
