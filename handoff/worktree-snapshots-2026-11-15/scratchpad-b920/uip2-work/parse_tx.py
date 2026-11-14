import json, sys, collections

path = sys.argv[1]
mode = sys.argv[2] if len(sys.argv) > 2 else 'files'

edits = collections.OrderedDict()
msgs = []
first_user = None
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
        if role == 'user' and first_user is None:
            if isinstance(content, str):
                first_user = content
            elif isinstance(content, list):
                for c in content:
                    if c.get('type') == 'text':
                        first_user = c.get('text')
                        break
        if isinstance(content, list):
            for c in content:
                if c.get('type') == 'tool_use':
                    name = c.get('name')
                    inp = c.get('input') or {}
                    if name in ('Edit', 'Write', 'MultiEdit', 'NotebookEdit'):
                        fp = inp.get('file_path')
                        edits.setdefault(fp, []).append((ts, name))
                    elif mode == 'cmds' and name in ('Bash', 'PowerShell'):
                        msgs.append((ts, 'CMD', (inp.get('command') or '')[:600]))
                elif c.get('type') == 'text' and role == 'assistant':
                    msgs.append((ts, 'TXT', c.get('text')[:3000]))
                elif mode == 'results' and c.get('type') == 'tool_result':
                    cc = c.get('content')
                    if isinstance(cc, list):
                        cc = ' '.join(x.get('text', '') for x in cc if isinstance(x, dict))
                    msgs.append((ts, 'RES', str(cc)[:800]))

if mode == 'files':
    for fp, l in edits.items():
        print(len(l), l[0][0], l[-1][0], fp)
elif mode == 'first':
    print(first_user)
else:
    for ts, k, t in msgs:
        print('-----', ts, k)
        print(t)
