import json, sys
path = sys.argv[1]
mode = sys.argv[2] if len(sys.argv) > 2 else 'text'
start = int(sys.argv[3]) if len(sys.argv) > 3 else 0
end = int(sys.argv[4]) if len(sys.argv) > 4 else 10**9
maxlen = int(sys.argv[5]) if len(sys.argv) > 5 else 3000
rows = []
with open(path, encoding='utf-8') as f:
    for i, line in enumerate(f):
        try:
            o = json.loads(line)
        except Exception:
            continue
        rows.append((i, o))
out = []
for i, o in rows:
    if i < start or i > end:
        continue
    t = o.get('type')
    msg = o.get('message') or {}
    content = msg.get('content')
    ts = o.get('timestamp', '')
    if t == 'assistant' and isinstance(content, list):
        for c in content:
            if c.get('type') == 'text' and mode in ('text', 'all'):
                out.append(f"[{i}] {ts} ASSISTANT TEXT: {c['text'][:maxlen]}")
            elif c.get('type') == 'tool_use' and mode in ('tools', 'all'):
                out.append(f"[{i}] {ts} TOOL_USE {c.get('name')}: {json.dumps(c.get('input'))[:maxlen]}")
    elif t == 'user':
        if isinstance(content, str) and mode in ('text', 'all', 'user'):
            out.append(f"[{i}] {ts} USER: {content[:maxlen]}")
        elif isinstance(content, list):
            for c in content:
                if c.get('type') == 'text' and mode in ('text', 'all', 'user'):
                    out.append(f"[{i}] {ts} USER TEXT: {c['text'][:maxlen]}")
                elif c.get('type') == 'tool_result' and mode in ('all', 'results'):
                    cc = c.get('content')
                    if isinstance(cc, list):
                        cc = ' '.join(x.get('text', '') for x in cc if isinstance(x, dict))
                    out.append(f"[{i}] {ts} RESULT: {str(cc)[:maxlen]}")
sys.stdout.reconfigure(encoding='utf-8')
print('\n'.join(out))
print(f"TOTAL ROWS {len(rows)}")
