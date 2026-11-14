"""Refutation harness for CONC-02 (heavy_lock v2): the README bash heartbeat loop after a kill of the job shell.

Runs ONLY against fresh copies of shared_lock.py / heavy_lock.py placed under this folder, with SHARED_LOCK_DIR set to
a lock folder under this folder. It never reads or writes the live lock folder.

    python -I harness.py <variant> [<variant> ...]
variants: winpid-single, msys-single, winpid-tree, winpid-term, winpid-kill9
    pid mode  winpid = --pid $(cat /proc/$$/winpid)   (Windows pid of the job shell, i.e. CONC-01 already fixed)
              msys   = --pid $$                       (README.md:105 verbatim)
    kill mode single = taskkill /F /PID <job shell>          (no /T: only the shell)
              tree   = taskkill /F /T /PID <job shell>       (whole process tree)
              term   = SIGTERM to the job shell (MSYS kill)  (soft kill: does the EXIT trap run?)
              kill9  = SIGKILL to the job shell (MSYS kill -KILL)
"""
import ctypes
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import time
from ctypes import wintypes

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
SRC = r"D:\GITHUB  Project\IFMS\00_MASTER_INDEX\session_tools\heavy_lock_v2"
BASH = r"C:\Program Files\Git\usr\bin\bash.exe"
PY = sys.executable
LIVE_MARK = "ff9f2caf"           # the live lock folder's session id; this harness must never point there
LOCK = "heavy-build.lock"
BEAT, LEASE = 2, 6               # README: beat 600 s, lease 1800 s (same 1:3 ratio)

JOB_SH = r'''#!/bin/bash
# README.md:105-107 bash pattern; differences: beat $BEAT s (README 600), --lease $LEASE (README default 1800),
# heartbeat output appended to beats.log instead of /dev/null (to count beats), heavy step = sleep 6001.
export PATH="/usr/bin:$PATH"
# On this machine /usr/bin/sleep exits 127 (STATUS_DLL_INIT_FAILED: no exploit-mitigation override for sleep.exe),
# so a working sleep is substituted; the README loop lines below are otherwise unchanged.
sleep() { python -I -c "import sys, time; time.sleep(float(sys.argv[1]))" "$1"; }
LOCK="$1"; BEAT="$2"; LEASE="$3"; OUT="$4"; PIDMODE="$5"
WINPID=$(cat /proc/$$/winpid)
if [ "$PIDMODE" = winpid ]; then HP=$WINPID; else HP=$$; fi
echo "job_msys_pid=$$ job_winpid=$WINPID holder_pid=$HP" > "$OUT/job.info"
python -I "$LOCK" acquire sangam "Trial 2 retest" --pid $HP --lease $LEASE >> "$OUT/acquire.log" 2>&1 || exit 1
( while sleep $BEAT; do python -I "$LOCK" heartbeat sangam >> "$OUT/beats.log" 2>&1; done ) & HB=$!
echo "hb_msys_pid=$HB hb_winpid=$(cat /proc/$HB/winpid)" >> "$OUT/job.info"
trap 'kill $HB 2>/dev/null; python -I "$LOCK" release sangam >> "$OUT/trap.log" 2>&1' EXIT
sleep 6001
'''


class PE32(ctypes.Structure):
    _fields_ = [("dwSize", wintypes.DWORD), ("cntUsage", wintypes.DWORD), ("th32ProcessID", wintypes.DWORD),
                ("th32DefaultHeapID", ctypes.c_size_t), ("th32ModuleID", wintypes.DWORD),
                ("cntThreads", wintypes.DWORD), ("th32ParentProcessID", wintypes.DWORD),
                ("pcPriClassBase", ctypes.c_long), ("dwFlags", wintypes.DWORD), ("szExeFile", ctypes.c_wchar * 260)]


def processes():
    k32 = ctypes.WinDLL("kernel32", use_last_error=True)
    k32.CreateToolhelp32Snapshot.restype = wintypes.HANDLE
    k32.CreateToolhelp32Snapshot.argtypes = (wintypes.DWORD, wintypes.DWORD)
    k32.Process32FirstW.argtypes = (wintypes.HANDLE, ctypes.POINTER(PE32))
    k32.Process32NextW.argtypes = (wintypes.HANDLE, ctypes.POINTER(PE32))
    k32.CloseHandle.argtypes = (wintypes.HANDLE,)
    snap = k32.CreateToolhelp32Snapshot(0x2, 0)
    out = {}
    entry = PE32()
    entry.dwSize = ctypes.sizeof(PE32)
    ok = k32.Process32FirstW(snap, ctypes.byref(entry))
    while ok:
        out[entry.th32ProcessID] = (entry.th32ParentProcessID, entry.szExeFile)
        ok = k32.Process32NextW(snap, ctypes.byref(entry))
    k32.CloseHandle(snap)
    return out


def descendants(root):
    procs = processes()
    kids = {}
    for pid, (ppid, _) in procs.items():
        kids.setdefault(ppid, []).append(pid)
    seen, stack = [], [root]
    while stack:
        for child in kids.get(stack.pop(), []):
            if child != root and child not in seen:
                seen.append(child)
                stack.append(child)
    return [(pid, procs[pid][1]) for pid in seen]


def alive_exe(pid):
    entry = processes().get(pid)
    return entry[1] if entry else None


def load_copy(code):
    spec = importlib.util.spec_from_file_location("shared_lock_copy", os.path.join(code, "shared_lock.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def read_lines(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read().splitlines()
    except OSError:
        return []


def parse_info(path):
    info = {}
    for line in read_lines(path):
        for part in line.split():
            key, _, value = part.partition("=")
            if value:
                info[key] = value
    return info


def run_variant(name):
    pidmode, killmode = name.split("-")
    base = os.path.join(HERE, name)
    assert LIVE_MARK not in os.path.abspath(base), "refusing: path points at the live lock folder"
    shutil.rmtree(base, ignore_errors=True)
    code, locks = os.path.join(base, "code"), os.path.join(base, "locks")
    os.makedirs(code)
    os.makedirs(locks)
    for n in ("shared_lock.py", "heavy_lock.py"):
        shutil.copyfile(os.path.join(SRC, n), os.path.join(code, n))
    job_sh = os.path.join(base, "job.sh")
    with open(job_sh, "w", encoding="utf-8", newline="\n") as f:
        f.write(JOB_SH)
    env = {k: v for k, v in os.environ.items() if not (k.startswith("SHARED_LOCK_") or k == "HEAVY_LOCK_PID")}
    env["SHARED_LOCK_DIR"] = locks
    env["SHARED_LOCK_POLL_SECONDS"] = "0.5"
    assert LIVE_MARK not in env["SHARED_LOCK_DIR"]
    for key in [k for k in os.environ if k.startswith("SHARED_LOCK_") or k == "HEAVY_LOCK_PID"]:
        del os.environ[key]
    sl = load_copy(code)
    cfg = sl.Config(LOCK, locks)
    assert os.path.dirname(cfg.lock) == os.path.abspath(locks)
    heavy = os.path.join(code, "heavy_lock.py")
    out_posix = base.replace("\\", "/")
    timeline = []

    def note(text):
        line = f"[{time.time() - t0:6.2f}s] {text}"
        timeline.append(line)
        print(f"  {line}", flush=True)

    def sample(tag):
        rec = sl.read(cfg)
        beats = sum(1 for l in read_lines(os.path.join(base, "beats.log")) if l.startswith("heartbeat by sangam"))
        auto = sum(1 for l in read_lines(cfg.served_log) if " auto-released stale lock " in l)
        if rec is None:
            note(f"{tag}: lock FREE; beats={beats} auto_release_lines={auto} waiter_running={waiter_running()}")
            return rec, beats, auto
        state, detail = sl.liveness(rec) if rec.get("holder_pid") is not None else ("none", "")
        left = float(rec.get("lease_until") or 0) - time.time() if rec.get("holder_pid") is not None else None
        left_s = f"{left:+.2f}s" if left is not None else "n/a"
        note(f"{tag}: owner={rec.get('owner')} holder_pid={rec.get('holder_pid')} alive={state} lease_left={left_s} "
             f"beats={beats} auto_release_lines={auto} waiter_running={waiter_running()}")
        return rec, beats, auto

    waiter = None

    def waiter_running():
        return waiter is not None and waiter.poll() is None

    t0 = time.time()
    console = open(os.path.join(base, "job.console"), "w", encoding="utf-8")
    job = subprocess.Popen([BASH, job_sh.replace("\\", "/"), heavy.replace("\\", "/"), str(BEAT), str(LEASE),
                            out_posix, pidmode], env=env, stdin=subprocess.DEVNULL, stdout=console,
                           stderr=subprocess.STDOUT)
    deadline = time.time() + 30
    info = {}
    while time.time() < deadline:
        info = parse_info(os.path.join(base, "job.info"))
        if "hb_winpid" in info and (sl.read(cfg) or {}).get("owner") == "sangam":
            break
        time.sleep(0.1)
    note(f"job started: {info}; acquire.log={read_lines(os.path.join(base, 'acquire.log'))}")
    job_win, hb_win = int(info["job_winpid"]), int(info["hb_winpid"])
    wout = open(os.path.join(base, "waiter.stdout"), "w", encoding="utf-8")
    werr = open(os.path.join(base, "waiter.stderr"), "w", encoding="utf-8")
    waiter = subprocess.Popen([PY, "-I", heavy, "acquire", "B", "waiter"], env=env, stdin=subprocess.DEVNULL,
                              stdout=wout, stderr=werr)
    note(f"waiter B started (pid {waiter.pid})")
    time.sleep(4.5)
    sample("before kill")
    tree_before = descendants(job_win)
    note(f"job shell {job_win} tree before kill: {tree_before}")
    # identity of the long-lived processes this variant started (pid + creation time), for a safe cleanup
    owned = []
    for pid, exe in [(job_win, "bash.exe"), (hb_win, "bash.exe")] + tree_before:
        if exe.lower() in ("bash.exe", "sleep.exe", "python.exe") and all(pid != o[0] for o in owned):
            alive, start, _ = sl.probe_process(pid)
            if alive and start is not None:
                owned.append((pid, exe, start))
    note(f"owned long-lived processes (pid, exe, start): {owned}")

    def still_mine(pid):
        for opid, _, ostart in owned:
            if opid == pid:
                alive, start, _ = sl.probe_process(pid)
                return bool(alive) and start is not None and abs(start - ostart) < 0.01
        return False

    if killmode == "single":
        r = subprocess.run(["taskkill", "/F", "/PID", str(job_win)], capture_output=True, text=True)
    elif killmode == "tree":
        r = subprocess.run(["taskkill", "/F", "/T", "/PID", str(job_win)], capture_output=True, text=True)
    elif killmode == "term":
        r = subprocess.run([BASH, "-c", f"kill -TERM {info['job_msys_pid']}"], capture_output=True, text=True)
    elif killmode == "kill9":
        r = subprocess.run([BASH, "-c", f"kill -KILL {info['job_msys_pid']}"], capture_output=True, text=True)
    else:
        raise SystemExit(f"unknown kill mode {killmode}")
    t_kill = time.time()
    note(f"KILL ({killmode}) rc={r.returncode} out={(r.stdout + r.stderr).strip()!r}")
    try:
        job.wait(10)
        note(f"job shell exited rc={job.returncode}")
    except subprocess.TimeoutExpired:
        note("job shell still running 10 s after the kill")
    note(f"after kill: job shell alive={alive_exe(job_win)} heartbeat subshell {hb_win} alive={alive_exe(hb_win)}; "
         f"trap.log={read_lines(os.path.join(base, 'trap.log'))}")

    beats_at_kill = sum(1 for l in read_lines(os.path.join(base, "beats.log")) if l.startswith("heartbeat by sangam"))
    observe_until = t_kill + 5 * LEASE        # five full leases
    while time.time() < observe_until and waiter_running():
        time.sleep(3)
        sample("after kill")
    rec, beats_end, auto_end = sample("end of observation (5 leases after the kill)")
    acquired_during_observation = not waiter_running()
    result = {
        "variant": name,
        "beats_after_kill": beats_end - beats_at_kill,
        "waiter_acquired_within_5_leases": acquired_during_observation,
        "owner_at_end": (rec or {}).get("owner"),
        "auto_release_lines": auto_end,
        "heartbeat_subshell_alive_at_end": alive_exe(hb_win),
    }
    if not acquired_during_observation:
        # control: stop the orphaned heartbeat loop; the waiter must now get the lock within one lease (+ polls)
        if not still_mine(hb_win):
            raise SystemExit(f"heartbeat subshell {hb_win} is gone or its pid was reused; not killing anything")
        r = subprocess.run(["taskkill", "/F", "/T", "/PID", str(hb_win)], capture_output=True, text=True)
        t_hb = time.time()
        note(f"CONTROL: killed heartbeat subshell tree {hb_win}: rc={r.returncode}")
        try:
            waiter.wait(LEASE + 10)
            result["control_waiter_acquired_after_s"] = round(time.time() - t_hb, 2)
            note(f"CONTROL: waiter exited rc={waiter.returncode} {time.time() - t_hb:.2f}s after the loop stopped")
        except subprocess.TimeoutExpired:
            result["control_waiter_acquired_after_s"] = None
            note("CONTROL: waiter still waiting after lease + 10 s")
        sample("after control")
    wout.close()
    werr.close()
    result["waiter_stdout"] = read_lines(os.path.join(base, "waiter.stdout"))
    result["waiter_stderr"] = read_lines(os.path.join(base, "waiter.stderr"))
    result["served_log"] = read_lines(cfg.served_log)
    result["trap_log"] = read_lines(os.path.join(base, "trap.log"))
    result["job_info"] = info

    # cleanup: only processes this variant started
    if waiter_running():
        waiter.kill()
        waiter.wait()
    leftovers = []
    for pid, exe, _ in owned:
        if still_mine(pid):                   # same pid AND same creation time: never an unrelated process
            leftovers.append((pid, exe))
            subprocess.run(["taskkill", "/F", "/T", "/PID", str(pid)], capture_output=True, text=True)
    result["leftovers_killed_at_cleanup"] = leftovers
    for owner in ("B", "sangam"):
        subprocess.run([PY, "-I", heavy, "release", owner], env=env, capture_output=True, text=True)
    console.close()
    with open(os.path.join(base, "result.json"), "w", encoding="utf-8") as f:
        json.dump({"result": result, "timeline": timeline}, f, indent=2)
    print(json.dumps(result, indent=2), flush=True)
    return result


if __name__ == "__main__":
    for variant in sys.argv[1:]:
        print(f"=== {variant}", flush=True)
        run_variant(variant)
