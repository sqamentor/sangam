# SPDX-License-Identifier: Apache-2.0
import glob, json, os, re

# tools/profilegen -> repository root (sangam/sangam-packs/tools/profilegen is four levels down)
ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..', '..'))
PACKS = os.path.join(ROOT, 'sangam', 'sangam-packs', 'src', 'main', 'resources', 'packs')
OUT = os.path.join(ROOT, 'sangam', 'sangam-packs', 'src', 'main', 'resources', 'profiles', 'README.md')
central = open(os.path.join(ROOT, 'spike', 'results', 'IFMS-CENTRAL-REQUIREMENTS.md'), encoding='utf-8').read()
states = open(os.path.join(ROOT, 'spike', 'results', 'IFMS-STATES-REQUIREMENTS.md'), encoding='utf-8').read()
delta = open(os.path.join(ROOT, 'spike', 'results', 'IFMS-RESEARCH-DELTA.md'), encoding='utf-8').read()

cov = {}
for pj in glob.glob(os.path.join(PACKS, '*', 'pack.json')):
    m = json.load(open(pj, encoding='utf-8'))
    for c in m.get('covers', []):
        cov.setdefault(c, []).append(m['id'])

# ------------------------------------------------------------------ central ids, titles and [U] flags
ifc = {}
for line in central.splitlines():
    m = re.match(r'\| (IFC-\d{3}) \| (.*?) \|', line)
    if m:
        ifc[m.group(1)] = (m.group(2), '[U]' in line or '| X' in line)
delta_v = set(re.findall(r'IFC-(\d{3})', delta))

AREAS = [  # (from, to, capability, data)
    (1, 15, 'config: profile `orgModel` (seat types, office tiers, registry events) + sangam-security seats / postings / delegation', 'in/GOI.yaml#orgModel'),
    (16, 28, 'config: profile `rulebooks` with versions and citations; DMN rows carry ruleRef (GP-19 binding)', 'in/GOI.yaml#rulebooks'),
    (29, 42, 'config: classification registry (segmented scheme, master codes, derivation, combinations, correction slips, crosswalks)', 'in/IN.yaml#classification; in/seeds/*.csv'),
    (43, 55, 'ledger (APPROPRIATION stage) + budget-formulation / supplementary packs', 'in/GOI.yaml#budgetControl'),
    (56, 74, 'ledger: N-stage chain, commitment / encumbrance, hold queue, transfer rules, freeze, period close', 'in/GOI.yaml#budgetControl; regime/REGIME-WESTMINSTER-SUPPLY.yaml#ledgers'),
    (75, 90, 'DMN delegation tables + core sanction / power guard', 'in/GOI.yaml#appraisal, budgetControl'),
    (91, 125, 'config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume', 'in/GOI.yaml#documentTypes, approvalChains'),
    (126, 137, 'config: receipt rules in profile; receipts pack; connectors receipt.portal / treasury.scroll', 'in/GOI.yaml#receipts'),
    (138, 144, 'config: payment-rail catalogue + rail DMN; core execution / reconciliation', 'in/IN.yaml#paymentRails'),
    (145, 165, 'ledger (grant / SNA limits) + GIA-UC and SNA-SPARSH packs', 'in/GOI.yaml#grants; in/IN.yaml#snaSparsh'),
    (166, 185, 'catalogue (pay, advance, GPF, retirement bill types) + pension DMN (CCS 2021) + GPF / advances packs', 'in/GOI.yaml#establishment'),
    (186, 198, 'existing procurement / works packs; thresholds as profile data', 'in/GOI.yaml#procurement'),
    (199, 214, 'monthly-accounts / year-end packs; ledger period lock and accounting-entry mapping', 'in/GOI.yaml#accounts'),
    (215, 223, 'audit-para settlement pack; core audit trail', 'in/GOI.yaml#audit'),
    (224, 250, 'department profiles (department layer) over GOI', 'in/goi/GOI.*.yaml'),
    (251, 258, 'platform core (forms, eSign, idempotency, SoD, tenancy, adapters)', '-'),
]
PLATFORM_C = {'IFC-028', 'IFC-099', 'IFC-110', 'IFC-111', 'IFC-162', 'IFC-191', 'IFC-196', 'IFC-197', 'IFC-218', 'IFC-223',
              'IFC-251', 'IFC-252', 'IFC-253', 'IFC-256', 'IFC-257', 'IFC-258', 'IFC-058'}
DEPT_FILE = {224: 'GOI.RAILWAYS', 225: 'GOI.DEFENCE', 226: 'GOI.DEFENCE', 227: 'GOI.POSTS', 228: 'GOI.TELECOM', 229: 'GOI.MEA',
             230: 'GOI.MEA', 231: 'GOI.MHA', 232: 'GOI.DAE / GOI.SPACE', 234: 'GOI.REVENUE'}
GAPS_IFC = {
    'IFC-001': 'registry seed of the 94 business units not loaded as rows (counts and events in profile)',
    'IFC-189': 'scientific-department list in profile; procurement packs not yet reading it',
    'IFC-238': 'CPWD account-code departments: no specific profile',
    'IFC-244': 'field-heavy CDDO patterns: data only',
}


def area(n):
    for a, b, cap, data in AREAS:
        if a <= n <= b:
            return cap, data
    return '-', '-'


rows_ifc = []
counts = {'C': 0, 'P': 0, 'D': 0, 'G': 0}
for k in sorted(ifc):
    n = int(k[4:])
    cap, data = area(n)
    if n in DEPT_FILE:
        data = 'in/goi/' + DEPT_FILE[n] + '.yaml'
    packs = sorted(set(cov.get(k, [])))
    title, unver = ifc[k]
    if k in GAPS_IFC:
        st = 'G'
    elif packs and not unver:
        st = 'C'
    elif packs or k in PLATFORM_C:
        st = 'P' if unver else 'C'
    else:
        st = 'D'
    if unver and k[4:] not in delta_v and st == 'C':
        st = 'P'
    counts[st] += 1
    note = GAPS_IFC.get(k, '[U] values are placeholders' if unver else '')
    rows_ifc.append(f'| {k} | {cap} | `{data}` | ' + (', '.join(f'`{p}`' for p in packs) or '-') + f' | {st} | {note} |')

# ------------------------------------------------------------------ IFS cross-state
ifs = sorted(set(re.findall(r'\*\*(IFS-0\d\d)\*\*', states)) | {'IFS-029', 'IFS-034', 'IFS-037'})
IFS_MAP = {}
def setm(ids, cap, data):
    for i in ids:
        IFS_MAP['IFS-%03d' % i] = (cap, data)
setm(list(range(1, 11)) + [38, 39], 'config: classification registry, per-state scheme, tags, crosswalks', 'in/IN.yaml; in/states/*.yaml#classification')
setm(list(range(11, 25)) + [35, 51, 52, 29], 'ledger (SNA limits) + SNA-SPARSH pack; profile snaSparsh', 'in/IN.yaml#snaSparsh; in/states/*.yaml#integrations')
setm([25, 26, 53, 54], 'config: rail catalogue + rail DMN (tenant overrides); core execution', 'in/IN.yaml#paymentRails; in/states/*.yaml#paymentRails')
setm([27, 28, 55], 'receipts pack; profile receipts rules', 'in/states/*.yaml#receipts')
setm([56], 'payee golden record via PayeeDirectory port (sangam-registry party registry) + registry.party bill step', 'in/states/*.yaml#payee')
setm([30, 31, 67, 68], 'DMN delegation (tenant overrides) + profile rulebooks / procurement family', 'in/states/*.yaml#rulebooks, procurement')
setm([32, 40, 41, 42, 43], 'ledger N-stage chain, hold queue, ceilings, period lock', 'in/states/*.yaml#budgetControl')
setm([44, 45, 46, 47, 48, 49, 50], 'catalogue + chains + series; bill / PAO-chain / GIA packs', 'in/states/*.yaml#documentTypes, approvalChains')
setm([57, 58, 59], 'ledger public-account sub-ledger types (profile publicAccounts)', 'in/IN.yaml#publicAccounts; in/states/*.yaml')
setm([33, 36, 60, 61, 62, 63, 64, 65, 66], 'profile orgTopology / subStateEntities / regime; n-level scopes', 'in/states/*.yaml#orgTopology, subStateEntities')
setm([69], 'existing works packs + profile works mode', 'in/states/*.yaml#works')
setm([70, 71], 'pension DMN + salary pack + profile pension / payroll', 'in/states/*.yaml#pension, payroll')
setm([72], 'config locale languages (scope, script, direction, effectiveFrom)', 'in/states/*.yaml#locale')
setm([73], 'chain templates (signatures, sequence)', 'in/states/*.yaml#signatures, approvalChains')
setm([74], 'monthly-accounts pack; profile accounts', 'in/states/*.yaml#accounts')
setm([75], 'config admin service (maker-checker, change register); ledger audit link', '-')
setm([76], 'connector bindings / ledger SHADOW mode', 'in/states/*.yaml#integrations')
setm([77], 'profile offlineUnits flags only', 'in/states/*.yaml#operations')
setm([78], 'existing DBT pack; ledger striping', '-')
setm([34, 37], 'classification tags (TSP) / grants to ULBs', 'in/states/*.yaml#tags')
IFS_GAPS = {'IFS-077': 'store-and-forward: field workstream (sangam-field)'}
rows_ifs = []
for k in ifs:
    cap, data = IFS_MAP.get(k, ('profile', 'in/states/*.yaml'))
    packs = sorted(set(cov.get(k, [])))
    st = 'G' if k in IFS_GAPS else ('C' if packs else 'D')
    counts[st] += 1
    rows_ifs.append(f'| {k} | {cap} | `{data}` | ' + (', '.join(f'`{p}`' for p in packs) or '-') + f' | {st} | {IFS_GAPS.get(k, "")} |')

# ------------------------------------------------------------------ state-specific
st_ids = sorted(set(re.findall(r'(IFS-[A-Z]{2}-\d{3})', states + delta)))
rows_st = []
for k in st_ids:
    code = k[4:6]
    packs = sorted(set(cov.get(k, [])))
    st = 'C' if packs else 'D'
    counts[st] += 1
    rows_st.append(f'| {k} | `in/states/{code}.yaml` | ' + (', '.join(f'`{p}`' for p in packs) or '-') + f' | {st} |')

# ------------------------------------------------------------------ GA
GA = {
    'GA-01': ('profile hierarchy (n-level scopes, layers, inheritance, locks, effective dating, admin workflow)', 'C', ''),
    'GA-02': ('classification registry + per-state schemes + seeds', 'P', 'LMMH seed partial; per-state master codes below minor to load'),
    'GA-03': ('ledger N-stage chain + allocation / surrender packs', 'C', ''),
    'GA-04': ('bill pack: sanction reference checked by the catalogue', 'P', 'sanction as linked case: via sanction chain template, no Work ID entity'),
    'GA-05': ('document-type catalogue', 'C', 'state form lists partly [U] (OD 20 types, JK 37)'),
    'GA-06': ('bill intake from external sources (integrations.sourceSystem)', 'P', 'inbound start contracts per source are WS-INTEGRATION'),
    'GA-07': ('PAO / treasury chain pack, token series, no-edit policy', 'C', ''),
    'GA-08': ('GIA-UC pack; AC/DC flags in profile', 'P', 'AC/DC pairing pack not separate (contingent family + profile acDc)'),
    'GA-09': ('SNA-SPARSH pack + ledger', 'C', ''),
    'GA-10': ('rail catalogue + rail DMN + overrides; two-stage failure / reissue', 'C', ''),
    'GA-11': ('receipts pack + profile receipt rules', 'C', ''),
    'GA-12': ('payee golden record: PayeeDirectory port over the sangam-registry party registry; bill step registry.party', 'C', ''),
    'GA-13': ('ledger sub-ledger types (profile publicAccounts)', 'P', 'PD/PLA policies as data; no dedicated PD pack'),
    'GA-14': ('profile orgTopology (typed units, servedGovernments, rendition parent)', 'P', 'unit registers per state to load as seeds'),
    'GA-15': ('profile subStateEntities + n-level scopes', 'P', 'share-formula DMNs per entity to write'),
    'GA-16': ('DMN delegation tables (reapp, appraisal, GIA, rail) + per-government financial-powers table skeleton', 'P', 'State values are [U] skeletons (in/dmn/<GOV>/)'),
    'GA-17': ('profile adopt (donor sections) + citations', 'C', ''),
    'GA-18': ('existing works packs + profile works mode', 'P', 'CCL/DCL / LoC as rail; e-MB attachments data only'),
    'GA-19': ('profile procurement family + per-government procurement-mode DMN skeleton', 'P', 'State thresholds are [U] skeletons'),
    'GA-20': ('pension DMN CCS 2021 + profile payer classes', 'P', 'state pension rules not loaded'),
    'GA-21': ('salary pack + profile payroll binding', 'P', ''),
    'GA-22': ('locale languages per profile', 'P', 'forms still en / hi; language packs for forms not generated'),
    'GA-23': ('chain template signatures / sequence', 'C', ''),
    'GA-24': ('monthly-accounts pack', 'C', ''),
    'GA-25': ('config admin change register; ledger period lock', 'C', ''),
    'GA-26': ('ledger SHADOW mode + connector bindings', 'P', 'SAP / NIC adapters are WS-INTEGRATION'),
    'GA-27': ('offline units (profile flags); store-and-forward belongs to the field workstream', 'G', 'out of this scope'),
    'GA-28': ('DBT pack + ledger striping', 'P', 'throughput not measured (testing phase)'),
    'GA-29': ('presets UT-LEG / UT-NOLEG; union demand; regime history', 'C', ''),
    'GA-30': ('two-level (n-level) tenant scopes', 'C', ''),
}
rows_ga = []
for k, (cap, st, note) in GA.items():
    packs = sorted(set(cov.get(k, [])))
    counts[st] += 1
    rows_ga.append(f'| {k} | {cap} | ' + (', '.join(f'`{p}`' for p in packs) or '-') + f' | {st} | {note} |')

o = []
w = o.append
w('<!-- SPDX-License-Identifier: Apache-2.0 -->')
w('# Government finance profiles (data): GOI, its departments and all 36 States / UTs')
w('')
w('Domain knowledge as data (ADR-D-20). Read by `sangam-config` (`ProfileLoader.loadIndex(cl, "profiles/profiles.idx")`), '
  'resolved per tenant scope by `ProfileResolver`, used by the `gov.ifms.*` packs through the `config.*` and `ledger.*` connectors. '
  'Generated from the research documents (`spike/results/IFMS-CENTRAL-REQUIREMENTS.md`, `IFMS-STATES-REQUIREMENTS.md`, '
  '`IFMS-RESEARCH-DELTA.md`); after generation these files are the source and are edited as data (maker-checker through '
  '`ConfigAdminService`).')
w('')
w('## Layout and layers')
w('')
w('| File | Layer | Content |')
w('|---|---|---|')
w('| `layers.yaml` | - | the ordered layer model: international, regime, jurisdiction, category, government, department, office |')
w('| `intl/INTL.yaml`, `intl/seeds/cofog-divisions.csv` | international | COFOG divisions (crosswalk target) |')
w('| `regime/REGIME-WESTMINSTER-SUPPLY.yaml` | regime | default ledger stage chain and year-end lapse |')
w('| `in/IN.yaml` | jurisdiction | locale (INR, lakh / crore grouping, April fiscal year, IST, calendars with 15:00 cut-off), the 15-digit head-of-account scheme (major / sub-major / minor locked), derivation (0/1 receipts, 2/3 revenue, 4/5 capital, 6/7 loans, 8000 Contingency Fund, 8001-8999 Public Account), crosswalk to COFOG, SNA-SPARSH constants, CSS sharing, payment-rail catalogue, unit types, public-account types |')
w('| `in/presets/IN-PRESET-*.yaml` | category | GENERAL, NE-HIMALAYAN, UT-LEG, UT-NOLEG, UNION |')
w('| `in/GOI.yaml` | government | Union profile: rulebooks (GFR 2017, DFPR 2024, RPR 2022, CAM 2024 ...), org model and registry (AoBR series 386: 94 business units), budget control (DFPR Rule 10 rule set), appraisal ladder, PAO chain, receipts, grants, establishment, accounts calendar, the RPR bill catalogue (~35 types) and chain templates |')
w('| `in/goi/GOI.<DEPT>.yaml` | department | RAILWAYS, DEFENCE, POSTS, TELECOM, MEA (multi-currency), DAE, SPACE, MHA (CAPF), REVENUE |')
w('| `in/states/<CODE>.yaml` | government | the 28 States and 8 UTs: systems, topology, rulebooks, rail, SNA-SPARSH hosting, per-state head-of-account scheme (`HOA_<CODE>`), statutory languages (scope, script, direction, effective date), procurement family, state forms, specials |')
w('| `in/seeds/*.csv` | - | head-of-account major heads (partial sample), standard minor heads, object heads (pre-2027 list, closes 01-04-2027) |')
w('| `profiles.idx` | - | classpath index of the profile documents |')
w('')
w('A department tenant `RJ.PWD` resolves `INTL > REGIME > IN > IN-PRESET-GENERAL > RJ > RJ.PWD` (plus `RJ.PWD/<office>`). '
  '`[U]` values are listed under `placeholders` in each file; `ResolvedProfile.isPlaceholder(path)` and the validator report them.')
w('')
w('## Coverage summary')
w('')
w('Status: **C** = generic capability + data + pack; **D** = configured as data in the profile on a generic capability '
  '(no pack step needed, or an existing pack reads it); **P** = partly (placeholder values or part of the behaviour); **G** = gap.')
w('')
w(f'| Set | Ids | C | D | P | G |')
w('|---|---|---|---|---|---|')
def cnt(rows):
    c = {'C': 0, 'D': 0, 'P': 0, 'G': 0}
    for r in rows:
        s = r.split('|')[-3 if r.count('|') >= 7 else -2].strip()
        if s in c:
            c[s] += 1
    return c
for name, rows in (('IFC (central)', rows_ifc), ('IFS cross-state', rows_ifs), ('IFS state-specific', rows_st), ('GA gaps', rows_ga)):
    c = {'C': 0, 'D': 0, 'P': 0, 'G': 0}
    for r in rows:
        cells = [x.strip() for x in r.strip('|').split('|')]
        for x in cells:
            if x in c:
                c[x] += 1
                break
    w(f'| {name} | {len(rows)} | {c["C"]} | {c["D"]} | {c["P"]} | {c["G"]} |')
w('')
w('## IFC-001 ... IFC-258')
w('')
w('| Id | Generic capability | Data | Packs | St | Note |')
w('|---|---|---|---|---|---|')
o += rows_ifc
w('')
w('## IFS-001 ... IFS-078 (cross-state)')
w('')
w('| Id | Generic capability | Data | Packs | St | Note |')
w('|---|---|---|---|---|---|')
o += rows_ifs
w('')
w('## State- and UT-specific items (IFS-<CODE>-nnn, incl. the pass-2 delta ids)')
w('')
w('| Id | Data | Packs | St |')
w('|---|---|---|---|')
o += rows_st
w('')
w('## GA-01 ... GA-30')
w('')
w('| Id | Capability | Packs | St | Note |')
w('|---|---|---|---|---|')
o += rows_ga
w('')
w('## Still placeholders (IFMS-RESEARCH-DELTA section C)')
w('')
w('Code structures of GJ, WB, TG, OD, ML, JK, LA (Union layout used); budget-document languages (27 entities); TG '
  'procurement rules; works manuals (GJ, HP, CT, ML, MN, MZ, TR, AR, LD); SNA-SPARSH dates (KL, JK); Chandigarh treasury '
  'software; rush-of-expenditure percentages (unset); March Prelim and Sy-II dates; e-bill post-check sampling; ATN months; '
  'GPF days; commutation rule number and full commutation table; CPWD manual after 2022; the 61-head object list from '
  'FY 2027-28 and its old -> new map; the full LMMH list (the seed is a sample).')
open(OUT, 'w', encoding='utf-8', newline='\n').write('\n'.join(o) + '\n')
print('IFC', len(rows_ifc), 'IFS', len(rows_ifs), 'state', len(rows_st), 'GA', len(rows_ga))
