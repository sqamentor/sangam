p='extract_lmmh.py'
s=open(p,encoding='utf-8').read()
def R(a,b):
    global s
    assert a in s, a
    s=s.replace(a,b)
R("""            if not line.strip():
                left_prev = right_prev = None
                continue
            if HDR.search(line):""","""            if not line.strip():
                left_prev = None
                if right_prev is not None and right_prev.get('level') != 'minor':
                    right_prev = None
                continue  # a wrapped minor name may continue after a blank line
            if HDR.search(line):""")
R("if x.start() >= mincol), None)","if x.start() >= mincol - 8), None)")
R(r"""    return s.strip(), ','.join(feet)""", r"""    s = s.strip()
    if s.count("'") % 2 or (s[:1] == "'" and s[-1:] == "'"):
        s = s.strip("'").strip()  # stray quotes left by the correction slips
    return s, ','.join(feet)""")
R("'PUBLIC_DEBT_LOANS' if code[:2] in ('60',) else 'LOANS_EXPENDITURE'","'PUBLIC_DEBT' if code[:2] == '60' else 'LOANS'")
open(p,'w',encoding='utf-8',newline='\n').write(s)
