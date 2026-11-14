import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
BSL = chr(92)
s = s.replace(chr(8), BSL + "b")
lines = s.split("\n")
for n, l in enumerate(lines):
    if l.startswith('            lead = src[max(0, src.rfind("') and l.endswith('src.rfind("'):
        lines[n] = '            lead = src[max(0, src.rfind("' + BSL + "n" + BSL + 'n", 0, m.start())):m.start()]     # annotations above the mapping'
        del lines[n + 1:n + 3]
        break
s = "\n".join(lines)
open(p, "w", encoding="utf-8", newline="\n").write(s)
print(chr(8) in s)
