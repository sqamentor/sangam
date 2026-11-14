"""Invariant check: in a generated pack tree, every pack still at a version 15e4135 held is LF-identical to 15e4135;
every cross-pack pin names a version the callee pack ships."""
import json, os, re, subprocess, sys

repo, gen = sys.argv[1], sys.argv[2]
base = '15e4135'
rel = 'sangam/sangam-packs/src/main/resources/packs'
packs_dir = os.path.join(gen, 'resources', 'packs')


def git(*a):
    return subprocess.run(['git', '-C', repo] + list(a), capture_output=True).stdout


basefiles = set(git('ls-tree', '-r', '--name-only', base, rel + '/').decode().splitlines())
norm = lambda b: b.replace(b'\r\n', b'\n').replace(b'\r', b'\n')
versions, proc_owner, bad, bumped, kept, new = {}, {}, [], [], [], []
for d in sorted(os.listdir(packs_dir)):
    pj = os.path.join(packs_dir, d, 'pack.json')
    if not os.path.exists(pj):
        continue
    m = json.load(open(pj, encoding='utf-8'))
    versions[m['id']] = m['version']
    files = {}
    for dp, _, fs in os.walk(os.path.join(packs_dir, d)):
        for f in fs:
            p = os.path.join(dp, f)
            files[rel + '/' + os.path.relpath(p, packs_dir).replace(os.sep, '/')] = open(p, 'rb').read()
            if f.endswith('.bpmn'):
                for pid in re.findall(r'<process\b[^>]*\bid="([^"]+)"', files[rel + '/' + os.path.relpath(p, packs_dir).replace(os.sep, '/')].decode()):
                    proc_owner[pid] = m['id']
    old = {k for k in basefiles if k.startswith(rel + '/' + d + '/')}
    if not old:
        new.append(m['id'])
        continue
    bv = json.loads(git('show', base + ':' + rel + '/' + d + '/pack.json'))['version']
    if m['version'] == bv:
        same = set(files) == old and all(norm(files[k]) == norm(git('show', base + ':' + k)) for k in old)
        (kept if same else bad).append(m['id'])
    else:
        bumped.append(f"{m['id']} {bv}->{m['version']}")
pins = []
for dp, _, fs in os.walk(packs_dir):
    for f in fs:
        if f.endswith('.bpmn'):
            x = open(os.path.join(dp, f), encoding='utf-8').read()
            for called, tag in re.findall(r'calledElement="([^"]+)" operaton:calledElementBinding="versionTag" operaton:calledElementVersionTag="([^"]+)"', x):
                owner = proc_owner.get(called)
                if owner is None or versions[owner] != tag:
                    pins.append(f'{f}: {called}@{tag} (callee pack {owner} ships {versions.get(owner)})')
print('bumped', len(bumped)); print('kept unchanged', kept); print('new', new)
print('CHANGED BUT NOT BUMPED', bad)
print('stale pins', len(pins), pins[:5])
