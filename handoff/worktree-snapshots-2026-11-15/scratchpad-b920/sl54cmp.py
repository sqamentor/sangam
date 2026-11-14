import json, os, subprocess, sys
root = sys.argv[1]
base = sys.argv[2] if len(sys.argv) > 2 else '15e4135'
rel = 'sangam/sangam-packs/src/main/resources/packs'


def git(*a):
    return subprocess.run(['git', '-C', root] + list(a), capture_output=True).stdout


basefiles = set(git('ls-tree', '-r', '--name-only', base, rel + '/').decode().splitlines())


def norm(b):
    return b.replace(b'\r\n', b'\n').replace(b'\r', b'\n')


out = {}
dirs = sorted(os.listdir(os.path.join(root, rel)))
for d in dirs:
    pj = os.path.join(root, rel, d, 'pack.json')
    if not os.path.exists(pj):
        continue
    m = json.load(open(pj, encoding='utf-8'))
    cur = {}
    for dp, _, fs in os.walk(os.path.join(root, rel, d)):
        for f in fs:
            p = os.path.join(dp, f)
            cur[os.path.relpath(p, root).replace(os.sep, '/')] = open(p, 'rb').read()
    old = {k for k in basefiles if k.startswith(rel + '/' + d + '/')}
    bv = None
    if old:
        try:
            bv = json.loads(git('show', base + ':' + rel + '/' + d + '/pack.json'))['version']
        except Exception:
            bv = '?'
    if not old:
        out[m['id']] = ('NEW', d, m['version'], bv, [])
        continue
    changed = []
    for k in sorted(set(cur) | old):
        short = k[len(rel) + len(d) + 2:]
        if k not in cur:
            changed.append(short + '(removed)')
        elif k not in old:
            changed.append(short + '(added)')
        elif norm(cur[k]) != norm(git('show', base + ':' + k)):
            changed.append(short)
    out[m['id']] = ('CHANGED' if changed else 'SAME', d, m['version'], bv, changed)
basedirs = {k[len(rel) + 1:].split('/')[0] for k in basefiles}
for k, (s, d, v, bv, c) in sorted(out.items()):
    print(s, k, d, v, bv, len(c), ' '.join(c))
print('dirs removed since base:', sorted(basedirs - set(dirs)))
