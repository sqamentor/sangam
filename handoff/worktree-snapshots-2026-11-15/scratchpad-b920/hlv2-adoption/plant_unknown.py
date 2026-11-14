"""Planted-record proof (COPY + temp lock folder): a dead holder whose pid now belongs to a process the caller cannot open
is judged 'unknown' -> treated as alive -> never auto-released, although its creation time (readable without opening
the process, via NtQuerySystemInformation) differs from holder_start."""
import ctypes, importlib.util, json, os, shutil, subprocess, sys, time
from ctypes import wintypes

BASE = os.path.dirname(os.path.abspath(__file__))
HEAVY = os.path.join(BASE, "code", "heavy_lock.py")
spec = importlib.util.spec_from_file_location("shared_lock_copy", os.path.join(BASE, "code", "shared_lock.py"))
sl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sl)
PY = sys.executable


def nt_process_table():
    """{pid: (create_epoch, image)} for every process, without opening any of them."""
    ntdll = ctypes.WinDLL("ntdll")
    size = 1 << 20
    while True:
        buf = ctypes.create_string_buffer(size)
        ret_len = wintypes.ULONG()
        status = ntdll.NtQuerySystemInformation(5, buf, size, ctypes.byref(ret_len)) & 0xFFFFFFFF
        if status == 0xC0000004:            # STATUS_INFO_LENGTH_MISMATCH
            size *= 2
            continue
        assert status == 0, hex(status)
        break
    out, off = {}, 0
    base = ctypes.addressof(buf)
    while True:
        nxt = ctypes.c_ulong.from_address(base + off).value
        create = ctypes.c_longlong.from_address(base + off + 32).value
        name_len = ctypes.c_ushort.from_address(base + off + 56).value
        name_ptr = ctypes.c_void_p.from_address(base + off + 64).value
        pid = ctypes.c_void_p.from_address(base + off + 80).value or 0
        image = ctypes.wstring_at(name_ptr, name_len // 2) if name_ptr else "System Idle"
        out[pid] = ((create - sl.WINDOWS_EPOCH_OFFSET_100NS) / 1e7 if create else None, image)
        if not nxt:
            return out
        off += nxt


table = nt_process_table()
# sanity: for an accessible process the NtQSI creation time equals GetProcessTimes
me = os.getpid()
print(f"self pid {me}: GetProcessTimes={sl.probe_process(me)[1]:.4f}  NtQuerySystemInformation={table[me][0]:.4f}")
target = None
for pid, (create, image) in sorted(table.items()):
    if pid > 1000 and create and sl.probe_process(pid)[0] is None:
        target = (pid, create, image)
        break
pid, create, image = target
print(f"inaccessible long-lived process: {image} pid {pid}; probe -> {sl.probe_process(pid)}; "
      f"NtQSI create={sl.iso(create)}")

d = os.path.join(BASE, "locks", "t9")
shutil.rmtree(d, ignore_errors=True)
os.makedirs(d)
now = time.time()
record = {"owner": "customwf-dead", "what": "runner died; its pid was reused by a process we cannot open",
          "since": sl.iso(now - 2400), "epoch": now - 2400, "held_by": sl.HELD_BY_NOTE, "acquirer_pid": 1,
          "acquirer_pid_note": sl.ACQUIRER_PID_NOTE, "holder_pid": pid, "holder_start": create - 3600.0,
          "lease_until": now - 60, "lease_seconds": 1800}
with open(os.path.join(d, "heavy-build.lock"), "w", encoding="utf-8") as f:
    json.dump(record, f)
print(f"planted: holder_pid={pid}, holder_start={sl.iso(create - 3600)} (1 h before the current owner of that pid), "
      "lease expired 60 s ago")
env = {k: v for k, v in os.environ.items() if not k.startswith("SHARED_LOCK_") and k != "HEAVY_LOCK_PID"}
env.update(SHARED_LOCK_DIR=d, SHARED_LOCK_POLL_SECONDS="0.2")
st = subprocess.run([PY, "-I", HEAVY, "status"], env=env, capture_output=True, text=True)
for line in st.stdout.splitlines()[1:3]:
    print("   status|", line)
w = subprocess.Popen([PY, "-I", HEAVY, "acquire", "W", "next job"], env=env, stdout=subprocess.PIPE,
                     stderr=subprocess.PIPE, text=True)
time.sleep(5)
print("waiter after 5 s:", "STILL WAITING (never auto-released while that process lives)" if w.poll() is None else f"exit {w.returncode}")
w.kill()
_, err = w.communicate()
print("   W.err|", (err or "").strip().splitlines()[:1])
