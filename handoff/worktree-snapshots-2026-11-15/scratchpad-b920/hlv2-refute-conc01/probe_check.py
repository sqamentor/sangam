"""Probe the v2 liveness code (private copy) with the pids a Git Bash runner would pass.
usage: python -I probe_check.py <codedir> <msys_pid> <winpid>"""
import importlib.util
import sys

code, msys, win = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
spec = importlib.util.spec_from_file_location("shared_lock", code + "/shared_lock.py")
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

print("sys.platform =", sys.platform)
for label, pid in [("msys $$", msys), ("msys $$ & ~3", msys & ~3), ("winpid", win),
                   ("winpid+1", win + 1), ("winpid+2", win + 2), ("winpid+3", win + 3),
                   ("winpid+2**32", win + 2 ** 32)]:
    print(f"{label:14} {pid:>12}  probe_process -> {m.probe_process(pid)}")

# what a waiter concludes about a record written by `acquire ... --pid $$`
rec_msys = {"owner": "sangam", "holder_pid": msys, "holder_start": m.probe_process(msys)[1],
            "lease_until": 0, "lease_seconds": 1800, "epoch": 0, "since": "x"}
print("liveness(record with --pid $$)     ->", m.liveness(rec_msys))
rec_win = {"owner": "sangam", "holder_pid": win, "holder_start": m.probe_process(win)[1],
           "lease_until": 0, "lease_seconds": 1800, "epoch": 0, "since": "x"}
print("liveness(record with --pid winpid) ->", m.liveness(rec_win))


class Cfg:
    no_autorelease = False
    stale_seconds = 3 * 3600


print("evaluate(--pid $$, lease expired)    ->", m.evaluate(Cfg, rec_msys))
print("evaluate(--pid winpid, lease expired) ->", m.evaluate(Cfg, rec_win))
