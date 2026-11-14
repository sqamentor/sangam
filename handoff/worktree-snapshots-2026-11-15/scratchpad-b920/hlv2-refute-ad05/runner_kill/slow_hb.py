"""Stand-in for an in-flight `heartbeat` process: marks start, stays 'in the gap' for 3 s, then marks done.
Touches only files in the folder given as argv[1]."""
import os, sys, time

d = sys.argv[1]
with open(os.path.join(d, f"start-{os.getpid()}"), "w") as f:
    f.write(str(time.time()))
time.sleep(3)
with open(os.path.join(d, f"done-{os.getpid()}"), "w") as f:
    f.write(str(time.time()))
