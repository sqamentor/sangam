"""Apply the UI-PARITY-2 register row edits (exact, count == 1) to a TESTING-GAP-REGISTER.md, keeping its EOL style.

Usage: reg_rows.py <register> [--check]
"""
import importlib.util
import sys

OLD = r"C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\uip2_reg.py"

# reuse the replacement table of the original script without running it
src = open(OLD, encoding='utf-8').read()
start = src.index('R=[')
end = src.index('\n]\n', start) + 3
ns = {}
exec(src[start:end], ns)
ROWS = ns['R']

f = sys.argv[1]
check = '--check' in sys.argv
raw = open(f, encoding='utf-8', newline='').read()
crlf = '\r\n' in raw
c = raw.replace('\r\n', '\n')
for a, b in ROWS:
    n = c.count(a)
    print(n, a[:70])
    if n != 1:
        sys.exit('row not found exactly once')
    c = c.replace(a, b)
if not check:
    out = c.replace('\n', '\r\n') if crlf else c
    open(f, 'w', encoding='utf-8', newline='').write(out)
    print('written', f, 'crlf' if crlf else 'lf')
