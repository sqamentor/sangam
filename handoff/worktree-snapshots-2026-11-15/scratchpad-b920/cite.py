import re, sys
topics = {}
for ln in open(sys.argv[1], encoding='utf-8'):
    ln = ln.strip()
    if ln.startswith('ADR:') and '=' in ln:
        k, v = ln[4:].split('=', 1)
        topics[k] = v.split('|')
cite = re.compile(r'(?<![A-Za-z0-9-])(ADR-|FLOWABLE-INTO-OPERATON )?(D|FC)-(\d+)\b')
for path in sys.argv[2:]:
    lines = open(path, encoding='utf-8').read().splitlines()
    for i, l in enumerate(lines):
        for m in cite.finditer(l):
            if m.group(1) != 'ADR-':
                continue
            idv = m.group(2) + '-' + m.group(3)
            ctx = ' '.join(lines[max(0, i - 3):min(len(lines), i + 4)]).lower()
            ok = any(k in ctx for k in topics.get(idv, []))
            print(path.split('/')[-1], i + 1, 'ADR-' + idv, 'OK' if ok else 'MISSING', max(len(x) for x in lines))
