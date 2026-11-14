"""W3-X1 Maven inventory builder. Read-only over ~/.m2 and the tree outputs; writes JSON for the report step."""
import os, re, sys, json, hashlib, zipfile, datetime, xml.etree.ElementTree as ET
from collections import defaultdict

W = os.path.dirname(os.path.abspath(__file__))
M2 = os.path.join(os.path.expanduser('~'), '.m2', 'repository')
NS = '{http://maven.apache.org/POM/4.0.0}'
COORD = re.compile(r'^([^:\s()]+):([^:\s()]+):([^:\s()]+):(?:([^:\s()]+):)?([^:\s()]+):([a-z]+)$')

def parse_tree(path, kind):
    """returns list of (module, g, a, type, classifier, version, scope, optional, depth)"""
    out, module = [], None
    for raw in open(path, encoding='utf-8', errors='replace'):
        line = raw.rstrip('\n')
        if not line.strip():
            continue
        m = re.match(r'^([| +\\-]*)(.*)$', line)
        prefix, body = m.group(1), m.group(2)
        if prefix == '':
            module = body.strip()
            continue
        if body.startswith('('):
            continue  # verbose omitted entries handled elsewhere
        opt = body.endswith(' (optional)')
        body = body.replace(' (optional)', '').strip()
        parts = body.split(':')
        if len(parts) == 5:
            g, a, t, v, s = parts; c = ''
        elif len(parts) == 6:
            g, a, t, c, v, s = parts
        else:
            print('UNPARSED', line, file=sys.stderr); continue
        depth = len(prefix) // 3
        out.append((module, g, a, t, c, v, s, opt, depth))
    return out

deps = parse_tree(os.path.join(W, 'tree.txt'), 'main')
plug = parse_tree(os.path.join(W, 'plugin-tree.txt'), 'plugin')

# verbose conflicts
conflicts = []
for raw in open(os.path.join(W, 'tree-verbose.txt'), encoding='utf-8', errors='replace'):
    line = raw.rstrip('\n')
    if not line.strip():
        continue
    if not line.startswith(('|', '+', '\\', ' ')):
        cur = line.strip(); continue
    m = re.search(r'\(([^ ]+) - (.*)\)$', line)
    if m and 'omitted for conflict' in m.group(2):
        conflicts.append((cur, m.group(1), m.group(2)))
    m2 = re.search(r'version managed from ([^;) ]+)', line)
    if m2:
        body = re.sub(r'^[| +\\-]*\(?', '', line).split(' ')[0]
        conflicts.append((cur, body, 'managed: ' + line.strip()))

def m2dir(g, a, v):
    return os.path.join(M2, *g.split('.'), a, v)

def sha(path, algo='sha256'):
    h = hashlib.new(algo)
    with open(path, 'rb') as f:
        for b in iter(lambda: f.read(1 << 20), b''):
            h.update(b)
    return h.hexdigest().upper()

def remote_repos(d, fname):
    p = os.path.join(d, '_remote.repositories')
    ids = []
    if os.path.exists(p):
        for l in open(p, encoding='utf-8', errors='replace'):
            l = l.strip()
            if l.startswith(fname + '>'):
                ids.append(l.split('>', 1)[1].rstrip('='))
    return ids

def text(e, tag):
    x = e.find(NS + tag)
    return x.text.strip() if x is not None and x.text else None

def read_pom(path):
    try:
        root = ET.parse(path).getroot()
    except Exception as ex:
        try:
            data = open(path, 'rb').read().decode('utf-8', 'replace')
            data = re.sub(r'xmlns="[^"]+"', 'xmlns="http://maven.apache.org/POM/4.0.0"', data, count=1)
            root = ET.fromstring(data)
        except Exception:
            return None
    if not root.tag.startswith(NS):
        # pom without namespace
        for el in root.iter():
            if not el.tag.startswith(NS) and isinstance(el.tag, str):
                el.tag = NS + el.tag
    return root

def pom_info(g, a, v, chain_limit=12):
    """licences (first POM in the parent chain that declares them), chain, output timestamp, url, scm"""
    chain, lic, lic_from, ts, url, scm, missing = [], None, None, None, None, None, None
    cg, ca, cv = g, a, v
    for _ in range(chain_limit):
        p = os.path.join(m2dir(cg, ca, cv), f'{ca}-{cv}.pom')
        if not os.path.exists(p):
            missing = f'{cg}:{ca}:{cv}'
            break
        root = read_pom(p)
        if root is None:
            missing = f'{cg}:{ca}:{cv} (unparseable)'
            break
        chain.append(f'{cg}:{ca}:{cv}')
        if lic is None:
            ls = root.find(NS + 'licenses')
            if ls is not None and len(ls.findall(NS + 'license')):
                lic = [(text(l, 'name') or '', text(l, 'url') or '') for l in ls.findall(NS + 'license')]
                lic_from = f'{cg}:{ca}:{cv}'
        props = root.find(NS + 'properties')
        if ts is None and props is not None:
            t = props.find(NS + 'project.build.outputTimestamp')
            if t is not None and t.text and not t.text.startswith('$') and len(chain) == 1:
                ts = t.text.strip()
        if url is None and len(chain) == 1:
            url = text(root, 'url')
        if scm is None:
            s = root.find(NS + 'scm')
            if s is not None:
                scm = text(s, 'url')
        par = root.find(NS + 'parent')
        if par is None:
            break
        cg, ca, cv = text(par, 'groupId'), text(par, 'artifactId'), text(par, 'version')
        if not (cg and ca and cv):
            break
    return dict(chain=chain, licences=lic, licence_from=lic_from, outputTimestamp=ts, url=url, scm=scm, parent_missing=missing)

SPDX_RULES = [
    (r'apache.*2|apache-2\.0|asl 2\.0|apache license, version 2|licenses/license-2\.0', 'Apache-2.0'),
    (r'^mit-0$|mit no attribution', 'MIT-0'),
    (r'\bmit\b|opensource\.org/licenses/mit', 'MIT'),
    (r'eclipse distribution license|edl[- ]v?1\.0|edl-v10|org/documents/edl', 'EDL-1.0'),
    (r'gnu general public license, version 2 with the gnu classpath exception|gpl2 w/ cpe|gpl-2\.0.*classpath|classpath exception', 'GPL-2.0-only WITH Classpath-exception-2.0'),
    (r'eclipse public license.*2\.0|epl[- ]2\.0|epl-v20|legal/epl-2\.0|eclipse public license v\. 2\.0', 'EPL-2.0'),
    (r'eclipse public license.*1\.0|epl[- ]1\.0|epl-v10|legal/epl-v10', 'EPL-1.0'),
    (r'mozilla public license.*2\.0|mpl[- ]2\.0|mozilla\.org/mpl/2\.0', 'MPL-2.0'),
    (r'mozilla public license.*1\.1|mpl[- ]1\.1', 'MPL-1.1'),
    (r'cddl.*1\.1|common development and distribution license.*1\.1', 'CDDL-1.1'),
    (r'cddl|common development and distribution license', 'CDDL-1.0'),
    (r'lesser general public license.*3|lgpl[- ]?v?3|lgpl-3', 'LGPL-3.0'),
    (r'lesser general public license|lgpl', 'LGPL-2.1'),
    (r'affero', 'AGPL-3.0'),
    (r'gnu general public license.*3|gpl[- ]?v?3', 'GPL-3.0'),
    (r'gnu general public license|gpl', 'GPL-2.0'),
    (r'bsd[- ]?2|simplified bsd|freebsd', 'BSD-2-Clause'),
    (r'bsd[- ]?3|new bsd|revised bsd|modified bsd|3-clause', 'BSD-3-Clause'),
    (r'postgresql', 'PostgreSQL'),
    (r'public domain|cc0', 'LicenseRef-Public-Domain'),
    (r'bouncy castle', 'Bouncy-Castle'),
    (r'universal permissive license|upl', 'UPL-1.0'),
    (r'json license|json\.org/license', 'JSON'),
    (r'go license|golang\.org/license', 'BSD-3-Clause?'),
    (r'unicode', 'Unicode-?'),
    (r'oracle free use|futc', 'LicenseRef-Oracle-Free-Use-Terms'),
    (r'scala license|scala-lang\.org/license', 'Apache-2.0?'),
]
AMBIG = {'bsd', 'the bsd license', 'bsd license', 'bsd style', 'bsd-style', 'bsd-style license'}

def to_spdx(name, url):
    n = (name or '').strip().lower(); u = (url or '').strip().lower()
    if n in AMBIG:
        return 'BSD-? (ambiguous: "%s")' % name
    if n == '' and u == '':
        return None
    if n == 'cc0' or 'cc0' in n:
        return 'CC0-1.0'
    for rx, sp in SPDX_RULES:
        if re.search(rx, n):
            return sp
    for rx, sp in SPDX_RULES:
        if u and re.search(rx, u):
            return sp
    return 'UNMAPPED(%s)' % name

TEXT_RULES = [
    (r'Apache License\s*,?\s*Version 2\.0|Apache License\s+Version 2\.0', 'Apache-2.0'),
    (r'Eclipse Public License - v 2\.0|Eclipse Public License v\. 2\.0|ECLIPSE PUBLIC LICENSE - v 2\.0|Eclipse Public License 2\.0', 'EPL-2.0'),
    (r'Eclipse Public License - v 1\.0', 'EPL-1.0'),
    (r'Eclipse Distribution License', 'EDL-1.0'),
    (r'Mozilla Public License,? [Vv]ersion 2\.0', 'MPL-2.0'),
    (r'GNU LESSER GENERAL PUBLIC LICENSE', 'LGPL'),
    (r'GNU AFFERO', 'AGPL'),
    (r'GNU GENERAL PUBLIC LICENSE', 'GPL'),
    (r'CLASSPATH EXCEPTION|Classpath Exception|"CLASSPATH" EXCEPTION', 'Classpath-exception'),
    (r'COMMON DEVELOPMENT AND DISTRIBUTION LICENSE', 'CDDL'),
    (r'Permission is hereby granted, free of charge', 'MIT-text'),
    (r'Redistribution and use in source and binary forms', 'BSD-text'),
    (r'nuclear facility', 'nuclear-disclaimer'),
    (r'Bouncy Castle', 'BouncyCastle-text'),
]

LICFILE = re.compile(r'^(META-INF/)?([^/]*/)?(LICENSE|LICENCE|NOTICE|COPYING|about\.html|THIRD[-_]PARTY|DEPENDENCIES)[^/]*$', re.I)
DATE_KEYS = ['Bnd-LastModified', 'Build-Date', 'Build-Time', 'Built-Date', 'Implementation-Build-Date', 'Build-Timestamp', 'Built-At', 'Created-At', 'Release-Date', 'Implementation-Build', 'Git-Commit-Time']

def jar_info(path):
    info = dict(lic_files=[], text_ids=set(), notices=[], manifest_date=None, entry_date=None, manifest={})
    try:
        z = zipfile.ZipFile(path)
    except Exception as ex:
        info['error'] = str(ex); return info
    with z:
        names = z.namelist()
        for n in names:
            if n.endswith('/'):
                continue
            if LICFILE.match(n) and n.count('/') <= 2 and not n.startswith('BOOT-INF/'):
                try:
                    data = z.read(n)[:200000].decode('utf-8', 'replace')
                except Exception:
                    continue
                info['lic_files'].append(n)
                for rx, tid in TEXT_RULES:
                    if re.search(rx, data):
                        info['text_ids'].add(tid)
                if re.search(r'NOTICE', n, re.I):
                    info['notices'].append((n, data.strip()))
        if 'META-INF/MANIFEST.MF' in names:
            mf = z.read('META-INF/MANIFEST.MF').decode('utf-8', 'replace').replace('\r\n ', '').replace('\n ', '')
            for l in mf.splitlines():
                if ':' in l:
                    k, v = l.split(':', 1)
                    info['manifest'][k.strip()] = v.strip()
            for k in DATE_KEYS:
                if k in info['manifest']:
                    v = info['manifest'][k]
                    if k == 'Bnd-LastModified' and v.isdigit():
                        v = datetime.datetime.fromtimestamp(int(v) / 1000, datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
                    info['manifest_date'] = f'{k}={v}'
                    break
            zi = z.getinfo('META-INF/MANIFEST.MF')
            info['entry_date'] = '%04d-%02d-%02d' % zi.date_time[:3]
    info['text_ids'] = sorted(info['text_ids'])
    return info

# ---------- aggregate ----------
comp = {}
def key(g, a, v, c):
    return f'{g}:{a}:{v}' + (f':{c}' if c else '')

for (mod, g, a, t, c, v, s, opt, depth) in deps:
    k = key(g, a, v, c)
    e = comp.setdefault(k, dict(g=g, a=a, v=v, c=c, t=t, uses=[]))
    modname = mod.split(':')[1]
    e['uses'].append(dict(module=modname, scope=s, optional=opt, direct=(depth == 1)))
for (mod, g, a, t, c, v, s, opt, depth) in plug:
    if mod.startswith('w3x1:plug-root'):
        continue
    k = key(g, a, v, c)
    e = comp.setdefault(k, dict(g=g, a=a, v=v, c=c, t=t, uses=[]))
    pname = mod.split(':')[1][2:]
    e['uses'].append(dict(module='plugin:' + pname, scope='build', optional=opt, direct=(depth == 1)))

BOMS = [('org.operaton.bpm', 'operaton-bom', '2.2.0'), ('com.fasterxml.jackson', 'jackson-bom', '2.22.3'),
        ('org.junit', 'junit-bom', '6.0.3'), ('org.testcontainers', 'testcontainers-bom', '1.21.4'),
        ('org.springframework.boot', 'spring-boot-dependencies', '4.1.2')]
for g, a, v in BOMS:
    k = key(g, a, v, '')
    e = comp.setdefault(k, dict(g=g, a=a, v=v, c='', t='pom', uses=[]))
    e['uses'].append(dict(module='sangam-parent (BOM import)', scope='import', optional=False, direct=True))
# build tooling not from Maven deps
EXTRA_BUILD = [('org.apache.maven', 'apache-maven', '3.9.9', 'zip', 'bin'), ('org.apache.maven.wrapper', 'maven-wrapper', '3.1.1', 'jar', '')]
for g, a, v, t, c in EXTRA_BUILD:
    k = key(g, a, v, c)
    e = comp.setdefault(k, dict(g=g, a=a, v=v, c=c, t=t, uses=[]))
    e['uses'].append(dict(module='mvnw (build tool)', scope='build', optional=False, direct=True))

DIST = {'sangam-server': 'server-fatjar', 'sangam-control-plane': 'control-plane-fatjar', 'sangam-gateway': 'gateway-fatjar',
        'sangam-rollup': 'rollup-fatjar', 'sangam-cli': 'cli-shaded-jar', 'sangam-sdk-java': 'sdk-java-jar'}

for k, e in comp.items():
    g, a, v, c, t = e['g'], e['a'], e['v'], e['c'], e['t']
    d = m2dir(g, a, v)
    ext = 'pom' if t in ('pom',) else ('zip' if t == 'zip' else 'jar')
    fname = f'{a}-{v}' + (f'-{c}' if c else '') + '.' + ext
    fpath = os.path.join(d, fname)
    if g == 'org.apache.maven' and a == 'apache-maven':
        fpath = os.path.join(d, fname)
    e['file'] = fpath if os.path.exists(fpath) else None
    e['file_name'] = fname
    e['sha256'] = sha(fpath) if e['file'] else None
    e['sha1_ok'] = None
    if e['file'] and os.path.exists(fpath + '.sha1'):
        try:
            stored = open(fpath + '.sha1').read().split()[0].strip().upper()
            e['sha1_ok'] = (stored == sha(fpath, 'sha1'))
        except Exception:
            pass
    e['repos'] = remote_repos(d, fname) or remote_repos(d, f'{a}-{v}.pom')
    e['pom'] = pom_info(g, a, v)
    e['jar'] = jar_info(fpath) if e['file'] and ext in ('jar',) else None

json.dump(dict(components=comp, conflicts=conflicts, generated=datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')),
          open(os.path.join(W, 'inventory.json'), 'w', encoding='utf-8'), indent=1, default=list)
print('components', len(comp), 'conflict lines', len(conflicts))
un = defaultdict(list)
for k, e in comp.items():
    for n, u in (e['pom']['licences'] or [('', '')]):
        s = to_spdx(n, u)
        if s is None or s.startswith('UNMAPPED') or '?' in s:
            un[(n, u, s)].append(k)
for (n, u, s), ks in sorted(un.items(), key=lambda x: str(x[0])):
    print('LIC?', repr(n), repr(u), s, len(ks), ks[:4])
