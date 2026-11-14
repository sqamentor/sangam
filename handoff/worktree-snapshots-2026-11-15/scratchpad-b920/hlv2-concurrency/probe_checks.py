"""Windows probe checks against my copy of shared_lock.py: pid low-bit aliasing, unknown verdicts machine-wide,
and the 'start not recorded' rule. Read-only with respect to every lock; spawns only its own sleepers."""
import ctypes
import importlib.util
import os
import subprocess
import sys
from ctypes import wintypes

BASE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("shared_lock", os.path.join(BASE, "code", "shared_lock.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

# 1. pid aliasing: OpenProcess ignores the two low bits of a pid
p = subprocess.Popen([sys.executable, "-I", "-c", "import time; time.sleep(30)"])
print(f"sleeper pid {p.pid} (pid % 4 = {p.pid % 4})")
for d in range(4):
    print(f"  probe_process({p.pid + d}) -> {m.probe_process(p.pid + d)}")
rec = {"holder_pid": p.pid + 3, "holder_start": m.probe_process(p.pid)[1]}
print(f"  liveness(record with holder_pid={p.pid + 3}, start of {p.pid}) -> {m.liveness(rec)}")

# 2. 'start time not recorded' rule: a record written for a pid that was not running at acquire (holder_start null),
#    once that number belongs to any live process
print(f"  liveness(holder_pid={p.pid}, holder_start=None) -> {m.liveness({'holder_pid': p.pid, 'holder_start': None})}")
p.kill(); p.wait()

# 3. machine-wide: how many running pids does the probe call 'unknown' (counts as alive, start unreadable)?
TH32CS_SNAPPROCESS = 0x2


class PROCESSENTRY32W(ctypes.Structure):
    _fields_ = [("dwSize", wintypes.DWORD), ("cntUsage", wintypes.DWORD), ("th32ProcessID", wintypes.DWORD),
                ("th32DefaultHeapID", ctypes.c_size_t), ("th32ModuleID", wintypes.DWORD),
                ("cntThreads", wintypes.DWORD), ("th32ParentProcessID", wintypes.DWORD),
                ("pcPriClassBase", ctypes.c_long), ("dwFlags", wintypes.DWORD), ("szExeFile", ctypes.c_wchar * 260)]


k32 = ctypes.WinDLL("kernel32", use_last_error=True)
k32.CreateToolhelp32Snapshot.restype = wintypes.HANDLE
snap = k32.CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0)
entry = PROCESSENTRY32W()
entry.dwSize = ctypes.sizeof(PROCESSENTRY32W)
procs = []
ok = k32.Process32FirstW(snap, ctypes.byref(entry))
while ok:
    procs.append((entry.th32ProcessID, entry.szExeFile))
    ok = k32.Process32NextW(snap, ctypes.byref(entry))
k32.CloseHandle(snap)
verdicts = {}
unknown_names = {}
for pid, exe in procs:
    if pid == 0:
        continue
    alive, start, detail = m.probe_process(pid)
    key = "alive" if alive else ("dead?!" if alive is False else "unknown")
    if alive and start is None:
        key = "alive, start unreadable"
    verdicts[key] = verdicts.get(key, 0) + 1
    if key != "alive":
        unknown_names[exe] = unknown_names.get(exe, 0) + 1
print(f"\nrunning processes: {len(procs) - 1}; probe verdicts: {verdicts}")
print("not fully readable by this user (examples):",
      ", ".join(f"{n}x{c}" for n, c in sorted(unknown_names.items(), key=lambda kv: -kv[1])[:25]))
