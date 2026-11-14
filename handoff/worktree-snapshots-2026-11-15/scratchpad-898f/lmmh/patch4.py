s=open('profilegen/main.py',encoding='utf-8').read()
a=s.index("MAJORS = [\n"); b=s.index("OBJ = [(")
s=s[:a]+"""csv('in/seeds/hoa-major-heads.csv', lmmh.HEADER, lmmh.major_rows())
csv('in/seeds/hoa-sub-major-heads.csv', lmmh.HEADER, lmmh.sub_major_rows())
csv('in/seeds/hoa-minor-heads.csv', lmmh.HEADER, lmmh.minor_rows())
csv('in/seeds/hoa-standard-minor-heads.csv', lmmh.HEADER, lmmh.standard_rows())
"""+s[b:]
x="""if (',' in str(x)) else"""
assert x in s; s=s.replace(x,"""if (',' in str(x) or '"' in str(x)) else""")
open('profilegen/main.py','w',encoding='utf-8',newline='\n').write(s)
s=open('profilegen/central.py',encoding='utf-8').read()
x="""        {'path': 'classification.seedStatus', 'reason': 'major heads seeded (verified sample + [U] rows per LMMH pattern); '
                                                       'major-specific minor heads not seeded: load the official CGA LMMH file'},
"""
assert x in s; s=s.replace(x,"")
s=s.replace("'seedStatus': 'PARTIAL',","'seedStatus': 'OFFICIAL_LMMH_CS1127',")
s=s.replace("LMMH (CGA, slips to CS 1132 of 16-10-2026)","LMMH (CGA; seeded from slips to CS 1127 of 09-10-2026)")
x="""              {'scheme': 'HOA', 'file': 'in/seeds/hoa-standard-minor-heads.csv'},"""
assert x in s
s=s.replace(x,"""              {'scheme': 'HOA', 'file': 'in/seeds/hoa-sub-major-heads.csv'},
              {'scheme': 'HOA', 'file': 'in/seeds/hoa-minor-heads.csv'},
              {'scheme': 'HOA', 'file': 'in/seeds/hoa-standard-minor-heads.csv'},""")
open('profilegen/central.py','w',encoding='utf-8',newline='\n').write(s)
s=open('profilegen/coverage.py',encoding='utf-8').read()
s=s.replace("'LMMH seed partial; per-state master codes below minor to load'","'official LMMH seed to CS 1127 (slips 1128-1132 pending); per-state master codes below minor to load'")
s=s.replace("the full LMMH list (the seed is a sample).","the LMMH slips after CS 1127 (the seed is the official list to CS 1127).")
s=s.replace("head-of-account major heads (partial sample), standard minor heads","official LMMH major, sub-major and minor heads (CS 1127), standard minor heads")
open('profilegen/coverage.py','w',encoding='utf-8',newline='\n').write(s)
s=open('lmmh/extract_lmmh.py',encoding='utf-8').read()
def R(a,b):
    global s
    assert a in s,a
    s=s.replace(a,b)
R("""        left_prev = right_prev = None
        for line in lines:""","""        left_prev = None
        for line in lines:""")
R("""    tmpl = {}
""","""    tmpl = {}
    right_prev = None
""")
R("""|^\\(?A (separate )?minor head'""","""|^\\(?A (separate )?minor head|^\\(?Minor heads'""")
R("""r'^\\((Each|A separate)\\b'""","""r'^\\((Each|A separate|Minor heads)\\b'""")
open('lmmh/extract_lmmh.py','w',encoding='utf-8',newline='\n').write(s)
