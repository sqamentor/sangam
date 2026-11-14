import re, sys, pathlib
root = pathlib.Path(sys.argv[1])
FIELD = re.compile(r'<operaton:field name="(\w+)"><operaton:string>([^<]*)</operaton:string>')
TASK = re.compile(r'<(serviceTask)\b[^>]*id="([^"]+)"[^>]*>(.*?)</\1>', re.S)
procs = {}
for f in sorted(root.rglob('*.bpmn')):
    xml = f.read_text(encoding='utf-8')
    for proc in re.finditer(r'<process\b[^>]*id="([^"]+)"[^>]*>(.*?)</process>', xml, re.S):
        pid, body = proc.group(1), proc.group(2)
        pre, defs = {}, []
        for t in TASK.finditer(body):
            fl = dict(FIELD.findall(t.group(3)))
            if 'connector' in fl:
                c = fl['connector']
                pre[fl.get('resultPrefix') or c[c.rfind('.') + 1:]] = c
            if fl.get('defaults'):
                for pair in fl['defaults'].split(';'):
                    if '=' in pair:
                        n, v = pair.split('=', 1)
                        defs.append((n.strip(), v.strip()))
        procs[pid] = (pre, defs, set(re.findall(r'calledElement="([^"]+)"', body)))
def callees(pid, seen):
    for c in procs.get(pid, ({}, [], set()))[2]:
        if c not in seen:
            seen.add(c); callees(c, seen)
    return seen
for pid, (pre, defs, _) in procs.items():
    for c in callees(pid, set()):
        for p, conn in procs.get(c, ({}, [], set()))[0].items():
            for n, v in defs:
                if v != 'null' and n.startswith(p) and len(n) > len(p) and n[len(p)].isupper():
                    print(pid, '->', c, f'{n}={v}', p, conn)
