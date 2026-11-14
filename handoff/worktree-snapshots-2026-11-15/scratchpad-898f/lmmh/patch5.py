s=open('extract_lmmh.py',encoding='utf-8').read()
a=r"""r'^\s*(\S.*?) (\S*[a-z]\d\S*) (\1 .*)$'"""
assert a in s
s=s.replace(a,r"""r'^\s*(\S.*?)\s+(\S*[a-z]\d\S*)\s+(\1\s.*)$'""")
open('extract_lmmh.py','w',encoding='utf-8',newline='\n').write(s)
s=open('README.md',encoding='utf-8').read()
i=s.index('| Range | Major'); j=s.index('## Validation')
s=s[:i]+"""| Range | Major | Sub-major | Minor |
|---|---|---|---|
| 0xxx Receipts | 84 | 105 | 905 |
| 1xxx Receipts | 22 | 32 | 368 |
| 2xxx Revenue expenditure | 96 | 153 | 1,500 |
| 3xxx Revenue expenditure | 40 | 99 | MIN3 |
| 4xxx Capital | 60 | 128 | 685 |
| 5xxx Capital | 24 | 47 | 368 |
| 6xxx Public debt, loans | 58 | 90 | 465 |
| 7xxx Loans, settlement, transfer to Contingency Fund | 22 | 27 | 139 |
| 8000 Contingency Fund | 1 | 0 | 1 |
| 8001-8999 Public Account | 82 | 36 | 606 |

Counts by section and sector are in `data/lmmh-report.json`.

"""+s[j:]
open('README.md','w',encoding='utf-8',newline='\n').write(s)
