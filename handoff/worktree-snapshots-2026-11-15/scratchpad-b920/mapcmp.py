import json, os, re, sys, collections


def load(d):
    a = os.path.join(d, 'assets')
    pk = collections.defaultdict(set)
    where = collections.defaultdict(set)
    for f in os.listdir(a):
        if not f.endswith('.js.map'):
            continue
        chunk = re.sub(r'-[A-Za-z0-9_-]{8}\.js\.map$', '', f)
        for s in json.load(open(os.path.join(a, f), encoding='utf-8'))['sources']:
            m = re.search(r'node_modules/((?:@[^/]+/)?[^/]+)/(.*)', s)
            if m:
                pk[m.group(1)].add(m.group(2))
                where[m.group(1)].add(chunk)
            else:
                pk['<app>'].add(s.split('sangam-ui/')[-1])
                where['<app>'].add(chunk)
    return pk, where


a, wa = load(sys.argv[1])
b, wb = load(sys.argv[2])
for k in sorted(set(a) | set(b)):
    na, nb = len(a.get(k, ())), len(b.get(k, ()))
    if na != nb or k in sys.argv[3:]:
        print(f'{k}: {na} -> {nb}  base={sorted(wa.get(k, ()))} v8={sorted(wb.get(k, ()))}')
        miss = sorted(a.get(k, set()) - b.get(k, set()))
        if miss:
            print('   missing in v8:', miss[:8], '...' if len(miss) > 8 else '')
