"""Runs the UNMODIFIED shared_lock.py main() with one in-process hook, to pin where a kill/Ctrl+C lands.

usage: hook_acquire.py <code_dir> <where: probe|fdopen> <mode: snapshot|sleep|kbint> <marker_json> <acquire args...>
  where=probe : hook the 2nd probe_process call (shared_lock.py:430, inside the window, --pid only)
  where=fdopen: hook os.fdopen (shared_lock.py:434, inside the window, with or without --pid)
  mode=snapshot: record the lock state at that point and continue normally
  mode=sleep   : record, then sleep so the parent can TerminateProcess us there
  mode=kbint   : record, then raise KeyboardInterrupt (what Ctrl+C landing there does)
"""
import importlib.util
import json
import os
import sys
import time

code_dir, where, mode, marker = sys.argv[1:5]
spec = importlib.util.spec_from_file_location("shared_lock", os.path.join(code_dir, "shared_lock.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
LOCK = os.path.join(os.environ["SHARED_LOCK_DIR"], "heavy-build.lock")


def snapshot(label):
    state = {"at": label, "lock_exists": os.path.exists(LOCK),
             "lock_size": os.path.getsize(LOCK) if os.path.exists(LOCK) else None}
    try:
        state["tickets"] = sorted(os.listdir(LOCK + ".queue"))
    except FileNotFoundError:
        state["tickets"] = None
    with open(marker, "w", encoding="utf-8") as f:
        json.dump(state, f)
    if mode == "sleep":
        time.sleep(120)
    elif mode == "kbint":
        raise KeyboardInterrupt


if where == "probe":
    real_probe = m.probe_process
    calls = [0]

    def hooked_probe(pid):
        calls[0] += 1
        result = real_probe(pid)
        if calls[0] == 2:
            snapshot("shared_lock.py:430 probe_process (2nd call)")
        return result

    m.probe_process = hooked_probe
elif where == "fdopen":
    real_fdopen = os.fdopen

    def hooked_fdopen(fd, *a, **k):
        snapshot("shared_lock.py:434 os.fdopen")
        return real_fdopen(fd, *a, **k)

    os.fdopen = hooked_fdopen
else:
    sys.exit(f"bad where {where!r}")

sys.exit(m.main(["heavy_lock.py"] + sys.argv[5:], lock_name="heavy-build.lock", default_dir=code_dir))
