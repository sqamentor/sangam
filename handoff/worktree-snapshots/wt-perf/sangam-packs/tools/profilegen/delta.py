# SPDX-License-Identifier: Apache-2.0
# IFMS-RESEARCH-DELTA.md (pass 2, 2026-11-05) and IFMS-STATES section 9 applied to the generated profiles.
from central import U

D2 = lambda i: (i, 2)
UNION = [('major', 4), ('subMajor', 2), ('minor', 3), ('subHead', 2), ('detailed', 2), ('object', 2)]

# code -> (segments [(id, width, opts)], example, status, source)
# opts: 'opt' optional, 'alpha' letters allowed, 'flag' a V/C or P/NP suffix, 'prefix' prefix segment
SEG = {
    'RJ': ([('demand', 2), ('major', 4), ('subMajor', 2), ('minor', 3), ('subHead', 2), ('groupHead', 2, 'opt'), ('object', 2)],
           '4202 01 202 (01) [90] 17', 'V', 'RJ Budget Manual Vol-I s.3.11'),
    'MH': ([('demandLetters', 2, 'alpha'), ('major', 4), ('subMajor', 2), ('minor', 3), ('subHead', 2), ('detailed', 2), ('object', 2)],
           '2011 02 101 (00)(01), BEAMS 20110011', 'V', 'MLS budget 2026'),
    'GA': ([('major', 4), ('subMajor', 2), ('minor', 3), ('subHead', 2), ('detailed', 2, 'opt'), ('object', 2, 'opt')],
           '2225-03-277-09', 'P', 'CAG Report 2 of 2018; lower widths [U]'),
    'MP': ([('demand', 2), ('major', 4), ('subMajor', 2), ('minor', 3), ('scheme', 4), ('object', 2), ('detailedObject', 3)],
           '22-008 (object-detailed object)', 'P', 'by analogy with CT'),
    'CT': ([('demand', 2), ('major', 4), ('subMajor', 2), ('minor', 3), ('scheme', 4), ('tag', 4), ('object', 2), ('detailedObject', 3)],
           '2012 [03] {090} (4330) 0112 #01 001', 'P', 'CG budget book 2026-27'),
    'UP': (UNION, '2058-103-03', 'P', 'CAG UP Appropriation Accounts 2024-25'),
    'UK': (UNION, '2052-00-090-19-00 / 42', 'V', 'UK budget 2025'),
    'BR': ([('demand', 2), ('major', 4), ('subMajor', 2), ('minor', 3), ('subHead', 4), ('detailed', 2), ('subDetailed', 2),
            ('votedCharged', 1, 'flag')], '12-2054-00-097-0001-01-01 V', 'V', 'BICA budget material'),
    'JH': ([('demand', 2), ('letter', 1, 'alpha'), ('major', 4), ('subMajor', 2), ('minor', 3), ('subHead', 2), ('detailed', 2),
            ('objectGroup', 2), ('object', 2)], '12-S-2052-00-090-08-00-01-01', 'V', 'JH FD budget 2026-27'),
    'PB': (UNION, '03.99.53 (SH.DH.OH; DH 99 = none)', 'V', 'Punjab Demands Vol-IV 2026-27'),
    'HR': ([('part', 1, 'alpha'), ('demand', 2), ('subDemand', 2), ('major', 4), ('subMajor', 2), ('minor', 3), ('subHead', 2),
            ('detailed', 2), ('object', 2), ('votedCharged', 1, 'flag')], '[P-01-01-2011-02-101-99-51] 01', 'V', 'HR budget 2024'),
    'HP': ([('major', 4), ('subMajor', 2), ('minor', 3), ('subHead', 2), ('scheme', 4, 'alpha'), ('soe', 2)],
           '2202-02-796-01-S00N / SOE 01', 'P', 'HP TADP 2022-23'),
    'KA': ([('major', 4), ('subMajor', 2), ('minor', 3), ('subHead', 1), ('detailed', 2), ('object', 3)],
           '2055-00-113-0-03-240', 'V', 'CAG KA Appropriation Accounts 2013-14 (IFS-KA-003 corrected)'),
    'TN': ([('major', 4), ('subMajor', 2), ('minor', 3), ('subHead', 2, 'alpha'), ('group', 1), ('detailed', 2), ('subDetailed', 2)],
           '2011 02 101 AA 30101 (group 3 rev / 4 cap / 8 PA; SDH 51-99 charged)', 'V', 'TN FD d01 2020'),
    'KL': ([('major', 4), ('subMajor', 2), ('minor', 3), ('subHead', 2), ('subSubHead', 2), ('detailed', 2), ('object', 2),
            ('planFlag', 1, 'flag'), ('votedCharged', 1, 'flag')], '2056-00-001-99-00-02-05', 'V', 'KL Appropriation Accounts 2020-21'),
    'AP': ([('major', 4), ('subMajor', 2), ('minor', 3), ('groupSubHead', 2), ('subHead', 2), ('detailed', 3), ('subDetailed', 3)],
           'to sub-head only', 'P', 'CAG AP Appropriation Accounts 2023-24'),
    'AS': ([('major', 4), ('subMajor', 2), ('minor', 3), ('subHead', 4), ('detailed', 3), ('object', 2), ('subDetailed', 2)],
           '8443-00-108-1994-000-00-00', 'P', 'AS finance (search excerpt)'),
    'SK': (UNION, '2401 00 001 01 44 / 01 (DH often = district)', 'V', 'CAG SK Appropriation Accounts 2023-24'),
    'AR': ([('major', 4), ('subMajor', 2), ('minor', 3), ('subHead', 2), ('object', 3)], '4217-01-800-03-051', 'P', 'CAG AR State Finances 2023-24'),
    'MN': (UNION, '2225 02 001 01 00 01', 'V', 'MN DMD-14 2026-27'),
    'MZ': ([('major', 4), ('subMajor', 2), ('minor', 3), ('subHead', 2, 'opt'), ('detailed', 2), ('object', 2, 'opt')],
           '2011-02-101 (01) 01 (01)', 'V', 'MZ demands 2026-27'),
    'NL': ([('major', 4), ('subMajor', 2), ('minor', 3), ('subHead', 2), ('subSubHead', 2), ('detailed', 2)],
           '2011 02 101 01 / 01 (last tier = object)', 'V', 'NL finance'),
    'TR': (UNION, '2406 01 001 98 32 02', 'V', 'TR expenditure budget Vol 2 Part II'),
    'DL': (UNION, '2052 00 090 60 00 01', 'V', 'Delhi finance d-11'),
    'PY': (UNION, 'object 28 (BEAMS); full code [U]', 'P', 'PY FD compendium 2022'),
    'CH': (UNION, 'Union layout', 'P', 'LMMH para 8'), 'DH': (UNION, 'Union layout', 'P', 'LMMH para 8'),
    'LD': (UNION, 'Union layout', 'P', 'LMMH para 8'), 'AN': (UNION, 'Union layout', 'P', 'LMMH para 8'),
}
SEG_UNKNOWN = ['GJ', 'TG', 'WB', 'OD', 'ML', 'JK', 'LA']

# statutory official languages (IFMS-STATES 9.1): (tag, script, scope, effectiveFrom, status)
LANG = {
    'RJ': [('hi', 'Deva'), ('en', 'Latn')], 'GJ': [('gu', 'Gujr')], 'MH': [('mr', 'Deva')],
    'GA': [('kok', 'Deva'), ('mr', 'Deva', 'permitted'), ('en', 'Latn', 'continues')], 'MP': [('hi', 'Deva')],
    'CT': [('hi', 'Deva'), ('hne', 'Deva', '[U] 2007 amendment')], 'UP': [('hi', 'Deva'), ('ur', 'Arab', 'additional (1989)')],
    'UK': [('hi', 'Deva'), ('sa', 'Deva', '[U] second language')], 'BR': [('hi', 'Deva'), ('ur', 'Arab', 'additional')],
    'JH': [('hi', 'Deva')], 'PB': [('pa', 'Guru')], 'HR': [('hi', 'Deva'), ('en', 'Latn', 'additional'), ('pa', 'Guru', '[U] second')],
    'HP': [('hi', 'Deva'), ('sa', 'Deva', '[U] 2019')], 'KA': [('kn', 'Knda')], 'TN': [('ta', 'Taml'), ('en', 'Latn', 'additional')],
    'KL': [('ml', 'Mlym'), ('en', 'Latn')], 'AP': [('te', 'Telu'), ('ur', 'Arab', 'additional')],
    'TG': [('te', 'Telu'), ('ur', 'Arab')], 'WB': [('bn', 'Beng'), ('ne', 'Deva', 'districts: Darjeeling, Kurseong')],
    'OD': [('or', 'Orya')], 'AS': [('as', 'Beng'), ('bn', 'Beng', 'districts: Barak Valley'), ('brx', 'Deva', '[U] 2020')],
    'SK': [('en', 'Latn'), ('ne', 'Deva'), ('sip', 'Tibt', 'Bhutia'), ('lep', 'Lepc', 'Lepcha')], 'AR': [('en', 'Latn')],
    'MN': [('mni', 'Mtei'), ('en', 'Latn')], 'ML': [('en', 'Latn'), ('kha', 'Latn', '2026-04-27 ordinance; commencement [U]'),
                                                   ('grt', 'Latn', '2026-04-27 ordinance; commencement [U]')],
    'MZ': [('lus', 'Latn'), ('en', 'Latn'), ('hi', 'Deva')], 'NL': [('en', 'Latn')],
    'TR': [('bn', 'Beng'), ('en', 'Latn'), ('trp', 'Latn')], 'DL': [('hi', 'Deva'), ('en', 'Latn'), ('pa', 'Guru', 'second (OL Act 2000)'),
                                                                   ('ur', 'Arab', 'second (OL Act 2000)')],
    'PY': [('ta', 'Taml'), ('te', 'Telu'), ('ml', 'Mlym'), ('en', 'Latn')],
    'JK': [('ks', 'Arab'), ('doi', 'Deva'), ('ur', 'Arab'), ('hi', 'Deva'), ('en', 'Latn')],
    'LA': [('en', 'Latn'), ('hi', 'Deva'), ('ur', 'Arab'), ('bo', 'Tibt', 'Bhoti; Regulation 2025, commencement [U]'),
           ('prx', 'Arab', 'Purgi; Regulation 2025, commencement [U]')],
    'CH': [('en', 'Latn')], 'DH': [('gu', 'Gujr'), ('hi', 'Deva'), ('en', 'Latn')], 'LD': [('en', 'Latn')],
    'AN': [('hi', 'Deva'), ('en', 'Latn')],
}
LANG_FROM = {'JK': '2020-09-29', 'LA': '2025-01-01', 'ML': '2026-04-27'}
VERIFIED_LANG = {'GA', 'UP', 'KL', 'SK', 'TR', 'DL', 'PY', 'JK', 'LA'}

# procurement family (a) Act, (b) financial-rules chapter / store purchase, (c) central GFR + CPWD
PROC = {
    'RJ': ('ACT', 'RTPP Act 2012 / Rules 2013', 'PWF&AR Parts I-III [U edition]'),
    'GJ': ('RULES', 'Gujarat Purchase Policy 2024 [U]', '[U]'), 'MH': ('RULES', 'Manual of Office Procedures for Procurement 2016', 'MH PW Manual'),
    'GA': ('CENTRAL', 'GFR 2017', 'CPWD Works Manual (adopted)'), 'MP': ('RULES', 'MP Bhandar Kray tatha Seva Upaarjan Niyam 2015', 'MP Works Dept Manual'),
    'CT': ('RULES', 'CG Bhandar Kray Niyam 2002 (to 10-12-2025)', '[U]'), 'UP': ('RULES', 'UP Procurement Manual (Goods) 2016; GO 26-11-2024', 'FHB Vol VI'),
    'UK': ('RULES', 'UK Procurement Rules 2017 (amended 2021)', 'UK PWD GOs'), 'BR': ('RULES', 'BFR procurement chapter (r.124-131ZZB)', 'Bihar PWD Code'),
    'JH': ('RULES', 'Jharkhand Financial Rules 2025 Ch.7 (r.165 GeM)', 'JFR 2025 Ch.10; PW Account Code 1935'),
    'PB': ('ACT', 'Punjab Transparency in Public Procurement Act 2019 + Rules 2022', 'Punjab PWD Code'),
    'HR': ('RULES', 'HPPC store purchase rules', 'Haryana PWD Code 2009'), 'HP': ('RULES', 'HPFR 2009 procurement chapter', '[U]'),
    'KA': ('ACT', 'KTPP Act 1999 / Rules 2000 (2024 amendments)', 'Karnataka PWD Code 2014'),
    'TN': ('ACT', 'TNTT Act 1998 / Rules 2000 (2024 amendments)', 'TN PWD Code'),
    'KL': ('RULES', 'Stores Purchase Manual 2013 (to 2021)', 'Kerala PWD Manual 2012'), 'AP': ('RULES', 'GO Ms 129 of 24-09-2019', 'AP PWD Code'),
    'TG': ('RULES', '[U] AP rules presumed', '[U]'), 'WB': ('RULES', 'WB Financial Rules Vol I', 'WB PWD Code + WBWD Manual'),
    'OD': ('RULES', 'OGFR 2023 (amended 04-06-2025, 16-03-2026)', 'OPWD Code'), 'AS': ('ACT', 'Assam Public Procurement Act 2017 + Rules 2020', 'Assam PWD Manual'),
    'SK': ('RULES', 'SFR 1979; DFP Rules 1977 (2020)', 'Sikkim Public Works Code'), 'AR': ('CENTRAL', 'GFR 2017 r.149 (adopted 11-10-2017)', '[U]'),
    'MN': ('RULES', 'Manipur Public Procurement Policy 2022', '[U]'), 'ML': ('RULES', 'Procurement Rules 2018', '[U]'),
    'MZ': ('ACT', 'Mizoram Transparency in Public Procurement Act 2008 (2019)', '[U]'), 'NL': ('CENTRAL', 'e-Procurement Policy; GFR', 'CPWD Account Code'),
    'TR': ('RULES', 'Manual for Procurement of Goods 2018', '[U]'), 'DL': ('CENTRAL', 'GFR 2017', 'CPWD Works Manual'),
    'PY': ('CENTRAL', 'GFR 2017 + CPWD (FD 24-10-2017)', 'CPWD Works Manual'), 'JK': ('CENTRAL', 'GFR 2017 (LG order 2020)', 'J&K PWD + Works Manual 2019'),
    'LA': ('CENTRAL', 'GFR 2017', 'Works Manual 2019'), 'CH': ('CENTRAL', 'GFR 2017 (assumed)', '[U]'),
    'DH': ('CENTRAL', 'GFR 2017 (assumed)', 'CPWD DSR [U]'), 'LD': ('CENTRAL', 'GFR 2017 (assumed)', '[U]'), 'AN': ('CENTRAL', 'GFR 2017', 'CPWD Manual'),
}

FIELD = {  # other delta field values
    'GA': {'integrations': {'systems': ['Accounts-Online', 'IBPS', 'e-Pagaar20', 'E-DDO', 'e-Challan (SBI MOPS)', 'Treasury Accounting', 'Works Audit']},
           'orgTopology': {'unitTypes': {'DISTRICT_TREASURY': 2, 'SUB_TREASURY': 10}, 'ddoCount': 550,
                           'treasuries': ['PORVORIM', 'MARGAO'], 'subTreasuries': ['PANAJI', 'MAPUSA', 'BICHOLIM', 'PERNEM', 'VALPOI', 'VASCO',
                                                                                   'PONDA', 'SANGUEM', 'QUEPEM', 'CANACONA']},
           'rulebooks': {'own': [{'id': 'GA_DFPR_2008', 'title': 'Goa DFPR 2008 (amended 2023)'}, {'id': 'GA_BUDGET_MANUAL', 'title': 'Budget Manual'},
                                 {'id': 'GFRBM_2006', 'title': 'Goa FRBM Act 2006 / Rules 2007'}]},
           'paymentRails': {'default': 'CENTRAL_BANK_SETTLEMENT', 'agencyBank': 'SBI e-payment + link banks'}},
    'AR': {'rulebooks': {'noStateBudgetManual': True}, 'paymentRails': {'default': 'CENTRAL_BANK_SETTLEMENT'}},
    'MZ': {'integrations': {'systems': ['COMPACT', 'eBillDDO', 'Mizo e-Lekha', 'MIWAS', 'e-Sanction', 'eGPF', 'GST-GRAS']},
           'paymentRails': {'default': 'CENTRAL_BANK_SETTLEMENT'}, 'accounts': {'exports': ['VLC']}},
    'DH': {'integrations': {'systems': ['e-Lekha']}, 'orgTopology': {'unitTypes': {'PAO': 2, 'DISTRICT_TREASURY': 2},
                                                                    'note': 'two PAO units still separate (Silvassa, Daman); treasuries Daman, Diu'},
           'rulebooks': {'own': [{'id': 'DFPR2024', 'title': 'DFPR'}, {'id': 'GFR2017', 'title': 'GFR'}, {'id': 'CTR', 'title': 'CTR'},
                                 {'id': 'FR', 'title': 'FR'}, {'id': 'DH_PANCHAYAT_ACCOUNTS_2024_DRAFT', 'title': 'draft Panchayat Accounts Rules 2024'}]},
           'budget': {'be2026_27Gross': '2835.20CR'}},
    'MH': {'integrations': {'snaSparsh': {'onboarded': 'BY_2025-11-01'}}, 'beamsCode': {'digits': 8, 'rule': 'MH + serial'}},
    'CT': {'orgTopology': {'unitTypes': {'DISTRICT_TREASURY': 34}}},
    'JH': {'orgTopology': {'directorate': None, 'tiers': ['FINANCE_DEPARTMENT', 'TREASURY']},
           'rulebooks': {'own': [{'id': 'JFR_2025', 'title': 'Jharkhand Financial Rules 2025 (notification [U])'}]}},
    'TN': {'rulebooks': {'own': [{'id': 'TN_TREASURY_CODE_I_7', 'title': 'TN Treasury Code Vol I 7th edn'}]}},
    'AP': {'orgTopology': {'unitTypes': {'DISTRICT_TREASURY': 26, 'HQ_TREASURY': 1, 'SUB_TREASURY': 196}},
           'budgetControl': {'cbro': {'quartersReleasedAtOnce': 4, 'chain': ['HOD_CBDO', 'DDO'], 'status': 'P'}}},
    'TG': {'integrations': {'snaSparsh': {'onboarded': '2024-05-22', 'orders': ['G.O.Ms.76', 'G.O.Ms.77']}}},
    'WB': {'orgTopology': {'unitTypes': {'CYBER_TREASURY_RECEIPTS': 2}, 'cyberTreasuryII': {'code': 'CAH', 'from': '2025-09-11'}},
           'procurement': {'gemMandatoryFrom': '2026-09-01'}},
    'OD': {'rulebooks': {'own': [{'id': 'OGFR_2023', 'title': 'OGFR 2023 (amended 04-06-2025, 16-03-2026 No. 6904)'}]}},
    'SK': {'rulebooks': {'continuity': 'Art. 371F(k)'}},
    'TR': {'subStateEntities': [{'id': 'TTAADC', 'fundRules': 'TTAADC (Budget & Account) Rules 1982'}]},
    'MZ_': None,
    'PY': {'rulebooks': {'own': [{'id': 'GFR2017', 'title': 'GFR 2017'}, {'id': 'DFPR2024', 'title': 'DFPR'}, {'id': 'RPR1983', 'title': 'R&P Rules 1983'},
                                 {'id': 'CTR', 'title': 'CTR'}, {'id': 'PY_TREASURY_ACCOUNTING_1992', 'title': 'Accounting Rules for Treasuries 1992'}]}},
    'CH': {'rulebookByEmployeeOrigin': {'utCadreRules': 'Chandigarh Employees (Conditions of Service) Rules 2022'}},
    'HP': {'paymentRails': {'default': 'CENTRAL_BANK_SETTLEMENT', 'noCentralBankLink': False,
                            'note': 'delta 2026-11-05: all states on RBI e-payments except Sikkim; cheque-drawing DDOs keep cheques'}},
}
UT_REPORTING = {'DH': 'Director of Accounts DNH&DD', 'AN': 'CPAO Port Blair', 'CH': 'AG (A&E) Chandigarh',
                'LA': 'Ladakh Finance Department & Treasuries', 'LD': 'Lakshadweep Pay & Accounts'}
REMOVE_PLACEHOLDERS = {
    'GA': ['integrations', 'rulebooks', 'paymentRails', 'chartOfAccounts'], 'AR': ['paymentRails.default', 'chartOfAccounts', 'locale.languages'],
    'MZ': ['rulebooks.own.DFPR_1978_ADAPTED', 'subStateEntities', 'locale.languages'], 'DH': ['integrations', 'rulebooks', 'locale.languages'],
    'MH': ['chartOfAccounts', 'integrations.snaSparsh.onboarded'], 'CT': ['orgTopology.units', 'chartOfAccounts'], 'JH': ['orgTopology.directorate'],
    'TN': ['rulebooks.own.TN_TREASURY_CODE', 'chartOfAccounts'], 'KL': ['locale.languages'], 'AP': ['orgTopology.units'],
    'TG': ['integrations.snaSparsh.onboarded'], 'SK': ['rulebooks.own.SFR_1979_AMENDMENT_2024', 'chartOfAccounts'],
    'TR': ['subStateEntities'], 'PY': ['rulebooks'], 'JK': ['locale.languages'], 'CH': ['rulebookByEmployeeOrigin'],
    'MN': [], 'NL': [], 'LA': ['locale.languages'], 'AS': ['chartOfAccounts'], 'WB': [], 'UK': ['chartOfAccounts'],
}


def scheme(code, derive):
    segs, example, status, src = SEG[code]
    out = []
    for s in segs:
        sid, w = s[0], s[1]
        opts = s[2] if len(s) > 2 else ''
        e = {'id': sid, 'width': w}
        if sid in ('major', 'subMajor', 'minor'):
            e['scheme'] = 'HOA'
            e['owner'] = 'jurisdiction'
        if 'opt' in opts:
            e['optional'] = True
        if 'alpha' in opts:
            e['pattern'] = '[A-Z0-9]{' + str(w) + '}'
        if 'flag' in opts:
            e['pattern'] = '[A-Z]'
            e['optional'] = True
        out.append(e)
    import copy
    return {'id': 'HOA_' + code, 'separator': '-', 'segments': out, 'derive': copy.deepcopy(derive),
            'example': example, 'evidence': status, 'source': src}


def apply(code, doc, derive):
    for p in REMOVE_PLACEHOLDERS.get(code, []):
        doc['placeholders'] = [x for x in doc['placeholders'] if x['path'] != p]
    if code in SEG:
        doc['classification'] = {'schemes': [scheme(code, derive)], 'defaultScheme': 'HOA_' + code,
                                 'note': 'segments below the minor head are set by each government (LMMH GD 1.1, IFS-001)'}
        doc.pop('chartOfAccounts', None)
        if SEG[code][2] == 'P':
            doc['placeholders'].append({'path': 'classification.schemes', 'reason': 'partially verified code structure (delta P)'})
    else:
        doc['classification'] = {'defaultScheme': 'HOA'}
        doc['placeholders'] = [x for x in doc['placeholders'] if x['path'] != 'chartOfAccounts']
        doc['placeholders'].append({'path': 'classification.defaultScheme', 'reason': U + ' (no official code structure found; Union layout used)'})
        doc.pop('chartOfAccounts', None)
    langs = []
    for i, entry in enumerate(LANG[code]):
        tag, script = entry[0], entry[1]
        note = entry[2] if len(entry) > 2 else None
        e = {'tag': tag, 'script': script, 'scope': 'statewide', 'docClass': ['statutory']}
        if note and note.startswith('districts:'):
            e['scope'] = 'districts'
            e['districts'] = [x.strip() for x in note[len('districts:'):].split(',')]
        elif note:
            e['note'] = note
        if script == 'Arab':
            e['direction'] = 'rtl'
        if code in LANG_FROM and note and 'commencement' in note:
            e['effectiveFrom'] = LANG_FROM[code]
        if i == 0:
            e['default'] = True
        langs.append(e)
    if not any(l['tag'] == 'en' for l in langs):
        langs.append({'tag': 'en', 'script': 'Latn', 'scope': 'statewide', 'docClass': ['accounts', 'audit'],
                      'note': 'accounts and audit outputs are usually English (IFMS-STATES 9.1)'})
    doc['locale']['languages'] = langs
    doc['locale']['documentLanguages'] = {'treasuryForms': 'STATUTORY_PLUS_ENGLISH', 'accounts': ['en'],
                                          'status': 'V' if code in VERIFIED_LANG else 'P'}
    if code not in VERIFIED_LANG:
        doc['placeholders'] = [x for x in doc['placeholders'] if x['path'] != 'locale.languages']
        doc['placeholders'].append({'path': 'locale.documentLanguages', 'reason': U + ' (budget-document language)'})
    fam, rule, works = PROC[code]
    doc['procurement'] = dict(doc.get('procurement', {}), **{'family': fam, 'rule': rule, 'worksCode': works})
    if code in UT_REPORTING:
        doc.setdefault('regime', {})['accountsRenderedTo'] = 'CGA'
        doc['regime']['reportingUnit'] = UT_REPORTING[code]
        doc['regime']['ruleRef'] = 'IFMS-RESEARCH-DELTA IFC-237: each UT reports to the CGA directly (not via MHA PAOs)'
    if code != 'SK' and doc.get('paymentRails', {}).get('default') not in ('PFMS',):
        doc.setdefault('integrations', {})['ekuber'] = True
    return FIELD.get(code)


GOI_DELTA = {
    'orgModel': {'registry': {'source': 'AoBR to Amendment Series 386 (22-07-2026)', 'ministries': 53, 'ministriesWithoutDepartments': 34,
                              'departments': 53, 'standaloneUnits': ['DAE', 'DOS', 'CABINET_SECRETARIAT', 'PRESIDENT_SECRETARIAT', 'PMO',
                                                                     'NITI', 'NSCS'], 'businessUnits': 94,
                              'addNotInIgod': ['MHA_INTERNAL_SECURITY', 'MHA_STATES', 'MHA_JK_LADAKH_AFFAIRS'],
                              'events': [{'unit': 'CAPITAL_DEVELOPMENT', 'event': 'MOVE', 'to': 'MOHUA', 'date': '2026-07-22'},
                                         {'unit': 'URBAN_DEVELOPMENT', 'event': 'MOVE', 'to': 'MOHUA', 'date': '2026-07-22'}],
                              'vicePresidentSecretariat': 'DFPR 3(1)(e) only'}},
    'procurement': {'limitedTenderUpTo': {'general': '50L', 'scientificDepartments': '1CR'},
                    'ruleRef': 'IFMS-RESEARCH-DELTA IFC-188 (corrected): 1 cr only for scientific departments'},
    'budgetFormulation': {'calendar': {'appendicesDue': '2025-10-03', 'circular': '2025-08-28', 'for': 'BE 2026-27'},
                          'financeCommission': {'id': '16FC', 'from': '2026-27', 'to': '2030-31', 'carryOver15thFc': False}},
    'budgetControl': {'voteOnAccount': {'newServiceBarred': 'GFR 55', 'cfAdvanceBeyondProvision': 'GFR 67(2)'},
                      'rushOfExpenditure': {'q4MaxPercent': None, 'marchMaxPercent': None,
                                            'ruleRef': 'GFR 62(3)-(4); DEA OM 25-05-2022 (MEP/QEP in UBIS); percentages [U], default unset'}},
    'deductions': {'gstTdsHead': '8658-00-139', 'rates': None},
    'accounts': {'ddoReconciliation': {'certificate': 'CAM-90', 'due': 'LAST_DAY_OF_NEXT_MONTH', 'ruleRef': 'CAM 2.19.12; GFR 57(5)'},
                 'marchCuts': [{'id': 'MARCH_PRELIM', 'closes': None}, {'id': 'MARCH_SUPPL_1', 'closes': '2026-04-30', 'lateAdjustments': '2026-05-06'},
                               {'id': 'MARCH_SUPPL_2', 'closes': None}], 'nonCivilFollowCgaCalendar': True},
    'grants': {'assetRegister': {'gfrForm': None, 'ruleRef': 'GFR 230(9): no disposal without approval; GFR 21 grants register; GFR 22 fixed assets'},
               'ngoDarpan': {'source': 'NITI Aayog', 'pfmsField': 'DARPAN_UID'},
               'snaReturnOms': ['2022-05-20', '2022-07-07', '2022-09-14', '2022-11-30'], 'cnaMasterCircular': '2024-05-21 paras 47, 52-53'},
    'establishment': {'ups': {'from': '2025-04-01', 'switchToNpsLatestMonthsBefore': 12, 'fullPayoutYears': 25, 'proRataFromYears': 10,
                              'minimumAfter10Years': '10000', 'basisPercentOf12MonthAverageBasic': 50},
                      'pension': {'timersMonthsBeforeRetirement': {'start': 12, 'verification': 8, 'forms4_6_6A': 6, 'form7': 4, 'toAo': 4,
                                                                    'ppo': 2}, 'aoToCpaoMonths': 2, 'cpaoToBankDays': 21,
                                  'ruleRef': 'CCS (Pension) Rules 2021 r.56-63', 'lifeProof': ['JEEVAN_PRAMAAN', 'FACE_AUTH'],
                                  'minimum': '9000', 'maximum': '125000', 'commutationPercent': 40},
                      'payCommission': {'id': '8CPC', 'constituted': '2025-11-03', 'reportDue': '2027-05'}},
    'audit': {'ebillPostCheck': {'paoPassDays': 3, 'system': 'IAOS', 'samplingPercent': None}},
    'retention': {'ruleRef': 'GFR 320 + App. 9', 'classes': {'SANCTION': 'P2Y_OR_1Y_AFTER_AUDIT', 'DDO_CASH_BOOK': 'P10Y',
                                                             'CONTINGENT': 'P3Y_OR_1Y_AFTER_AUDIT'}, 'holdIfAuditObjection': True},
    'chargedBodies': {'list': 'Art. 112(3)(a)-(g)', 'status': 'V'},
    'worksManual': {'doeManualWorks2025': {'listed': '2026-01-05', 'amended': '2026-06-18'}, 'cpwdAfter2022': None},
}
GOI_REMOVE = ['budgetFormulation.calendar', 'establishment.ups', 'delegationAmendment']
GOI_ADD_PLACEHOLDERS = [('budgetControl.rushOfExpenditure.q4MaxPercent', 'percentages still [U]: keep unset'),
                        ('accounts.marchCuts', '[U] March Prelim and Sy-II dates'), ('worksManual.cpwdAfter2022', '[U]'),
                        ('deductions.rates', '[U] TDS / GST-TDS rates')]

POSTS_DELTA = {'receipts': {'pliHeads': {'major': '8014', 'subMajor': {'01': 'PLI', '02': 'RPLI'}, 'minor': '101-108',
                                         'investments': '8015', 'onlyDopBooks': ['8014', '8015', '8016']}}}

# object heads: versioned master; the current list closes at FY 2027-28 when the 61-head harmonised list starts
OBJECT_VERSIONING = {'currentValidTo': '2027-04-01', 'newListFrom': '2027-04-01',
                     'newList': 'DoE OM 09-06-2026: 61 heads in 8 classes (load the list; mapping old -> new is a crosswalk)'}
