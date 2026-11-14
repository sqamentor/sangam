"""Targeted edits of the CURRENT integration register for UI-PARITY-2 (each exact and checked):
  1. the 8 parity rows (P02, P13, P14, P32/P33, P35, P45, P49, P59): exact old row -> new row, count must be 1;
  2. the SL-60 row inserted right after the SL-59 row (SL-59 must occur once, SL-60 must not exist yet);
  3. two paragraphs appended at the end (taskForm root cause; pointer to SL-60), unless already present.
EOL style of the file is kept. usage: reg_apply_int.py <register> [--dry-run]
"""
import sys

HERE = r"C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\uip2-work"
OLD = r"C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\uip2_reg.py"
sys.path.insert(0, HERE)
from reg_notes import NOTES  # noqa: E402
from reg_sl60 import ROW, POINTER  # noqa: E402

src = open(OLD, encoding='utf-8').read()
start = src.index('R=[')
ns = {}
exec(src[start:src.index('\n]\n', start) + 3], ns)
ROWS = ns['R']

f = sys.argv[1]
dry = '--dry-run' in sys.argv
raw = open(f, encoding='utf-8', newline='').read()
crlf = '\r\n' in raw
c = raw.replace('\r\n', '\n')
for a, b in ROWS:
    n = c.count(a)
    if n != 1:
        sys.exit(f'row not found exactly once ({n}): {a[:60]}')
    c = c.replace(a, b)
print('parity rows: 8 replaced')
lines = c.split('\n')
if any(l.startswith('| SL-60 |') for l in lines):
    sys.exit('an SL-60 row already exists')
at = [i for i, l in enumerate(lines) if l.startswith('| SL-59 |')]
if len(at) != 1:
    sys.exit('SL-59 row not found exactly once')
lines.insert(at[0] + 1, ROW)
print('SL-60 row inserted at line', at[0] + 2)
c = '\n'.join(lines)
for p in (NOTES[0], POINTER):
    if p[:60] in c:
        print('already present:', p[:50])
        continue
    c = c.rstrip('\n') + '\n\n' + p + '\n'
    print('appended:', p[:50])
if not dry:
    open(f, 'w', encoding='utf-8', newline='').write(c.replace('\n', '\r\n') if crlf else c)
    print('written', 'crlf' if crlf else 'lf')
