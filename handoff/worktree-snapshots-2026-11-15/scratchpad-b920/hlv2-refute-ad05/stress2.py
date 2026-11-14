"""UNPATCHED stress for HLV2-AD-05 (no injected sleeps; code/shared_lock.py is byte-identical to the live v2 source).

Roles, all on a temp lock folder:
  HB-A  tight loop of the unmodified heartbeat(cfg, "customwf-a")          (amplifies "a heartbeat is in flight")
  A     loop: grant A (unmodified acquire when free), short hold, unmodified release(cfg, "customwf-a")
  B     mode "tool": unmodified acquire(cfg, "customwf-b", ...) with SHARED_LOCK_POLL_SECONDS=poll
        mode "spin": B's grant step only -- the exact calls of acquire lines 407 and 424-435 (O_CREAT|O_EXCL create,
                     probe_process, os.fdopen + json.dump of the same record) retried in a tight loop, i.e. the
                     instant at which B's 5 s poll happens to land; then B checks and releases with the
                     unmodified read()/release().
After each grant B reads the record with the unmodified read(); anything but its own record is a violation:
  overwritten  = A's in-flight heartbeat replaced B's complete record (finding's main scenario)
  torn         = A's heartbeat wrote between B's create and B's dump, B's shorter dump left A's tail (AD-08 shape)
usage: python -I stress2.py <tool|spin> [seconds=20] [poll=0.001]
"""
import importlib.util, json, multiprocessing as mp, os, random, shutil, subprocess, sys, time

ROOT = os.path.dirname(os.path.abspath(__file__))
CODE = os.path.join(ROOT, "code")
LOCK_NAME = "heavy-build.lock"


def load(lockdir, poll):
    assert "ff9f2caf" not in lockdir
    os.environ["SHARED_LOCK_DIR"] = lockdir
    os.environ["SHARED_LOCK_POLL_SECONDS"] = str(poll)
    for k in ("SHARED_LOCK_NO_AUTORELEASE", "SHARED_LOCK_STALE_SECONDS", "HEAVY_LOCK_PID"):
        os.environ.pop(k, None)
    sys.dont_write_bytecode = True
    spec = importlib.util.spec_from_file_location("shared_lock", os.path.join(CODE, "shared_lock.py"))
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m, m.Config(LOCK_NAME, lockdir)


def quiet():
    devnull = open(os.devnull, "w")
    sys.stdout = devnull
    sys.stderr = devnull


def hb_loop(lockdir, owner, stop, out):
    sl, cfg = load(lockdir, 0.001)
    quiet()
    ok = refused = crashed = 0
    calls = []                                         # (start, end, rc) of every call, wall clock
    while not stop.is_set():
        t0 = time.time()
        try:
            rc = sl.heartbeat(cfg, owner)
        except PermissionError:                        # line 496 open of a delete-pending file; the CLI would crash
            rc = -1
            crashed += 1
        calls.append((t0, time.time(), rc))
        if rc == 0:
            ok += 1
        else:
            refused += 1
    durs = sorted(e - s for s, e, _ in calls)
    out.put(("hb", owner, ok, refused, crashed))
    out.put(("hbcalls", [c for c in calls if c[2] == 0][-400:],
             {"n": len(durs), "p50_ms": 1000 * durs[len(durs) // 2] if durs else None,
              "p99_ms": 1000 * durs[int(len(durs) * 0.99)] if durs else None, "max_ms": 1000 * durs[-1] if durs else None}))


def holder_a(lockdir, pid_a, stop, out, go_b, b_done):
    """A: take the lock, hold briefly, release; then wait until B has been granted and released."""
    sl, cfg = load(lockdir, 0.0005)
    quiet()
    rounds = busy = 0
    while not stop.is_set():
        while True:
            try:
                sl.acquire(cfg, "customwf-a", "job customwf-a", holder_pid=pid_a, lease_seconds=600)
                break
            except PermissionError:
                out.put(("acq-eacces",))
        rounds += 1
        b_done.clear()
        go_b.set()                                     # B starts trying to take the lock while A still holds it
        time.sleep(random.uniform(0.001, 0.004))
        while True:
            rc = sl.release(cfg, "customwf-a")
            if rc == 1:
                busy += 1
                continue
            break
        if rc == 2:                                    # A's own record was replaced (cannot happen in this design)
            out.put(("violation", "customwf-a", rounds, "release-refused", json.dumps(sl.read(cfg))))
            stop.set()
            break
        while not stop.is_set() and not b_done.wait(0.05):
            pass
    out.put(("a", rounds, busy))


def b_grant_spin(sl, cfg, pid_b, stop):
    """Exactly acquire()'s grant step (lines 407, 424-435), retried in a tight loop."""
    while not stop.is_set():
        try:
            fd = os.open(cfg.lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
        except (FileExistsError, PermissionError):   # PermissionError = delete-pending (Windows); acquire() would crash here
            continue
        epoch = time.time()
        record = {"owner": "customwf-b", "what": "job customwf-b", "since": sl.now(), "epoch": epoch,
                  "held_by": sl.HELD_BY_NOTE, "acquirer_pid": os.getpid(), "acquirer_pid_note": sl.ACQUIRER_PID_NOTE}
        _, start, _ = sl.probe_process(pid_b)
        record.update({"holder_pid": pid_b, "holder_start": start, "lease_until": epoch + 600, "lease_seconds": 600})
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(record, f)
        return True
    return False


def holder_b(lockdir, pid_b, mode, poll, stop, out, go_b, b_done):
    sl, cfg = load(lockdir, poll)
    quiet()
    grants = busy = 0
    while not stop.is_set():
        if not go_b.wait(0.05):
            continue
        go_b.clear()
        if mode == "spin":
            if not b_grant_spin(sl, cfg, pid_b, stop):
                break
        else:
            while True:
                try:
                    sl.acquire(cfg, "customwf-b", "job customwf-b", holder_pid=pid_b, lease_seconds=600)
                    break
                except PermissionError:
                    out.put(("acq-eacces",))
        grants += 1
        t_granted = time.time()
        time.sleep(0.002)                              # let any in-flight heartbeat finish
        seen = sl.read(cfg)
        if seen is None or seen.get("owner") != "customwf-b":
            try:
                raw = open(cfg.lock, "rb").read().decode("utf-8", "replace")
            except OSError as exc:
                raw = repr(exc)
            kind = "torn" if seen and seen.get("owner") == "?" else ("gone" if seen is None else "overwritten")
            out.put(("violation", "customwf-b", grants, kind, raw, t_granted))
            stop.set()
            break
        while True:
            rc = sl.release(cfg, "customwf-b")
            if rc == 1:
                busy += 1
                continue
            break
        if rc == 2:
            out.put(("violation", "customwf-b", grants, "release-refused", json.dumps(sl.read(cfg))))
            stop.set()
            break
        b_done.set()
    out.put(("b", grants, busy))


def trial(idx, budget, mode, poll, pids):
    lockdir = os.path.join(ROOT, "locks", f"stress2-{mode}-{idx}")
    shutil.rmtree(lockdir, ignore_errors=True)
    os.makedirs(lockdir)
    ctx = mp.get_context("spawn")
    stop, out, go_b, b_done = ctx.Event(), ctx.Queue(), ctx.Event(), ctx.Event()
    procs = [ctx.Process(target=hb_loop, args=(lockdir, "customwf-a", stop, out)),
             ctx.Process(target=holder_a, args=(lockdir, pids[0], stop, out, go_b, b_done)),
             ctx.Process(target=holder_b, args=(lockdir, pids[1], mode, poll, stop, out, go_b, b_done))]
    for p in procs:
        p.start()
    t0 = time.time()
    while time.time() - t0 < budget and not stop.is_set():
        time.sleep(0.05)
    stop.set()
    for p in procs:
        p.join(3)
    for p in procs:
        if p.is_alive():
            p.terminate()
            p.join(2)
    msgs = []
    while True:
        try:
            msgs.append(out.get(timeout=0.3))
        except Exception:
            break
    return time.time() - t0, msgs


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "spin"
    total = float(sys.argv[2]) if len(sys.argv) > 2 else 20.0
    poll = float(sys.argv[3]) if len(sys.argv) > 3 else 0.001
    sleepers = [subprocess.Popen([sys.executable, "-I", "-c", "import time; time.sleep(900)"]) for _ in range(2)]
    pids = [s.pid for s in sleepers]
    t_start, idx, handovers, kinds, hb_ok, busy, eacces, hb_crash = time.time(), 0, 0, {}, 0, 0, 0, 0
    try:
        while time.time() - t_start < total:
            idx += 1
            elapsed, msgs = trial(idx, total - (time.time() - t_start), mode, poll, pids)
            hbcalls = next((m for m in msgs if m[0] == "hbcalls"), None)
            if hbcalls:
                print(f"trial {idx}: heartbeat() call durations {hbcalls[2]}")
            for m in msgs:
                if m[0] == "violation" and hbcalls and len(m) > 5:
                    tg = m[5]
                    culprit = [c for c in hbcalls[1] if c[0] <= tg <= c[1] + 0.003]
                    for s, e, rc in culprit[-2:]:
                        print(f"  successful heartbeat call spanning B's grant: started {1000 * (s - tg):+.1f} ms, "
                              f"returned {1000 * (e - tg):+.1f} ms relative to B's grant (duration {1000 * (e - s):.1f} ms)")
                    if m[4].startswith("{"):
                        try:
                            r = json.loads(m[4])
                            print(f"  record found by B: owner={r['owner']} epoch={r['epoch']:.3f} "
                                  f"lease_until-epoch={r['lease_until'] - r['epoch']:.3f}s (acquire writes exactly "
                                  f"{r['lease_seconds']}s; anything else was written by heartbeat)")
                        except (ValueError, KeyError):
                            pass
            for m in msgs:
                if m[0] == "b":
                    handovers += m[1]
                    busy += m[2]
                elif m[0] == "a":
                    busy += m[2]
                elif m[0] == "hb":
                    hb_ok += m[2]
                    hb_crash += m[4]
                elif m[0] == "acq-eacces":
                    eacces += 1
                elif m[0] == "violation":
                    kinds[m[3]] = kinds.get(m[3], 0) + 1
                    if sum(kinds.values()) <= 4:
                        print(f"trial {idx} at {elapsed:.1f}s: {m[3]} seen by {m[1]} at B grant #{m[2]}: {m[4][:430]}")
    finally:
        for s in sleepers:
            s.kill()
    print(f"SUMMARY mode={mode} poll={poll}s wall={time.time() - t_start:.1f}s trials={idx} B-grants={handovers} "
          f"violations={sum(kinds.values())} kinds={kinds} heartbeats-ok={hb_ok} release-busy-retries={busy} "
          f"acquire-PermissionError-retried={eacces} heartbeat-PermissionError={hb_crash}")
