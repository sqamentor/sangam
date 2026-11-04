# SPDX-License-Identifier: Apache-2.0
import os, sys, copy
import yaml
sys.path.insert(0, os.path.dirname(__file__))
from central import LAYERS, INTL, REGIME, IN, PRESETS, COFOG, U
from goi import GOI, DEPTS
from states import S, L
import delta
import lmmh
import skeletons

# tools/profilegen -> sangam-packs/src/main/resources/profiles, so the generator runs from any checkout location
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
            f.write(','.join('"' + str(x).replace('"', '""') + '"' if (',' in str(x)) else str(x) for x in r) + '\n')


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
dump('in/IN.yaml', IN)
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
MAJORS = [
    ('0005', 'Central Goods and Services Tax'), ('0006', 'State Goods and Services Tax'), ('0008', 'Integrated Goods and Services Tax'),
    ('0020', 'Corporation Tax'), ('0021', 'Taxes on Income other than Corporation Tax'), ('0029', 'Land Revenue'),
    ('0030', 'Stamps and Registration Fees'), ('0037', 'Customs'), ('0038', 'Union Excise Duties'), ('0039', 'State Excise'),
    ('0040', 'Taxes on Sales, Trade etc.'), ('0041', 'Taxes on Vehicles'), ('0049', 'Interest Receipts'),
    ('0050', 'Dividends and Profits'), ('0070', 'Other Administrative Services'), ('0071', 'Contributions and Recoveries towards Pension and other Retirement Benefits'),
    ('0075', 'Miscellaneous General Services'), ('1275', 'Other Communication Services'), ('1601', 'Grants-in-aid from Central Government'),
    ('2011', 'Parliament / State / Union Territory Legislatures'), ('2012', 'President, Vice-President / Governor / Administrator of UTs'),
    ('2013', 'Council of Ministers'), ('2014', 'Administration of Justice'), ('2015', 'Elections'), ('2020', 'Collection of Taxes on Income and Expenditure'),
    ('2029', 'Land Revenue'), ('2030', 'Stamps and Registration'), ('2039', 'State Excise'), ('2040', 'Taxes on Sales, Trade etc.'),
    ('2047', 'Other Fiscal Services'), ('2048', 'Appropriation for Reduction or Avoidance of Debt'), ('2049', 'Interest Payments'),
    ('2051', 'Public Service Commission'), ('2052', 'Secretariat - General Services'), ('2053', 'District Administration'),
    ('2054', 'Treasury and Accounts Administration'), ('2055', 'Police'), ('2056', 'Jails'), ('2058', 'Stationery and Printing'),
    ('2059', 'Public Works'), ('2062', 'Vigilance'), ('2070', 'Other Administrative Services'), ('2071', 'Pensions and other Retirement Benefits'),
    ('2075', 'Miscellaneous General Services'), ('2076', 'Defence Services - Army'), ('2077', 'Defence Services - Navy'),
    ('2078', 'Defence Services - Air Force'), ('2079', 'Defence Ordnance Factories'), ('2080', 'Defence Services - Research and Development'),
    ('2202', 'General Education'), ('2203', 'Technical Education'), ('2204', 'Sports and Youth Services'), ('2205', 'Art and Culture'),
    ('2210', 'Medical and Public Health'), ('2211', 'Family Welfare'), ('2215', 'Water Supply and Sanitation'), ('2216', 'Housing'),
    ('2217', 'Urban Development'), ('2220', 'Information and Publicity'), ('2225', 'Welfare of SC, ST, OBC and Minorities'),
    ('2230', 'Labour, Employment and Skill Development'), ('2235', 'Social Security and Welfare'), ('2236', 'Nutrition'),
    ('2245', 'Relief on account of Natural Calamities'), ('2246', '[U] opened by CS 1128-1132 (16-10-2026); title to confirm'),
    ('2250', 'Other Social Services'), ('2251', 'Secretariat - Social Services'), ('2401', 'Crop Husbandry'),
    ('2402', 'Soil and Water Conservation'), ('2403', 'Animal Husbandry'), ('2404', 'Dairy Development'), ('2405', 'Fisheries'),
    ('2406', 'Forestry and Wild Life'), ('2408', 'Food, Storage and Warehousing'), ('2415', 'Agricultural Research and Education'),
    ('2425', 'Co-operation'), ('2435', 'Other Agricultural Programmes'), ('2501', 'Special Programmes for Rural Development'),
    ('2505', 'Rural Employment'), ('2506', 'Land Reforms'), ('2515', 'Other Rural Development Programmes'), ('2551', 'Hill Areas'),
    ('2552', 'North Eastern Areas'), ('2575', 'Other Special Areas Programmes'), ('2701', 'Major and Medium Irrigation'),
    ('2702', 'Minor Irrigation'), ('2705', 'Command Area Development'), ('2711', 'Flood Control and Drainage'), ('2801', 'Power'),
    ('2810', 'New and Renewable Energy'), ('2851', 'Village and Small Industries'), ('2852', 'Industries'),
    ('3051', 'Ports and Light Houses'), ('3053', 'Civil Aviation'), ('3054', 'Roads and Bridges'), ('3055', 'Road Transport'),
    ('3451', 'Secretariat - Economic Services'), ('3452', 'Tourism'), ('3454', 'Census, Surveys and Statistics'),
    ('3475', 'Other General Economic Services'), ('3601', 'Transfers to State Governments'), ('3602', 'Transfers to Union Territories'),
    ('3604', 'Compensation and Assignments to Local Bodies and Panchayati Raj Institutions'), ('4000', 'Miscellaneous Capital Receipts'),
    ('4059', 'Capital Outlay on Public Works'), ('4202', 'Capital Outlay on Education, Sports, Art and Culture'),
    ('4210', 'Capital Outlay on Medical and Public Health'), ('4215', 'Capital Outlay on Water Supply and Sanitation'),
    ('4216', 'Capital Outlay on Housing'), ('4217', 'Capital Outlay on Urban Development'),
    ('4225', 'Capital Outlay on Welfare of SC, ST, OBC and Minorities'), ('4245', '[U] opened by CS 1128-1132 (16-10-2026); title to confirm'),
    ('4401', 'Capital Outlay on Crop Husbandry'), ('4552', 'Capital Outlay on North Eastern Areas'),
    ('4701', 'Capital Outlay on Major and Medium Irrigation'), ('4801', 'Capital Outlay on Power Projects'),
    ('5054', 'Capital Outlay on Roads and Bridges'), ('6003', 'Internal Debt of the State Government'),
    ('6004', 'Loans and Advances from the Central Government'), ('6401', 'Loans for Crop Husbandry'), ('6552', 'Loans for North Eastern Areas'),
    ('7601', 'Loans and Advances to State Governments'), ('7610', 'Loans to Government Servants etc.'), ('8000', 'Contingency Fund'),
    ('8009', 'State Provident Funds'), ('8011', 'Insurance and Pension Funds'), ('8121', 'General and Other Reserve Funds'),
    ('8336', 'Civil Deposits'), ('8342', 'Other Deposits'), ('8443', 'Civil Deposits'), ('8448', 'Deposits of Local Funds'),
    ('8449', 'Other Deposits'), ('8454', '[U] Treasury Single Account head (title to confirm against LMMH)'), ('8658', 'Suspense Accounts'),
    ('8670', 'Cheques and Bills'), ('8675', 'Deposits with Reserve Bank'), ('8680', 'Miscellaneous Government Accounts'),
    ('8782', 'Cash Remittances and adjustments between officers rendering accounts to the same Accounts Officer'),
    ('8793', 'Inter-State Suspense Account'), ('8999', 'Cash Balance')]
csv('in/seeds/hoa-major-heads.csv', ['scheme', 'segment', 'parent', 'code', 'name_en', 'flags', 'validFrom', 'validTo', 'source', 'status'],
    [('HOA', 'major', '', c, n, '', '', '', 'LMMH (sample seed)', 'U' if n.startswith('[U]') else 'V') for c, n in MAJORS]
    + [('HOA', 'major', '', c, n, '', '', '', 'LMMH coding pattern (not yet checked against CGA LMMH CS-1132)', 'U')
       for c, n in lmmh.rows({c for c, _ in MAJORS})])
MINORS = [('001', 'Direction and Administration'), ('003', 'Training'), ('004', 'Research'), ('052', 'Machinery and Equipment'),
          ('101', 'Programme minor head (101, varies by major)'), ('191', 'Assistance to Local Bodies, Corporations etc.'),
          ('192', 'Assistance to Municipalities'), ('193', 'Assistance to Nagar Panchayats'), ('196', 'Assistance to Zilla Parishads'),
          ('197', 'Assistance to Block Panchayats'), ('198', 'Assistance to Gram Panchayats'), ('500', 'Receipts awaiting transfer'),
          ('502', 'Expenditure awaiting transfer'), ('503', '[U] new standard minor head (CS 1068 of 28-07-2025)'),
          ('789', 'Special Component Plan for Scheduled Castes'), ('791', 'Loss / gain by exchange'), ('796', 'Tribal Area Sub-Plan'),
          ('797', 'Transfer to / from Reserve Fund / Deposit Account'), ('799', 'Suspense'), ('800', 'Other Expenditure / Other Receipts'),
          ('900', 'Deduct - Refunds'), ('902', 'Deduct - Amount met from funds'), ('911', 'Deduct - Recoveries of Overpayments')]
csv('in/seeds/hoa-standard-minor-heads.csv', ['scheme', 'segment', 'parent', 'code', 'name_en', 'flags', 'validFrom', 'validTo', 'source', 'status'],
    [('HOA', 'minor', '', c, n, 'standard=true', '', '', 'LMMH Standard Minor Heads (CS 1068)', 'U' if n.startswith('[U]') else 'P')
     for c, n in MINORS] + [('HOA', 'subMajor', '', '00', 'No sub-major head', '', '', '', 'LMMH', 'V'),
                            ('HOA', 'subMajor', '', '80', 'General', '', '', '', 'LMMH', 'V')])
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
