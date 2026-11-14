p='extract_lmmh.py'
s=open(p,encoding='utf-8').read()
def R(a,b):
    global s
    assert a in s, a
    s=s.replace(a,b)
R(r"""            segs = segments(line)
            col0, t0 = segs[0]
            mm = re.match(r'^(\d{4})""", r"""            segs = segments(line)
            if segs[0][0] < mincol:  # a minor code that touches the left column text: open a gap before it
                g = next((x for x in re.finditer(r'(?<![\d.,/-])(\d{3})(?=\s+\S)', line) if x.start() >= mincol), None)
                if g and not re.match(r'^\s*\d{3}\s', line):
                    line = line[:g.start()].rstrip() + ' ' * 3 + ' ' * max(0, mincol - len(line[:g.start()].rstrip()) - 3) + line[g.start():]
                    segs = segments(line)
            col0, t0 = segs[0]
            mm = re.match(r'^(\d{4})""")
R(r"""                    if target is not None and target.get('level') == 'minor':
                        if target.get('ruleOpen') or""", r"""                    isrule = re.search(r'will be an? |^\(?Each\b|^\(?A (separate )?minor head', t, re.I)
                    if isrule and (target is None or target.get('level') == 'minor') and not (target or {}).get('ruleOpen') and not t.startswith('('):
                        holder = sub or major
                        holder['rule'] = join(holder['rule'], t).strip()
                        right_cur = holder
                    elif target is not None and target.get('level') == 'minor':
                        if target.get('ruleOpen') or""")
R(r"""                        holder['rule'] = join(holder['rule'], t).strip()
                        right_cur = holder
            left_prev""", r"""                        holder['rule'] = join(holder['rule'], t).strip()
                        right_cur = holder
                        if not re.search(r'minor|each|separate|name of', holder['rule'], re.I):
                            anomalies.append({'page': pno + 1, 'issue': 'text without code in minor column', 'text': norm(t)})
            left_prev""")
open(p,'w',encoding='utf-8',newline='\n').write(s)
