import re, sys, glob
sys.path.insert(0, '.')
import money
rep = []
for f in glob.glob('*.py'):
    if f in ('money.py', 'lib.py'): continue
    s = open(f, encoding='utf-8').read()
    # input: (label, 'var', 'double') ; output: ('var', label, 'double')
    def inp(m):
        var = m.group(2)
        t = 'money' if money.is_money(var) else 'number'
        rep.append((f, var, t)); return m.group(1) + t + "'"
    s2 = re.sub(r"(\('[^']*',\s*'(\w+)',\s*')(?:double|float)'", inp, s)
    def out(m):
        var = m.group(2)
        t = 'money' if money.is_money(var) else 'number'
        rep.append((f, var, t)); return m.group(1) + t + "'"
    s2 = re.sub(r"(\('(\w+)',\s*'[^']*',\s*')(?:double|float)'", out, s2)
    if s2 != s: open(f, 'w', encoding='utf-8', newline='').write(s2)
for r in sorted(set(rep)): print(*r)
