import re, collections
P = r"D:\GITHUB  Project\sangam-handoff\FILE-CLAIMS.md"
raw = open(P, "rb").read()
print("bytes", len(raw), "CRLF", raw.count(b"\r\n"), "LF", raw.count(b"\n"), "NUL", raw.count(b"\x00"), "BOM", raw[:3] == b"\xef\xbb\xbf")
try:
    raw.decode("utf-8")
    print("utf8 ok")
except Exception as e:
    print("utf8 bad", e)
lines = raw.decode("utf-8", "replace").split("\n")
TS = re.compile(r"^(\d{4}-\d\d-\d\dT\d\d:\d\d(?::\d\d)?Z)\s")
prev = None
nonts = []
backs = []
for i, l in enumerate(lines, 1):
    s = l.rstrip("\r")
    m = TS.match(s)
    if not m:
        if s.strip():
            nonts.append((i, s[:120]))
        continue
    t = m.group(1)
    if len(t) == 17:
        t = t[:-1] + ":00Z"
    if prev and t < prev[1]:
        backs.append((i, t, prev))
    prev = (i, t)
print("non-timestamp lines:")
for x in nonts:
    print("  ", x)
print("time going backwards:", len(backs))
for b in backs:
    print("  ", b)
c = collections.Counter(l.rstrip("\r") for l in lines if l.strip())
d = [(k[:140], v) for k, v in c.items() if v > 1]
print("duplicate lines:", len(d))
for x in d:
    print("  ", x)
# lane open claims at checkpoint
CP = "2026-11-12T07:07:55Z"
print("last line:", lines[-2][:160] if lines[-1] == "" else lines[-1][:160])
# any line with stray mid-line timestamp (two entries glued)
for i, l in enumerate(lines, 1):
    if len(re.findall(r"20\d\d-\d\d-\d\dT\d\d:\d\d(?::\d\d)?Z", l)) > 1:
        print("multi-ts line", i, l[:200])
