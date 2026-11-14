import os, re, sys, json, collections

root = sys.argv[1]
files = []
for dp, dn, fn in os.walk(root):
    dn[:] = [d for d in dn if not d.startswith('target') and d not in ('node_modules', '.git')]
    for f in fn:
        if f.endswith(('.bpmn', '.cmmn', '.bpmn20.xml', '.cmmn11.xml')):
            p = os.path.join(dp, f).replace(chr(92), '/')
            if '/src/test/' in p:
                continue
            files.append(p)
cls = collections.Counter(); dexp = collections.Counter(); exprs = collections.Counter()
ident = collections.Counter(); calls = collections.Counter(); ns = collections.Counter()
where = collections.defaultdict(set)
attr_re = re.compile(r'(\w+):(class|delegateExpression)="([^"]*)"')
el_re = re.compile(r'[$#]\{([^}]*)\}')
for p in files:
    t = open(p, encoding='utf-8').read()
    rel = p[len(root):].lstrip('/')
    for m in re.finditer(r'xmlns:(\w+)="([^"]+)"', t):
        ns[m.group(2)] += 1
    for m in attr_re.finditer(t):
        if m.group(2) == 'class':
            cls[m.group(3)] += 1
        else:
            dexp[m.group(3)] += 1
        where[m.group(3)].add(rel.split('/')[0])
    for m in el_re.finditer(t):
        e = m.group(1).strip()
        exprs[e] += 1
        for c in re.finditer(r'([A-Za-z_]\w*)(?:\s*\.\s*([A-Za-z_]\w*))?\s*\(', e):
            k = c.group(1) + ('.' + c.group(2) if c.group(2) else '')
            calls[k] += 1
            where[k].add(rel.split('/')[0])
        stripped = re.sub(r"'[^']*'|\"[^\"]*\"", ' ', e)
        for c in re.finditer(r'(?<![\w.])([A-Za-z_]\w*)', stripped):
            ident[c.group(1)] += 1
print('files', len(files))
mods = collections.Counter(f[len(root):].lstrip('/').split('/')[0] for f in files)
print('by module', dict(mods))
print('namespaces', dict(ns))
print('CLASSES', {k: (v, sorted(where[k])) for k, v in cls.items()})
print('DELEGATE_EXPR', {k: (v, sorted(where[k])) for k, v in dexp.items()})
print('CALLS', {k: (v, sorted(where[k])) for k, v in sorted(calls.items())})
print('IDENTS', sorted(ident.items(), key=lambda x: -x[1]))
print('N distinct expressions', len(exprs))
json.dump({'files': files, 'exprs': dict(exprs)}, open(os.path.join(os.path.dirname(os.path.abspath(sys.argv[0])), 'inv.json'), 'w'), indent=0)
