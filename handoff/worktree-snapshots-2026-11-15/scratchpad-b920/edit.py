"""edit.py FILE: apply (old -> new) replacement pairs read from a JSON file, keeping the file's line endings."""
import json, sys
path, spec = sys.argv[1], sys.argv[2]
raw = open(path, 'rb').read().decode('utf-8')
crlf = '\r\n' in raw
s = raw.replace('\r\n', '\n')
for old, new, *cnt in json.load(open(spec, encoding='utf-8')):
    n = s.count(old)
    if n != (cnt[0] if cnt else 1):
        sys.exit(f"{path}: expected {(cnt[0] if cnt else 1)} match(es), found {n}: {old[:80]!r}")
    s = s.replace(old, new)
if crlf:
    s = s.replace('\n', '\r\n')
open(path, 'wb').write(s.encode('utf-8'))
print('edited', path, 'crlf' if crlf else 'lf')
