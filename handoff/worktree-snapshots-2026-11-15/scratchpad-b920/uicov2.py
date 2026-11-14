import re, sys, os
root, uncf = sys.argv[1], sys.argv[2]
unc = [l.strip() for l in open(uncf, encoding='utf-8') if l.strip().startswith('/')]
src = ''
for dp, _, fs in os.walk(os.path.join(root, 'sangam-ui/src')):
    for f in fs:
        if f.endswith(('.ts', '.tsx')) and '.test.' not in f:
            src += open(os.path.join(dp, f), encoding='utf-8').read() + '\n'
strong, weak = [], []
for p in unc:
    segs = [s for s in p.split('/') if s and not s.startswith('{')]
    # distinctive tail: last two static segments
    tail = segs[-1]
    pat = re.compile(r"[/'\"`]" + re.escape(tail) + r"[/'\"`?]")
    (weak if pat.search(src) and len(segs) > 1 and segs[0] in src else strong).append(p)
print('LIKELY-UNCOVERED (tail segment never appears in UI source):', len(strong))
for p in strong: print('  ', p)
print('POSSIBLY COVERED via variable-built paths (tail appears):', len(weak))
for p in weak: print('  ', p)
