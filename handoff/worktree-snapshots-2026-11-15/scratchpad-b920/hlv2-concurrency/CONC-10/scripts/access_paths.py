"""Read-only. Which branch of _probe_windows does each running pid take? (QUERY_LIMITED|SYNCHRONIZE ok -> wait path;
only QUERY_LIMITED ok -> GetExitCodeProcess path, start time still read; neither -> 'unknown')."""
import collections
import ctypes
import importlib.util
import os
from ctypes import wintypes

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
spec = importlib.util.spec_from_file_location("ntqsi", os.path.join(BASE, "scripts", "ntqsi.py"))
nq = importlib.util.module_from_spec(spec)
spec.loader.exec_module(nq)
k32 = ctypes.WinDLL("kernel32", use_last_error=True)
k32.OpenProcess.restype = wintypes.HANDLE
k32.OpenProcess.argtypes = (wintypes.DWORD, wintypes.BOOL, wintypes.DWORD)
k32.CloseHandle.argtypes = (wintypes.HANDLE,)
QL, SYNC = 0x1000, 0x00100000
paths = collections.Counter()
exitcode_path = []
for pid, (start, name, _pp) in nq.snapshot().items():
    if pid == 0:
        continue
    h = k32.OpenProcess(QL | SYNC, False, pid)
    if h:
        k32.CloseHandle(h)
        paths["QL|SYNC ok (WaitForSingleObject path)"] += 1
        continue
    e1 = ctypes.get_last_error()
    h = k32.OpenProcess(QL, False, pid)
    if h:
        k32.CloseHandle(h)
        paths[f"SYNC denied (err {e1}), QL ok (GetExitCodeProcess path, start time read)"] += 1
        exitcode_path.append(name)
    else:
        paths[f"both denied (err {e1}/{ctypes.get_last_error()}) -> 'unknown', no start time"] += 1
print(dict(paths))
print("GetExitCodeProcess-path processes:", collections.Counter(exitcode_path).most_common())
