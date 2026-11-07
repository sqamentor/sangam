# SPDX-License-Identifier: Apache-2.0
import os, sys, copy
import yaml
sys.path.insert(0, os.path.dirname(__file__))
from central import FlowList, LAYERS, INTL, REGIME, IN, PRESETS, COFOG, U
from goi import GOI, DEPTS
from states import S, L
import delta
import lmmh
import skeletons

# tools/profilegen -> sangam-packs/src/main/resources/profiles, so the generator runs from any checkout location
yaml.SafeDumper.add_representer(
    FlowList, lambda d, v: d.represent_sequence('tag:yaml.org,2002:seq', list(v), flow_style=True))

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'src', 'main', 'resources', 'profiles')
HEADER = ('# SPDX-License-Identifier: Apache-2.0\n'
          '# Sangam configuration profile (data, not code; ADR-D-20). Values marked under "placeholders" are [U]:\n'
          '# unverified in the research pass and must be confirmed from the government\'s own orders before go-live.\n')

for seg in IN['classification']['schemes'][0]['segments']:
    seg.pop('parent', None)

index = []


def deep_set(d, k, v):
    if isinstance(v, dict) and isinstance(d.get(k), dict):
        for kk, vv in v.items():
            deep_set(d[k], kk, vv)
    else:
        d[k] = v




def dump(rel, data):
    path = os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(HEADER)
        yaml.safe_dump(data, f, allow_unicode=True, sort_keys=False, width=120, default_flow_style=False)
    if 'profile' in data:
        index.append(rel.replace('\\', '/'))


def csv(rel, header, rows):
    path = os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write('# SPDX-License-Identifier: Apache-2.0\n')
        f.write(','.join(header) + '\n')
        for r in rows:
            f.write(','.join('"' + str(x).replace('"', '""') + '"' if (',' in str(x) or '"' in str(x)) else str(x) for x in r) + '\n')


dump('layers.yaml', LAYERS)
dump('intl/INTL.yaml', INTL)
dump('regime/REGIME-WESTMINSTER-SUPPLY.yaml', REGIME)
for p in PRESETS:
    dump('in/presets/' + p['profile']['id'] + '.yaml', p)
GOI['placeholders'] = [p for p in GOI['placeholders'] if p['path'] not in delta.GOI_REMOVE]
GOI['placeholders'] += [{'path': p, 'reason': r} for p, r in delta.GOI_ADD_PLACEHOLDERS]
IN['classification']['objectHeadVersioning'] = delta.OBJECT_VERSIONING
IN['crosswalks'].append({'id': 'OBJECT_2026_TO_2027', 'from': 'OBJECT', 'to': 'OBJECT', 'rows': [],
                         'note': 'old -> new object-head map for FY 2027-28 (DoE OM 09-06-2026); rows to load'})
IN['placeholders'].append({'path': 'crosswalks.OBJECT_2026_TO_2027', 'reason': '[U] 61-head list and mapping not yet loaded'})
IN['jurisdiction'] = 'IN'
dump('in/IN.yaml', IN)
dump('us/US.yaml', {
    'profile': {'id': 'US', 'layer': 'jurisdiction', 'version': '2026.11.0', 'effectiveFrom': '2026-01-01', 'extends': ['INTL'],
                'name': {'en': 'United States: health revenue-cycle reference'},
                'source': 'spike/results/US-HEALTH-WORKFLOWS.md US-HLT-089, US-HLT-106'},
    'placeholders': [{'path': 'ledgers', 'reason': U + ' (the claim balance rule used in posting was not read from a public source)'}],
    'jurisdiction': 'US',
    'ledgers': [{'id': 'RECEIVABLES', 'kind': 'RECEIVABLE', 'mode': 'AUTHORITATIVE', 'currency': 'USD', 'stages': ['DEMAND'],
                 'availability': {'type': 'NO_YEAR'}}]})
for k, v in delta.GOI_DELTA.items():
    deep_set(GOI, k, copy.deepcopy(v))
for d in DEPTS:
    if d['profile']['id'] == 'GOI.POSTS':
        for k, v in delta.POSTS_DELTA.items():
            deep_set(d, k, copy.deepcopy(v))
_sec, _ph = skeletons.write(ROOT, 'GOI')
GOI['delegation'] = _sec
dump('in/GOI.yaml', GOI)
for d in DEPTS:
    dump('in/goi/' + d['profile']['id'] + '.yaml', d)


for code, s in S.items():
    doc = {'profile': {'id': code, 'layer': 'government', 'version': '2026.11.0', 'effectiveFrom': '2026-04-01',
                       'extends': ['IN', 'IN-PRESET-' + s['preset']],
                       'name': {'en': s['en'], 'hi': s['hi']},
                       'source': 'IFMS-STATES-REQUIREMENTS.md section 1 and 4 (sources ' + s['src'] + ', accessed 2026-11-04)'}}
    if s['adopt']:
        secs = s['adopt']['sections'] or ['rulebooks']
        doc['profile']['adopt'] = [{'profile': s['adopt']['profile'], 'sections': secs}]
    ph = list(s['ph'])
    if s['lang_u']:
        ph.append(('locale.languages', U + ' (finance-document language policy)'))
    if s['v8'] < 5:
        ph.append(('evidence', 'thin profile: ' + str(s['v8']) + ' of 8 fields verified'))
    doc['placeholders'] = [{'path': p, 'reason': r} for p, r in ph]
    doc['evidence'] = {'fieldsVerifiedOf8': s['v8'], 'sources': s['src']}
    langs = []
    for i, t in enumerate(s['langs']):
        tag, script = L[t]
        e = {'tag': tag, 'script': script}
        if tag in ('ur', 'ks'):
            e['direction'] = 'rtl'
        if i == 0:
            e['default'] = True
        langs.append(e)
    doc['locale'] = {'languages': langs}
    doc['integrations'] = {'systems': s['ifms'], 'snaSparsh': {k: v for k, v in s['ss'].items() if v is not None}}
    doc['orgTopology'] = {'directorate': s['directorate'], 'unitTypes': s['units'], 'ddoCount': s['ddos'],
                          'units': 'seed: load the unit register (code, type, banking, renditionParent, agencyBank, '
                                   'owningGovernment, servedGovernments, physicalState, effectiveFrom)'}
    doc['rulebooks'] = {'citationsOnValidation': True}
    if s['rules']:
        doc['rulebooks']['own'] = [{'id': i, 'title': t} for i, t in s['rules']]
    doc['paymentRails'] = {'default': s['rail']}
    if s['hoa']:
        doc['chartOfAccounts'] = s['hoa']
    else:
        doc['chartOfAccounts'] = {'belowMinor': [{'id': 'subHead', 'width': 2}, {'id': 'detailed', 'width': 2},
                                                 {'id': 'object', 'width': 2}], 'status': 'U'}
        if not any(p == 'chartOfAccounts' for p, _ in ph):
            doc['placeholders'].append({'path': 'chartOfAccounts', 'reason': U + ' (state digit structure not found)'})
    if s['docs']:
        doc['documentTypes'] = [{'id': code + '_' + i.replace('-', '_'), 'formNumber': i, 'family': fam,
                                 'approvalChain': 'TREASURY_STD', 'sanction': 'REFERENCE',
                                 'integrations': {'sourceSystem': src} if src else {}} for i, fam, src in s['docs']]
        doc['approvalChains'] = [{'id': 'TREASURY_STD', 'steps': [{'role': 'DEALING_ASSISTANT'}, {'role': 'ACCOUNTANT'},
                                                                  {'role': 'TREASURY_OFFICER', 'sign': True}],
                                  'returnBy': ['TREASURY_OFFICER'], 'mayEdit': False,
                                  'queue': {'token': True, 'tokenSeries': 'TOKEN', 'fifo': True}}]
    for k, v in s['specials'].items():
        deep_set(doc, k, copy.deepcopy(v))
    extra = delta.apply(code, doc, IN['classification']['schemes'][0]['derive'])
    for k, v in (extra or {}).items():
        v = copy.deepcopy(v)
        if k == 'rulebooks' and 'own' in v and doc.get('rulebooks', {}).get('own'):
            have = {x['id'] for x in doc['rulebooks']['own']}
            doc['rulebooks']['own'] += [x for x in v.pop('own') if x['id'] not in have]
        if k == 'integrations' and 'systems' in v:
            doc['integrations']['systems'] = v.pop('systems')
        deep_set(doc, k, v)
    _sec, _ph = skeletons.write(ROOT, code, delta.PROC[code][1])
    doc['delegation'] = _sec
    doc['placeholders'] += _ph
    dump('in/states/' + code + '.yaml', doc)

# ------------------------------------------------------------------------------------------------ seeds
csv('in/seeds/hoa-major-heads.csv', lmmh.HEADER, lmmh.major_rows())
csv('in/seeds/hoa-sub-major-heads.csv', lmmh.HEADER, lmmh.sub_major_rows())
csv('in/seeds/hoa-minor-heads.csv', lmmh.HEADER, lmmh.minor_rows())
csv('in/seeds/hoa-standard-minor-heads.csv', lmmh.HEADER, lmmh.standard_rows())
OBJ = [('01', 'Salaries', 'OTHER_15CR', 1, 'salaries=true'), ('02', 'Wages', 'OTHER_15CR', 1, ''),
       ('03', 'Overtime Allowance', 'OTHER_15CR', 1, ''), ('04', 'Pensionary Charges', 'OTHER_15CR', 1, ''),
       ('05', 'Rewards', 'NONEST_5CR', 1, ''), ('06', 'Medical Treatment', 'OTHER_15CR', 1, ''),
       ('07', 'Allowances', 'OTHER_15CR', 1, 'allowances=true'), ('08', 'Leave Travel Concession', 'NONEST_5CR', 1, ''),
       ('09', 'Training Expenses', 'NONEST_5CR', 1, ''), ('11', 'Domestic Travel Expenses', 'EST_2CR', 2, ''),
       ('12', 'Foreign Travel Expenses', 'EST_2CR', 2, ''), ('13', 'Office Expenses', 'EST_2CR', 2, ''),
       ('14', 'Rents, Rates and Taxes', 'OTHER_15CR', 2, ''), ('16', 'Publications', 'OTHER_15CR', 2, ''),
       ('20', 'Other Administrative Expenses', 'OTHER_15CR', 2, ''), ('21', 'Supplies and Materials', 'NONEST_5CR', 3, ''),
       ('22', 'Arms and Ammunition', 'OTHER_15CR', 3, ''), ('23', 'Cost of Ration', 'NONEST_5CR', 3, ''),
       ('24', 'Fuel and Lubricants', 'NONEST_5CR', 3, ''), ('25', 'Clothing and Tentage', 'OTHER_15CR', 3, ''),
       ('26', 'Advertising and Publicity', 'OTHER_15CR', 2, ''), ('27', 'Minor Works', 'NONEST_5CR', 3, ''),
       ('28', 'Professional Services', 'NONEST_5CR', 2, ''), ('29', 'Repair and Maintenance', 'NONEST_5CR', 3, ''),
       ('31', 'Grants-in-aid - General', 'OTHER_15CR', 4, ''), ('32', 'Contributions', 'OTHER_15CR', 4, ''),
       ('33', 'Subsidies', 'OTHER_15CR', 4, ''), ('34', 'Scholarships and Stipends', 'OTHER_15CR', 4, ''),
       ('35', 'Grants for Creation of Capital Assets', 'OTHER_15CR', 4, 'igas2CapitalGrant=true'),
       ('36', 'Grants-in-aid - Salaries', 'OTHER_15CR', 4, ''), ('41', 'Secret Service Expenditure', 'OTHER_15CR', 5, 'secretService=true'),
       ('42', 'Lumpsum Provision', 'OTHER_15CR', 5, ''), ('44', 'Loss in Exchange', 'NONEST_5CR', 5, ''),
       ('45', 'Interest', 'OTHER_15CR', 5, ''), ('49', 'Other Charges', 'OTHER_15CR', 5, ''),
       ('50', 'Other Revenue Expenditure', 'EST_2CR', 5, ''), ('51', 'Motor Vehicles', 'OTHER_15CR', 6, 'fixedAsset=true'),
       ('52', 'Machinery and Equipment', 'OTHER_15CR', 6, 'fixedAsset=true'), ('53', 'Major Works', 'OTHER_15CR', 6, 'buildings=true'),
       ('54', 'Investments', 'OTHER_15CR', 7, ''), ('55', 'Loans and Advances', 'OTHER_15CR', 7, ''),
       ('60', 'Other Capital Expenditure', 'OTHER_15CR', 6, ''), ('61', 'Depreciation', 'OTHER_15CR', 8, ''),
       ('63', 'Inter-Account Transfers', 'OTHER_15CR', 8, ''), ('70', 'Deduct Recoveries', 'OTHER_15CR', 8, '')]
csv('in/seeds/object-heads.csv', ['scheme', 'segment', 'parent', 'code', 'name_en', 'flags', 'validFrom', 'validTo', 'source', 'status'],
    [('OBJECT', 'object', '', c, n, ';'.join(x for x in ['reappGroup=' + g, 'objectClass=' + str(k), f] if x), '', '2027-04-01',
      'DFPR 2024 Annexure I (pre-2027 list; names/codes to verify)', 'U') for c, n, g, k, f in OBJ])
csv('intl/seeds/cofog-divisions.csv', ['scheme', 'segment', 'parent', 'code', 'name_en', 'flags', 'validFrom', 'validTo', 'source', 'status'],
    [('COFOG', 'division', '', c, n, '', '', '', 'UN COFOG 1999', 'V') for c, n in COFOG])

with open(os.path.join(ROOT, 'profiles.idx'), 'w', encoding='utf-8', newline='\n') as f:
    f.write('# Sangam configuration profiles (one resource path per line, relative to this index). Generated list.\n')
    for p in sorted(index):
        f.write(p + '\n')
print(len(index), 'profiles')
