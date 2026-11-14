"""Read-only sanity check: for every process the copied v2 probe CAN open, does the NtQuerySystemInformation CreateTime
equal the GetProcessTimes start time the probe records as holder_start? (If yes, the NtQSI value is a drop-in for the
start-time comparison on processes the probe cannot open.)"""
import importlib.util
import os

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


sl = load("shared_lock", os.path.join(BASE, "code", "shared_lock.py"))
nq = load("ntqsi", os.path.join(BASE, "scripts", "ntqsi.py"))
diffs = []
for pid, (start, name, _pp) in nq.snapshot().items():
    if pid == 0 or start is None:
        continue
    alive, pstart, _ = sl.probe_process(pid)
    if pstart is not None:
        diffs.append((abs(pstart - start), pid, name))
diffs.sort(reverse=True)
print(f"compared {len(diffs)} openable processes; max |GetProcessTimes - NtQSI CreateTime| = {diffs[0][0]:.9f} s "
      f"({diffs[0][2]}); count with diff > 1 microsecond: {sum(1 for d in diffs if d[0] > 1e-6)}")
