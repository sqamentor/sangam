import json, os, sys
new, old = sys.argv[1], sys.argv[2]
names = sys.argv[3:]
ok = True
for n in names:
    a = json.load(open(os.path.join(new, 'packs', n, 'pack.json'), encoding='utf-8'))
    b = json.load(open(os.path.join(old, 'packs', n, 'pack.json'), encoding='utf-8'))
    binds = {c['id']: (c['simulatorBinding']['connector'], c['simulatorBinding'].get('operation')) for c in a['connectors'] if 'simulatorBinding' in c}
    workers = [w['topic'] for w in a.get('workers', [])]
    for c in a['connectors']:
        c.pop('simulatorBinding', None)
    a.pop('workers', None)
    same = a == b
    ok &= same
    print(f"{n:24} only-my-keys={same}  bindings={binds}  workers={workers}")
print('ALL ONLY MY KEYS' if ok else 'OTHER DIFFERENCES PRESENT')
