# cmpsrc.py <integration-sangam> <wt> : compare every file under any src/ tree (EOL-insensitive)
import os, sys, hashlib
base, wt = sys.argv[1], sys.argv[2]
SKIP = {'node_modules', 'dist'}


def files(root):
    out = {}
    for d, dirs, fs in os.walk(root):
        parts = os.path.relpath(d, root).split(os.sep)
        dirs[:] = [x for x in dirs if x not in SKIP and not x.startswith('target-')
                   and not (x == 'target' and os.path.exists(os.path.join(d, 'pom.xml')))]
        if 'src' not in parts:
            continue
        for f in fs:
            p = os.path.join(d, f)
            key = '/'.join(os.path.relpath(p, root).split(os.sep))
            out[key] = hashlib.sha256(open(p, 'rb').read().replace(b'\r\n', b'\n')).hexdigest()
    return out


a, b = files(base), files(wt)
print('integration src files:', len(a), ' wt src files:', len(b))
print('MISSING in wt:', sorted(set(a) - set(b)))
print('EXTRA in wt:', sorted(set(b) - set(a)))
print('DIFFERENT:', sorted(k for k in set(a) & set(b) if a[k] != b[k]))
