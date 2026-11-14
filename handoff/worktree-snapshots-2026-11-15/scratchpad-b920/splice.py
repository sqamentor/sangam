import sys
p = sys.argv[1]; new = open(sys.argv[2], encoding="utf-8").read()
s = open(p, encoding="utf-8").read()
a = s.index("# ---------------------------------------------------------------- map reads")
b = s.index("# ---------------------------------------------------------------- schemas")
s = s[:a] + new + s[b:]
old = 'w.run(path, ms[0], spec["var"], ())'
if old in s:
    s = s.replace(old, 'w.run(path, ms[0], spec["var"], "list" if spec.get("array") else "map", [()])')
open(p, "w", encoding="utf-8", newline="\n").write(s)
print("ok")
