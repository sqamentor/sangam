"""Character-level change regions between two (single-line) files: prints each replaced span with context."""
import difflib, sys

a = open(sys.argv[1], encoding='utf-8', newline='').read()
b = open(sys.argv[2], encoding='utf-8', newline='').read()
ctx = int(sys.argv[3]) if len(sys.argv) > 3 else 40
sys.stdout.reconfigure(encoding='utf-8')
sm = difflib.SequenceMatcher(None, a, b, autojunk=False)
for tag, i1, i2, j1, j2 in sm.get_opcodes():
    if tag == 'equal':
        continue
    print(f'{tag} a[{i1}:{i2}] b[{j1}:{j2}]')
    print('  before: ' + repr(a[max(0, i1 - ctx):i1]))
    print('  old:    ' + repr(a[i1:i2]))
    print('  new:    ' + repr(b[j1:j2]))
    print('  after:  ' + repr(a[i2:i2 + ctx]))
