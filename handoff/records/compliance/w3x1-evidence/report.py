"""W3-X1: classify the Maven inventory and write MAVEN-INVENTORY.csv plus generated findings fragments."""
import json, os, re, csv, sys, collections, hashlib
W = os.path.dirname(os.path.abspath(__file__))
OUT = sys.argv[1]
POLICY = sys.argv[2]
RUN = sys.argv[3]  # tree run timestamp
inv = json.load(open(os.path.join(W, 'inventory.json'), encoding='utf-8'))
C = inv['components']
P = json.load(open(POLICY, encoding='utf-8'))
M2 = os.path.join(os.path.expanduser('~'), '.m2', 'repository')
HOME_M2 = '~/.m2/repository'

DIST = {'sangam-server': 'server-fatjar', 'sangam-control-plane': 'control-plane-fatjar', 'sangam-gateway': 'gateway-fatjar',
        'sangam-rollup': 'rollup-fatjar', 'sangam-sdk-java': 'sdk-java-jar(deps declared, not bundled)',
        'sangam-cli': 'cli-shaded-jar(built; not a release asset)'}
EXCL_MARIADB = {'sangam-server', 'sangam-control-plane', 'sangam-rollup', 'sangam-spring-boot-starter'}

def spdx_one(name, url):
    n = (name or '').strip().lower(); u = (url or '').strip().lower()
    if not n and not u:
        return None
    table = [
        (r'^lgpl-2\.1-or-later$', 'LGPL-2.1-or-later'), (r'^lgpl-2\.1-only$', 'LGPL-2.1-only'),
        (r'lesser general public license.*2\.1|lgpl[ -]?2\.1', 'LGPL-2.1 (only/or-later not stated)'),
        (r'lgpl[ -]?3|lesser general public license.*3', 'LGPL-3.0 (only/or-later not stated)'),
        (r'lesser general public license|^lgpl$', 'LGPL (version not stated)'),
        (r'affero', 'AGPL-3.0'),
        (r'gpl2 w/ cpe|classpath exception|gpl-2\.0-with-classpath-exception', 'GPL-2.0-only WITH Classpath-exception-2.0'),
        (r'^gpl v2$|general public license.*version 2(?!.*classpath)', 'GPL-2.0 (only/or-later not stated)'),
        (r'oracle free use|futc', 'LicenseRef-Oracle-FUTC'),
        (r'^mit-0$', 'MIT-0'),
        (r'jquery license', 'MIT? (named "jQuery license"; not an SPDX id)'),
        (r'mit x11', 'MIT? ("MIT X11 License"; SPDX has MIT and X11 separately)'),
        (r'\bmit\b', 'MIT'),
        (r'apache public license 2\.0', 'Apache-2.0'),
        (r'^al 2\.0$|apache.*2|apache-2\.0', 'Apache-2.0'),
        (r'eclipse distribution license|^edl 1\.0$', 'BSD-3-Clause (EDL-1.0)'),
        (r'eclipse public license.*2\.0|^epl[ -]2\.0$|eclipse public license v\. 2\.0', 'EPL-2.0'),
        (r'eclipse public license.*1\.0|^epl[ -]1\.0$', 'EPL-1.0'),
        (r'mozilla public license.*2\.0|^mpl 2\.0$', 'MPL-2.0'),
        (r'^mpl 1\.1$', 'MPL-1.1'),
        (r'cddl 1\.1', 'CDDL-1.1'),
        (r'common development and distribution license.*1\.0', 'CDDL-1.0'),
        (r'per creative commons cc0', 'CC0-1.0'),
        (r'^public domain$', 'LicenseRef-Public-Domain'),
        (r'bsd[- ]?2-clause|bsd 2-clause', 'BSD-2-Clause'),
        (r'bsd-3-clause|bsd 3-clause|bsd3|modified bsd', 'BSD-3-Clause'),
        (r'bouncy castle', 'MIT (Bouncy Castle Licence; policy id Bouncy-Castle)'),
        (r'w3c license', 'W3C? (W3C document-licence URL 1999-04-05; SPDX W3C-19980720 vs W3C differ)'),
        (r'universal permissive', 'UPL-1.0'),
        (r'postgresql', 'PostgreSQL'),
    ]
    for rx, s in table:
        if re.search(rx, n):
            return s
    # ambiguous BSD names: URL may name the variant, still flagged
    if re.fullmatch(r'(the )?bsd( licen[cs]e)?( 2\.0)?', n):
        if 'bsd-3-clause' in u:
            return 'BSD-3-Clause? (name "%s" ambiguous; URL says bsd-3-clause)' % name
        return 'BSD-? (name "%s" ambiguous; variant unproven)' % name
    if 'similar to apache' in n:
        return 'UNMAPPED ("%s"; JDOM-style licence, no exact SPDX match verified)' % name
    return 'UNMAPPED ("%s")' % name

PERMISSIVE = {'Apache-2.0', 'MIT', 'MIT-0', 'BSD-2-Clause', 'BSD-3-Clause', 'BSD-3-Clause (EDL-1.0)', 'CC0-1.0',
              'LicenseRef-Public-Domain', 'UPL-1.0', 'PostgreSQL', 'MIT (Bouncy Castle Licence; policy id Bouncy-Castle)'}
WEAK = {'EPL-2.0', 'EPL-1.0', 'MPL-2.0', 'CDDL-1.0', 'CDDL-1.1'}
OBLIG = {
    'Apache-2.0': 'attribution (licence copy); carry NOTICE if present (s.4d); patent grant/termination; no trademark use',
    'MIT': 'attribution (copyright + licence text)', 'MIT-0': 'none',
    'BSD-2-Clause': 'attribution (copyright + licence text in docs)', 'BSD-3-Clause': 'attribution; no endorsement',
    'BSD-3-Clause (EDL-1.0)': 'attribution; no endorsement', 'CC0-1.0': 'none (no patent licence)',
    'LicenseRef-Public-Domain': 'none (jurisdiction-dependent)', 'UPL-1.0': 'attribution; patent grant',
    'PostgreSQL': 'attribution', 'MIT (Bouncy Castle Licence; policy id Bouncy-Castle)': 'attribution',
    'EPL-2.0': 'weak copyleft (module/file); source offer for EPL code distributed in object form; patent; attribution',
    'EPL-1.0': 'weak copyleft; source offer; patent; attribution',
    'MPL-2.0': 'weak copyleft (file-level); state where MPL source is available; patent; attribution',
    'CDDL-1.0': 'weak copyleft (file-level); source offer; patent; attribution',
    'CDDL-1.1': 'weak copyleft (file-level); source offer; patent; attribution',
}

def purl(e):
    return f"pkg:maven/{e['g']}/{e['a']}@{e['v']}"

def use_profile(e):
    scopes, dists, mods, libs = set(), set(), set(), set()
    for u in e['uses']:
        m, s = u['module'], u['scope']
        mods.add(m)
        lab = s + ('(optional)' if u['optional'] else '')
        scopes.add(lab)
        if s in ('compile', 'runtime') and not m.startswith('plugin:') and 'BOM' not in m and m != 'mvnw (build tool)':
            if m in DIST:
                if e['a'] == 'mariadb-java-client' and m in EXCL_MARIADB:
                    dists.add('lib-ext separate jar (excluded from %s fat jar)' % m)
                else:
                    dists.add(DIST[m])
            else:
                libs.add(m)
    shipped_scope = any(s.split('(')[0] in ('compile', 'runtime') for s in scopes)
    if dists:
        cls = 'distribution'
    elif shipped_scope:
        cls = 'runtime'
    elif any(s.startswith('test') for s in scopes) and not any(s.split('(')[0] in ('provided', 'build') for s in scopes):
        cls = 'test'
    elif 'import' in scopes:
        cls = 'bom'
    elif any(s.startswith('provided') for s in scopes) and any(s.startswith('test') for s in scopes):
        cls = 'build+test'
    elif any(s.startswith('provided') for s in scopes):
        cls = 'build(provided)'
    else:
        cls = 'build'
    return cls, sorted(scopes), sorted(dists), sorted(mods), sorted(libs)

def classify(k, e, cls, dists, terms, multi_kind):
    p = purl(e)
    shipped = cls in ('distribution', 'runtime')
    reasons = []
    # forbidden components
    for fc in P['forbidden_components']:
        if p.startswith(fc['purl']):
            btt = [b for b in P['build_time_tools'] if b['coordinate'] == f"{e['g']}:{e['a']}:{e['v']}"]
            if btt and not shipped and cls.startswith('build'):
                return 'ACCEPTED WITH CONDITIONS', f"build-time tool only ({btt[0]['decision']}); exact version {e['v']}; never in a shipped scope, fat jar or image ({fc['rule']})"
            if shipped:
                return 'REJECTED FOR DEFINED USE', f"forbidden component in a shipped scope: {fc['rule']}"
            return 'REVIEW REQUIRED', f"forbidden component ({fc['rule']}) present in {cls} scope; licence-policy forbids only shipping, and no owner record found that approves downloading/using it in tests"
    # SG-27 namespace ban + pinned exceptions
    pins = {x['purl']: x for x in P['pinned_exceptions']}
    if p in pins:
        x = pins[p]
        ok = x['license'] in terms
        if not ok:
            return 'REVIEW REQUIRED', f"pinned exception expects {x['license']} but POM declares {terms}"
        return 'ACCEPTED WITH CONDITIONS', f"SG-27 owner exception ({x['reason']}); exact version only (watch=true); evidence deploy/licence/SG-27-SD-301-exception.md"
    for x in P['pinned_exceptions']:
        if x.get('watch') and x['purl'].rsplit('@', 1)[0] == p.rsplit('@', 1)[0]:
            return ('REJECTED FOR DEFINED USE' if shipped else 'REVIEW REQUIRED'), f"only {x['purl']} is approved (SG-27 watch); this version is not"
    for b in P['namespace_bans']:
        if p.startswith(b['purl']):
            return ('REJECTED FOR DEFINED USE' if shipped else 'REVIEW REQUIRED'), f"banned namespace ({b['rule']}) without exact exception"
    # conditional components
    for c in P['conditional']:
        if any(p.startswith(x) or p == x for x in c['components'] if x.startswith('pkg:maven/')):
            if e['a'] == 'mariadb-java-client':
                return 'ACCEPTED WITH CONDITIONS', c['condition'] + '; also: runtime dependency of the published sangam-server POM (plain jar consumers inherit it unless excluded, as sangam-spring-boot-starter does)'
            return 'ACCEPTED WITH CONDITIONS', c['condition']
    if not terms:
        return 'REVIEW REQUIRED', 'no licence declared in the POM or any parent POM in ~/.m2' + ('' if e['file'] else '; artifact not in the offline cache (UNRESOLVED)')
    unk = [t for t in terms if t.startswith('UNMAPPED') or '?' in t or 'not stated' in t]
    allowed_terms = [t for t in terms if t in PERMISSIVE or t in WEAK]
    copyleft = [t for t in terms if re.match(r'(L?GPL|AGPL)', t)]
    if multi_kind == 'list' and len(terms) > 3:
        if unk:
            return 'REVIEW REQUIRED', f"POM lists {len(terms)} licences without AND/OR semantics (bundled third-party code); unproven terms: {', '.join(unk)}; needs a per-file review of the jar's licence/NOTICE content before an election can be recorded"
        return 'ACCEPTED WITH CONDITIONS', f"POM lists {len(terms)} licences without AND/OR semantics; every term is policy-allowed or a GPL+CPE alternative; record the EPL-2.0 election and carry all listed licence texts"
    if len(terms) == 1:
        t = terms[0]
        if t in PERMISSIVE:
            return 'ACCEPTED FOR DEFINED USE', ''
        if t in WEAK:
            if shipped:
                return 'ACCEPTED WITH CONDITIONS', f"{t} weak copyleft: ship unmodified; keep licence text; state where the source is available"
            return 'ACCEPTED FOR DEFINED USE', f'{t}, not shipped'
        if re.match(r'(L?GPL|AGPL|GPL)', t) or 'FUTC' in t:
            if shipped:
                return 'REJECTED FOR DEFINED USE', f'{t} is outside licence-policy for shipped scopes (no conditional entry for this component)'
            return 'REVIEW REQUIRED', f'{t} in {cls} scope; not one of the owner-listed build_time_tools / LGPL exceptions (decisions 22, 33, 56)'
        return 'REVIEW REQUIRED', f'licence {t} is ambiguous or unmapped; do not infer'
    # dual / multi
    if unk and not allowed_terms:
        return 'REVIEW REQUIRED', f'licences {terms} include ambiguous terms and no clearly allowed option'
    if allowed_terms:
        elect = sorted(allowed_terms, key=lambda t: (t not in PERMISSIVE, t))[0]
        # a copyleft alternative whose only/or-later variant is unstated does not matter once an allowed option is elected
        unk = [t for t in unk if not re.match(r'(L?GPL|AGPL|GPL)', t)]
        if unk:
            return 'REVIEW REQUIRED', f'licences {terms}: AND/OR not stated and {unk} unproven'
        cond = f"multi-licence POM ({' | '.join(terms)}); OR (choice) semantics ASSUMED from the POM, confirm against the project's licence statement; elect {elect}; record the election in the SBOM (SPDX expression) and NOTICE"
        if elect in WEAK and shipped:
            cond += f"; {elect} weak copyleft: unmodified, licence text kept, source location stated"
        return 'ACCEPTED WITH CONDITIONS', cond
    return 'REVIEW REQUIRED', f'licences {terms}: no policy-allowed option'

rows, gen = [], collections.defaultdict(list)
notice_texts = collections.OrderedDict()
dec_count = collections.Counter()
for k, e in sorted(C.items()):
    if e['g'] == 'org.sangam':
        continue
    cls, scopes, dists, mods, libs = use_profile(e)
    lic = e['pom']['licences'] or []
    terms = []
    for n, u in lic:
        s = spdx_one(n, u)
        if s and s not in terms:
            terms.append(s)
    multi_kind = 'list' if len(lic) > 1 else 'single'
    decision, cond = classify(k, e, cls, dists, terms, multi_kind)
    # integrity / text cross-checks
    extra = []
    if e['g'] == 'org.cryptacular':
        extra.append('VERIFY CONDITION WORDING: spring-security-saml2-service-provider/OpenSAML are non-optional compile dependencies of sangam-server, so cryptacular is inside the default server fat jar; "optional" in the policy can only mean feature-optional (disabled by configuration)')
    if not e['file'] and e['t'] != 'pom':
        extra.append('jar NOT in offline cache: sha256 and licence text UNRESOLVED')
        if decision == 'ACCEPTED FOR DEFINED USE' and cls in ('distribution', 'runtime'):
            decision, cond = 'REVIEW REQUIRED', 'integrity unproven: jar absent from the offline cache'
    if e.get('sha1_ok') is False:
        extra.append('SHA-1 MISMATCH vs cached .sha1')
        decision = 'REVIEW REQUIRED'
    jar = e.get('jar') or {}
    tids = jar.get('text_ids', [])
    if tids and terms:
        if any(t in ('GPL', 'LGPL', 'AGPL') for t in tids) and not any(re.search(r'GPL', t) for t in terms) and 'Classpath-exception' not in tids:
            extra.append(f'jar licence text mentions {tids} but POM declares {terms}')
            decision, cond = 'REVIEW REQUIRED', (cond + '; ' if cond else '') + 'jar licence text disagrees with POM'
    if cls in ('distribution', 'runtime') and decision != 'REJECTED FOR DEFINED USE' and e['file'] and not jar.get('lic_files') and e['t'] != 'pom':
        extra.append('no LICENSE/NOTICE file inside the jar: licence text must be supplied from the POM URL')
    notices = jar.get('notices', [])
    if cls == 'distribution':
        for n, txt in notices:
            h = hashlib.sha256(txt.encode()).hexdigest()[:12]
            notice_texts.setdefault(h, dict(text=txt, comps=[]))['comps'].append(f'{k} ({n})')
    if cls in ('distribution', 'runtime'):
        nreq = 'YES (' + ', '.join(n for n, _ in notices) + ')' if notices else ('NO NOTICE file in jar' if e['file'] else 'UNRESOLVED (jar not cached)')
    else:
        nreq = 'n/a (not shipped)' + (' [jar has NOTICE]' if notices else '')
    obl = []
    for t in terms:
        o = OBLIG.get(t)
        if o:
            obl.append(f'{t}: {o}')
        elif re.match(r'(L?GPL|AGPL|GPL)', t):
            obl.append(f'{t}: copyleft (source offer, relink/replace for LGPL)')
        else:
            obl.append(f'{t}: UNKNOWN until licence proven')
    if notices and cls in ('distribution', 'runtime'):
        obl.append('carry NOTICE content in the distribution')
    src = ','.join(sorted(set(e['repos']))) or ('none recorded' if e['file'] else 'not cached')
    srcl = 'Shibboleth' if 'shibboleth' in src else ('Central' if 'central' in src else src)
    if 'central-8fac3ebd' in src:
        srcl += ' (also recorded under mirror id central-8fac3ebd...)'
    pomi = e['pom']
    cands = [('POM project.build.outputTimestamp=' + pomi['outputTimestamp']) if pomi.get('outputTimestamp') else None,
             ('MANIFEST ' + jar['manifest_date']) if jar.get('manifest_date') else None,
             ('MANIFEST.MF zip entry date ' + jar['entry_date']) if jar.get('entry_date') else None]
    cands = [c for c in cands if c and not re.search(r'19[78]\d', c)]
    when = cands[0] if cands else 'no reliable date offline (absent, or normalised to 1980 by a reproducible build)'
    relpath = os.path.join(*e['g'].split('.'), e['a'], e['v'])
    ev = f"{HOME_M2}/{relpath.replace(os.sep, '/')}/{e['file_name']}" + (f"; licence from POM {pomi['licence_from']}" if pomi['licence_from'] else '; no <licenses> in POM chain ' + ' > '.join(pomi['chain'])) + \
         (f"; parent POM missing {pomi['parent_missing']}" if pomi.get('parent_missing') and not pomi['licence_from'] else '') + \
         (f"; jar licence files {jar['lic_files'][:4]} text={tids}" if jar.get('lic_files') else '') + f"; release signal: {when}; dependency:tree {RUN}"
    gav = f"{e['g']}:{e['a']}:{e['v']}" + (f":{e['c']}" if e['c'] else '')
    scope_col = f"use={cls}" + (f" [{'; '.join(dists)}]" if dists else '') + f"; maven={','.join(scopes)}"
    mod_col = ' '.join(mods)
    row = dict(gav=gav, sha256=e['sha256'] or ('UNRESOLVED (not in ~/.m2)' if not e['file'] else ''), source=srcl, scopes=scope_col, modules=mod_col,
               declared_licence=' | '.join(n for n, u in lic) or 'NONE DECLARED', spdx=(' OR '.join(terms) if len(terms) <= 3 else ' ; '.join(terms) + ' (AND/OR unstated)') or 'UNKNOWN',
               notice_required=nreq, obligations='; '.join(obl), decision=decision,
               conditions_or_reason='; '.join(x for x in [cond] + extra if x), evidence=ev)
    if gav == 'org.apache.maven:apache-maven:3.9.9:bin':
        row.update(sha256='4EC3F26FB1A692473AEA0235C300BD20F0F9FE741947C82C1234CEFD76AC3A3C', source='Central (distributionUrl repo.maven.apache.org, via mvnw)',
                   declared_licence='Apache License Version 2.0 (LICENSE file in the unpacked distribution)', spdx='Apache-2.0',
                   obligations=OBLIG['Apache-2.0'], notice_required='n/a (build tool, not shipped)', decision='ACCEPTED WITH CONDITIONS',
                   conditions_or_reason='build tool only; maven-wrapper.properties has no distributionSha256Sum, so the wrapper does not verify the download: add the checksum',
                   evidence='~/.m2/wrapper/dists/apache-maven-3.9.9-bin/33b4b2b4/apache-maven-3.9.9-bin.zip (sha256 computed 2026-11-12) and apache-maven-3.9.9/LICENSE; sangam/.mvn/wrapper/maven-wrapper.properties')
        decision = row['decision']
    if gav == 'org.apache.maven.wrapper:maven-wrapper:3.1.1':
        row.update(sha256='FF7F21F2EF81723377E3D42D06661C4E3AF60CF4BDFB7579AC8F22051399942D', source='committed file sangam/.mvn/wrapper/maven-wrapper.jar (wrapperUrl repo.maven.apache.org)',
                   declared_licence='Apache License 2.0 (META-INF/LICENSE in the jar; Implementation-Vendor The Apache Software Foundation)', spdx='Apache-2.0',
                   obligations=OBLIG['Apache-2.0'], notice_required='n/a (build tool, not shipped)', decision='ACCEPTED WITH CONDITIONS',
                   conditions_or_reason='build tool only; a binary jar is committed in the repository and no wrapperSha256Sum is set: record and verify its checksum',
                   evidence='sangam/.mvn/wrapper/maven-wrapper.jar (META-INF/LICENSE, META-INF/NOTICE, MANIFEST Implementation-Version 3.1.1), read 2026-11-12')
        decision = row['decision']
    rows.append(row)
    dec_count[decision] += 1
    gen['decisions'].append((decision, gav, cls, row['conditions_or_reason'], row['spdx'], dists, libs))

os.makedirs(OUT, exist_ok=True)
cols = ['gav', 'sha256', 'source', 'scopes', 'modules', 'declared_licence', 'spdx', 'notice_required', 'obligations', 'decision', 'conditions_or_reason', 'evidence']
with open(os.path.join(OUT, 'MAVEN-INVENTORY.csv'), 'w', newline='', encoding='utf-8') as f:
    w = csv.DictWriter(f, fieldnames=cols, quoting=csv.QUOTE_ALL)
    w.writeheader()
    for r in rows:
        w.writerow(r)

# version conflicts across modules (resolved)
byga = collections.defaultdict(lambda: collections.defaultdict(set))
for k, e in C.items():
    if e['g'] == 'org.sangam':
        continue
    for u in e['uses']:
        byga[(e['g'], e['a'], e['c'])][e['v']].add((u['module'], u['scope']))
frag = []
frag.append('### Generated: one artifact resolved at more than one version\n')
frag.append('| group:artifact | version -> where (module:scope) |\n|---|---|')
for (g, a, c), vs in sorted(byga.items()):
    if len(vs) > 1:
        cells = []
        for v, uses in sorted(vs.items()):
            ms = sorted({f'{m}:{s}' for m, s in uses})
            cells.append(f"**{v}** ({len(ms)}): " + ', '.join(ms[:6]) + (' ...' if len(ms) > 6 else ''))
        frag.append(f"| {g}:{a}{':' + c if c else ''} | " + '<br>'.join(cells) + ' |')
open(os.path.join(W, 'frag-conflicts.md'), 'w', encoding='utf-8').write('\n'.join(frag) + '\n')

# within-module mediation (verbose): pick shipped scopes only
med = collections.Counter(); medex = {}
for mod, coord, note in inv['conflicts']:
    if 'omitted for conflict' in note:
        parts = coord.split(':')
        ga = parts[0] + ':' + parts[1]
        win = note.split('omitted for conflict with ')[-1].rstrip(')').strip()
        if parts[-1] in ('compile', 'runtime'):
            key2 = (ga, parts[-2], win)
            med[key2] += 1
            medex.setdefault(key2, set()).add(mod.split(':')[1])
fr = ['### Generated: Maven nearest-wins mediation in shipped scopes (requested -> resolved)\n', '| group:artifact | requested | resolved | modules |', '|---|---|---|---|']
for (ga, req, win), n in sorted(med.items()):
    fr.append(f'| {ga} | {req} | {win} | {", ".join(sorted(medex[(ga, req, win)])[:5])}{" ..." if len(medex[(ga, req, win)]) > 5 else ""} |')
open(os.path.join(W, 'frag-mediation.md'), 'w', encoding='utf-8').write('\n'.join(fr) + '\n')

# managed-version overrides (BOM managed vs what the library asked)
mg = collections.Counter(); mgx = {}
for mod, coord, note in inv['conflicts']:
    if note.startswith('managed:'):
        m = re.search(r'([\w.\-]+:[\w.\-]+):[\w\-]+:(?:[\w.\-]+:)?([\w.\-]+):(\w+) - version managed from ([\w.\-]+)', note)
        if m and m.group(3) in ('compile', 'runtime'):
            kk = (m.group(1), m.group(4), m.group(2))
            mg[kk] += 1; mgx.setdefault(kk, set()).add(mod.split(':')[1])
fm = ['### Generated: versions overridden by dependencyManagement / BOMs in shipped scopes (library asked -> managed)\n', '| group:artifact | asked | managed to | modules |', '|---|---|---|---|']
for (ga, asked, got), n in sorted(mg.items()):
    if asked != got:
        fm.append(f'| {ga} | {asked} | {got} | {", ".join(sorted(mgx[(ga, asked, got)])[:4])}{" ..." if len(mgx[(ga, asked, got)]) > 4 else ""} |')
open(os.path.join(W, 'frag-managed.md'), 'w', encoding='utf-8').write('\n'.join(fm) + '\n')

# decisions lists
fd = []
for label in ['REJECTED FOR DEFINED USE', 'REVIEW REQUIRED', 'ACCEPTED WITH CONDITIONS']:
    items = [d for d in gen['decisions'] if d[0] == label]
    fd.append(f'### {label} ({len(items)})\n')
    fd.append('| gav | use | spdx | reason / conditions |\n|---|---|---|---|')
    for d in sorted(items, key=lambda x: (x[2] not in ('distribution', 'runtime'), x[1])):
        fd.append(f'| {d[1]} | {d[2]}{" " + str(d[5]) if d[5] else ""} | {d[4]} | {d[3].replace("|", "/")} |')
    fd.append('')
open(os.path.join(W, 'frag-decisions.md'), 'w', encoding='utf-8').write('\n'.join(fd) + '\n')

fn = ['### Generated: NOTICE files inside jars that ship in a release fat jar (deduplicated by content)\n']
for h, d in notice_texts.items():
    fn.append(f"#### NOTICE {h}: carried for {len(d['comps'])} component(s)\n")
    fn.append('Components: ' + '; '.join(d['comps']) + '\n')
    lines = [l.rstrip() for l in d['text'].splitlines() if l.strip()]
    head = '\n'.join(lines[:6])[:600]
    more = len(lines) > 6 or len('\n'.join(lines[:6])) > 600
    fn.append('Opening lines (attribution header; the release NOTICE must carry the FULL text from the jar entry named above, ' + str(len(lines)) + ' non-empty lines):\n')
    fn.append('```text\n' + head + ('\n[...]' if more else '') + '\n```\n')
open(os.path.join(W, 'frag-notices.md'), 'w', encoding='utf-8').write('\n'.join(fn) + '\n')

cl = collections.Counter(d[2] for d in gen['decisions'])
src = collections.Counter(r['source'] for r in rows)
print('rows', len(rows), dict(dec_count), dict(cl), dict(src), 'notices', len(notice_texts))
