"""SHA-256 of every APPROVE-401 file in the worktree and the integration tree, with EOL style. Usage: a401_sha.py WT IT"""
import hashlib, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from a401_files import ALL, MODIFIED, NEW

WT, IT = sys.argv[1], sys.argv[2]


def info(p):
    try:
        b = open(p, 'rb').read()
    except FileNotFoundError:
        return None, '-'
    crlf = b.count(b'\r\n')
    lf = b.count(b'\n') - crlf
    style = 'CRLF' if crlf and not lf else 'LF' if lf and not crlf else 'MIXED' if crlf and lf else 'NONE'
    return hashlib.sha256(b).hexdigest(), style


same = 0
for rel in ALL:
    a, sa = info(os.path.join(WT, rel))
    b, sb = info(os.path.join(IT, rel))
    ok = a is not None and a == b
    same += ok
    print(f"{'IDENTICAL' if ok else 'DIFFERENT':9} wt={sa:5} it={sb:5} {a or '-'} {rel}")
print(f'{same}/{len(ALL)} identical')
