import re, sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text(encoding='utf-8-sig')
s = s[s.find('but was: <[')+len('but was: <['):]
parts = re.split(r', (?=(?:GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS) /)', s)
seen=set()
for x in parts:
    op = x.split(' -> ')[0]
    key = re.sub(r' \((guessed id|spoofed.*?)\)', '', op)
    if any(k in op for k in sys.argv[2].split(',')):
        print('=====', op)
        i = x.find('] ', x.find('carries ['))
        print('MARKERS:', x[x.find('carries ['):i+1][:1500])
        print('BODY:', x[i+2:][:700])
