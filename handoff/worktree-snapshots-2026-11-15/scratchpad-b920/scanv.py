import re, sys, pathlib, collections
root = pathlib.Path(sys.argv[1])
FIELD = re.compile(r'<operaton:field name="(\w+)"><operaton:string>([^<]*)</operaton:string>')
TASK = re.compile(r'<(serviceTask|businessRuleTask|sendTask)\b[^>]*id="([^"]+)"[^>]*>(.*?)</\1>', re.S)
out = []
for f in sorted(root.rglob('*.bpmn')):
    xml = f.read_text(encoding='utf-8')
    for proc in re.finditer(r'<process\b[^>]*id="([^"]+)"[^>]*>(.*?)</process>', xml, re.S):
        pid, body = proc.group(1), proc.group(2)
        prefixes = {}  # prefix -> (task id, connector, mode)
        defaults = []
        for t in TASK.finditer(body):
            tid, inner = t.group(2), t.group(3)
            fl = dict(FIELD.findall(inner))
            if 'connector' in fl:
                c = fl['connector']
                pre = fl.get('resultPrefix') or c[c.rfind('.') + 1:]
                prefixes.setdefault(pre, []).append((tid, c, fl.get('mode', 'query')))
            if 'decisionKey' in fl and fl.get('resultPrefix'):
                prefixes.setdefault(fl['resultPrefix'], []).append((tid, 'DMN:' + fl['decisionKey'], 'rule'))
            if fl.get('values'):
                for pair in fl['defaults'].split(';'):
                    if '=' in pair:
                        n, v = pair.split('=', 1)
                        defaults.append((tid, n.strip(), v.strip()))
        for tid, n, v in defaults:
            for pre, users in prefixes.items():
                if n.startswith(pre) and len(n) > len(pre) and n[len(pre)].isupper():
                    out.append((f.relative_to(root).as_posix(), pid, tid, n, v, pre, users))
for o in out:
    print(o[0], o[1], o[2], f'{o[3]}={o[4]}', 'prefix', o[5], [(u[1], u[2]) for u in o[6]])
print(len(out), file=sys.stderr)
