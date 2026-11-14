import re, glob, yaml, os, subprocess
root = 'D:/GITHUB  Project/Custom WorkFlow/.claude/worktrees/workspace-scan-resume-06b524/sangam'
y = yaml.safe_load(open(root + '/sangam-rest/api/sangam-api.yaml', encoding='utf8'))
norm = lambda p: re.sub(r'\{[^}]+\}', '{}', p)
ops = {}
for p, item in y['paths'].items():
    for m, op in item.items():
        if m in ('get', 'post', 'put', 'patch', 'delete'):
            ops[(m.upper(), norm(p))] = (p, op)
# java mappings index
java = {}
for f in glob.glob(root + '/sangam-*/src/main/java/**/*.java', recursive=True):
    try:
        lines = open(f, encoding='utf8').read().split('\n')
    except Exception:
        continue
    base = ''
    for i, l in enumerate(lines):
        m = re.search(r'@RequestMapping\(\s*(?:value\s*=\s*|path\s*=\s*)?"([^"]+)"', l)
        if m and i < 80:
            base = m.group(1)
        m = re.search(r'@(Get|Post|Put|Patch|Delete)Mapping\(\s*(?:value\s*=\s*|path\s*=\s*)?"([^"]*)"', l)
        if m:
            full = (base + m.group(2)).replace('/api/v1', '')
            sig = ' '.join(x.strip() for x in lines[i + 1:i + 4])
            java.setdefault((m.group(1).upper(), norm(full)), []).append(f'{os.path.relpath(f, root)}:{i + 2}  {sig[:260]}')
for line in open(os.path.join(os.path.dirname(__file__), 'drift.txt'), encoding='utf8'):
    m = re.match(r'(\S+) (\w+) (\S+) extra', line)
    if not m or m.group(1).startswith('resources'):
        continue
    meth, path = m.group(2), m.group(3)
    p, op = ops[(meth, norm(path))]
    rb = op.get('requestBody', {}).get('content', {}).get('application/json', {}).get('schema', {})
    print(m.group(1), meth, path, 'schema:', rb.get('$ref', rb).__str__()[-60:])
    for j in java.get((meth, norm(path)), ['  NO JAVA MAPPING']):
        print('   ', j)
