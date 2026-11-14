import os
import sys
import time

log = sys.argv[1]
for i in range(8):
    with open(log, "a", encoding="utf-8") as f:
        f.write(f"{time.time():.2f} in-flight python pid={os.getpid()} tick {i}\n")
    time.sleep(0.4)
with open(log, "a", encoding="utf-8") as f:
    f.write(f"{time.time():.2f} in-flight python pid={os.getpid()} finished\n")
