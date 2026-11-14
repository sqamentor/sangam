"""Control for CONC-02: README.md:84-100 PowerShell pattern (Start-Job heartbeat) after taskkill /F of the host only.
Runs only against a copy of the v2 code in this folder and a lock folder in this folder."""
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import time

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("harness", os.path.join(HERE, "harness.py"))
H = importlib.util.module_from_spec(spec)
spec.loader.exec_module(H)
PWSH = r"C:\Program Files\PowerShell\7\pwsh.exe"
LEASE = 6

HOST_PS1 = r'''param($lock, $owner, $out)
# README.md:87-99 pattern; differences: --lease 6, Start-Sleep 2 instead of 600, heartbeat output kept in beats.log
$env:SHARED_LOCK_DIR = "$out\locks"
python -I $lock acquire $owner "Trial 2 retest" --pid $PID --lease 6 *>> "$out\acquire.log"
if ($LASTEXITCODE -ne 0) { throw "heavy lock not acquired (rc=$LASTEXITCODE)" }
$hb = Start-Job -ArgumentList $lock, $owner, $out -ScriptBlock {
    param($lock, $owner, $out)
    $env:SHARED_LOCK_DIR = "$out\locks"
    while ($true) { Start-Sleep -Seconds 2; python -I $lock heartbeat $owner *>> "$out\beats.log" }
}
"host=$PID" | Set-Content "$out\host.info"
try {
    Start-Sleep -Seconds 600
}
finally {
    Stop-Job $hb -ErrorAction SilentlyContinue; Remove-Job $hb -Force -ErrorAction SilentlyContinue
    python -I $lock release $owner *>> "$out\finally.log"
}
'''


def main():
    base = os.path.join(HERE, "psjob-single")
    assert H.LIVE_MARK not in os.path.abspath(base)
    shutil.rmtree(base, ignore_errors=True)
    code, locks = os.path.join(base, "code"), os.path.join(base, "locks")
    os.makedirs(code)
    os.makedirs(locks)
    for n in ("shared_lock.py", "heavy_lock.py"):
        shutil.copyfile(os.path.join(H.SRC, n), os.path.join(code, n))
    host_ps1 = os.path.join(base, "host.ps1")
    with open(host_ps1, "w", encoding="utf-8") as f:
        f.write(HOST_PS1)
    env = {k: v for k, v in os.environ.items() if not (k.startswith("SHARED_LOCK_") or k == "HEAVY_LOCK_PID")}
    env["SHARED_LOCK_DIR"] = locks
    env["SHARED_LOCK_POLL_SECONDS"] = "0.5"
    sl = H.load_copy(code)
    cfg = sl.Config(H.LOCK, locks)
    heavy = os.path.join(code, "heavy_lock.py")
    t0 = time.time()

    def note(text):
        print(f"  [{time.time() - t0:6.2f}s] {text}", flush=True)

    def beats():
        return sum(1 for l in H.read_lines(os.path.join(base, "beats.log")) if l.startswith("heartbeat by sangam"))

    host = subprocess.Popen([PWSH, "-NoProfile", "-NonInteractive", "-File", host_ps1, heavy, "sangam", base],
                            env=env, stdin=subprocess.DEVNULL, stdout=open(os.path.join(base, "host.console"), "w"),
                            stderr=subprocess.STDOUT)
    deadline = time.time() + 60
    while time.time() < deadline:
        if os.path.exists(os.path.join(base, "host.info")) and (sl.read(cfg) or {}).get("owner") == "sangam":
            break
        time.sleep(0.2)
    while time.time() < deadline and beats() < 1:      # wait until the job loop has beaten once
        time.sleep(0.2)
    note(f"host pid {host.pid}; record holder_pid={(sl.read(cfg) or {}).get('holder_pid')}; beats={beats()}")
    tree = H.descendants(host.pid)
    note(f"host tree: {tree}")
    owned = []
    for pid, exe in [(host.pid, "pwsh.exe")] + tree:
        alive, start, _ = sl.probe_process(pid)
        if alive and start is not None and exe.lower() in ("pwsh.exe", "python.exe", "conhost.exe"):
            owned.append((pid, exe, start))
    waiter = subprocess.Popen([sys.executable, "-I", heavy, "acquire", "B", "waiter"], env=env,
                              stdin=subprocess.DEVNULL, stdout=open(os.path.join(base, "waiter.stdout"), "w"),
                              stderr=open(os.path.join(base, "waiter.stderr"), "w"))
    time.sleep(2)
    r = subprocess.run(["taskkill", "/F", "/PID", str(host.pid)], capture_output=True, text=True)
    t_kill = time.time()
    b0 = beats()
    note(f"KILL host only: {(r.stdout + r.stderr).strip()!r}; beats at kill={b0}")
    time.sleep(1.5)
    note(f"job processes still alive 1.5 s after the kill: "
         f"{[(p, e) for p, e, s in owned[1:] if (lambda a: a[0] and a[1] is not None and abs(a[1] - s) < 0.01)(sl.probe_process(p))]}")
    while time.time() < t_kill + 5 * LEASE and waiter.poll() is None:
        time.sleep(3)
        rec = sl.read(cfg)
        left = (float(rec.get("lease_until") or 0) - time.time()) if rec else None
        note(f"owner={(rec or {}).get('owner')} lease_left={left if left is None else round(left, 2)} "
             f"beats_after_kill={beats() - b0} waiter_running={waiter.poll() is None}")
    result = {"beats_after_kill": beats() - b0, "waiter_acquired": waiter.poll() == 0,
              "acquired_after_kill_s": round(time.time() - t_kill, 2) if waiter.poll() == 0 else None,
              "waiter_stderr": H.read_lines(os.path.join(base, "waiter.stderr")),
              "served_log": H.read_lines(cfg.served_log)}
    if waiter.poll() is None:
        waiter.kill()
        waiter.wait()
    for pid, exe, start in owned:
        alive, s, _ = sl.probe_process(pid)
        if alive and s is not None and abs(s - start) < 0.01:
            subprocess.run(["taskkill", "/F", "/T", "/PID", str(pid)], capture_output=True, text=True)
            result.setdefault("killed_at_cleanup", []).append((pid, exe))
    for owner in ("B", "sangam"):
        subprocess.run([sys.executable, "-I", heavy, "release", owner], env=env, capture_output=True, text=True)
    print(json.dumps(result, indent=2), flush=True)


if __name__ == "__main__":
    main()
