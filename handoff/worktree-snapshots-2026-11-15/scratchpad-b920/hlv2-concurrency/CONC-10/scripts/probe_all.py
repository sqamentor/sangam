"""Read-only. Which running pids does the copied v2 probe (shared_lock.probe_process) call 'unknown', and is their
creation time readable anyway through NtQuerySystemInformation? Writes nothing outside stdout."""
import collections
import ctypes
import importlib.util
import json
import os
import sys
import time
from ctypes import wintypes

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


sl = load("shared_lock", os.path.join(BASE, "code", "shared_lock.py"))
nq = load("ntqsi", os.path.join(BASE, "scripts", "ntqsi.py"))

# --- who am I (elevation / integrity)
adv = ctypes.WinDLL("advapi32", use_last_error=True)
k32 = ctypes.WinDLL("kernel32", use_last_error=True)
k32.GetCurrentProcess.restype = wintypes.HANDLE
adv.OpenProcessToken.argtypes = (wintypes.HANDLE, wintypes.DWORD, ctypes.POINTER(wintypes.HANDLE))
adv.GetTokenInformation.argtypes = (wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD,
                                    ctypes.POINTER(wintypes.DWORD))
tok = wintypes.HANDLE()
adv.OpenProcessToken(k32.GetCurrentProcess(), 0x0008, ctypes.byref(tok))
elev = wintypes.DWORD()
ln = wintypes.DWORD()
adv.GetTokenInformation(tok, 20, ctypes.byref(elev), 4, ctypes.byref(ln))      # TokenElevation
etype = wintypes.DWORD()
adv.GetTokenInformation(tok, 18, ctypes.byref(etype), 4, ctypes.byref(ln))     # TokenElevationType
print(f"python {sys.version.split()[0]}  token elevated={bool(elev.value)}  elevation type={etype.value} "
      "(1 default, 2 full/elevated, 3 limited/filtered)  IsUserAnAdmin={}".format(ctypes.windll.shell32.IsUserAnAdmin()))

procs = nq.snapshot()
details = collections.Counter()
denied_names = collections.Counter()
denied_with_ntqsi_start = 0
denied = []
for pid, (start, name, ppid) in sorted(procs.items()):
    if pid == 0:
        continue
    alive, pstart, detail = sl.probe_process(pid)
    if alive is None:
        details[detail] += 1
        denied_names[name] += 1
        if start is not None:
            denied_with_ntqsi_start += 1
        denied.append((pid, name, start))
    else:
        details["alive=" + str(alive)] += 1
print(f"processes listed by NtQuerySystemInformation: {len(procs)} (pid 0 skipped)")
print("copied v2 probe verdicts:", dict(details))
print("'unknown' by image name (top 25):", denied_names.most_common(25))
print(f"of the {len(denied)} 'unknown' pids, NtQuerySystemInformation gives a CreateTime for {denied_with_ntqsi_start}")
now = time.time()
old_svchost = [(pid, name, start) for pid, name, start in denied if name.lower() == "svchost.exe" and start
               and now - start > 3600]
young = sorted([(now - start, pid, name) for pid, name, start in denied if start], key=lambda r: r[0])[:15]
print("youngest 'unknown' processes (age s, pid, name):", [(int(a), p, n) for a, p, n in young])
if old_svchost:
    pid, name, start = old_svchost[0]
    print("CANDIDATE", json.dumps({"pid": pid, "name": name, "ntqsi_start": start,
                                   "probe": sl.probe_process(pid)}))
