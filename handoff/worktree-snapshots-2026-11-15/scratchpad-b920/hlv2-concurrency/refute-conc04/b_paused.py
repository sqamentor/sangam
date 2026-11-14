"""Run the UNMODIFIED shared_lock.acquire(cfg, owner, what, holder_pid) from the copied code, with one hook: the
os.fdopen() call at shared_lock.py:434 (after the O_EXCL create at :407, before json.dump at :435) first writes a
marker and waits for a 'go' file. That only stretches the create-to-write gap; no lock logic is changed.

usage: b_paused.py <code_dir> <lock_dir> <owner> <what> <holder_pid> <marker_file> <go_file>
"""
import importlib.util
import os
import sys
import time

code_dir, lock_dir, owner, what, holder_pid, marker, go = sys.argv[1:8]
os.environ["SHARED_LOCK_DIR"] = lock_dir
spec = importlib.util.spec_from_file_location("shared_lock", os.path.join(code_dir, "shared_lock.py"))
sl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sl)

real_fdopen = os.fdopen


def paused_fdopen(fd, *args, **kwargs):
    with open(marker, "w", encoding="utf-8") as f:
        f.write("created")
    while not os.path.exists(go):
        time.sleep(0.01)
    return real_fdopen(fd, *args, **kwargs)


os.fdopen = paused_fdopen
cfg = sl.Config("heavy-build.lock", lock_dir)
sys.exit(sl.acquire(cfg, owner, what, int(holder_pid), None))
