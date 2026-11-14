"""Print text context around byte patterns in a file (read-only)."""
import sys

path = sys.argv[1]
before, after = int(sys.argv[2]), int(sys.argv[3])
data = open(path, "rb").read()
for pat in sys.argv[4:]:
    needle = pat.encode()
    start = 0
    n = 0
    while n < 2:
        i = data.find(needle, start)
        if i < 0:
            break
        print(f"=== {pat!r} at {i}")
        print(data[max(0, i - before):i + after].decode("utf-8", "replace"))
        start = i + 1
        n += 1
