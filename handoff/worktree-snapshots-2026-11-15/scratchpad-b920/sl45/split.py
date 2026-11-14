import re, sys, pathlib
p = pathlib.Path(sys.argv[1])
s = p.read_text(encoding='utf-8-sig')
s = s[s.find('but was: <[')+len('but was: <['):]
parts = re.split(r', (?=(?:GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS) /)', s)
print(len(parts), 'leaks')
for x in parts:
    m = re.match(r'(.*?) -> (\d+) (carries \[.*?\]|\(accepted.*?\))', x, re.S)
    if m:
        print('-', m.group(1), '->', m.group(2), m.group(3)[:400])
    else:
        print('? ', x[:300].replace('\n',' '))
