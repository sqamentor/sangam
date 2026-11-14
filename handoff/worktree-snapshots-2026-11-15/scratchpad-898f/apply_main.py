import sys, difflib, io
M = r'D:\GITHUB  Project\Custom WorkFlow'
W = r'C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-bulk2'
FILES = """sangam/sangam-ops/src/main/java/org/sangam/ops/vars/ScopedVariables.java
sangam/sangam-ops/src/main/java/org/sangam/ops/OpsSchema.java
sangam/sangam-persistence/tools/generate_changelogs.py
sangam/sangam-persistence/src/main/java/org/sangam/persistence/SangamSchema.java
sangam/sangam-rest/src/main/java/org/sangam/rest/modules/ops/OpsRestConfiguration.java
sangam/sangam-server/src/main/java/org/sangam/server/config/OpsWiring.java
sangam/sangam-rest/api/routes/ops.py
sangam/sangam-ops/pom.xml
sangam/sangam-ops/README.md""".split()
apply = len(sys.argv) > 1 and sys.argv[1] == 'apply'
out = io.open(r'C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\final-normalized.diff', 'w', encoding='utf-8')
for f in FILES:
    raw = open(M + '/' + f, encoding='utf-8', newline='').read()
    nl = '\r\n' if '\r\n' in raw else '\n'
    a = raw.replace('\r\n', '\n').splitlines(True)
    b = open(W + '/' + f, encoding='utf-8', newline='').read().replace('\r\n', '\n').splitlines(True)
    d = list(difflib.unified_diff(a, b, 'main/' + f, 'mine/' + f, n=1))
    out.writelines(d)
    add = sum(1 for l in d if l.startswith('+') and not l.startswith('+++'))
    rem = sum(1 for l in d if l.startswith('-') and not l.startswith('---'))
    print('%4d+ %4d- %s' % (add, rem, f.split('/')[-1]))
    if apply and d:
        open(M + '/' + f, 'w', encoding='utf-8', newline='').write(''.join(b).replace('\n', nl))
out.close()
