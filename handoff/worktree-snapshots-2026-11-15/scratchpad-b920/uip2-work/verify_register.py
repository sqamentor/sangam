"""Check my register edits are present in the integration register and list what others changed since the apply."""
import difflib
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from reg_notes import NOTES  # noqa: E402
from reg_sl60 import ROW, POINTER  # noqa: E402

OLD = r"C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\uip2_reg.py"
INT = r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam\TESTING-GAP-REGISTER.md"


def text(p):
    with open(p, encoding='utf-8', newline='') as fh:
        return fh.read().replace('\r\n', '\n')


src = open(OLD, encoding='utf-8').read()
start = src.index('R=[')
ns = {}
exec(src[start:src.index('\n]\n', start) + 3], ns)
cur = text(INT)
print('8 parity rows present:', all(cur.count(b) == 1 for _, b in ns['R']))
print('SL-60 row present once:', cur.count(ROW) == 1, '| any SL-60 row lines:', sum(1 for l in cur.split('\n') if l.startswith('| SL-60 |')))
print('taskForm note present:', cur.count(NOTES[0]) == 1, '| SL-60 pointer present:', cur.count(POINTER) == 1)
mine = {b for _, b in ns['R']} | {ROW, NOTES[0], POINTER, ''}
before = text(os.path.join(HERE, 'int-register-before.md'))
others = [l for l in difflib.unified_diff(before.splitlines(), cur.splitlines(), lineterm='', n=0)
          if l[:1] in '+-' and l[:3] not in ('+++', '---') and l[1:] not in mine
          and not any(l[1:] == a for a, _ in ns['R'])]
print('changes by others since my apply:', len(others))
for l in others:
    print('   ', l[:150])
