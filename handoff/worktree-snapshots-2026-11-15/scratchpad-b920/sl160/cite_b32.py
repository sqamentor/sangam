import os, re, sys
# Static replica of sangam-server DecisionCitationGuardTest (no test run).
sangam = sys.argv[1]
repo = os.path.dirname(sangam)
adr = set(re.findall(r'^\|\s*(D-\d+)\s*\|', open(os.path.join(repo, 'SANGAM.md'), encoding='utf-8').read(), re.M))
fio = set(re.findall(r'^\|\s*((?:D|FC)-\d+)\s*\|', open(os.path.join(repo, 'spike/results/FLOWABLE-INTO-OPERATON.md'), encoding='utf-8').read(), re.M))
topics = {}
for l in open(os.path.join(sangam, 'sangam-server/src/test/resources/citations/decision-topics.txt'), encoding='utf-8-sig').read().splitlines():
    l = l.strip().replace('﻿', '')
    if not l or l.startswith('#'):
        continue
    k, v = l.split('=', 1)
    topics[k] = v.split('|')
CITE = re.compile(r'(?<![A-Za-z0-9-])(ADR-|FLOWABLE-INTO-OPERATON )?(D|FC)-(\d+)\b')
errors = []
checked = 0
for dp, dn, fn in os.walk(sangam):
    for f in fn:
        p = os.path.join(dp, f)
        rp = p.replace(chr(92), '/')
        if not f.endswith('.java') or '/src/main/' not in rp or 'node_modules' in rp:
            continue
        lines = open(p, encoding='utf-8').read().splitlines()
        for i, line in enumerate(lines):
            for m in CITE.finditer(line):
                checked += 1
                where = os.path.relpath(p, sangam).replace(chr(92), '/') + ':' + str(i + 1) + ' ' + m.group(0)
                id_ = m.group(2) + '-' + m.group(3)
                prefix = m.group(1)
                if prefix is None:
                    code = m.start() > 0 and line[m.start() - 1] == '"' and m.end() < len(line) and line[m.end()] == '"'
                    before = line[max(0, m.start() - 7):m.start()]
                    if code and id_ in fio: continue
                    if id_ == 'D-0' and before.lower().endswith('rule '): continue
                    if id_ == 'D-1' and before.endswith('{@code '): continue
                    errors.append(where + ': bare'); continue
                is_adr = prefix.startswith('ADR')
                if (not (id_.startswith('D-') and id_ in adr)) if is_adr else (id_ not in fio):
                    errors.append(where + ': not in register'); continue
                if id_.startswith('FC-'): continue
                key = ('ADR:' if is_adr else 'FIO:') + id_
                kws = topics.get(key)
                ctx = ' '.join(lines[max(0, i - 3):min(len(lines), i + 4)]).lower()
                if kws is None:
                    errors.append(where + ': no topic for ' + key)
                elif not any(k in ctx for k in kws):
                    errors.append(where + ': topic miss ' + key)
print('checked', checked, 'errors', len(errors))
for e in errors:
    if 'sdks/' not in e:
        print(e)
