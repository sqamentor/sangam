"""A's runner calls `release A` at a chosen instant: the copied, unmodified shared_lock module is loaded first
(Python start-up on this machine varies 0.2-1 s), the process waits until target_epoch, then runs main() straight
through. Nothing is paused inside release; os.remove attempts are only logged.

mode log: behaviour unchanged.
mode v1 : control. The first PermissionError ends the process with exit 1 (v1 heavy_lock.py:112: a single
          os.remove, no retry).

argv: code_dir mode log_file target_epoch
"""
import importlib.util
import os
import sys
import time

code_dir, mode, log_file, target = sys.argv[1], sys.argv[2], sys.argv[3], float(sys.argv[4])
spec = importlib.util.spec_from_file_location("shared_lock", os.path.join(code_dir, "shared_lock.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
real_remove = os.remove
events = []


def _flush():
    with open(log_file, "w", encoding="utf-8") as f:
        f.write("\n".join(events) + "\n")


def observed_remove(path, *a, **k):
    t = time.time()
    try:
        real_remove(path, *a, **k)
        events.append(f"{t - target:+.4f}s remove ok")
    except PermissionError:
        events.append(f"{t - target:+.4f}s remove PermissionError")
        if mode == "v1":
            _flush()
            os._exit(1)
        raise
    except FileNotFoundError:
        events.append(f"{t - target:+.4f}s remove FileNotFoundError")
        raise


real_read = m.read


def observed_read(cfg):
    t0 = time.time()
    rec = real_read(cfg)
    t1 = time.time()
    events.append(f"{t0 - target:+.4f}s read start, {t1 - target:+.4f}s read done (owner={None if rec is None else rec.get('owner')})")
    return rec


m.os.remove = observed_remove
m.read = observed_read          # logging only, no pause
late = time.time() - target
if late > 0:
    events.append(f"started late by {late:.4f}s")
while True:
    left = target - time.time()
    if left <= 0:
        break
    time.sleep(left - 0.002 if left > 0.004 else 0)
rc = m.main(["heavy_lock.py", "release", "A"], lock_name="heavy-build.lock", default_dir=code_dir)
_flush()
sys.exit(rc)
