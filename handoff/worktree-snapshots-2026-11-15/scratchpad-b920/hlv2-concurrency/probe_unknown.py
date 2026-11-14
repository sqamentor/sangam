"""Which processes does the v2 probe call 'unknown', and why (detail text)? Read-only."""
import collections
import ctypes
import importlib.util
import os
from ctypes import wintypes

BASE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("shared_lock", os.path.join(BASE, "code", "shared_lock.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class PROCESSENTRY32W(ctypes.Structure):
    _fields_ = [("dwSize", wintypes.DWORD), ("cntUsage", wintypes.DWORD), ("th32ProcessID", wintypes.DWORD),
                ("th32DefaultHeapID", ctypes.c_size_t), ("th32ModuleID", wintypes.DWORD),
                ("cntThreads", wintypes.DWORD), ("th32ParentProcessID", wintypes.DWORD),
                ("pcPriClassBase", ctypes.c_long), ("dwFlags", wintypes.DWORD), ("szExeFile", ctypes.c_wchar * 260)]


k32 = ctypes.WinDLL("kernel32", use_last_error=True)
k32.CreateToolhelp32Snapshot.restype = wintypes.HANDLE
snap = k32.CreateToolhelp32Snapshot(0x2, 0)
e = PROCESSENTRY32W()
e.dwSize = ctypes.sizeof(PROCESSENTRY32W)
rows = []
ok = k32.Process32FirstW(snap, ctypes.byref(e))
while ok:
    rows.append((e.th32ProcessID, e.th32ParentProcessID, e.szExeFile))
    ok = k32.Process32NextW(snap, ctypes.byref(e))
k32.CloseHandle(snap)
details = collections.Counter()
for pid, ppid, exe in rows:
    if pid == 0:
        continue
    alive, start, detail = m.probe_process(pid)
    if alive is None:
        details[detail] += 1
        if exe.lower() in ("bash.exe", "conhost.exe", "postgres.exe", "python.exe", "java.exe", "pwsh.exe"):
            print(f"unknown: {exe} pid={pid} ppid={ppid}: {detail}")
print("unknown details:", dict(details))
