"""Hunk-selective port helper (B1 PACKS4).

usage:
  hunkport.py list  <diff> <target>
  hunkport.py apply <diff> <target> <i,j,...|money>

list: prints each hunk with status: PRESENT (all + lines already in target as a block), MONEY (touches money
keywords), OTHER, and whether its old block is found uniquely in the target (APPLICABLE).
apply: replaces each selected hunk's old block (context and - lines) with its new block (context and + lines). The
old block must occur exactly once in the target, else the hunk is refused (printed). Line endings are preserved.
"""
import re
import sys

MONEY = re.compile(r'(?i)minor|paise|money|\bM\(|is_money|SD-398|cents|\bmajor\b|convert_|_000L|\b\d+L\b')


def hunks(diff_text):
    out, cur = [], None
    for line in diff_text.splitlines():
        if line.startswith('@@'):
            cur = {'head': line, 'lines': []}
            out.append(cur)
        elif cur is not None and (line[:1] in (' ', '+', '-') or line == ''):
            if line.startswith('+++') or line.startswith('---'):
                continue
            cur['lines'].append(line if line else ' ')
        elif line.startswith('\\'):
            continue
    for h in out:
        h['old'] = [l[1:] for l in h['lines'] if l[0] in (' ', '-')]
        h['new'] = [l[1:] for l in h['lines'] if l[0] in (' ', '+')]
        h['changed'] = [l for l in h['lines'] if l[0] in '+-']
    return out


def find(hay, block):
    if not block:
        return []
    n = len(block)
    return [i for i in range(len(hay) - n + 1) if hay[i:i + n] == block]


def read(path):
    raw = open(path, 'rb').read().decode('utf-8')
    crlf = '\r\n' in raw
    text = raw.replace('\r\n', '\n')
    trail = text.endswith('\n')
    return text.split('\n')[:-1] if trail else text.split('\n'), crlf, trail


def write(path, lines, crlf, trail):
    text = '\n'.join(lines) + ('\n' if trail else '')
    if crlf:
        text = text.replace('\n', '\r\n')
    open(path, 'wb').write(text.encode('utf-8'))


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    mode, diff, target = sys.argv[1], sys.argv[2], sys.argv[3]
    hs = hunks(open(diff, encoding='utf-8-sig').read())
    lines, crlf, trail = read(target)
    if mode == 'list':
        for i, h in enumerate(hs):
            present = bool(find(lines, h['new'])) and not find(lines, h['old'])
            same = h['old'] == h['new']
            kind = 'MONEY' if any(MONEY.search(l) for l in h['changed']) else 'OTHER'
            status = 'PRESENT' if present or same else ('APPLICABLE' if len(find(lines, h['old'])) == 1 else
                                                         'NOT-FOUND(%d)' % len(find(lines, h['old'])))
            print(f'[{i}] {kind:5} {status:14} {h["head"]}')
            for l in h['changed']:
                print('      ' + l[:160])
        return
    sel = sys.argv[4]
    idx = [i for i, h in enumerate(hs) if any(MONEY.search(l) for l in h['changed'])] if sel == 'money' \
        else [int(x) for x in sel.split(',') if x]
    applied, refused = [], []
    for i in idx:
        h = hs[i]
        pos = find(lines, h['old'])
        if len(pos) != 1:
            if find(lines, h['new']):
                refused.append((i, 'already present'))
            else:
                refused.append((i, f'old block found {len(pos)}x'))
            continue
        p = pos[0]
        lines = lines[:p] + h['new'] + lines[p + len(h['old']):]
        applied.append(i)
    write(target, lines, crlf, trail)
    print('applied', applied)
    for i, why in refused:
        print('REFUSED', i, why, hs[i]['head'])


if __name__ == '__main__':
    main()
