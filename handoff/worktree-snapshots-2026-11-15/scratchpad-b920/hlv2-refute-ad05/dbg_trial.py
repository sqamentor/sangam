import os, sys, runpy, time
ROOT = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, ROOT)
import stress2
def quiet_to_file():
    f = open(os.path.join(ROOT, f"dbg-{os.getpid()}.log"), "w", buffering=1)
    sys.stdout = f; sys.stderr = f
stress2.quiet = quiet_to_file
if __name__ == "__main__":
    sl = [__import__("subprocess").Popen([sys.executable, "-I", "-c", "import time; time.sleep(60)"]) for _ in range(2)]
    try:
        el, msgs = stress2.trial(99, 4, "spin", 0.001, [s.pid for s in sl])
        print("elapsed", round(el, 2), "msgs", [m[:3] for m in msgs])
    finally:
        for s in sl: s.kill()
