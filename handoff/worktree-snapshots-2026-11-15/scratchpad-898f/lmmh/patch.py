p='extract_lmmh.py'
s=open(p,encoding='utf-8').read()
def R(a,b):
    global s
    assert a in s, a
    s=s.replace(a,b)
R(r"""            segs = segments(line)
            col0, t0 = segs[0]
            mm = re.match(r'^(\d{4})""", r"""            g = re.match(r'^(\s*\d{4}\s.*\(\d+\))(\d{3})([A-Z].*)$', line)
            if g:  # major title and first minor glued together
                line = g.group(1) + ' ' * max(3, mincol + 2 - len(g.group(1))) + g.group(2) + '   ' + g.group(3)
                anomalies.append({'page': pno + 1, 'issue': 'repaired: minor glued to major title', 'text': norm(line)})
            g = re.match(r'^\s*(\S.*?) (\S*[a-z]\d\S*) (\1 .*)$', line)
            if g:  # code overprinted on a duplicate of the name
                code = ''.join(ch for ch in g.group(2) if ch.isdigit())
                line = ' ' * (mincol + 2) + code + '   ' + g.group(3)
                anomalies.append({'page': pno + 1, 'issue': 'repaired: code overprinted on name', 'text': norm(line)})
            segs = segments(line)
            col0, t0 = segs[0]
            mm = re.match(r'^(\d{4})""")
R(r"""                    target = left_cur or left_prev
                    if target is not None and not (target is major and re.search(r'will be a', t, re.I)):""",
  r"""                    target = left_cur or left_prev
                    each = re.match(r'^Each\b', t)
                    if re.match(r'^(\([a-z]+\)|[A-N]\s*[.-])(\s|$)', t) or t.isupper():
                        continue  # a sector heading between lists
                    if target is None and not each and right_prev is not None and right_prev.get('level') == 'minor':
                        right_prev['raw'] = join(right_prev['raw'], t)  # wrapped minor name printed further left
                        right_cur = right_prev
                    elif target is not None and not each:""")
R(r"""elif re.search(r'sub[\s-]*major', t, re.I) or re.search(r'will be a', t, re.I):""", r"""elif each or re.search(r'sub[\s-]*major', t, re.I) or re.search(r'will be a', t, re.I):""")
R(r"""                    if target is not None and target.get('level') == 'minor':
                        target['raw'] = join(target['raw'], t)
                        right_cur = target""", r"""                    if target is not None and target.get('level') == 'minor':
                        if target.get('ruleOpen') or re.match(r'^\((Each|A separate)\b', t):
                            target['rule'] = join(target['rule'], t).strip()
                            target['ruleOpen'] = not t.endswith(')')
                        else:
                            target['raw'] = join(target['raw'], t)
                        right_cur = target""")
R("""        h['rule'] = norm(h['rule'])""","""        h['rule'] = norm(h['rule'])
        if h['level'] == 'template':
            h['name'] = h['template']""")
R("""subs = {(r['major'], r['code']) for r in rows if r['level'] == 'subMajor'}""","""subs = {(r['major'], r['code']) for r in rows if r['level'] in ('subMajor', 'template')}""")
R("""            m = re.match(r'^\s{0,8}(\d{3}(?:/\d{3})?)\s{2,}""","""            if re.search(r'Illustrative List|\(Contd|^\s*(Standard|3-digit|Code)\b|Directions\)\s*$', line):
                cur = None
                continue
            line = re.sub(r'^\s*onwards\s+', ' ' * 14, line)
            m = re.match(r'^\s{0,8}(\d{3}(?:/\d{3})?)\s{2,}""")
open(p,'w',encoding='utf-8',newline='\n').write(s)
