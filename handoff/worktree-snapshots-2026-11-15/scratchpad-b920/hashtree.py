import hashlib, os, sys, json
out = {}
for r in sys.argv[2:]:
    walk = [(os.path.dirname(r), [], [os.path.basename(r)])] if os.path.isfile(r) else os.walk(r)
    for d, _, fs in walk:
        dd = d.replace(os.sep, '/')
        if 'node_modules' in dd or '/target' in dd or '/bin/' in dd or '/obj/' in dd: continue
        for f in fs:
            p = os.path.join(d, f)
            out[p.replace(os.sep, '/')] = hashlib.sha256(open(p, 'rb').read()).hexdigest()
json.dump(out, open(sys.argv[1], 'w'), indent=0, sort_keys=True)
print(len(out))
