"""Read-only helper: process list with CreateTime via NtQuerySystemInformation(SystemProcessInformation),
which needs no OpenProcess and therefore works for processes this user cannot open."""
import ctypes
import struct
from ctypes import wintypes

WINDOWS_EPOCH_OFFSET_100NS = 116444736000000000
_ntdll = ctypes.WinDLL("ntdll")
_ntdll.NtQuerySystemInformation.restype = ctypes.c_long
_ntdll.NtQuerySystemInformation.argtypes = (ctypes.c_ulong, ctypes.c_void_p, ctypes.c_ulong,
                                            ctypes.POINTER(ctypes.c_ulong))
STATUS_INFO_LENGTH_MISMATCH = -1073741820  # 0xC0000004
SystemProcessInformation = 5
_bufsize = [1 << 20]


def snapshot(with_threads=False):
    """{pid: (create_epoch_or_None, image_name, ppid)} and, when with_threads, a set of live thread ids."""
    while True:
        size = _bufsize[0]
        buf = ctypes.create_string_buffer(size)
        ret = ctypes.c_ulong(0)
        status = _ntdll.NtQuerySystemInformation(SystemProcessInformation, buf, size, ctypes.byref(ret))
        if status == STATUS_INFO_LENGTH_MISMATCH:
            _bufsize[0] = max(size * 2, ret.value + (1 << 16))
            continue
        if status != 0:
            raise OSError(f"NtQuerySystemInformation status 0x{status & 0xFFFFFFFF:08X}")
        break
    base = ctypes.addressof(buf)
    raw = buf.raw
    procs, tids = {}, set()
    off = 0
    while True:
        next_off, nthreads = struct.unpack_from("<II", raw, off)
        (create,) = struct.unpack_from("<q", raw, off + 32)
        name_len, _maxlen = struct.unpack_from("<HH", raw, off + 56)
        (name_ptr,) = struct.unpack_from("<Q", raw, off + 64)
        (pid,) = struct.unpack_from("<Q", raw, off + 80)
        (ppid,) = struct.unpack_from("<Q", raw, off + 88)
        name = ""
        if name_ptr and name_len:
            rel = name_ptr - base
            if 0 <= rel < size:
                name = raw[rel:rel + name_len].decode("utf-16-le", errors="replace")
        start = (create - WINDOWS_EPOCH_OFFSET_100NS) / 1e7 if create > 0 else None
        procs[pid] = (start, name or ("System Idle Process" if pid == 0 else "?"), ppid)
        if with_threads:
            t = off + 256
            for _ in range(nthreads):
                (tid,) = struct.unpack_from("<Q", raw, t + 48)
                tids.add(tid)
                t += 80
        if next_off == 0:
            break
        off += next_off
    return (procs, tids) if with_threads else procs
