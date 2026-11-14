"""M-010b proof: for each compliance BPMN, before/after semantic digest (B23 di_check: root children without
bpmndi:BPMNDiagram, canonical), plus a stricter check: the definitions root attributes and every non-DI element and
attribute are identical, and the only new root children are bpmndi:BPMNDiagram."""
import hashlib
import os
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import di_check  # noqa: E402  B23's checker (canon, DI)

before_dir, after_dir = sys.argv[1], sys.argv[2]
ok = True
names = sorted(f for f in os.listdir(before_dir) if f.endswith('.bpmn'))
assert names == sorted(f for f in os.listdir(after_dir) if f.endswith('.bpmn')), 'file sets differ'
for f in names:
    rb = ET.parse(os.path.join(before_dir, f)).getroot()
    ra = ET.parse(os.path.join(after_dir, f)).getroot()
    sb = hashlib.sha256(''.join(di_check.canon(p) for p in rb if p.tag != di_check.DI + 'BPMNDiagram').encode()).hexdigest()
    sa = hashlib.sha256(''.join(di_check.canon(p) for p in ra if p.tag != di_check.DI + 'BPMNDiagram').encode()).hexdigest()
    root_same = rb.attrib == ra.attrib
    extra = [c.tag for c in ra if c.tag != di_check.DI + 'BPMNDiagram' and c.tag not in [x.tag for x in rb]]
    ndi = sum(1 for c in ra if c.tag == di_check.DI + 'BPMNDiagram')
    good = sb == sa and root_same and not extra and ndi == 1
    ok &= good
    print(f'{f}\n  before {sb}\n  after  {sa}\n  root-attributes-equal={root_same} diagrams={ndi} '
          f'{"SEMANTIC-EQUAL" if good else "DIFFERENT"}')
print('ALL SEMANTIC-EQUAL' if ok else 'DIFFERENCES FOUND', len(names))
sys.exit(0 if ok else 1)
