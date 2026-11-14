"""A's `release A`, run through the unmodified copied shared_lock.main, with ONE scheduling pause injected
between the record read (shared_lock.py:451, file already closed on return) and the owner check / os.remove
(:455-460). The pause models the OS descheduling this process; no lock logic is changed.

argv: code_dir ready_file go_file
"""
import importlib.util
import os
import sys
import time

code_dir, ready_file, go_file = sys.argv[1:4]
spec = importlib.util.spec_from_file_location("shared_lock", os.path.join(code_dir, "shared_lock.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
orig_read = m.read


def paused_read(cfg):
    rec = orig_read(cfg)
    with open(ready_file, "w", encoding="utf-8") as f:
        f.write(repr(rec))
    deadline = time.time() + 60
    while not os.path.exists(go_file) and time.time() < deadline:
        time.sleep(0.01)
    return rec


m.read = paused_read
sys.exit(m.main(["heavy_lock.py", "release", "A"], lock_name="heavy-build.lock", default_dir=code_dir))
