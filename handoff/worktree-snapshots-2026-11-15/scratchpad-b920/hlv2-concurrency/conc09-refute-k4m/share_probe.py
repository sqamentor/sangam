import os, sys
d = sys.argv[1]
p = os.path.join(d, "share-probe.lock"); q = p + ".aside"
for f in (p, q):
    try: os.remove(f)
    except FileNotFoundError: pass
open(p, "w").write("{}")
fd = os.open(p, os.O_WRONLY | getattr(os, "O_BINARY", 0))   # exactly how heartbeat() opens the lock (shared_lock.py:496)
try:
    os.replace(p, q)                                          # exactly how _auto_release() moves it aside (shared_lock.py:340)
    print("rename SUCCEEDED while the heartbeat handle was open")
except PermissionError as e:
    print("rename blocked while the heartbeat handle was open:", e.winerror, e.strerror)
os.close(fd)
os.replace(p, q); print("rename after close: ok")
os.remove(q)
