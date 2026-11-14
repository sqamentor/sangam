"""Owner name with ':' (NTFS alternate data stream in the ticket path) and a pid above 2**32, against my copy only."""
import importlib.util
import os
import shutil
import subprocess
import sys
import time

BASE = os.path.dirname(os.path.abspath(__file__))
HEAVY = os.path.join(BASE, "code", "heavy_lock.py")
LOCKS = os.path.join(BASE, "locks-owner-colon")
shutil.rmtree(LOCKS, ignore_errors=True)
os.makedirs(LOCKS)
env = {k: v for k, v in os.environ.items() if not k.startswith(("SHARED_LOCK_", "HEAVY_LOCK_"))}
env.update(SHARED_LOCK_DIR=LOCKS, SHARED_LOCK_POLL_SECONDS="0.2")


def run(*args):
    r = subprocess.run([sys.executable, "-I", HEAVY, *args], capture_output=True, text=True, env=env, timeout=30)
    print(f"  $ {' '.join(args)} -> rc={r.returncode} {r.stdout.strip()} {r.stderr.strip()[:200]}")


run("acquire", "H", "holder")
w1 = subprocess.Popen([sys.executable, "-I", HEAVY, "acquire", "sangam:trial2", "colon owner"], env=env,
                      stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
time.sleep(1.0)
w2 = subprocess.Popen([sys.executable, "-I", HEAVY, "acquire", "ifms", "normal owner behind it"], env=env,
                      stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
time.sleep(1.0)
print("  queue listing:", os.listdir(os.path.join(LOCKS, "heavy-build.lock.queue")))
run("release", "H")
time.sleep(5)
print(f"  5 s after release: colon waiter still waiting={w1.poll() is None}; normal waiter still waiting={w2.poll() is None}")
print("  lock file present:", os.path.exists(os.path.join(LOCKS, "heavy-build.lock")))
for p in (w1, w2):
    p.kill()
    p.wait()

spec = importlib.util.spec_from_file_location("shared_lock", os.path.join(BASE, "code", "shared_lock.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
me = os.getpid()
print(f"  probe_process({me}) = {m.probe_process(me)}")
print(f"  probe_process(2**32 + {me}) = {m.probe_process(2 ** 32 + me)}")
