"""A's `release A` through the copied, unmodified shared_lock.main, with os.remove observed in this process only.

mode log: every os.remove attempt is logged (time, outcome); behaviour unchanged.
mode v1 : control. The first PermissionError ends the process with exit 1 (v1 heavy_lock.py:112 has one os.remove
          and no retry, so a sharing violation crashes instead of retrying 50 ms later).

argv: code_dir mode log_file
"""
import importlib.util
import os
import sys
import time

code_dir, mode, log_file = sys.argv[1:4]
spec = importlib.util.spec_from_file_location("shared_lock", os.path.join(code_dir, "shared_lock.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
real_remove = os.remove
events = []


def observed_remove(path, *a, **k):
    t = time.time()
    try:
        real_remove(path, *a, **k)
        events.append(f"{t:.6f} remove ok")
    except PermissionError:
        events.append(f"{t:.6f} remove PermissionError")
        if mode == "v1":
            _flush()
            os._exit(1)
        raise
    except FileNotFoundError:
        events.append(f"{t:.6f} remove FileNotFoundError")
        raise


def _flush():
    with open(log_file, "w", encoding="utf-8") as f:
        f.write("\n".join(events) + "\n")


m.os.remove = observed_remove   # this process only; shared_lock calls os.remove through the os module
rc = m.main(["heavy_lock.py", "release", "A"], lock_name="heavy-build.lock", default_dir=code_dir)
_flush()
sys.exit(rc)
