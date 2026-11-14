import sys, datetime
# usage: w3i_post.py <claims|board> <text>   (text without the timestamp)
kind, text = sys.argv[1], sys.argv[2]
ts = datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
base = 'D:/GITHUB  Project/sangam-handoff/'
if kind == 'claims':
    line = f'{ts} {text}'
    path = base + 'FILE-CLAIMS.md'
else:
    assert '|' not in text.split('||')[0] or True
    parts = text.split('||')  # TYPE||body||to
    assert len(parts) == 3 and all('|' not in p for p in parts)
    line = f'{ts} | W3-I/B50 | {parts[0]} | {parts[1]} | {parts[2]}'
    assert line.count('|') == 4
    path = base + 'WAVE3-SYNC-BOARD.md'
with open(path, 'rb') as f:
    data = f.read()
sep = b'' if data.endswith(b'\n') else b'\n'
with open(path, 'ab') as f:
    f.write(sep + line.encode('utf-8') + b'\n')
print(line[:400])
