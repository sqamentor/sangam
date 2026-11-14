"""Parse the UNSD COFOG structure file, cross-check with the Eurostat COFOG99 codelist, emit a Python literal."""
import re
import sys

unsd, estat = sys.argv[1], sys.argv[2]
rows = []
with open(unsd, encoding='ascii') as f:
    lines = f.read().splitlines()
assert lines[0].split() == ['Code', 'Description'], lines[0]
for ln in lines[1:]:
    if not ln.strip():
        continue
    m = re.fullmatch(r'(\d{2}(?:\.\d(?:\.\d)?)?)\s+(.*?)\s*(?:\((CS|IS)\))?\s*', ln)
    assert m, ln
    code, title, svc = m.group(1), re.sub(r'\s+', ' ', m.group(2)).strip(), m.group(3) or ''
    rows.append((code, title, svc))
codes = {c for c, _, _ in rows}
assert len(codes) == len(rows)
for c, t, s in rows:
    parts = c.split('.')
    if len(parts) > 1:
        assert '.'.join(parts[:-1]) in codes, c
    assert (len(parts) == 3) == bool(s), (c, s)
est = {}
with open(estat, encoding='utf-8') as f:
    for ln in f.read().splitlines():
        k, v = ln.split('\t', 1)
        est[k] = v.strip()
mism = []
for c, t, s in rows:
    p = c.split('.')
    if len(p) == 1:
        k = 'GF' + p[0]
    elif len(p) == 2:
        k = 'GF' + p[0] + '0' + p[1]
    else:
        continue
    if k not in est:
        mism.append(('missing in eurostat', c, t))
    elif est[k].lower() != t.lower():
        mism.append(('title differs', c, t, est[k]))
extra = [k for k in est if k.startswith('GF') and len(k) in (4, 6)
         and (k[2:4] + ('.' + k[5] if len(k) == 6 else '')) not in codes]
print('counts', [sum(1 for c, _, _ in rows if c.count('.') == n) for n in range(3)], file=sys.stderr)
print('eurostat mismatches', mism, 'extra', extra, file=sys.stderr)
print(repr(rows))
