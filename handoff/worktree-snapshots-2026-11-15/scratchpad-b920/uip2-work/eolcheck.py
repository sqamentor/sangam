"""Report the line-ending style of each listed file (relative to a root): crlf, lf or MIXED."""
import sys, os

root = sys.argv[1]
fix = '--fix-crlf' in sys.argv
for rel in [a for a in sys.argv[2:] if not a.startswith('--')]:
    p = os.path.join(root, rel)
    b = open(p, 'rb').read()
    crlf = b.count(b'\r\n')
    lf = b.count(b'\n') - crlf
    kind = 'crlf' if lf == 0 and crlf else 'lf' if crlf == 0 else 'MIXED'
    if fix and kind == 'MIXED':
        b = b.replace(b'\r\n', b'\n').replace(b'\n', b'\r\n')
        open(p, 'wb').write(b)
        kind += ' -> crlf'
    print(f'{kind:12} {rel}')
