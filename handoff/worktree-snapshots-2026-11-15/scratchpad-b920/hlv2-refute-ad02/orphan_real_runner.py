"""Refutation check for HLV2-AD-02, real parent/child chain: a pwsh runner runs `acquire --pid $PID` as its child
(README recipe), is killed with a plain TerminateProcess while queued, and every handle to it is closed (as when the
agent shell that started it has exited). COPY of the tool and a private lock folder only."""
import json, os, shutil, subprocess, sys, time

BASE = os.path.dirname(os.path.abspath(__file__))
CODE = os.path.join(BASE, "code")
HEAVY = os.path.join(CODE, "heavy_lock.py")
D = os.path.join(BASE, "locks", "orphan-real")
PY = sys.executable
PWSH = r"C:\Program Files\PowerShell\7\pwsh.exe"
assert "ff9f2caf" not in BASE.lower()
sys.dont_write_bytecode = True
sys.path.insert(0, CODE)
import shared_lock as SL

ENV = {k: v for k, v in os.environ.items() if not k.startswith("SHARED_LOCK_") and k != "HEAVY_LOCK_PID"}
ENV.update(SHARED_LOCK_DIR=D, SHARED_LOCK_POLL_SECONDS="0.2")


def tool(*args):
    r = subprocess.run([PY, "-I", HEAVY, *args], capture_output=True, text=True, env=ENV, timeout=30)
    return r.returncode, r.stdout.strip(), r.stderr.strip()


def t():
    return time.strftime("%H:%M:%S", time.gmtime()) + f".{int(time.time() * 1000) % 1000:03d}"


shutil.rmtree(D, ignore_errors=True)
os.makedirs(D)
log = os.path.join(D, "runner.log")
print(t(), "H (old-style) acquire:", tool("acquire", "H", "blocking holder")[:2])
runner = subprocess.Popen([PWSH, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
                           os.path.join(BASE, "runner_min.ps1"), "-Lock", HEAVY, "-Dir", D, "-Log", log, "-Lease", "8"],
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
rpid = runner.pid
rstart = SL.probe_process(rpid)[1]
q = os.path.join(D, "heavy-build.lock.queue")
acq, t0 = None, time.time()
while acq is None and time.time() - t0 < 30:
    for n in (os.listdir(q) if os.path.isdir(q) else []):
        if "-customwf-s4-" in n:
            acq = int(n.rsplit("-", 1)[1])
    time.sleep(0.2)
assert acq, "runner never queued"
print(t(), f"runner pwsh pid={rpid} (started {SL.iso(rstart)}) is queued; its child acquire pid={acq}")
runner.kill(); runner.wait(); runner._handle.Close()
time.sleep(1.0)
print(t(), f"runner killed (TerminateProcess, no tree), handle closed: runner probe={SL.probe_process(rpid)[0::2]}, "
           f"orphaned acquire probe={SL.probe_process(acq)[0::2]}")
print(t(), "release H:", tool("release", "H")[:2])
time.sleep(1.5)
with open(os.path.join(D, "heavy-build.lock"), encoding="utf-8") as f:
    r = json.load(f)
print(t(), "lock record after H released:", json.dumps(r))
print(t(), "orphaned acquire still running:", SL.probe_process(acq)[0], "| runner.log:",
      open(log, encoding="utf-8", errors="replace").read().strip().replace("\n", " / "))
w = subprocess.Popen([PY, "-I", HEAVY, "acquire", "W", "next heavy job"], env=ENV, stdout=subprocess.PIPE,
                     stderr=subprocess.PIPE, text=True)
tw = time.time()
out, err = w.communicate(timeout=60)
print(t(), f"next job W got the lock after {time.time() - tw:.1f} s (lease 8 s; the job it waited for never ran)")
for line in err.strip().splitlines():
    print("    W.err|", line)
print(open(os.path.join(D, "heavy-build.lock.served.log"), encoding="utf-8").read().strip())
