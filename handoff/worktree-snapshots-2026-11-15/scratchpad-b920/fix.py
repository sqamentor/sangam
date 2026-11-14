import sys
p = sys.argv[1]
lines = open(p, encoding="utf-8").read().split("\n")
BS = "\b"
for n, l in enumerate(lines):
    if l.startswith('    m = _v2_re.search(r"') and "\x08" in l or l.startswith('    m = _v2_re.search(r"" + name'):
        if n < 150:
            lines[n] = '    m = _v2_re.search(r"' + BS + r'(record|enum)\s+" + simple + r"' + BS + r'\s*(?:<[^>]*>)?\s*([({])", src)'
        else:
            lines[n] = '    m = _v2_re.search(r"' + BS + '" + name + r\'\s*=\s*"([^"]*)"\', text)'
s = "\n".join(lines)
s = s.replace('            lead = src[max(0, src.rfind("\n\n", 0, m.start())):m.start()]',
              '            lead = src[max(0, src.rfind("\n\n", 0, m.start())):m.start()]')
open(p, "w", encoding="utf-8", newline="\n").write(s)
print("\x08" in s)
