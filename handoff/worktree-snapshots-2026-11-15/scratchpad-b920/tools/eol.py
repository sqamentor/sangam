# Usage: eol.py <wt-root> <integration-sangam-root> <relpath>...  -> give each file the EOL of its integration-tree original (CRLF default for new files)
import sys, os
wt, base = sys.argv[1], sys.argv[2]
for rel in sys.argv[3:]:
    p = os.path.join(wt, rel); o = os.path.join(base, rel)
    data = open(p, 'rb').read().replace(b'\r\n', b'\n')
    crlf = True
    if os.path.exists(o):
        od = open(o, 'rb').read()
        crlf = b'\r\n' in od
    if crlf:
        data = data.replace(b'\n', b'\r\n')
    open(p, 'wb').write(data)
    print(('CRLF ' if crlf else 'LF   ') + rel)
