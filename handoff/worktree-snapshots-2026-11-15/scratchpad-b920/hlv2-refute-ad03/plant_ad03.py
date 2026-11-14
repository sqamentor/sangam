"""AD-03 reproduction on a COPY of v2 against temp lock folders (SHARED_LOCK_DIR) only.

1. NtQuerySystemInformation creation time == GetProcessTimes for accessible pids (offset check for the proposed fix).
2. Tally of the v2 probe over every running pid (how many are 'unknown').
3. Planted: holder_pid = a live process this user cannot open, holder_start 1 h before that process was created,
   lease expired 60 s ago -> status / evaluate() / a real waiter.
4. Controls with the same planted shape: (i) accessible live pid with a wrong holder_start (selftest case e),
   (ii) a freed pid -> both auto-released.
"""
import collections
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import time

BASE = os.path.dirname(os.path.abspath(__file__))
CODE = os.path.join(BASE, "code")
HEAVY = os.path.join(CODE, "heavy_lock.py")
LOCKS = os.path.join(BASE, "locks")
PY = sys.executable


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


sl = load("shared_lock_copy", os.path.join(CODE, "shared_lock.py"))
nt = load("ntproc", os.path.join(BASE, "ntproc.py"))


def env_for(d):
    env = {k: v for k, v in os.environ.items() if not k.startswith("SHARED_LOCK_") and k != "HEAVY_LOCK_PID"}
    env.update(SHARED_LOCK_DIR=d, SHARED_LOCK_POLL_SECONDS="0.2")
    return env


def fresh_dir(name):
    d = os.path.join(LOCKS, name)
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(d)
    assert os.path.commonpath([d, BASE]) == BASE
    return d


def plant(d, holder_pid, holder_start, owner):
    now = time.time()
    record = {"owner": owner, "what": "planted: runner died", "since": sl.iso(now - 2400), "epoch": now - 2400,
              "held_by": sl.HELD_BY_NOTE, "acquirer_pid": 1, "acquirer_pid_note": sl.ACQUIRER_PID_NOTE,
              "holder_pid": holder_pid, "holder_start": holder_start, "lease_until": now - 60, "lease_seconds": 1800}
    with open(os.path.join(d, "heavy-build.lock"), "w", encoding="utf-8") as f:
        json.dump(record, f)
    return record


def run_case(name, holder_pid, holder_start, wait_s=6.0):
    d = fresh_dir(name)
    env = env_for(d)
    rec = plant(d, holder_pid, holder_start, f"dead-{name}")
    st = subprocess.run([PY, "-I", HEAVY, "status"], env=env, capture_output=True, text=True)
    hp = next((l for l in st.stdout.splitlines() if l.startswith("holder pid:")), "")
    os.environ.pop("SHARED_LOCK_DIR", None)          # this process only: Config must resolve to the temp dir d
    cfg = sl.Config("heavy-build.lock", d)
    assert cfg.dir == os.path.abspath(d), cfg.dir
    action, msg = sl.evaluate(cfg, rec)             # pure decision: reads nothing but the pid probe
    w = subprocess.Popen([PY, "-I", HEAVY, "acquire", "W", "next job"], env=env, stdout=subprocess.PIPE,
                         stderr=subprocess.PIPE, text=True)
    try:
        rc = w.wait(wait_s)
        waited = f"exit {rc}"
    except subprocess.TimeoutExpired:
        waited = f"STILL WAITING after {wait_s:.0f}s"
        w.kill()
    out, err = w.communicate()
    served = ""
    try:
        with open(os.path.join(d, "heavy-build.lock.served.log"), encoding="utf-8") as f:
            served = f.read().strip()
    except FileNotFoundError:
        served = "<no served log>"
    try:
        with open(os.path.join(d, "heavy-build.lock"), encoding="utf-8") as f:
            now_owner = json.load(f).get("owner")
    except FileNotFoundError:
        now_owner = None
    print(f"--- case {name}: holder_pid={holder_pid} holder_start={sl.iso(holder_start)}")
    print(f"   status    | {hp}")
    print(f"   evaluate  | {action}: {(msg or '')[:220]}")
    print(f"   waiter    | {waited}; stdout={out.strip()!r}")
    print(f"   waiter err| {(err or '').strip().splitlines()[:2]}")
    print(f"   served    | {served.splitlines()[-2:] if served else served}")
    print(f"   lock owner now: {now_owner!r}")
    if now_owner == "W":
        subprocess.run([PY, "-I", HEAVY, "release", "W"], env=env, capture_output=True, text=True)


table = nt.nt_table()
now = time.time()
me = os.getpid()
gpt = sl.probe_process(me)[1]
print(f"self pid {me}: GetProcessTimes={gpt:.4f} NtQSI={table[me][0]:.4f} diff={abs(gpt - table[me][0]):.6f}")

tally = collections.Counter()
by_session = collections.Counter()
inacc = []
max_diff = 0.0
for pid, (create, image, session) in sorted(table.items()):
    if pid == 0:
        continue
    alive, start, detail = sl.probe_process(pid)
    tally[(alive, start is not None, detail)] += 1
    if alive is None:
        by_session[session] += 1
        inacc.append((pid, create, image, session, detail))
    elif start is not None and create is not None:
        max_diff = max(max_diff, abs(start - create))
print(f"{len(table) - 1} processes probed with the v2 probe (medium integrity caller)")
for (alive, has_start, detail), n in tally.most_common():
    print(f"   {n:4d}  alive={alive} start={'yes' if has_start else 'NONE'} ({detail})")
print(f"   max |GetProcessTimes - NtQSI CreateTime| over accessible pids: {max_diff:.6f} s")
print(f"   inaccessible by session: {dict(by_session)}")
ages = collections.Counter()
for pid, create, image, session, detail in inacc:
    age = now - create if create else None
    bucket = ("<30m" if age < 1800 else "<2h" if age < 7200 else "<24h" if age < 86400 else ">=24h") if age else "?"
    ages[bucket] += 1
print(f"   inaccessible by age: {dict(ages)}")
recent = sorted((p for p in inacc if p[1] and now - p[1] < 7200), key=lambda p: -p[1])
print("   inaccessible processes created in the last 2 h:",
      [f"{img}:{pid} ({(now - c) / 60:.0f} min old)" for pid, c, img, s, _ in recent][:20])

# target: an inaccessible, long-lived process
target = next(p for p in sorted(inacc, key=lambda p: p[1] or 1e18) if p[0] > 4 and p[1])
tpid, tcreate, timage, tsess, tdetail = target
print(f"\ntarget: {timage} pid {tpid} session {tsess}, created {sl.iso(tcreate)} (NtQSI); "
      f"v2 probe -> {sl.probe_process(tpid)}")

run_case("unknown-reuser", tpid, tcreate - 3600.0)
print(f"   proposed fix would compare: |holder_start - NtQSI CreateTime| = {abs((tcreate - 3600.0) - tcreate):.0f} s "
      f"> {sl.START_TOLERANCE_SECONDS} -> 'no' (pid reused)")

# control (i): accessible live pid, wrong holder_start (selftest case e shape)
sleeper = subprocess.Popen([PY, "-I", "-c", "import time; time.sleep(60)"])
time.sleep(0.3)
real_start = sl.probe_process(sleeper.pid)[1]
run_case("accessible-reuser", sleeper.pid, real_start - 3600.0)
sleeper.kill()
sleeper.wait()

# control (ii): freed pid
dead = subprocess.Popen([PY, "-I", "-c", "pass"])
dead.wait()
dead_start = time.time() - 1
dead_pid = dead.pid
dead._handle.Close()
print(f"\nfreed pid {dead_pid}: v2 probe now -> {sl.probe_process(dead_pid)}")
run_case("freed-pid", dead_pid, dead_start)
