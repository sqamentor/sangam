import csv, json, os, re, collections
W = os.path.dirname(os.path.abspath(__file__))
C = json.load(open(os.path.join(W, 'inventory.json'), encoding='utf-8'))['components']
rows = list(csv.DictReader(open(r'D:\GITHUB  Project\sangam-handoff\compliance\MAVEN-INVENTORY.csv', encoding='utf-8')))
bad = [k for k, e in C.items() if e.get('sha1_ok') is False]
nosha1 = [k for k, e in C.items() if e['file'] and e.get('sha1_ok') is None and e['g'] != 'org.sangam']
print('SHA1 mismatch', bad)
print('no .sha1 sidecar', len(nosha1), nosha1[:20])
print('shibboleth', [k for k, e in C.items() if any('shib' in r for r in e['repos'])])
old = []
for r in rows:
    if not r['scopes'].startswith(('use=distribution', 'use=runtime')):
        continue
    m = re.search(r'release signal: ([^;]+)', r['evidence'])
    s = m.group(1) if m else ''
    y = re.search(r'(19|20)\d\d', s)
    old.append((y.group(0) if y else '????', r['gav'], s))
for x in sorted(old)[:45]:
    print('OLD', x)
print('unknown release', sum(1 for x in old if x[0] == '????'))
print('dist no licfile', sum(1 for r in rows if 'no LICENSE/NOTICE file inside the jar' in r['conditions_or_reason']))
print('notice yes', sum(1 for r in rows if r['notice_required'].startswith('YES')))
print('server fatjar count', sum(1 for r in rows if 'server-fatjar' in r['scopes']))
for d in ['control-plane-fatjar', 'gateway-fatjar', 'rollup-fatjar', 'cli-shaded', 'sdk-java']:
    print(d, sum(1 for r in rows if d in r['scopes']))
print('runtime-only', [r['gav'] for r in rows if r['scopes'].startswith('use=runtime')])
print('test', collections.Counter(r['decision'] for r in rows if r['scopes'].startswith('use=test')))
print('build', collections.Counter(r['decision'] for r in rows if r['scopes'].startswith('use=build')))
print('dist', collections.Counter(r['decision'] for r in rows if r['scopes'].startswith('use=dist')))
for g in ['bcprov', 'jaxb', 'jackson-databind', 'jcl-over', 'commons-logging', 'okhttp', 'httpclient5', 'gson', 'org.json', 'tomcat-embed-core', 'reactor-netty-http', 'metrics-core', 'guava', 'kotlin-stdlib', 'joda', 'commons-fileupload', 'jsr305', 'mybatis', 'protobuf']:
    print('DUP?', g, [(r['gav'], r['scopes'][:60], r['modules'][:80]) for r in rows if g in r['gav'] and not r['scopes'].startswith('use=build')])
