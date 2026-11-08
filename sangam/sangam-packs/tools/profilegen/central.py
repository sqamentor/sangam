# SPDX-License-Identifier: Apache-2.0
# Shared (reference, regime, jurisdiction, presets) profile data for India public finance.
class FlowList(list):
    """A list written inline in the YAML ([a, b]); main.py registers its representer."""


U = '[U] unverified in the 2026-11-04 research pass; configuration placeholder, confirm before go-live'

LAYERS = {
    'layers': [
        {'id': 'international', 'overridable': True},
        {'id': 'regime', 'overridable': True},
        {'id': 'jurisdiction', 'overridable': True, 'required': ['locale.currency', 'locale.fiscal']},
        {'id': 'category', 'overridable': True},
        {'id': 'government', 'overridable': True},
        {'id': 'department', 'overridable': True},
        {'id': 'office', 'overridable': True},
    ],
    'note': 'Profile layer model of this deployment (lowest precedence first). Donor sections ("adopt") sit at the '
            'layer of the donor document. The regime-preset layer is approved by the integrator (INTERNATIONAL-PFM 4.1).',
}

from cofog_data import COFOG_FULL, SOURCE as COFOG_SOURCE  # noqa: E402  (official UNSD transcription)

COFOG = [(c, n) for c, n, _ in COFOG_FULL if '.' not in c]  # the 10 divisions

INTL = {
    'profile': {'id': 'INTL', 'layer': 'international', 'version': '2026.11.0', 'effectiveFrom': '2000-01-01',
                'name': {'en': 'International public-finance reference'},
                'source': 'IMF GFSM 2014; UN COFOG 1999 (divisions, groups, classes: ' + COFOG_SOURCE + '); ISO 4217'},
    'locked': ['classification.schemes.COFOG'],
    'classification': {'seedStatus': 'OFFICIAL_UNSD_COFOG_1999', 'schemes': [{'id': 'COFOG', 'separator': '.', 'segments': [
        {'id': 'division', 'width': 2, 'master': True}, {'id': 'group', 'width': 1, 'optional': True, 'master': True, 'parent': 'division',
         'hierarchy': {'path': FlowList(['division']), 'separator': '.'}},
        {'id': 'class', 'width': 1, 'optional': True, 'master': True, 'parent': 'group',
         'hierarchy': {'path': FlowList(['division', 'group']), 'separator': '.'}}]}]},
    'seeds': [{'scheme': 'COFOG', 'file': 'intl/seeds/cofog.csv'}],
    # Default org hierarchy (level types, top first) that onboarding defines for a tenant whose request names none
    # (security.orgLevels). Pack seat seeds name one of these levels (tools/packgen ORG_LEVELS must match).
    'security': {'orgLevels': ['HEAD_OFFICE', 'REGIONAL_OFFICE', 'FIELD_OFFICE']},
}

REGIME = {
    'profile': {'id': 'REGIME-WESTMINSTER-SUPPLY', 'layer': 'regime', 'version': '2026.11.0',
                'effectiveFrom': '2000-01-01', 'extends': ['INTL'],
                'name': {'en': 'Regime preset: Westminster supply (vote, appropriation, lapse at year end)'},
                'source': 'INTERNATIONAL-PFM-REQUIREMENTS 4.1 (L1 regime preset)'},
    'ledgers': [{'id': 'BUDGET', 'kind': 'LIMIT', 'mode': 'AUTHORITATIVE',
                 'stages': ['APPROPRIATION', 'ALLOCATION', 'RELEASE', 'AUTHORISATION', 'SUB_ALLOTMENT'],
                 'checks': {'APPROPRIATION': 'HARD', 'ALLOCATION': 'HARD', 'RELEASE': 'HARD', 'AUTHORISATION': 'HARD',
                            'SUB_ALLOTMENT': 'HARD'},
                 'availability': {'type': 'ANNUAL', 'carryForwardCommitments': False, 'cancelAfterYears': 0},
                 'holdQueue': True, 'stripes': 1,
                 'accounting': [{'event': 'CONSUME', 'basis': 'CASH', 'debit': '{line}', 'credit': 'BANK:{holder}'},
                                {'event': 'ENCUMBER', 'basis': 'ACCRUAL', 'debit': '{line}', 'credit': 'PAYABLE:{holder}'}]}],
    'yearEnd': {'policy': 'LAPSE', 'surrenderBeforeClose': True},
}

# ---------------------------------------------------------------------------------------------------- jurisdiction IN
IN = {
    'profile': {'id': 'IN', 'layer': 'jurisdiction', 'version': '2026.11.0', 'effectiveFrom': '2017-04-01',
                'extends': ['REGIME-WESTMINSTER-SUPPLY'],
                'name': {'en': 'India: national public-finance reference', 'hi': 'भारत: राष्ट्रीय लोक वित्त संदर्भ'},
                'source': 'Art. 150; GAR 1990; LMMH (CGA; seeded from slips to CS 1127 of 09-10-2026); GFR 2017; DFPR 2024; '
                          'RPR 2022; SNA-SPARSH (DoE 13-07-2023, CGA OM 53 of 26-06-2025); IFMS-CENTRAL / IFMS-STATES'},
    'placeholders': [
        {'path': 'snaSparsh.allCssFrom', 'reason': 'DoE OM 10-06-2025 date taken from research table (V)'},
    ],
    'locked': ['classification.schemes.HOA.segments', 'snaSparsh.motherSanction', 'snaSparsh.cutoff'],
    'locale': {
        'zone': 'Asia/Kolkata', 'currency': 'INR', 'symbol': '₹', 'grouping': {'first': 3, 'rest': 2},
        'units': {'L': 100000, 'CR': 10000000}, 'fiscal': {'startMonth': 4, 'startDay': 1, 'label': 'START-END2'},
        'languages': [{'tag': 'hi', 'script': 'Deva', 'default': True}, {'tag': 'en', 'script': 'Latn'}],
        'calendars': {
            'settlement': {'weekend': ['SUNDAY'], 'cutoffs': {'paymentFile': '15:00'},
                           'note': 'e-Kuber holidays are loaded yearly from the RBI calendar (connector or data file)'},
            'government': {'weekend': ['SATURDAY', 'SUNDAY']}},
    },
    'classification': {
        'seedStatus': 'OFFICIAL_LMMH_CS1127',
        'schemes': [
            {'id': 'HOA', 'separator': '-',
             'segments': [
                 {'id': 'major', 'width': 4, 'master': True, 'owner': 'jurisdiction'},
                 {'id': 'subMajor', 'width': 2, 'master': True, 'owner': 'jurisdiction', 'parent': 'major',
                  'hierarchy': {'path': FlowList(['major']), 'noneCode': '00', 'templateWildcard': '*', 'templateChild': 'minor'}},
                 {'id': 'minor', 'width': 3, 'master': True, 'owner': 'jurisdiction', 'parent': 'subMajor',
                  'hierarchy': {'path': FlowList(['major', 'subMajor']), 'anyParentFlag': 'standard', 'openRuleFlag': 'minorRule',
                                'templateWildcard': '*'}},
                 {'id': 'subHead', 'width': 2},
                 {'id': 'detailed', 'width': 2},
                 {'id': 'object', 'width': 2, 'scheme': 'OBJECT'}],
             'attributes': ['chargedVoted', 'grantNo', 'schemeType', 'tags'],
             'derive': [
                 {'segment': 'major', 'prefix': '0', 'set': {'section': 'REVENUE', 'nature': 'RECEIPT'}},
                 {'segment': 'major', 'prefix': '1', 'set': {'section': 'REVENUE', 'nature': 'RECEIPT'}},
                 {'segment': 'major', 'prefix': '2', 'set': {'section': 'REVENUE', 'nature': 'EXPENDITURE'}},
                 {'segment': 'major', 'prefix': '3', 'set': {'section': 'REVENUE', 'nature': 'EXPENDITURE'}},
                 {'segment': 'major', 'equals': '4000', 'set': {'section': 'CAPITAL', 'nature': 'RECEIPT'}},
                 {'segment': 'major', 'prefix': '4', 'set': {'section': 'CAPITAL', 'nature': 'EXPENDITURE'}},
                 {'segment': 'major', 'prefix': '5', 'set': {'section': 'CAPITAL', 'nature': 'EXPENDITURE'}},
                 {'segment': 'major', 'prefix': '6', 'set': {'section': 'LOANS', 'nature': 'BOTH'}},
                 {'segment': 'major', 'prefix': '7', 'set': {'section': 'LOANS', 'nature': 'BOTH'}},
                 {'segment': 'major', 'equals': '8000', 'set': {'section': 'CONTINGENCY_FUND', 'nature': 'BOTH'}},
                 {'segment': 'major', 'prefix': '8', 'set': {'section': 'PUBLIC_ACCOUNT', 'nature': 'BOTH'}},
                 {'segment': 'major', 'matches': '255[2]|455[2]|655[2]', 'set': {'nerLumpSum': True}},
                 {'segment': 'minor', 'matches': '9\\d\\d', 'set': {'deductEntry': True}},
                 {'segment': 'minor', 'equals': '791', 'set': {'exchangeDifference': True}}],
             'ruleRef': 'DFPR 2024 r.8(5)(i) (6 tiers / 15 digits); LMMH coding pattern'},
            {'id': 'OBJECT', 'separator': '-', 'segments': [{'id': 'object', 'width': 2, 'master': True}],
             'ruleRef': 'DFPR 2024 Annexure I; object heads are data with old -> new mapping (IFC-034)'}],
        'combinations': [
            {'scheme': 'HOA', 'if': {'major': '4*|5*'}, 'forbid': {'object': '01|02|04|11|12|13'},
             'ruleRef': 'capital section may not carry establishment object heads (illustrative; confirm per DDG)'}],
        'tags': [{'id': 'CHARGED_VOTED', 'values': ['CHARGED', 'VOTED']},
                 {'id': 'SCHEME_TYPE', 'values': ['CS', 'CSS', 'ESTABLISHMENT', 'OTHER']},
                 {'id': 'PLAN_LEGACY', 'values': ['PLAN', 'NON_PLAN'], 'note': 'history only (merged by DoE OM 05-08-2016)'}],
    },
    'seeds': [{'scheme': 'HOA', 'file': 'in/seeds/hoa-major-heads.csv'},
              {'scheme': 'HOA', 'file': 'in/seeds/hoa-sub-major-heads.csv'},
              {'scheme': 'HOA', 'file': 'in/seeds/hoa-minor-heads.csv'},
              {'scheme': 'HOA', 'file': 'in/seeds/hoa-standard-minor-heads.csv'},
              {'scheme': 'OBJECT', 'file': 'in/seeds/object-heads.csv'}],
    'crosswalks': [{'id': 'HOA_TO_COFOG', 'from': 'HOA', 'to': 'COFOG', 'rows': [
        {'match': '2011-*', 'target': '01', 'share': 100}, {'match': '2012-*', 'target': '01', 'share': 100},
        {'match': '2013-*', 'target': '01', 'share': 100}, {'match': '2014-*', 'target': '03', 'share': 100},
        {'match': '2015-*', 'target': '01', 'share': 100}, {'match': '2049-*', 'target': '01', 'share': 100},
        {'match': '2052-*', 'target': '01', 'share': 100}, {'match': '2054-*', 'target': '01', 'share': 100},
        {'match': '2055-*', 'target': '03', 'share': 100}, {'match': '2056-*', 'target': '03', 'share': 100},
        {'match': '2071-*', 'target': '10', 'share': 100}, {'match': '2076-*', 'target': '02', 'share': 100},
        {'match': '2077-*', 'target': '02', 'share': 100}, {'match': '2078-*', 'target': '02', 'share': 100},
        {'match': '2202-*', 'target': '09', 'share': 100}, {'match': '2203-*', 'target': '09', 'share': 100},
        {'match': '2204-*', 'target': '08', 'share': 100}, {'match': '2205-*', 'target': '08', 'share': 100},
        {'match': '2210-*', 'target': '07', 'share': 100}, {'match': '2211-*', 'target': '07', 'share': 100},
        {'match': '2215-*', 'target': '06', 'share': 100}, {'match': '2216-*', 'target': '06', 'share': 100},
        {'match': '2217-*', 'target': '06', 'share': 100}, {'match': '2225-*', 'target': '10', 'share': 100},
        {'match': '2235-*', 'target': '10', 'share': 100}, {'match': '2236-*', 'target': '10', 'share': 100},
        {'match': '2401-*', 'target': '04', 'share': 100}, {'match': '2406-*', 'target': '05', 'share': 100},
        {'match': '2501-*', 'target': '04', 'share': 100}, {'match': '2701-*', 'target': '04', 'share': 100},
        {'match': '2801-*', 'target': '04', 'share': 100}, {'match': '3054-*', 'target': '04', 'share': 100},
        {'match': '4202-*', 'target': '09', 'share': 100}, {'match': '4210-*', 'target': '07', 'share': 100},
        {'match': '5054-*', 'target': '04', 'share': 100}],
        'note': 'Illustrative function-level mapping (COFOG divisions); a full crosswalk is a data deliverable [U]'}],
    'snaSparsh': {
        'motherSanction': {'onePerSls': True, 'lapseAt': '23:59', 'lapseOn': 'FISCAL_YEAR_END',
                           'ruleRef': 'CGA OM 53 of 26-06-2025'},
        'cutoff': {'calendar': 'settlement', 'name': 'paymentFile', 'time': '15:00', 'nextBusinessDay': True},
        'reissueTag': True, 'reconciliationUnit': 'TREASURY', 'noTransferToPd': True,
        'allCssFrom': '2025-11-01', 'chains': {
            'DEFAULT': ['PD_CHECKER', 'PR_AO', 'PAO'],
            'LEGACY_7_ROLE': ['PD_MAKER', 'PD_CHECKER', 'PR_AO', 'BILL_DISTRIBUTOR', 'PAO_DH', 'PAO_AAO', 'PAO']},
        'ruleRefs': ['DoE 13-07-2023', 'CGA SOP 11-08-2025 (3-role flow)', 'CGA FAQ OM 115 of 25-09-2025', 'DoE OM 10-06-2025']},
    'cssSharing': {'presets': {
        'GENERAL': {'core': [60, 40], 'optional': [50, 50]},
        'NE_HIMALAYAN': {'core': [90, 10], 'optional': [80, 20]},
        'UT_WITHOUT_LEGISLATURE': {'core': [100, 0], 'optional': [100, 0]}},
        'ruleRef': 'NITI Sub-Group of CMs 2016-17'},
    'paymentRails': {'catalogue': [
        {'id': 'CENTRAL_BANK_SETTLEMENT', 'label': 'RBI e-Kuber', 'connector': 'rail.ekuber', 'capabilityClass': 'c'},
        {'id': 'AGENCY_BANK', 'label': 'Agency bank cash-management product', 'connector': 'rail.agencyBank', 'capabilityClass': 'c'},
        {'id': 'STATE_OWNED_BANK', 'label': 'State-owned government bank (cheque / draft)', 'connector': 'rail.stateBank', 'capabilityClass': 'c'},
        {'id': 'TREASURY_CHEQUE', 'label': 'Treasury cheque', 'connector': 'rail.cheque', 'capabilityClass': 'b'},
        {'id': 'LETTER_OF_CREDIT', 'label': 'Letter of credit / CCL-DCL for cheque-drawing divisions', 'connector': 'rail.loc', 'capabilityClass': 'b'},
        {'id': 'PFMS', 'label': 'PFMS payment file', 'connector': 'pfms.payment', 'capabilityClass': 'c'},
        {'id': 'APBS', 'label': 'NPCI Aadhaar payment bridge', 'connector': 'rail.apbs', 'capabilityClass': 'c'},
        {'id': 'FOREIGN_CURRENCY_ADVICE', 'label': 'Accredited-bank foreign-currency advice', 'connector': 'rail.fcy', 'capabilityClass': 'c'}],
        'failure': {'twoStage': True, 'reissueNewOperation': True, 'alterationMemoAfter': 'P1Y'},
        'chequeValidityMonths': 3, 'ruleRefs': {'chequeValidity': 'RPR 2022 r.16'}},
    'unitTypes': ['DISTRICT_TREASURY', 'SUB_TREASURY', 'UPGRADED_SUB_TREASURY', 'DIVISIONAL_SUB_TREASURY',
                  'HQ_TREASURY', 'CYBER_TREASURY_RECEIPTS', 'CYBER_TREASURY_SNA', 'GST_TREASURY', 'CAU_SNA',
                  'PENSION_TREASURY', 'SPECIAL_TREASURY', 'PAO', 'SUB_PAO', 'PRINCIPAL_PAO', 'CHIEF_PAO', 'CPAO_NON_BANKING',
                  'LEGISLATURE_PAO', 'FIELD_PAY_UNIT', 'WORKS_DIVISION', 'FOREST_DIVISION', 'CDDO', 'DDO'],
    'publicAccounts': {'types': [
        {'id': 'PD', 'controls': {'noMinusBalance': True, 'inactivityClose': 'P3Y'}},
        {'id': 'PLA', 'controls': {'noMinusBalance': True}},
        {'id': 'TSA_ASSIGNMENT', 'controls': {'lapseAtYearEnd': True}, 'head': '8454'}]},
    'accounts': {'monthlyDueDay': 10, 'periodLock': True, 'extraPeriods': ['MARCH_PRELIM', 'MARCH_SUPPL_1', 'MARCH_SUPPL_2']},
}


def preset(code, name, category, extra):
    d = {'profile': {'id': 'IN-PRESET-' + code, 'layer': 'category', 'version': '2026.11.0', 'effectiveFrom': '2017-04-01',
                     'name': {'en': name}, 'source': 'IFMS-STATES 6.1 L1 category preset'},
         'regime': {'category': category}}
    d.update(extra)
    return d


PRESETS = [
    preset('GENERAL', 'General-category State', 'GENERAL',
           {'regime': {'category': 'GENERAL', 'budgetMode': 'OWN_CONSOLIDATED_FUND', 'accountsRenderedTo': 'AG_AE',
                       'cssSharing': 'GENERAL'},
            'integrations': {'pfms': {'model': 4}, 'snaSparsh': {'enabled': True}}}),
    preset('NE-HIMALAYAN', 'North-East / Himalayan State (90:10)', 'NE_HIMALAYAN',
           {'regime': {'category': 'NE_HIMALAYAN', 'budgetMode': 'OWN_CONSOLIDATED_FUND', 'accountsRenderedTo': 'AG_AE',
                       'cssSharing': 'NE_HIMALAYAN'},
            'integrations': {'pfms': {'model': 4}, 'snaSparsh': {'enabled': True}}}),
    preset('UT-LEG', 'Union Territory with legislature', 'UT_WITH_LEGISLATURE',
           {'regime': {'category': 'UT_WITH_LEGISLATURE', 'budgetMode': 'OWN_CONSOLIDATED_FUND',
                       'cssSharing': 'GENERAL', 'unionDemandForTransfers': True},
            'integrations': {'pfms': {'model': 4}, 'snaSparsh': {'enabled': True}}}),
    preset('UT-NOLEG', 'Union Territory without legislature', 'UT_WITHOUT_LEGISLATURE',
           {'regime': {'category': 'UT_WITHOUT_LEGISLATURE', 'budgetMode': 'UNION_DEMAND', 'accountsRenderedTo': 'CGA',
                       'cssSharing': 'UT_WITHOUT_LEGISLATURE',
                       'administratorDelegation': {'governorOrLg': '50CR', 'other': '25CR',
                                                   'ruleRef': 'CAG Report 3 of 2018'}},
            'integrations': {'pfms': {'model': 3}, 'snaSparsh': {'enabled': True, 'directInPfms': True}}}),
    preset('UNION', 'Union Government (departmentalised accounts)', 'UNION',
           {'regime': {'category': 'UNION', 'budgetMode': 'UNION_BUDGET', 'accountsRenderedTo': 'CGA'},
            'integrations': {'pfms': {'model': 2}}}),
]
