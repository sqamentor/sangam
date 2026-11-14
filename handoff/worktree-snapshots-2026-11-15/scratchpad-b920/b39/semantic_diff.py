"""M-010c proof: per file, every semantic difference (DI excluded) between before and after, element by element.
Prints each changed attribute; exits 1 if anything other than operaton:candidateGroups differs or the structure
(tags, ids, child order) differs. Also prints the new full semantic digest (B23 canon)."""
import hashlib
import os
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import di_check  # noqa: E402

CG = '{http://operaton.org/schema/1.0/bpmn}candidateGroups'


def walk(a, b, path, out, bad):
    if a.tag != b.tag or len(a) != len(b) or (a.text or '').strip() != (b.text or '').strip():
        bad.append(f'{path}: structure/text differs')
        return
    keys = set(a.attrib) | set(b.attrib)
    for k in sorted(keys):
        if a.get(k) != b.get(k):
            (out if k == CG else bad).append(f'{path}#{a.get("id")}: {k.split("}")[-1]} {a.get(k)!r} -> {b.get(k)!r}')
    for i, (x, y) in enumerate(zip(a, b)):
        walk(x, y, f'{path}/{x.tag.split("}")[-1]}[{i}]', out, bad)


before, after = sys.argv[1], sys.argv[2]
ok = True
for f in sorted(x for x in os.listdir(before) if x.endswith('.bpmn')):
    ra, rb = ET.parse(os.path.join(before, f)).getroot(), ET.parse(os.path.join(after, f)).getroot()
    pa = [c for c in ra if c.tag != di_check.DI + 'BPMNDiagram']
    pb = [c for c in rb if c.tag != di_check.DI + 'BPMNDiagram']
    out, bad = [], []
    if len(pa) != len(pb) or ra.attrib != rb.attrib:
        bad.append('root differs')
    for i, (x, y) in enumerate(zip(pa, pb)):
        walk(x, y, x.tag.split('}')[-1], out, bad)
    sem = hashlib.sha256(''.join(di_check.canon(p) for p in pb).encode()).hexdigest()
    print(f'{f}  after-digest {sem}  changed {len(out)}  other {len(bad)}')
    for line in out + bad:
        print('   ', line.split('/')[-1] if 'userTask' in line else line)
    ok &= not bad
print('ONLY candidateGroups CHANGED' if ok else 'OTHER DIFFERENCES FOUND')
sys.exit(0 if ok else 1)
