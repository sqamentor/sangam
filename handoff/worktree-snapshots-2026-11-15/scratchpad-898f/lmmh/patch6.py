s=open('extract_lmmh.py',encoding='utf-8').read()
a=r"""(\1\s.*)$', line)"""
assert a in s
s=s.replace(a,r"""(\1\s.*)$', re.sub(r'\s+', ' ', line))""")
open('extract_lmmh.py','w',encoding='utf-8',newline='\n').write(s)
s=open('README.md',encoding='utf-8').read()
for x,y in (('5,772','5,773'),('| 1,500 |','| 1,501 |')):
    assert x in s; s=s.replace(x,y)
open('README.md','w',encoding='utf-8',newline='\n').write(s)
