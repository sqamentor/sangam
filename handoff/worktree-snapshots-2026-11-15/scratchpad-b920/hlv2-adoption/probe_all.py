"""Run the COPY's probe_process over every running pid (medium-integrity caller) and tally what liveness() would say
if that pid were a recorded holder (holder_start = whatever the probe read at 'acquire' time)."""
import collections, ctypes, importlib.util, os
from ctypes import wintypes

BASE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("shared_lock_copy", os.path.join(BASE, "code", "shared_lock.py"))
sl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sl)

arr = (wintypes.DWORD * 8192)()
needed = wintypes.DWORD()
ctypes.WinDLL("psapi").EnumProcesses(ctypes.byref(arr), ctypes.sizeof(arr), ctypes.byref(needed))
pids = [arr[i] for i in range(needed.value // ctypes.sizeof(wintypes.DWORD))]


def image(pid):
    k32 = ctypes.WinDLL("kernel32", use_last_error=True)
    k32.OpenProcess.restype = wintypes.HANDLE
    h = k32.OpenProcess(0x1000, False, pid)
    if not h:
        return "?"
    buf = ctypes.create_unicode_buffer(1024)
    size = wintypes.DWORD(1024)
    ok = k32.QueryFullProcessImageNameW(h, 0, buf, ctypes.byref(size))
    k32.CloseHandle(h)
    return os.path.basename(buf.value) if ok else "?"


tally = collections.Counter()
examples = collections.defaultdict(list)
for pid in pids:
    alive, start, detail = sl.probe_process(pid)
    state, _ = sl.liveness({"holder_pid": pid, "holder_start": start})
    key = f"alive={alive} start={'yes' if start is not None else 'NONE'} -> liveness={state} ({detail})"
    tally[key] += 1
    if len(examples[key]) < 8:
        examples[key].append(f"{image(pid)}:{pid}")
print(f"{len(pids)} pids probed")
for key, n in tally.most_common():
    print(f"{n:4d}  {key}   e.g. {', '.join(examples[key])}")
