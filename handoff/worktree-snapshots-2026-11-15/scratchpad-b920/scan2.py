import re, sys, pathlib, collections
root = pathlib.Path(sys.argv[1])
FIELD = re.compile(r'<operaton:field name="(\w+)"><operaton:string>([^<]*)</operaton:string>')
TASK = re.compile(r'<(serviceTask|businessRuleTask|sendTask)\b[^>]*id="([^"]+)"[^>]*>(.*?)</\1>', re.S)
COND = re.compile(r'<conditionExpression[^>]*>([^<]*)</conditionExpression>')
procs = {}
for f in sorted(root.rglob('*.bpmn')):
    xml = f.read_text(encoding='utf-8')
    for proc in re.finditer(r'<process\b[^>]*id="([^"]+)"[^>]*>(.*?)</process>', xml, re.S):
        pid, body = proc.group(1), proc.group(2)
        prefixes = {}
        defaults = []
        for t in TASK.finditer(body):
            tid, inner = t.group(2), t.group(3)
            fl = dict(FIELD.findall(inner))
            if 'connector' in fl:
                c = fl['connector']
                pre = fl.get('resultPrefix') or c[c.rfind('.') + 1:]
                prefixes.setdefault(pre, []).append((tid, c, fl.get('mode', 'query'), fl.get('inputs', '')))
            if 'decisionKey' in fl:
                prefixes.setdefault(fl.get('resultPrefix') or '', []).append((tid, 'DMN:' + fl['decisionKey'], 'rule', ''))
            if fl.get('defaults'):
                for pair in fl['defaults'].split(';'):
                    if '=' in pair:
                        n, v = pair.split('=', 1)
                        defaults.append((tid, n.strip(), v.strip()))
        conds = [c.replace('&gt;', '>').replace('&lt;', '<').replace('&amp;', '&') for c in COND.findall(body)]
        calls = re.findall(r'calledElement="([^"]+)"', body)
        procs[pid] = dict(file=f.relative_to(root).as_posix(), prefixes=prefixes, defaults=defaults, conds=conds, calls=calls)
mode = sys.argv[2] if len(sys.argv) > 2 else 'own'
for pid, p in procs.items():
    for tid, n, v in p['defaults']:
        hits = []
        for pre, users in p['prefixes'].items():
            if pre and n.startswith(pre) and len(n) > len(pre) and n[len(pre)].isupper():
                hits += [(u[1], u[2], 'IN' if re.search(r'(^|,)' + n + r'(>|,|$)', u[3]) else '') for u in users]
        used = [c for c in p['conds'] if re.search(r'\b' + n + r'\b', c)]
        if hits:
            print(f'{pid} {n}={v} {hits} conds={used}')
