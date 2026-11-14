"""Review experiment (COPY + temp lock folder only): a queued acquire whose --pid process dies while it waits records,
at grant time (shared_lock.py:430), the start time of whatever process holds that pid THEN."""
import json, os, shutil, subprocess, sys, time

BASE = os.path.dirname(os.path.abspath(__file__))
HEAVY = os.path.join(BASE, "code", "heavy_lock.py")
PY = sys.executable
D = os.path.join(BASE, "locks", "t6")
assert "ff9f2caf" not in HEAVY and D.startswith(BASE)
shutil.rmtree(D, ignore_errors=True)
os.makedirs(D)
ENV = {k: v for k, v in os.environ.items() if not k.startswith("SHARED_LOCK_") and k != "HEAVY_LOCK_PID"}
ENV.update(SHARED_LOCK_DIR=D, SHARED_LOCK_POLL_SECONDS="0.2")
CMD = os.path.join(os.environ["SystemRoot"], "System32", "cmd.exe")


def tool(*args):
    r = subprocess.run([PY, "-I", HEAVY, *args], capture_output=True, text=True, env=ENV, timeout=20)
    return r.returncode, r.stdout.strip(), r.stderr.strip()


def long_lived():
    # an unrelated long-lived process: cmd.exe blocked on an empty stdin pipe
    return subprocess.Popen([CMD, "/d", "/k"], stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def free(p):
    try:
        p.kill(); p.wait(); p._handle.Close()
    except Exception:
        pass


print("hold H (old-style):", tool("acquire", "H", "blocking holder"))
runner = long_lived()
P = runner.pid
q = subprocess.Popen([PY, "-I", HEAVY, "acquire", "customwf-q", "queued job", "--pid", str(P), "--lease", "5"],
                     env=ENV, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
time.sleep(1.5)
print(f"runner stand-in pid={P} alive; customwf-q queued behind H (its first probe saw the runner)")
free(runner)
print(f"runner stand-in pid={P} killed and its handle closed (the job will never run)")
reuser, spawned, t0 = None, 0, time.time()
while time.time() - t0 < 150 and reuser is None:
    p = long_lived()
    spawned += 1
    if p.pid == P:
        reuser = p
    else:
        free(p)
if reuser is None:
    print(f"pid {P} not reused within {spawned} spawns / {time.time() - t0:.0f} s; mechanism shown by code only")
    tool("release", "H"); q.wait(10); print(tool("status")[1]); free(q); sys.exit(0)
print(f"pid {P} reused by an unrelated cmd.exe after {spawned} spawns ({time.time() - t0:.1f} s)")
print("release H:", tool("release", "H"))
try:
    qout, qerr = q.communicate(timeout=10)
except subprocess.TimeoutExpired:
    qout, qerr = "", "still waiting"
print("queued acquire finished:", q.returncode, (qout or "").strip(), "|", (qerr or "").strip().splitlines()[-1:])
rc, out, err = tool("status")
for line in out.splitlines()[:3]:
    print("   status|", line)
w = subprocess.Popen([PY, "-I", HEAVY, "acquire", "W", "next heavy job"], env=ENV, stdout=subprocess.PIPE,
                     stderr=subprocess.PIPE, text=True)
time.sleep(12)
print("waiter W after 12 s (lease 5 s, the real holder is dead):", "STILL WAITING" if w.poll() is None else f"exit {w.returncode}")
free(w)
_, werr = w.communicate()
for line in (werr or "").strip().splitlines()[:2]:
    print("   W.err|", line)
free(reuser)
time.sleep(0.5)
print("after the unrelated process exits:", tool("status")[1].splitlines()[1])
