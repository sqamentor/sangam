import os
import sys

os.chdir(sys.argv[1])
p = 'sangam-persistence/README.md'
lines = open(p, encoding='utf-8').read().split('\n')
add = {
    '| `INTEGRATION_DSC_PREPARED`': '; decision 241 alter `0045-12`: the approved enrolment id and its `enrolment_approval_seq` bound at prepare (NOT NULL, no backfill)',
    '| `INTEGRATION_DSC_CERTIFICATE`': '; decision 241 alter `0045-11`: `approval_seq` (default 0, +1 on every move to APPROVED, never reset)',
}
for prefix, text in add.items():
    idx = [k for k, l in enumerate(lines) if l.startswith(prefix)]
    assert len(idx) == 1, prefix
    line = lines[idx[0]].rstrip()
    assert line.endswith(' |'), line[-20:]
    lines[idx[0]] = line[:-2] + text + ' |'
open(p, 'w', encoding='utf-8', newline='').write('\n'.join(lines))
print('ok')
