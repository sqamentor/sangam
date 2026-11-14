"""List files that differ in content (CRLF/LF ignored) or exist on one side only, between WT and IT (build outputs
skipped). Also lists files that differ only in line endings for a given set of directories.

Usage: python difflist.py <WT> <IT>
"""
import os
import sys

WT, IT = sys.argv[1], sys.argv[2]
JUNK = ('sangam-ui/dist/', 'sdks/python/.pytest_cache/')


def walk(root):
    out = {}
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if not d.startswith('target-') and d != 'node_modules'
                       and not (d == 'target' and os.path.isfile(os.path.join(dirpath, 'pom.xml')))]
        for f in filenames:
            full = os.path.join(dirpath, f)
            rel = os.path.relpath(full, root).replace('\\', '/')
            if rel.startswith(JUNK) or '/bin/Debug/' in rel or '/obj/' in rel:
                continue
            out[rel] = full
    return out


def read(p):
    with open(p, 'rb') as fh:
        return fh.read()


wt, it = walk(WT), walk(IT)
content, eol_only = [], []
for rel in sorted(set(wt) & set(it)):
    a, b = read(wt[rel]), read(it[rel])
    if a == b:
        continue
    if a.replace(b'\r\n', b'\n') != b.replace(b'\r\n', b'\n'):
        content.append(rel)
    else:
        eol_only.append(rel)
only_wt = sorted(set(wt) - set(it))
only_it = sorted(set(it) - set(wt))
gen = [r for r in content if r.startswith('sangam-packs/src/main/resources/packs/')]
print('CONTENT DIFFERS (non-generated):')
for r in content:
    if r not in gen:
        print('  M ' + r)
print(f'CONTENT DIFFERS (generated packs): {len(gen)} files')
print('ONLY IN WT:')
for r in only_wt:
    print('  A ' + r)
print('ONLY IN IT:')
for r in only_it:
    print('  D ' + r)
eol_dirs = {}
for r in eol_only:
    k = '/'.join(r.split('/')[:3])
    eol_dirs[k] = eol_dirs.get(k, 0) + 1
print(f'LINE-ENDING-ONLY differences: {len(eol_only)} files, by area: {dict(sorted(eol_dirs.items(), key=lambda x: -x[1])[:8])}')
