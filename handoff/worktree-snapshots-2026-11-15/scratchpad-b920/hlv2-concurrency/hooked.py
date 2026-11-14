"""Run the v2 CLI (my private copy) with one deterministic pause injected, to force a race interleaving.

HL_CODE  folder holding my copy of shared_lock.py
HL_SYNC  folder for the handshake files (<name>.paused written by this process, <name>.go written by the harness)
HL_HOOK  which pause:
  pause_after_first_read   pause right after the first read(cfg) returns (heartbeat / release TOCTOU)
  pause_auto_release       pause before os.replace(lock, aside) and again before the put-back rename
  pause_after_create       pause after O_EXCL create, before the record is written (2nd probe_process call)
  pause_before_ftruncate   pause in heartbeat between os.write and os.ftruncate
HL_ROUND_TIME=1          heartbeat computes lease_until from a whole-second clock (only to make its JSON shorter)
argv: lock_name cmd args...
"""
import importlib.util
import os
import sys
import time

CODE = os.environ["HL_CODE"]
SYNC = os.environ["HL_SYNC"]
HOOK = os.environ.get("HL_HOOK", "")

spec = importlib.util.spec_from_file_location("shared_lock", os.path.join(CODE, "shared_lock.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def pause(name):
    open(os.path.join(SYNC, name + ".paused"), "w").close()
    while not os.path.exists(os.path.join(SYNC, name + ".go")):
        time.sleep(0.005)


if HOOK == "pause_after_first_read":
    _read, _n = m.read, [0]

    def read(cfg):
        rec = _read(cfg)
        _n[0] += 1
        if _n[0] == 1:
            pause("p1")
        return rec
    m.read = read

elif HOOK == "pause_auto_release":
    _replace, _rn = os.replace, m._rename_noreplace

    def replace(src, dst):
        if ".stale-" in dst:
            pause("p1")                       # judged stale, about to move the lock aside
        return _replace(src, dst)

    def rename_noreplace(src, dst):
        pause("p2")                           # record did not match, about to put it back
        return _rn(src, dst)
    m.os.replace = replace
    m._rename_noreplace = rename_noreplace

elif HOOK == "pause_after_create":
    _probe, _n = m.probe_process, [0]

    def probe(pid):
        _n[0] += 1
        if _n[0] == 2:                        # the second probe runs after O_EXCL succeeded, before json.dump
            pause("p1")
        return _probe(pid)
    m.probe_process = probe

elif HOOK == "pause_before_record_write":
    _fdopen = os.fdopen

    def fdopen(fd, *a, **k):
        pause("p2")                           # O_EXCL create done, record not yet written
        return _fdopen(fd, *a, **k)
    m.os.fdopen = fdopen

elif HOOK == "pause_before_ftruncate":
    _ftr = os.ftruncate

    def ftruncate(fd, n):
        pause("p1")
        return _ftr(fd, n)
    m.os.ftruncate = ftruncate

if os.environ.get("HL_ROUND_TIME") == "1":
    _t = time.time
    m.time = type("T", (), {"time": staticmethod(lambda: float(int(_t()))), "sleep": staticmethod(time.sleep),
                            "time_ns": staticmethod(time.time_ns)})

lock_name = sys.argv[1]
sys.exit(m.main(["hooked"] + sys.argv[2:], lock_name=lock_name, default_dir=CODE))
