"""Handle-free process table via NtQuerySystemInformation(SystemProcessInformation=5), x64 layout.
Review helper only (no lock files touched)."""
import ctypes

WINDOWS_EPOCH_OFFSET_100NS = 116444736000000000

ntdll = ctypes.WinDLL("ntdll")
ntdll.NtQuerySystemInformation.restype = ctypes.c_long
ntdll.NtQuerySystemInformation.argtypes = (ctypes.c_ulong, ctypes.c_void_p, ctypes.c_ulong,
                                           ctypes.POINTER(ctypes.c_ulong))


def nt_table():
    """{pid: (create_epoch_or_None, image, session_id)} for every process, without opening any of them."""
    assert ctypes.sizeof(ctypes.c_void_p) == 8, "x64 layout only"
    size = 1 << 20
    while True:
        buf = ctypes.create_string_buffer(size)
        ret = ctypes.c_ulong()
        status = ntdll.NtQuerySystemInformation(5, buf, size, ctypes.byref(ret)) & 0xFFFFFFFF
        if status == 0xC0000004:  # STATUS_INFO_LENGTH_MISMATCH
            size = max(size * 2, ret.value + 65536)
            continue
        if status != 0:
            raise OSError(f"NtQuerySystemInformation status {status:#x}")
        break
    out, off, base = {}, 0, ctypes.addressof(buf)
    while True:
        nxt = ctypes.c_ulong.from_address(base + off).value
        create = ctypes.c_longlong.from_address(base + off + 0x20).value
        name_len = ctypes.c_ushort.from_address(base + off + 0x38).value
        name_ptr = ctypes.c_void_p.from_address(base + off + 0x40).value
        pid = ctypes.c_void_p.from_address(base + off + 0x50).value or 0
        session = ctypes.c_ulong.from_address(base + off + 0x64).value
        image = ctypes.wstring_at(name_ptr, name_len // 2) if name_ptr else "System Idle Process"
        out[pid] = ((create - WINDOWS_EPOCH_OFFSET_100NS) / 1e7 if create else None, image, session)
        if not nxt:
            return out
        off += nxt
