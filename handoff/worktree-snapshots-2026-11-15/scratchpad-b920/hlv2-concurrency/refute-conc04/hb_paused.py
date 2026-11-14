"""Run the UNMODIFIED shared_lock.heartbeat(cfg, owner) from the copied code, with one hook: right after the
heartbeat's read() (shared_lock.py:477) returns, the process writes a marker and waits for a 'go' file before
continuing. That only stretches the gap between line 477 (read by name) and line 496 (os.open by name) -- it models
the process being pre-empted there. No lock logic is changed.

usage: hb_paused.py <code_dir> <lock_dir> <owner> <marker_file> <go_file>
"""
import importlib.util
import json
import os
import sys
import time

code_dir, lock_dir, owner, marker, go = sys.argv[1:6]
os.environ["SHARED_LOCK_DIR"] = lock_dir
spec = importlib.util.spec_from_file_location("shared_lock", os.path.join(code_dir, "shared_lock.py"))
sl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sl)

real_read = sl.read


def paused_read(cfg):
    rec = real_read(cfg)                      # the real read at shared_lock.py:477 (file opened, parsed, closed)
    with open(marker, "w", encoding="utf-8") as f:
        f.write(json.dumps(rec))
    while not os.path.exists(go):             # simulated pre-emption between :477 and :496
        time.sleep(0.01)
    return rec


sl.read = paused_read
cfg = sl.Config("heavy-build.lock", lock_dir)
sys.exit(sl.heartbeat(cfg, owner))
