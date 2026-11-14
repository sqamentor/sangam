import json, sys

path = sys.argv[1]
n = int(sys.argv[2]) if len(sys.argv) > 2 else 3
texts = []
with open(path, encoding='utf-8') as fh:
    for line in fh:
        try:
            o = json.loads(line)
        except Exception:
            continue
        m = o.get('message') or {}
        role = m.get('role')
        content = m.get('content')
        ts = o.get('timestamp')
        if isinstance(content, str):
            texts.append((ts, role, content))
        elif isinstance(content, list):
            for c in content:
                if c.get('type') == 'text':
                    texts.append((ts, role, c.get('text')))
for ts, role, t in texts[-n:]:
    print('=====', ts, role)
    print(t)
