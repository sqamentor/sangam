"""Print the imported DLL names of PE files (read-only; stdlib only)."""
import struct
import sys


def imports(path):
    data = open(path, "rb").read()
    pe = struct.unpack_from("<I", data, 0x3C)[0]
    nsec = struct.unpack_from("<H", data, pe + 6)[0]
    opt_size = struct.unpack_from("<H", data, pe + 20)[0]
    opt = pe + 24
    magic = struct.unpack_from("<H", data, opt)[0]
    dd = opt + (112 if magic == 0x20B else 96)
    imp_rva = struct.unpack_from("<I", data, dd + 8)[0]
    secs = []
    for i in range(nsec):
        s = opt + opt_size + 40 * i
        va, vsize, raw_size, raw_ptr = struct.unpack_from("<IIII", data, s + 8)[0:1] + \
            struct.unpack_from("<I", data, s + 8) + struct.unpack_from("<I", data, s + 16) + struct.unpack_from("<I", data, s + 20)
        vsize = struct.unpack_from("<I", data, s + 8)[0]
        va = struct.unpack_from("<I", data, s + 12)[0]
        secs.append((va, max(vsize, raw_size), raw_ptr))

    def off(rva):
        for va, size, raw in secs:
            if va <= rva < va + size:
                return rva - va + raw
        raise ValueError(hex(rva))

    out = []
    p = off(imp_rva)
    while True:
        name_rva = struct.unpack_from("<I", data, p + 12)[0]
        if name_rva == 0:
            break
        q = off(name_rva)
        out.append(data[q:data.index(b"\0", q)].decode())
        p += 20
    return out


for path in sys.argv[1:]:
    try:
        print(path, imports(path))
    except Exception as exc:
        print(path, "error", exc)
