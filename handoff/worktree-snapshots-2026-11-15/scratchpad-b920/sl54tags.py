import json, os, re, sys
packs_dir = os.path.join(sys.argv[1], 'resources', 'packs')
bad = []
for d in sorted(os.listdir(packs_dir)):
    pj = os.path.join(packs_dir, d, 'pack.json')
    if not os.path.exists(pj):
        continue
    m = json.load(open(pj, encoding='utf-8'))
    v = m['version']
    paths = [r['path'] for r in m['resources']]
    for path in paths:
        x = open(os.path.join(sys.argv[1], 'resources', path), encoding='utf-8').read()
        if path.endswith('.bpmn'):
            for pid, tag in re.findall(r'<process\b[^>]*\bid="([^"]+)"[^>]*?(?:operaton:versionTag="([^"]*)")?[^>]*>', x):
                t = re.search(r'<process\b[^>]*\bid="' + re.escape(pid) + r'"[^>]*>', x).group(0)
                mt = re.search(r'operaton:versionTag="([^"]*)"', t)
                if not mt or mt.group(1) != v:
                    bad.append((m['id'], path, pid, mt and mt.group(1)))
        elif path.endswith('.dmn'):
            for t in re.findall(r'<decision\b[^>]*>', x):
                mt = re.search(r'operaton:versionTag="([^"]*)"', t)
                if not mt or mt.group(1) != v:
                    bad.append((m['id'], path, t[:60], mt and mt.group(1)))
        elif path.endswith('.form.json'):
            f = json.loads(x)
            if f.get('version') != v:
                bad.append((m['id'], path, 'form', f.get('version')))
print(len(bad), bad[:10])
print(sorted({r['kind'] for d in os.listdir(packs_dir) if os.path.exists(os.path.join(packs_dir, d, 'pack.json')) for r in json.load(open(os.path.join(packs_dir, d, 'pack.json'), encoding='utf-8'))['resources']}))
