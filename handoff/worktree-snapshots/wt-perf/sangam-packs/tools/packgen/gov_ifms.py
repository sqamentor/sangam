# SPDX-License-Identifier: Apache-2.0
"""Government finance (IFMS) packs `gov.ifms.*` built on the generic services (ADR-D-20):

- configuration profiles (`config.*` connectors: profile values, document-type catalogue, classification, number series)
  from `sangam-config`; the government data is in `src/main/resources/profiles/` (GOI, its departments, 36 States/UTs);
- the limit / fund ledger (`ledger.*` connectors) from `sangam-ledger`;
- delegation and rule tables as DMN (DFPR / GFR / CCS (Pension) Rules 2021 defaults; a State replaces a table with a
  tenant override, never with code).

Requirement ids: IFC-nnn (spike/results/IFMS-CENTRAL-REQUIREMENTS.md), IFS-nnn / IFS-<ST>-nnn and GA-nn
(spike/results/IFMS-STATES-REQUIREMENTS.md).
"""
from lib import C, q
from packs import Pack
from common import core, outcome, end_ok, rejection, reject_with, withdraw
import forms
from sector import powers

SIDE = ['GP-14', 'VI.5']

# ------------------------------------------------------------------------------------------------ labels (en / hi)
IFMS_L = {
    'billFamily': ('Bill family', 'बिल श्रेणी', 'readonly'),
    'formNumber': ('Form number', 'प्रपत्र संख्या', 'readonly'),
    'headOfAccount': ('Head of account', 'लेखा शीर्ष', 'string'),
    'grantNo': ('Grant / demand number', 'अनुदान / माँग संख्या', 'string'),
    'chargedVoted': ('Charged / voted', 'भारित / दत्तमत', 'select'),
    'ddoCode': ('DDO code', 'आहरण एवं संवितरण अधिकारी कोड', 'string'),
    'payee': ('Payee', 'आदाता', 'string'),
    'payeeAccount': ('Payee bank account', 'आदाता का बैंक खाता', 'string'),
    'sanctionRef': ('Sanction reference', 'स्वीकृति संदर्भ', 'string'),
    'claimPeriod': ('Claim period / due date', 'दावा अवधि / देय तिथि', 'date'),
    'tokenNo': ('Token number', 'टोकन संख्या', 'readonly'),
    'objectionCodes': ('Objection codes (with rule)', 'आपत्ति कोड (नियम सहित)', 'multiselect'),
    'objectionMemo': ('Objection memo', 'आपत्ति ज्ञापन', 'text'),
    'dscSerial': ('DSC serial', 'डीएससी क्रमांक', 'string'),
    'objectGroup': ('Object-head group', 'उद्देश्य शीर्ष समूह', 'select'),
    'reappFlags': ('Line flags (charged, NS/NIS, supplementary, CF advance, secret service ...)', 'शीर्ष फ़्लैग', 'multiselect'),
    'percentOfBe': ('Increase as % of BE', 'बजट अनुमान का % वृद्धि', 'number'),
    'beAmount': ('Budget estimate (BE)', 'बजट अनुमान', 'money'),
    'reAmount': ('Revised estimate (RE)', 'संशोधित अनुमान', 'money'),
    'actuals': ('Actuals to date', 'अब तक का वास्तविक व्यय', 'money'),
    'fiscalYear': ('Financial year', 'वित्तीय वर्ष', 'string'),
    'supplementaryKind': ('Supplementary kind (cash / technical / token)', 'अनुपूरक का प्रकार', 'select'),
    'newServiceFlag': ('New service / new instrument of service', 'नई सेवा / सेवा का नया साधन', 'boolean'),
    'surrenderAmount': ('Amount to surrender', 'समर्पित राशि', 'money'),
    'grantee': ('Grantee', 'अनुदान प्राप्तकर्ता', 'string'),
    'granteeType': ('Grantee type', 'अनुदान प्राप्तकर्ता का प्रकार', 'select'),
    'recurringGrant': ('Recurring grant', 'आवर्ती अनुदान', 'boolean'),
    'ucStatus': ('Utilisation certificate status', 'उपयोगिता प्रमाण पत्र की स्थिति', 'select'),
    'unspentBalance': ('Unspent balance with the grantee', 'प्राप्तकर्ता के पास अव्ययित शेष', 'money'),
    'releasePercent': ('Release as % of sanction', 'स्वीकृति का % विमुक्ति', 'number'),
    'ucForm': ('UC form (12-A / 12-B / 12-C)', 'उपयोगिता प्रमाण पत्र प्रपत्र', 'readonly'),
    'slsCode': ('State-linked scheme (SLS)', 'राज्य-संबद्ध योजना', 'string'),
    'motherSanctionNo': ('Mother sanction', 'मूल स्वीकृति', 'string'),
    'centreShare': ('Centre share', 'केंद्र अंश', 'money'),
    'stateShare': ('State share', 'राज्य अंश', 'money'),
    'reissueTag': ('Reissue tag', 'पुनर्जारी टैग', 'readonly'),
    'receiptHead': ('Receipt head', 'प्राप्ति शीर्ष', 'string'),
    'utr': ('UTR / CIN', 'यूटीआर / सीआईएन', 'string'),
    'lastEmoluments': ('Last emoluments', 'अंतिम परिलब्धियाँ', 'money'),
    'averageEmoluments': ('Average emoluments (10 months)', 'औसत परिलब्धियाँ (10 माह)', 'money'),
    'ageNextBirthday': ('Age next birthday', 'अगले जन्मदिन पर आयु', 'integer'),
    'commutationPercent': ('Commutation (%)', 'राशिकरण (%)', 'number'),
    'gpfAccount': ('GPF account number', 'जीपीएफ खाता संख्या', 'string'),
    'gpfPurpose': ('Purpose of advance / withdrawal', 'अग्रिम / आहरण का प्रयोजन', 'select'),
    'gpfBalance': ('GPF balance', 'जीपीएफ शेष', 'money'),
    'advanceKind': ('Advance kind', 'अग्रिम का प्रकार', 'select'),
    'instalments': ('Recovery instalments', 'वसूली किस्तें', 'integer'),
    'accountsMonth': ('Accounts month', 'लेखा माह', 'string'),
    'closingCut': ('Closing cut', 'समापन चरण', 'select'),
    'schemeName': ('Scheme / project', 'योजना / परियोजना', 'string'),
    'totalOutlay': ('Total outlay (incl. EBR, external aid, state share)', 'कुल परिव्यय', 'money'),
    'appraisalForum': ('Appraisal forum', 'मूल्यांकन मंच', 'readonly'),
    'approvalAuthority': ('Approval authority', 'अनुमोदन प्राधिकारी', 'readonly'),
    'paraNo': ('Audit para number', 'लेखापरीक्षा पैरा संख्या', 'string'),
    'atnText': ('Action taken note', 'की गई कार्रवाई टिप्पणी', 'text'),
    'stage': ('Stage', 'चरण', 'select'),
    'holder': ('Holder (office / DDO)', 'धारक (कार्यालय / डीडीओ)', 'string'),
}
IFMS_OPT = {
    'passBill': ('Pass', 'पारित करें'), 'object': ('Raise objection', 'आपत्ति उठाएँ'), 'returnBill': ('Return to DDO', 'डीडीओ को वापस करें'),
    'forwardNext': ('Forward to next level', 'अगले स्तर को अग्रेषित करें'), 'signed': ('Signed (DSC)', 'हस्ताक्षरित (डीएससी)'),
    'faConcur': ('Concur', 'सहमति'), 'faNotConcur': ('Do not concur', 'असहमति'), 'surrenderNow': ('Surrender', 'समर्पित करें'),
    'retainSavings': ('Retain', 'रखें'), 'accepted': ('Accepted', 'स्वीकृत'), 'pending': ('Pending', 'लंबित'),
    'settlePara': ('Settle', 'निपटान करें'), 'furtherAction': ('Further action required', 'आगे कार्रवाई आवश्यक'),
    'allocate': ('Allocate', 'आवंटित करें'), 'matched': ('Matched', 'मिलान हुआ'), 'unaccounted': ('Unaccounted', 'अलेखित'),
    'refund': ('Refund', 'वापसी करें'), 'book': ('Book to head', 'शीर्ष में दर्ज करें'),
    'compiled': ('Compiled', 'संकलित'), 'blacklist': ('Blacklist grantee', 'प्राप्तकर्ता को काली सूची में डालें'),
    'remind': ('Remind', 'स्मरण कराएँ'),
}
for k, v in IFMS_L.items():
    forms.L.setdefault(k, v)
for k, v in IFMS_OPT.items():
    forms.OPT.setdefault(k, v)


def seeds(pk, office, rows, prefix):
    pk.seeds(offices=[{'id': office, 'parent': ''}],
             seats=[{'id': sid, 'role': role, 'office': office, 'reportsTo': up, 'name': {'en': en, 'hi': hi}}
                    for sid, role, up, en, hi in rows],
             holdings=[{'seat': sid, 'person': prefix + '.' + sid.lower(), 'basis': 'SUBSTANTIVE', 'from': '2026-01-01'}
                       for sid, _, _, _, _ in rows],
             roles=[{'code': role, 'name': {'en': en, 'hi': hi}} for _, role, _, en, hi in rows])


PAO_SEATS = [('DDO-1', 'DDO', 'HOO-1', 'Drawing and disbursing officer', 'आहरण एवं संवितरण अधिकारी'),
             ('HOO-1', 'HOO', None, 'Head of Office', 'कार्यालय प्रमुख'),
             ('BD-1', 'BILL_DISTRIBUTOR', 'AAO-1', 'Bill distributor', 'बिल वितरक'),
             ('DH-1', 'DEALING_HAND', 'AAO-1', 'Dealing hand', 'व्यवहार सहायक'),
             ('AAO-1', 'AAO', 'PAO-1', 'Assistant accounts officer', 'सहायक लेखा अधिकारी'),
             ('PAO-1', 'PAO', 'PRAO-1', 'Pay and accounts officer', 'वेतन एवं लेखा अधिकारी'),
             ('SS-1', 'SECOND_SIGNATORY', 'PRAO-1', 'Second signatory', 'द्वितीय हस्ताक्षरकर्ता'),
             ('PRAO-1', 'PR_AO', 'FA-1', 'Principal accounts officer', 'प्रधान लेखा अधिकारी'),
             ('FA-1', 'FA', 'SECY-1', 'Financial adviser', 'वित्तीय सलाहकार'),
             ('SECY-1', 'CAA', None, 'Secretary (Chief Accounting Authority)', 'सचिव (मुख्य लेखा प्राधिकारी)')]

CONFIG_CONNECTORS = [('config.profile', 'a', 'G-CONFIG', 'Profile values of the tenant (sangam-config)'),
                     ('config.documentType', 'a', 'G-CONFIG', 'Document-type catalogue (bill types, forms, chains, rails)'),
                     ('config.classification', 'a', 'G-CONFIG', 'Classification validation (head of account)'),
                     ('config.series', 'a', 'G-CONFIG', 'Number series (token, voucher)')]


def ledger_conn(op, purpose):
    return ('ledger.' + op, 'a', 'G-LEDGER', purpose + ' (sangam-ledger, idempotent by operationId)')


def authority(pk, key, rows, doc):
    pk.dmn(key, 'Competent authority', 'FIRST', [('Amount (INR)', 'amount', 'double')],
           [('competentRole', 'Competent role', 'string')], [([t], [q(r)], n) for t, r, n in rows], doc=doc)


def ifms_powers(pk, key, case, rows, unverified=None):
    """Delegated-power table of one IFMS case type (SL-31): rows (role, limit, citation); limit None = the rule confers
    the power without a money ceiling. `unverified` (reason) marks the table as a pack placeholder: the value is the
    pack's working default, not read from the rule text, and readiness reports PACK_PLACEHOLDER_UNCONFIRMED until the
    tenant deploys a confirmed override of `key`."""
    powers(pk, key, [((role, case), limit, note) for role, limit, note in rows],
           doc='Delegated powers for ' + case + ' (SL-31). ' + ('UNVERIFIED: ' + unverified if unverified
                                                               else 'Values cited from the rule text in the corpus.'))
    if unverified:
        for role, limit, _ in rows:
            pk.placeholder(key, f'{role} {case} ' + ('no monetary limit' if limit is None else f'up to {limit}'),
                           unverified)


def task(s, id, name, seat, form, decision, fields, spec='5WD', sod=False):
    return s.sla_task(id, name, seat, form=form, decision=decision, fields=fields, default_spec=spec, sod=sod)


# ================================================================================================ PAO chain
def pao_chain():
    pk = Pack('gov.ifms.pao-chain', 'ifms-pao-chain', 'Pay and accounts office pre-check chain',
              'वेतन एवं लेखा कार्यालय पूर्व-जाँच शृंखला', 'IFMS',
              'Token from the number series (unique per office per day), bill distributor with FIFO assignment, '
              'dealing hand and AAO checks, PAO pass / objection / return (only the PAO returns a bill), objection memo '
              'citing the rule, signature count from the chain template (two DSCs at or above the profile threshold), '
              'treasury variant of configurable depth with no-edit policy. Called by every gov.ifms bill.',
              ['IFC-093', 'IFC-094', 'IFC-095', 'IFC-096', 'IFC-097', 'IFC-098', 'IFC-112', 'IFC-256', 'IFS-047', 'IFS-048',
               'IFS-073', 'IFS-ML-003', 'IFS-ML-004', 'IFS-HR-002', 'IFS-OD-003', 'IFS-TG-005', 'IFS-KL-007', 'IFS-UP-003',
               'IFS-AP-004', 'GA-07', 'GA-23', 'GP-02', 'GP-17'],
              connectors=CONFIG_CONNECTORS)
    p = pk.proc('sangam-ifms-pao-chain', 'PAO / treasury pre-check', '', start={'materialFields': 'billType,amount,tokenNo'})
    s = p.root
    st = s.start('pc_start', 'Bill received', initiator=None)
    d = s.setv('pc_defaults', 'Configuration', defaults='chainId=PAO_3;tokenless=false;billDistributorSeat=BD-1;'
               'dealingSeat=DH-1;aaoSeat=AAO-1;paoSeat=PAO-1;secondSignatorySeat=SS-1;chainSignatures=1;amount=0;'
               'paoDecision=pass;tokenNo=;billType=GENERIC')
    tok = s.conn('pc_token', 'Token number (series TOKEN)', 'config.series', 'series.next', 'submit',
                 'billType,ddoCode', 'token', step='token')
    sig = s.conn('pc_sigs', 'Signatures required (chain template)', 'config.documentType', 'chain.signatures', 'query',
                 'chainId,amount', 'chain')
    s.seq(st, d, tok, sig)
    e1, x1 = task(s, 'pc_distribute', 'Assign bill in FIFO order | फीफो क्रम में बिल सौंपें', 'billDistributorSeat',
                  'ifms-pao-chain/distribute', ('distributeDecision', ['forwardNext']), ['tokenNo', 'billType', 'amount'], '1WD')
    e2, x2 = task(s, 'pc_dealing', 'Dealing hand check | व्यवहार सहायक की जाँच', 'dealingSeat', 'ifms-pao-chain/dealing',
                  ('dealingDecision', ['forwardNext', 'object']), ['tokenNo', 'objectionCodes', 'remarks'], '2WD')
    e3, x3 = task(s, 'pc_aao', 'AAO check | सहायक लेखा अधिकारी की जाँच', 'aaoSeat', 'ifms-pao-chain/aao',
                  ('aaoDecision', ['forwardNext', 'object']), ['tokenNo', 'objectionCodes', 'remarks'], '1WD', sod=True)
    e4, x4 = task(s, 'pc_pao', 'PAO pass, object or return | पीएओ: पारित, आपत्ति या वापसी', 'paoSeat', 'ifms-pao-chain/pao',
                  ('paoDecision', ['passBill', 'object', 'returnBill']), ['tokenNo', 'objectionCodes', 'objectionMemo', 'dscSerial'],
                  '1WD', sod=True)
    s.flow(sig, e1)
    s.seq(x1, e2)
    s.seq(x2, e3)
    s.seq(x3, e4)
    memo = s.svc('pc_memo', 'Objection memo (rule cited)', C['DOC'], {'template': 'objection-memo'})
    out_ret = s.setv('pc_returned', 'Outcome: returned', values='paoOutcome=RETURNED')
    out_obj = s.setv('pc_objected', 'Outcome: objection', values='paoOutcome=OBJECTED')
    g = s.xgw('pc_paoGw', 'PAO decision?')
    s.flow(x4, g)
    s.flow(g, memo, "paoDecision == 'object'", 'object')
    s.flow(g, out_ret, "paoDecision == 'returnBill'", 'return (PAO only)')
    s.seq(memo, out_obj)
    sg = s.xgw('pc_sigGw', 'Second signatory needed?')
    s.flow(g, sg, None, 'pass', default=True)
    e5, x5 = task(s, 'pc_second', 'Second signature (DSC) | द्वितीय हस्ताक्षर (डीएससी)', 'secondSignatorySeat',
                  'ifms-pao-chain/second', ('secondDecision', ['signed', 'returnBill']), ['dscSerial', 'remarks'], '1WD', sod=True)
    passed = s.setv('pc_passed', 'Outcome: passed', values='paoOutcome=PASSED')
    s.flow(sg, e5, 'chainSignatures >= 2', 'two signatures')
    s.flow(sg, passed, None, 'one signature', default=True)
    g5 = s.xgw('pc_secGw')
    s.flow(x5, g5)
    s.flow(g5, passed, "secondDecision == 'signed'", 'signed')
    s.flow(g5, out_ret, None, 'returned', default=True)
    # dealing hand / AAO objections go up to the PAO as remarks: only the PAO returns a bill (CAM 2.2.8)
    end = s.end('pc_end', 'Chain complete')
    s.flow(passed, end)
    s.flow(out_obj, s.end('pc_endObj', 'Objected'))
    s.flow(out_ret, s.end('pc_endRet', 'Returned'))
    seeds(pk, 'PAO-OFFICE', PAO_SEATS, 'pc')
    pk.accept('PC-1', 'Two signatures at or above the threshold', ['IFC-098'], 'chain PAO_3 with band 10 lakh',
              'a bill of 12 lakh passes', 'the second-signatory task is created', {'amount': 1200000, 'chainSignatures': 2})


# ================================================================================================ catalogue-driven bill
FAMILIES = [('establishment-pay', 'ESTABLISHMENT_PAY', 'PAY', 'Pay bill', 'वेतन बिल', ['IFC-166', 'IFC-167', 'IFC-168', 'IFC-169', 'IFS-071', 'IFS-HP-004', 'IFS-TR-001']),
            ('personal-claim', 'PERSONAL_CLAIM', 'TA_TOUR', 'Personal claim (TA, LTC, medical, CEA)', 'व्यक्तिगत दावा', ['IFC-091', 'IFC-117', 'IFC-120', 'IFC-182', 'IFC-183']),
            ('contingent', 'CONTINGENT', 'CONTINGENT', 'Contingent / imprest bill', 'आकस्मिक / अग्रदाय बिल', ['IFC-107', 'IFS-049', 'IFS-GJ-001', 'IFS-BR-002']),
            ('vendor', 'VENDOR', 'VENDOR_INVOICE', 'Supplier / GeM bill', 'आपूर्तिकर्ता / जेम बिल', ['IFC-115', 'IFS-068', 'IFS-OD-009']),
            ('grant', 'GRANT', 'GIA', 'Grant-in-aid bill', 'सहायता अनुदान बिल', ['IFC-085', 'IFS-050']),
            ('refund', 'REFUND', 'REFUND_REVENUE', 'Refund bill', 'वापसी बिल', ['IFC-116']),
            ('advance', 'ADVANCE', 'HBA', 'Advance bill', 'अग्रिम बिल', ['IFC-180']),
            ('provident-fund', 'PROVIDENT_FUND', 'GPF_FINAL', 'Provident fund bill', 'भविष्य निधि बिल', ['IFC-171']),
            ('retirement', 'RETIREMENT', 'RETIREMENT_BENEFITS', 'Retirement benefits bill', 'सेवानिवृत्ति लाभ बिल', ['IFC-177']),
            ('contingency-fund', 'CONTINGENCY_FUND', 'CF_WITHDRAWAL', 'Contingency Fund withdrawal', 'आकस्मिकता निधि आहरण', ['IFC-050']),
            ('nil-bill', 'NIL_BILL', 'NPS_NIL', 'Nil bill (adjustment)', 'शून्य बिल (समायोजन)', ['IFC-106', 'IFC-172']),
            ('cddo', 'ASSIGNMENT', 'CDDO_ASSIGNMENT', 'Cheque-drawing DDO payment', 'चेक आहरण डीडीओ भुगतान', ['IFC-108', 'IFS-064', 'IFS-HP-003']),
            ('classified', 'CLASSIFIED', 'SECRET_SERVICE', 'Classified / secret-service payment', 'गोपनीय भुगतान', ['IFC-119'])]


def bill():
    covers = ['IFC-042', 'IFC-057', 'IFC-058', 'IFC-075', 'IFC-077', 'IFC-079', 'IFC-092', 'IFC-099', 'IFC-100', 'IFC-101',
              'IFC-102', 'IFC-103', 'IFC-104', 'IFC-105', 'IFC-109', 'IFC-110', 'IFC-111', 'IFC-114', 'IFC-118', 'IFC-121',
              'IFC-122', 'IFC-123', 'IFC-124', 'IFC-125', 'IFC-246', 'IFC-252', 'IFC-253', 'IFS-039', 'IFS-041', 'IFS-043',
              'IFS-044', 'IFS-045', 'IFS-046', 'IFS-053', 'IFS-054', 'IFS-056', 'IFS-RJ-002', 'IFS-RJ-004', 'IFS-MH-001',
              'IFS-MH-002', 'IFS-MH-003', 'IFS-MP-001', 'IFS-MP-003', 'IFS-HP-002', 'IFS-HP-005', 'IFS-KA-001',
              'IFS-KA-004', 'IFS-KA-007', 'IFS-KA-008', 'IFS-PB-003', 'IFS-PB-004', 'IFS-PB-005', 'IFS-OD-005',
              'IFS-OD-010', 'IFS-JK-002', 'IFS-AP-003', 'IFS-KL-005', 'IFS-KL-006', 'IFS-SK-001', 'IFS-SK-004',
              'IFS-UP-007', 'IFS-TN-001', 'IFS-TR-002', 'IFS-HR-003', 'IFS-CH-003', 'GA-04', 'GA-05', 'GA-06', 'GA-10', 'GA-12', 'GP-11', 'GP-34', 'GP-50']
    for f in FAMILIES:
        covers += f[5]
    pk = Pack('gov.ifms.bill', 'ifms-bill', 'Bill by catalogue type (all bill-type families)', 'सूची प्रकार अनुसार बिल (सभी बिल श्रेणियाँ)',
              'IFMS', 'One catalogue-driven bill process: the bill type (form number, attachments, certificates, allowed '
              'heads, one-major-head rule, sanction requirement, chain, rail) comes from the tenant profile '
              '(config.documentType); the head of account is validated against the classification master; funds are '
              'checked and encumbered in the ledger at the profile control dimension (hold-for-budget queue when short); '
              'the PAO / treasury chain runs; payment goes through the rail the profile selects (rail DMN, tenant '
              'override per State) with operationId and reconciliation; consumption is posted. One thin process per '
              'bill-type family sets the family defaults.',
              sorted(set(covers)) + SIDE,
              connectors=CONFIG_CONNECTORS + [ledger_conn('available', 'Funds available at the control dimension'),
                                               ledger_conn('encumber', 'Encumber the bill (from the sanction commitment)'),
                                               ledger_conn('consume', 'Post the payment'),
                                               ledger_conn('release', 'Release the encumbrance on rejection / cancel'),
                                               ('ifms.duplicateBill', 'a', 'G-PFMS', 'Duplicate / similar claim check'),
                                               ('registry.party', 'a', 'G-REGISTRY', 'Payee golden record (sangam-registry party registry, GA-12)'),
                                               ('pfms.payment', 'c', 'G-PFMS', 'Payment file (PFMS)'),
                                               ('rail.ekuber', 'c', 'G-RBI', 'RBI e-Kuber payment'),
                                               ('rail.agencyBank', 'c', 'G-BANK', 'Agency-bank payment'),
                                               ('rail.stateBank', 'c', 'G-BANK', 'State-owned bank (cheque / draft)'),
                                               ('rail.cheque', 'b', 'G-TREASURY', 'Treasury cheque'),
                                               ('rail.loc', 'b', 'G-TREASURY', 'Letter of credit / CCL-DCL'),
                                               ('rail.fcy', 'c', 'G-BANK', 'Foreign-currency advice')],
              depends=['sangam.core', 'gov.ifms.pao-chain'], main=['sangam-ifms-bill'])
    p = pk.proc('sangam-ifms-bill', 'Bill by catalogue type', '',
                start={'materialFields': 'billType,headOfAccount,amount,payee,payeeAccount,sanctionRef'})
    s = p.root
    st = s.start('bl_start', 'Bill drafted by the DDO', form='ifms-bill/draft')
    p.forms.append(dict(key='ifms-bill/draft', name='Bill | बिल', decision=None,
                        fields=['billType*', 'headOfAccount*', 'grantNo', 'chargedVoted', 'amount*', 'payee*', 'payeeAccount*',
                                'ifsc', 'sanctionRef', 'claimPeriod', 'ddoCode*', 'documents'], doc=None))
    d = s.setv('bl_defaults', 'Configuration', defaults='billType=CONTINGENT;headOfAccount=2054-00-001-01-00-13;amount=0;'
               'payee=;payeeAccount=;sanctionRef=;ddoCode=DDO-1;ledgerId=BUDGET;fundsAvailable=999999999999.0;'
               'docValid=true;clsValid=true;dupDuplicate=false;payeeMultiple=false;partyId=;ifsc=;docPaymentRail=DEFAULT;docApprovalChain=PAO_3;'
               'paoOutcome=PASSED;caseType=BILL;routingDecisionKey=ifms-bill-authority;scrutinySeat=DDO-1;'
               'executionStep=pay;reconcileRequired=true;outputTemplate=payment-advice;appealAllowed=false;'
               'encumbranceLedger=BUDGET;releaseConnector=ledger.release')
    bind = s.conn('bl_profile', 'Bind profile version', 'config.profile', 'profile.binding', 'query', 'billType', 'prof')
    typ = s.conn('bl_type', 'Bill type from the catalogue', 'config.documentType', 'documentType.get', 'query', 'billType', 'doc')
    cls = s.conn('bl_cls', 'Validate head of account', 'config.classification', 'classification.validate', 'query',
                 'headOfAccount', 'cls')
    chk = s.conn('bl_check', 'Check bill against its type', 'config.documentType', 'documentType.check', 'query',
                 'billType,headOfAccount,amount,sanctionRef,claimPeriod', 'doc')
    dup = s.conn('bl_dup', 'Duplicate / similar claim', 'ifms.duplicateBill', 'check', 'query', 'payee,amount,claimPeriod', 'dup')
    payee = s.conn('bl_payee', 'Payee golden record (party registry)', 'registry.party', 'payee.resolve', 'query',
                   'partyId,payee,payeeAccount,ifsc', 'payee')
    s.seq(st, d, bind, typ, cls, chk, dup, payee)
    vg = s.xgw('bl_validGw', 'Valid?', policy='priority')  # ADR-D-25: conditions can both be true; explicit precedence
    s.flow(payee, vg)
    s.flow(vg, reject_with(p, s, 'bl_rjInvalid', 'BILL_INVALID'), '!docValid || !clsValid', 'invalid (findings cite the rule)', priority=1)
    s.flow(vg, reject_with(p, s, 'bl_rjDup', 'DUPLICATE_CLAIM'), 'dupDuplicate', 'duplicate', priority=2)
    fa = s.conn('bl_funds', 'Funds at the control dimension', 'ledger.available', 'available', 'query',
                'ledgerId,headOfAccount,ddoCode', 'funds')
    pr = s.user('bl_payeeReview', 'Confirm payee: account held by several parties | आदाता की पुष्टि', 'PAO-1',
                form='ifms-bill/payee-review', decision=('payeeDecision', ['approve', 'reject']),
                fields=['payee', 'payeeAccount', 'remarks*'], sod=True)
    s.flow(vg, pr, 'payeeMultiple', 'several beneficiaries on the account', priority=3)
    pg = s.xgw('bl_payeeGw')
    s.flow(pr, pg)
    s.flow(pg, fa, "payeeDecision == 'approve'", 'confirmed')
    s.flow(pg, reject_with(p, s, 'bl_rjPayee', 'PAYEE_NOT_CONFIRMED'), None, 'rejected', default=True)
    s.flow(vg, fa, None, 'valid', default=True)
    fg = s.xgw('bl_fundsGw', 'Funds available?')
    s.flow(fa, fg)
    hold = s.catch_msg('bl_hold', 'Held for budget (released on allotment)', 'sangam.ifms.fundsAllotted')
    s.flow(fg, hold, 'fundsAvailable < amount', 'pending for budget')
    s.flow(hold, fa)
    enc = s.conn('bl_encumber', 'Encumber', 'ledger.encumber', 'encumber', 'submit', 'ledgerId,headOfAccount,ddoCode,amount',
                 'enc', step='encumber')
    s.flow(fg, enc, None, 'available', default=True)
    rail = s.rule('bl_rail', 'Payment rail (profile / tenant DMN)', 'ifms-rail-connector')
    chain = s.call('bl_chain', 'PAO / treasury chain', 'sangam-ifms-pao-chain')
    s.seq(enc, rail, chain)
    rel = s.conn('bl_release', 'Release encumbrance', 'ledger.release', 'release', 'submit',
                 'ledgerId,headOfAccount,ddoCode,amount', 'rel', step='release')
    rev = s.user('bl_revise', 'Reply to objection / correct | आपत्ति का उत्तर / सुधार', '${initiatorSeat}',
                 form='ifms-bill/revise', decision=('reviseDecision', ['resubmit', 'withdraw']),
                 fields=['objectionMemo', 'remarks*', 'documents'])
    ex = core(s, 'bl_pay', 'Payment through the rail (core execution)', 'execution')
    outcome(s, 'bl_chainGw', 'paoOutcome', {'PASSED': ex}, rel)
    s.flow(chain, 'bl_chainGw')
    s.flow(rel, rev)
    rg = s.xgw('bl_revGw')
    s.flow(rev, rg)
    ver = s.svc('bl_version', 'New bill version (same case, GP-50)', C['CASE'], {'op': 'RESUBMIT'})
    s.flow(rg, ver, "reviseDecision == 'resubmit'", 'resubmit')
    s.flow(ver, typ)
    s.flow(rg, withdraw(p, s, 'bl_wd'), None, 'withdraw', default=True)
    con = s.conn('bl_consume', 'Post payment (consume)', 'ledger.consume', 'consume', 'submit',
                 'ledgerId,headOfAccount,ddoCode,amount,operationId', 'con', step='consume')
    cl = core(s, 'bl_close', 'Payment advice and voucher (core closure)', 'closure')
    fail = s.notify('bl_payFailed', 'Payment failed: suspense, reissue with a new operation', 'ifms-payment-failed', 'inapp',
                    'initiatorSeat')
    outcome(s, 'bl_exGw', 'executionOutcome', {'DONE': con}, fail)
    s.flow(ex, 'bl_exGw')
    reissue = s.setv('bl_reissue', 'Reissue tag', values='reissueTag=true')
    s.seq(fail, reissue, ex)
    s.seq(con, cl, end_ok(p, s, 'bl_end'))
    s.side_exits('bl')
    pk.dmn('ifms-rail-connector', 'Payment rail to connector', 'FIRST',
           [('Payment rail (catalogue / profile)', 'docPaymentRail', 'string')],
           [('executionConnector', 'Execution connector', 'string')],
           [([q('CENTRAL_BANK_SETTLEMENT')], [q('rail.ekuber')], 'RBI e-Kuber'),
            ([q('AGENCY_BANK')], [q('rail.agencyBank')], 'agency bank CMP'),
            ([q('STATE_OWNED_BANK')], [q('rail.stateBank')], 'state-owned bank (Sikkim)'),
            ([q('TREASURY_CHEQUE')], [q('rail.cheque')], 'treasury cheque'),
            ([q('LETTER_OF_CREDIT')], [q('rail.loc')], 'LoC / CCL-DCL'),
            ([q('FOREIGN_CURRENCY_ADVICE')], [q('rail.fcy')], 'missions'),
            (['-'], [q('pfms.payment')], 'DEFAULT: PFMS (Union); States override this table per tenant')],
           doc='IFS-053 rail per state / unit / bill type. State defaults are tenant overrides of this table.')
    pk.override('SK', 'ifms-rail-connector', 'Payment rail to connector (Sikkim)', 'FIRST',
                [('Payment rail', 'docPaymentRail', 'string')], [('executionConnector', 'Execution connector', 'string')],
                [(['-'], [q('rail.stateBank')], 'State Bank of Sikkim, cheques (IFS-SK-001)')])
    pk.override('HP', 'ifms-rail-connector', 'Payment rail to connector (Himachal Pradesh)', 'FIRST',
                [('Payment rail', 'docPaymentRail', 'string')], [('executionConnector', 'Execution connector', 'string')],
                [([q('LETTER_OF_CREDIT')], [q('rail.loc')], 'cheque-drawing divisions'),
                 ([q('TREASURY_CHEQUE')], [q('rail.cheque')], 'cheque-drawing DDOs (IFS-HP-003)'),
                 (['-'], [q('rail.ekuber')], 'RBI e-payments (IFMS-RESEARCH-DELTA: all states except Sikkim)')])
    authority(pk, 'ifms-bill-authority', [('<= 1000000', 'DDO', 'DDO passes bills within the sanction'),
                                          ('-', 'HOO', 'Head of Office above 10 lakh [U: department order]')],
              'Bill authority. Delegation comes from the department order; replace by tenant override.')
    seeds(pk, 'PAO-OFFICE', PAO_SEATS, 'bl')
    pk.accept('BL-1', 'A bill mixing two major heads is refused with the rule cited', ['IFC-042'],
              'GOI profile, bill type CONTINGENT', 'lines carry 2054 and 2052', 'BILL_INVALID with RPR SI 2.2(5)(g)',
              {'docValid': False})
    for slug, fam, default_type, en, hi, ids in FAMILIES:
        fp = pk.proc('sangam-ifms-bill-' + slug, en, '', start={'materialFields': 'billType,amount'})
        fs = fp.root
        a = fs.start('bf_' + slug.replace('-', '') + '_start', en + ' started', form='ifms-bill/' + slug)
        fp.forms.append(dict(key='ifms-bill/' + slug, name=en + ' | ' + hi, decision=None,
                             fields=['billType*', 'headOfAccount*', 'amount*', 'payee*', 'payeeAccount', 'sanctionRef',
                                     'ddoCode*', 'documents'], doc=None))
        b = fs.setv('bf_' + slug.replace('-', '') + '_family', 'Family defaults',
                    values='billFamily=' + fam, defaults='billType=' + default_type)
        c = fs.call('bf_' + slug.replace('-', '') + '_bill', 'Bill (catalogue-driven)', 'sangam-ifms-bill')
        fs.seq(a, b, c, fs.end('bf_' + slug.replace('-', '') + '_end', 'Done'))


# ================================================================================================ budget allocation chain
def allocation():
    pk = Pack('gov.ifms.budget-allocation', 'ifms-budget-allocation', 'Budget allocation, release, authorisation, sub-allotment',
              'बजट आवंटन, विमुक्ति, प्राधिकार, उप-आवंटन', 'IFMS',
              'Distribution of the grant down the N-stage chain the profile defines (appropriation -> allocation -> '
              'release / BRO -> authorisation (AIE, BEAMS slip, e-Vitran) -> DDO sub-allotment), each stage a ledger '
              'DISTRIBUTE guarded under row locks; held bills are released when the allotment arrives; ceilings and '
              'freeze orders are ledger policies.',
              ['IFC-046', 'IFC-047', 'IFC-056', 'IFC-057', 'IFC-071', 'IFC-072', 'IFS-040', 'IFS-041', 'IFS-042',
               'IFS-032', 'IFS-KA-002', 'IFS-JK-001', 'IFS-MN-002', 'IFS-NL-001', 'IFS-TG-006', 'IFS-CT-001', 'IFS-TN-005',
               'GA-03', 'GP-01', 'GP-11'] + SIDE,
              connectors=CONFIG_CONNECTORS + [ledger_conn('distribute', 'Pass the limit to the next stage'),
                                               ledger_conn('releaseHolds', 'Release bills held for budget')])
    p = pk.proc('sangam-ifms-budget-allocation', 'Budget distribution order', '',
                start={'materialFields': 'stage,headOfAccount,holder,amount'})
    s = p.root
    st = s.start('ba_start', 'Distribution proposed', form='ifms-budget-allocation/proposal')
    p.forms.append(dict(key='ifms-budget-allocation/proposal', name='Distribution of budget | बजट वितरण', decision=None,
                        fields=['fiscalYear*', 'stage*', 'headOfAccount*', 'holder*', 'amount*', 'remarks'], doc=None))
    d = s.setv('ba_defaults', 'Configuration', defaults='ledgerId=BUDGET;stage=RELEASE;amount=0;holder=DDO-1;'
               'caseType=BUDGET_DISTRIBUTION;routingDecisionKey=ifms-allocation-authority;scrutinySeat=PRAO-1;'
               'executionConnector=ledger.distribute;executionStep=distribute;reconcileRequired=false;'
               'outputTemplate=budget-distribution-order;appealAllowed=false')
    scr = core(s, 'ba_scrutiny', 'Budget section scrutiny (core)', 'scrutiny')
    san = core(s, 'ba_sanction', 'Approval (core)', 'sanction')
    ex = core(s, 'ba_post', 'Post the distribution in the ledger (core execution)', 'execution')
    hold = s.conn('ba_holds', 'Release held bills', 'ledger.releaseHolds', 'releaseHolds', 'submit', 'ledgerId,holder,headOfAccount',
                  'holds', step='holds')
    cl = core(s, 'ba_close', 'Order and closure (core)', 'closure')
    rej = rejection(p, s, 'ba_rj')
    s.seq(st, d, scr)
    outcome(s, 'ba_scrGw', 'scrutinyOutcome', {'ACCEPTED': san}, rej)
    s.flow(scr, 'ba_scrGw')
    outcome(s, 'ba_sanGw', 'sanctionOutcome', {'SANCTIONED': ex, 'RETURNED': scr}, rej)
    s.flow(san, 'ba_sanGw')
    outcome(s, 'ba_exGw', 'executionOutcome', {'DONE': hold}, rej)
    s.flow(ex, 'ba_exGw')
    s.seq(hold, cl, end_ok(p, s, 'ba_end'))
    s.side_exits('ba')
    authority(pk, 'ifms-allocation-authority', [('-', 'PR_AO', 'Pr.AO approves DDO-wise allocation entered by the PAO (CAM 2.2.4)')],
              'Allocation approval; a State replaces this table by tenant override.')
    seeds(pk, 'PAO-OFFICE', PAO_SEATS, 'ba')


# ================================================================================================ re-appropriation
def reappropriation():
    pk = Pack('gov.ifms.reappropriation', 'ifms-reappropriation', 'Re-appropriation (rule set)', 'पुनर्विनियोजन (नियम समूह)',
              'IFMS', 'Re-appropriation between lines of one grant and section: restrictions as hard rules with citations '
              '(no charged <-> voted, no capital <-> revenue, no NS/NIS, not from a head augmented by a supplementary or a '
              'CF-advance activity, works only with AA/TS), the Q1 ban, object-group monetary limits, Finance Ministry / '
              'Budget Division cases, C&AG for secret service, Parliament reporting; FA consultation mandatory; posted as a '
              'ledger TRANSFER with the same constraints enforced by the ledger. DFPR 2024 defaults; States override the DMN.',
              ['IFC-061', 'IFC-062', 'IFC-063', 'IFC-064', 'IFC-065', 'IFC-066', 'IFC-067', 'IFC-068', 'IFC-007', 'IFC-035',
               'IFC-036', 'IFC-222', 'IFC-239', 'IFC-240', 'IFS-030', 'GA-16', 'GA-17', 'GP-01', 'GP-32', 'GP-37'] + SIDE,
              connectors=CONFIG_CONNECTORS + [ledger_conn('available', 'Savings in the source line'),
                                               ledger_conn('transfer', 'Post the re-appropriation'),
                                               ('referral.send', 'b', '', 'Finance Ministry / Budget Division referral')])
    p = pk.proc('sangam-ifms-reappropriation', 'Re-appropriation', '', start={'materialFields': 'fromHead,toHead,amount'})
    s = p.root
    st = s.start('ra_start', 'Proposal', form='ifms-reappropriation/proposal')
    p.forms.append(dict(key='ifms-reappropriation/proposal', name='Re-appropriation proposal | पुनर्विनियोजन प्रस्ताव',
                        decision=None, fields=['fiscalYear*', 'fromHead*', 'toHead*', 'amount*', 'objectGroup*', 'reappFlags',
                                               'percentOfBe', 'beAmount', 'justification*', 'documents'], doc=None))
    d = s.setv('ra_defaults', 'Configuration', defaults='ledgerId=BUDGET;amount=0;objectGroup=OTHER_15CR;quarter=2;'
               'chargedToVoted=false;capitalRevenue=false;betweenGrants=false;newService=false;fromSupplementary=false;'
               'fromCfAdvance=false;worksWithoutAaTs=false;secretServicePercent=0;mofCase=false;percentOfBe=0;'
               'afterLastBatch=false;nerTechnical=false;fundsAvailable=999999999999.0;faSeat=FA-1;'
               'caseType=REAPPROPRIATION;routingDecisionKey=ifms-reapp-authority;powersDecisionKey=ifms-reapp-powers;scrutinySeat=PRAO-1;'
               'referToTenant=finance;referralQuestion=Concurrence for re-appropriation;executionConnector=ledger.transfer;'
               'executionStep=transfer;reconcileRequired=false;outputTemplate=reappropriation-order;appealAllowed=false')
    sav = s.conn('ra_savings', 'Savings in the source line', 'ledger.available', 'available', 'query', 'ledgerId,fromHead', 'funds')
    rules = s.rule('ra_rules', 'Re-appropriation rules (DMN, cited)', 'ifms-reapp-rules')
    s.seq(st, d, sav, rules)
    g = s.xgw('ra_allowedGw', 'Allowed?', policy='priority')  # ADR-D-25: conditions can both be true; explicit precedence
    s.flow(rules, g)
    s.flow(g, reject_with(p, s, 'ra_rjRule', 'REAPP_RULE'), "reappAllowed == false", 'refused (rule cited)', priority=1)
    s.flow(g, reject_with(p, s, 'ra_rjSav', 'SAVINGS_NOT_AVAILABLE'), 'fundsAvailable < amount', 'no savings', priority=2)
    e1, x1 = task(s, 'ra_fa', 'FA consultation | वित्तीय सलाहकार से परामर्श', 'faSeat', 'ifms-reappropriation/fa',
                  ('faDecision', ['faConcur', 'faNotConcur']), ['remarks*'], '5WD', sod=True)
    s.flow(g, e1, None, 'allowed', default=True)
    fg = s.xgw('ra_faGw')
    s.flow(x1, fg)
    s.flow(fg, reject_with(p, s, 'ra_rjFa', 'FA_NOT_CONCURRED'), "faDecision == 'faNotConcur'", 'not concurred')
    mg = s.xgw('ra_mofGw', 'Finance Ministry case?')
    s.flow(fg, mg, None, 'concurred', default=True)
    ref = core(s, 'ra_mof', 'Budget Division / DoE referral (core)', 'referral')
    san = core(s, 'ra_sanction', 'Sanction by CAA (core)', 'sanction')
    s.flow(mg, ref, 'mofCase', 'MoF approval needed')
    s.flow(mg, san, None, 'within delegation', default=True)
    cg = s.xgw('ra_concurGw')
    s.flow(ref, cg)
    s.flow(cg, san, "referralDecision == 'faConcur'", 'approved')
    s.flow(cg, reject_with(p, s, 'ra_rjMof', 'MOF_NOT_APPROVED'), None, 'not approved', default=True)
    ex = core(s, 'ra_post', 'Post TRANSFER in the ledger (core execution)', 'execution')
    rej = rejection(p, s, 'ra_rj')
    outcome(s, 'ra_sanGw', 'sanctionOutcome', {'SANCTIONED': ex}, rej)
    s.flow(san, 'ra_sanGw')
    rep = s.setv('ra_report', 'Parliament report list (if reportable)', values='parliamentListed=@parliamentReport')
    cl = core(s, 'ra_close', 'Order to Pr.AO / PAO and closure (core)', 'closure')
    outcome(s, 'ra_exGw', 'executionOutcome', {'DONE': rep}, rej)
    s.flow(ex, 'ra_exGw')
    s.seq(rep, cl, end_ok(p, s, 'ra_end'))
    s.side_exits('ra')
    R = 'DFPR 2024 '
    pk.dmn('ifms-reapp-rules', 'Re-appropriation rules (DFPR 2024 Rule 10 and GoI decisions)', 'FIRST',
           [('Charged to voted', 'chargedToVoted', 'boolean'), ('Capital to revenue', 'capitalRevenue', 'boolean'),
            ('Between grants', 'betweenGrants', 'boolean'), ('New service', 'newService', 'boolean'),
            ('From supplementary head', 'fromSupplementary', 'boolean'), ('From CF-advance activity', 'fromCfAdvance', 'boolean'),
            ('Works without AA/TS', 'worksWithoutAaTs', 'boolean'), ('Quarter', 'quarter', 'integer'),
            ('NER technical', 'nerTechnical', 'boolean'), ('Object group', 'objectGroup', 'string'), ('Amount', 'amount', 'double'),
            ('Increase % of BE', 'percentOfBe', 'double'), ('Secret service %', 'secretServicePercent', 'double')],
           [('reappAllowed', 'Allowed', 'boolean'), ('mofCase', 'Finance Ministry approval', 'boolean'),
            ('parliamentReport', 'Report to Parliament', 'boolean'), ('ruleRef', 'Rule', 'string')],
           [(['true', '-', '-', '-', '-', '-', '-', '-', '-', '-', '-', '-', '-'], ['false', 'false', 'false', q(R + 'r.10(4)')], 'charged <-> voted'),
            (['-', 'true', '-', '-', '-', '-', '-', '-', '-', '-', '-', '-', '-'], ['false', 'false', 'false', q(R + 'r.10(6)')], 'capital <-> revenue'),
            (['-', '-', 'true', '-', '-', '-', '-', '-', '-', '-', '-', '-', '-'], ['false', 'false', 'false', q(R + 'r.10(5)')], 'between grants'),
            (['-', '-', '-', 'true', '-', '-', '-', '-', '-', '-', '-', '-', '-'], ['false', 'false', 'false', q(R + 'r.10(1)')], 'new service'),
            (['-', '-', '-', '-', 'true', '-', '-', '-', '-', '-', '-', '-', '-'], ['false', 'false', 'false', q(R + 'r.10(7)')], 'head augmented by supplementary'),
            (['-', '-', '-', '-', '-', 'true', '-', '-', '-', '-', '-', '-', '-'], ['false', 'false', 'false', q(R + 'r.10(8)')], 'CF advance activity'),
            (['-', '-', '-', '-', '-', '-', 'true', '-', '-', '-', '-', '-', '-'], ['false', 'false', 'false', q(R + 'r.10(3)')], 'works without AA/TS'),
            (['-', '-', '-', '-', '-', '-', '-', '1', 'false', '-', '-', '-', '-'], ['true', 'true', 'false', q('DoE OM 01-04-2024: Q1 needs MoF')], 'Q1 ban'),
            (['-', '-', '-', '-', '-', '-', '-', '-', '-', q('EST_2CR'), '> 20000000', '-', '-'], ['true', 'true', 'false', q('GoI decision (1) para 3(v): 2 cr')], 'establishment group above 2 cr'),
            (['-', '-', '-', '-', '-', '-', '-', '-', '-', q('NONEST_5CR'), '> 50000000', '-', '-'], ['true', 'true', 'false', q('GoI decision (1) para 3(v): 5 cr')], 'group above 5 cr'),
            (['-', '-', '-', '-', '-', '-', '-', '-', '-', q('OTHER_15CR'), '> 150000000', '-', '-'], ['true', 'true', 'false', q('GoI decision (1) para 3(v): 15 cr')], 'other above 15 cr'),
            (['-', '-', '-', '-', '-', '-', '-', '-', '-', '-', '> 1000000000', '-', '-'], ['true', 'false', 'true', q('GoI decision (1) para 4: > 100 cr')], 'Parliament: above 100 cr'),
            (['-', '-', '-', '-', '-', '-', '-', '-', '-', '-', '-', '> 20', '-'], ['true', 'false', 'true', q('GoI decision (1) para 4: > 20% of BE')], 'Parliament: above 20% of BE'),
            (['-', '-', '-', '-', '-', '-', '-', '-', '-', '-', '-', '-', '>= 25'], ['true', 'true', 'false', q(R + 'r.10(10)(v): C&AG too')], 'secret service >= 25%'),
            (['-', '-', '-', '-', '-', '-', '-', '-', '-', '-', '-', '-', '-'], ['true', 'false', 'false', q(R + 'r.10(9)')], 'within CAA powers')],
           doc='FIRST hit, ordered: refusals, then Finance Ministry cases, then Parliament reporting, then within CAA powers. Thresholds come from the GOI profile budgetControl.reappropriation; a State deploys its own '
               'table as a tenant override.')
    authority(pk, 'ifms-reapp-authority', [('-', 'CAA', 'Secretary as Chief Accounting Authority (DFPR r.10(9)); non-delegable (r.12(2))')],
              'Re-appropriation power is not re-delegable (IFC-007).')
    # DFPR 2024 r.10(9) (corpus S03, IFC-062, P): the CAA re-appropriates within the grant; the cases that need the
    # Finance Ministry, the C&AG or a report to Parliament are decided by ifms-reapp-rules, not by a money ceiling.
    ifms_powers(pk, 'ifms-reapp-powers', 'REAPPROPRIATION',
                [('CAA', None, 'CAA re-appropriation power, DFPR 2024 r.10(9); non-delegable r.12(2); '
                               'Finance Ministry cases by ifms-reapp-rules (r.10(10))')])
    seeds(pk, 'PAO-OFFICE', PAO_SEATS, 'ra')


# ================================================================================================ BE / RE, supplementary, surrender
def formulation():
    pk = Pack('gov.ifms.budget-formulation', 'ifms-budget-formulation', 'Budget estimates (BE / RE)', 'बजट अनुमान (बीई / आरई)',
              'IFMS', 'Budget circular to divisions, estimates per head (BE next year, RE current year, actuals), FA '
              'scrutiny, Secretary approval, submission to the budget system (adapter), then the approved detailed demands '
              'loaded into the ledger as APPROPRIATION (ALLOCATE).',
              ['IFC-043', 'IFC-044', 'IFC-045', 'IFC-046', 'IFC-052', 'IFC-053', 'IFC-054', 'IFC-055', 'IFS-005', 'IFS-009',
               'IFS-CT-005', 'IFS-UP-002', 'IFS-DL-005', 'GA-29'] + SIDE,
              connectors=CONFIG_CONNECTORS + [ledger_conn('allocate', 'Load the approved appropriation'),
                                               ('budget.submit', 'b', 'G-BUDGET', 'Budget information system (UBIS / state system)')])
    p = pk.proc('sangam-ifms-budget-formulation', 'BE / RE preparation', '', start={'materialFields': 'fiscalYear,beAmount,reAmount'})
    s = p.root
    st = s.start('bf_start', 'Budget circular issued', form='ifms-budget-formulation/estimates')
    p.forms.append(dict(key='ifms-budget-formulation/estimates', name='Budget estimates | बजट अनुमान', decision=None,
                        fields=['fiscalYear*', 'headOfAccount*', 'beAmount*', 'reAmount*', 'actuals', 'justification*'], doc=None))
    d = s.setv('bf_defaults', 'Configuration', defaults='beAmount=0;reAmount=0;amount=0;faSeat=FA-1;caaSeat=SECY-1;'
               'caseType=BUDGET_ESTIMATE;routingDecisionKey=ifms-formulation-authority;powersDecisionKey=ifms-formulation-powers;scrutinySeat=PRAO-1;'
               'executionConnector=budget.submit;executionStep=submit;reconcileRequired=false;outputTemplate=budget-statement;'
               'appealAllowed=false;ledgerId=BUDGET')
    e1, x1 = task(s, 'bf_fa', 'FA scrutiny of estimates | अनुमानों की वित्तीय सलाहकार द्वारा जाँच', 'faSeat',
                  'ifms-budget-formulation/fa', ('faDecision', ['faConcur', 'faNotConcur']), ['remarks*'], '10WD')
    amt = s.setv('bf_amount', 'Amount for the power check', values='amount=@beAmount')
    san = core(s, 'bf_sanction', 'Secretary approval (core)', 'sanction')
    ex = core(s, 'bf_submit', 'Submit to the budget system (core execution)', 'execution')
    load = s.catch_msg('bf_voted', 'Appropriation Act / demands voted', 'sangam.ifms.appropriationVoted')
    al = s.conn('bf_load', 'Load appropriation (ledger ALLOCATE)', 'ledger.allocate', 'allocate', 'submit',
                'ledgerId,headOfAccount,beAmount,fiscalYear', 'alloc', step='appropriation')
    cl = core(s, 'bf_close', 'Closure (core)', 'closure')
    rej = rejection(p, s, 'bf_rj')
    s.seq(st, d, e1)
    fg = s.xgw('bf_faGw')
    s.flow(x1, fg)
    s.flow(fg, st, "faDecision == 'faNotConcur'", 'revise estimates')
    s.flow(fg, amt, None, 'concurred', default=True)
    s.flow(amt, san)
    outcome(s, 'bf_sanGw', 'sanctionOutcome', {'SANCTIONED': ex}, rej)
    s.flow(san, 'bf_sanGw')
    outcome(s, 'bf_exGw', 'executionOutcome', {'DONE': load}, rej)
    s.flow(ex, 'bf_exGw')
    s.seq(load, al, cl, end_ok(p, s, 'bf_end'))
    s.side_exits('bf')
    authority(pk, 'ifms-formulation-authority', [('-', 'CAA', 'Secretary approves the department estimates')], '')
    ifms_powers(pk, 'ifms-formulation-powers', 'BUDGET_ESTIMATE',
                [('CAA', None, 'Secretary approves the department estimates (pack default)')],
                unverified='the approving authority of the estimates and the absence of a money ceiling are not read '
                           'from the rule text (corpus IFC-043 to IFC-053 are not verified for powers); confirm from the '
                           'budget circular / delegation order and deploy it as a tenant DMN override')
    seeds(pk, 'PAO-OFFICE', PAO_SEATS, 'bf')


def supplementary():
    pk = Pack('gov.ifms.supplementary', 'ifms-supplementary', 'Supplementary demands, new service, Contingency Fund',
              'अनुपूरक माँगें, नई सेवा, आकस्मिकता निधि', 'IFMS',
              'Supplementary proposal in the batch the budget division calls (cash / technical / token), new-service '
              'determination by DMN (prior Parliament approval or report with the next batch), Contingency Fund advance with '
              'recoupment by supplementary, ledger ADJUST on approval.',
              ['IFC-048', 'IFC-049', 'IFC-050', 'IFC-051', 'IFC-073', 'GA-03'] + SIDE,
              connectors=CONFIG_CONNECTORS + [ledger_conn('adjust', 'Add the supplementary to the line')])
    p = pk.proc('sangam-ifms-supplementary', 'Supplementary demand', '', start={'materialFields': 'headOfAccount,amount,supplementaryKind'})
    s = p.root
    st = s.start('sd_start', 'Proposal', form='ifms-supplementary/proposal')
    p.forms.append(dict(key='ifms-supplementary/proposal', name='Supplementary proposal | अनुपूरक प्रस्ताव', decision=None,
                        fields=['fiscalYear*', 'headOfAccount*', 'amount*', 'supplementaryKind*', 'newServiceFlag', 'justification*'],
                        doc=None))
    d = s.setv('sd_defaults', 'Configuration', defaults='amount=0;supplementaryKind=CASH;newServiceFlag=false;faSeat=FA-1;'
               'ledgerId=BUDGET;caseType=SUPPLEMENTARY;routingDecisionKey=ifms-supplementary-authority;powersDecisionKey=ifms-supplementary-powers;scrutinySeat=PRAO-1;'
               'referToTenant=finance;referralQuestion=Supplementary demand;executionConnector=ledger.adjust;'
               'executionStep=adjust;reconcileRequired=false;outputTemplate=supplementary-order;appealAllowed=false')
    ns = s.rule('sd_newService', 'New service / instrument (DMN)', 'ifms-new-service')
    e1, x1 = task(s, 'sd_fa', 'FA examination | वित्तीय सलाहकार द्वारा परीक्षण', 'faSeat', 'ifms-supplementary/fa',
                  ('faDecision', ['faConcur', 'faNotConcur']), ['remarks*'], '5WD')
    cf = s.xgw('sd_cfGw', 'Urgent: Contingency Fund advance?')
    cfa = s.notify('sd_cf', 'Contingency Fund advance requested (recoup by supplementary)', 'cf-advance-requested', 'inapp', 'faSeat')
    ref = core(s, 'sd_dea', 'Budget division batch (core referral)', 'referral')
    san = core(s, 'sd_sanction', 'Approval (core)', 'sanction')
    ex = core(s, 'sd_post', 'Ledger ADJUST (core execution)', 'execution')
    cl = core(s, 'sd_close', 'Closure (core)', 'closure')
    rej = rejection(p, s, 'sd_rj')
    s.seq(st, d, ns, e1)
    fg = s.xgw('sd_faGw')
    s.flow(x1, fg)
    s.flow(fg, rej, "faDecision == 'faNotConcur'", 'not concurred')
    s.flow(fg, cf, None, 'concurred', default=True)
    s.flow(cf, cfa, "newServiceRoute == 'CONTINGENCY_FUND'", 'urgent')
    s.flow(cf, ref, None, 'batch', default=True)
    s.flow(cfa, ref)
    cg = s.xgw('sd_refGw')
    s.flow(ref, cg)
    s.flow(cg, san, "referralDecision == 'faConcur'", 'included')
    s.flow(cg, rej, None, 'not included', default=True)
    outcome(s, 'sd_sanGw', 'sanctionOutcome', {'SANCTIONED': ex}, rej)
    s.flow(san, 'sd_sanGw')
    outcome(s, 'sd_exGw', 'executionOutcome', {'DONE': cl}, rej)
    s.flow(ex, 'sd_exGw')
    s.flow(cl, end_ok(p, s, 'sd_end'))
    s.side_exits('sd')
    pk.dmn('ifms-new-service', 'New service / new instrument of service', 'FIRST',
           [('New service', 'newServiceFlag', 'boolean'), ('Amount', 'amount', 'double')],
           [('newServiceRoute', 'Route', 'string'), ('ruleRef', 'Rule', 'string')],
           [(['true', '-'], [q('PRIOR_PARLIAMENT'), q('DEA OM F.1(22)-B(AC)/2022 of 23-02-2024 [U limits]')], 'NS/NIS: limits are placeholders'),
            (['false', '-'], [q('BATCH'), q('ordinary supplementary')], '')])
    authority(pk, 'ifms-supplementary-authority', [('-', 'CAA', 'Secretary forwards to the budget division')], '')
    ifms_powers(pk, 'ifms-supplementary-powers', 'SUPPLEMENTARY',
                [('CAA', None, 'Secretary approves the proposal for the budget division batch (pack default)')],
                unverified='the approving authority of a supplementary proposal is not read from the rule text; '
                           'confirm from the delegation order and deploy it as a tenant DMN override')
    seeds(pk, 'PAO-OFFICE', PAO_SEATS, 'sd')


def surrender():
    pk = Pack('gov.ifms.surrender-lapse', 'ifms-surrender-lapse', 'Surrender of savings and year-end lapse',
              'बचत का समर्पण और वर्षांत व्यपगत', 'IFMS',
              'Savings foreseen -> controlling officer -> FA -> CAA -> Finance Ministry by the profile date (WITHDRAW up the '
              'stages); at the year-end cut-off (timer from the profile) the ledger closes the period: ANNUAL funds lapse, '
              'non-lapsable / multi-year funds and carried-forward commitments follow the ledger availability policy.',
              ['IFC-069', 'IFC-070', 'IFC-074', 'IFC-213', 'IFS-RJ-006', 'GA-03'] + SIDE,
              connectors=CONFIG_CONNECTORS + [ledger_conn('withdraw', 'Surrender up the stages'),
                                               ledger_conn('closePeriod', 'Year-end lapse / carry-forward')])
    p = pk.proc('sangam-ifms-surrender', 'Surrender of savings', '', start={'materialFields': 'headOfAccount,surrenderAmount'})
    s = p.root
    st = s.start('su_start', 'Savings foreseen', form='ifms-surrender-lapse/surrender')
    p.forms.append(dict(key='ifms-surrender-lapse/surrender', name='Surrender of savings | बचत का समर्पण', decision=None,
                        fields=['fiscalYear*', 'headOfAccount*', 'surrenderAmount*', 'holder*', 'remarks'], doc=None))
    d = s.setv('su_defaults', 'Configuration', defaults='surrenderAmount=0;amount=0;faSeat=FA-1;ledgerId=BUDGET;'
               'caseType=SURRENDER;routingDecisionKey=ifms-surrender-authority;powersDecisionKey=ifms-surrender-powers;scrutinySeat=PRAO-1;'
               'executionConnector=ledger.withdraw;executionStep=withdraw;reconcileRequired=false;outputTemplate=surrender-order;'
               'appealAllowed=false')
    amt = s.setv('su_amount', 'Amount', values='amount=@surrenderAmount')
    e1, x1 = task(s, 'su_fa', 'FA concurrence | वित्तीय सलाहकार की सहमति', 'faSeat', 'ifms-surrender-lapse/fa',
                  ('faDecision', ['surrenderNow', 'retainSavings']), ['remarks*'], '3WD')
    san = core(s, 'su_sanction', 'CAA approval (core)', 'sanction')
    ex = core(s, 'su_post', 'Ledger WITHDRAW (core execution)', 'execution')
    cl = core(s, 'su_close', 'Communicate to the accounts officer (core closure)', 'closure')
    rej = rejection(p, s, 'su_rj')
    s.seq(st, d, amt, e1)
    fg = s.xgw('su_faGw')
    s.flow(x1, fg)
    s.flow(fg, san, "faDecision == 'surrenderNow'", 'surrender')
    s.flow(fg, s.end('su_retained', 'Retained'), None, 'retain', default=True)
    outcome(s, 'su_sanGw', 'sanctionOutcome', {'SANCTIONED': ex}, rej)
    s.flow(san, 'su_sanGw')
    outcome(s, 'su_exGw', 'executionOutcome', {'DONE': cl}, rej)
    s.flow(ex, 'su_exGw')
    s.flow(cl, end_ok(p, s, 'su_end'))
    s.side_exits('su')
    y = pk.proc('sangam-ifms-year-end-lapse', 'Year-end lapse', '', start={'materialFields': 'fiscalYear'})
    s = y.root
    t = s.start('yl_timer', '31 March 23:59 IST', timer_cycle='0 59 23 31 3 ?', initiator=None)
    m = s.start('yl_manual', 'Run now (message)', msg='sangam.ifms.closeYearNow', initiator=None)
    j = s.xgw('yl_join')
    s.flow(t, j)
    s.flow(m, j)
    dd = s.setv('yl_defaults', 'Configuration', defaults='ledgerId=BUDGET;fiscalYear=current')
    cp = s.conn('yl_close', 'Close the period in the ledger', 'ledger.closePeriod', 'closePeriod', 'submit', 'ledgerId,fiscalYear',
                'close', step='close')
    rep = s.svc('yl_report', 'Lapse statement', C['DOC'], {'template': 'lapse-statement'})
    s.seq(j, dd, cp, rep, s.end('yl_end', 'Closed'))
    authority(pk, 'ifms-surrender-authority', [('-', 'CAA', 'surrender approved by the CAA, sent to MoF (GFR 62)')], '')
    ifms_powers(pk, 'ifms-surrender-powers', 'SURRENDER',
                [('CAA', None, 'surrender of savings to the Finance Ministry, GFR 2017 r.62(1)-(2) (pack default)')],
                unverified='GFR 62(1)-(2) (corpus IFC-069) requires the surrender but does not name the CAA as the '
                           'approving authority; confirm from the delegation order and deploy it as a tenant DMN override')
    seeds(pk, 'PAO-OFFICE', PAO_SEATS, 'su')


# ================================================================================================ grants / UC
def gia_uc():
    pk = Pack('gov.ifms.gia-uc', 'ifms-gia-uc', 'Grant-in-aid release and utilisation certificates',
              'सहायता अनुदान विमुक्ति और उपयोगिता प्रमाण पत्र', 'IFMS',
              'Release check by DMN (unspent balance, recurring grant 75 % rule, UC status, EAT use), FA, sanction, '
              'release bill through gov.ifms.bill, then a UC case per grant with the profile deadline (12 months after the '
              'year), reminders and blacklisting; UC form by grantee type (12-A / 12-B / 12-C); assets register flag.',
              ['IFC-145', 'IFC-146', 'IFC-147', 'IFC-148', 'IFC-149', 'IFC-150', 'IFC-151', 'IFC-152', 'IFC-153', 'IFC-154',
               'IFC-155', 'IFC-156', 'IFC-163', 'IFC-164', 'IFC-165', 'IFC-012', 'IFS-050', 'IFS-PB-002', 'IFS-CH-004',
               'IFS-AN-004', 'IFS-DH-003', 'GA-08', 'GP-05', 'GP-22'] + SIDE,
              connectors=CONFIG_CONNECTORS + [ledger_conn('available', 'Unspent balance and funds'),
                                               ('grantee.registry', 'a', 'G-PFMS', 'Implementing-agency register (EAT)')],
              depends=['sangam.core', 'gov.ifms.pao-chain', 'gov.ifms.bill'])
    p = pk.proc('sangam-ifms-gia-release', 'Grant release', '', start={'materialFields': 'grantee,amount,releasePercent'})
    s = p.root
    st = s.start('gr_start', 'Release proposed', form='ifms-gia-uc/release')
    p.forms.append(dict(key='ifms-gia-uc/release', name='Grant release | अनुदान विमुक्ति', decision=None,
                        fields=['grantee*', 'granteeType*', 'recurringGrant', 'amount*', 'releasePercent', 'ucStatus',
                                'unspentBalance', 'headOfAccount*', 'sanctionRef'], doc=None))
    d = s.setv('gr_defaults', 'Configuration', defaults='amount=0;releasePercent=0;recurringGrant=false;ucStatus=SUBMITTED;'
               'unspentBalance=0;granteeType=INSTITUTION;faSeat=FA-1;billType=GIA;caseType=GIA;'
               'routingDecisionKey=ifms-gia-authority;powersDecisionKey=ifms-gia-powers;scrutinySeat=PRAO-1;outputTemplate=grant-sanction;appealAllowed=false')
    chk = s.rule('gr_rules', 'Release rules (GFR 230(7), 238)', 'ifms-gia-release')
    s.seq(st, d, chk)
    g = s.xgw('gr_okGw', 'Release allowed?')
    s.flow(chk, g)
    s.flow(g, reject_with(p, s, 'gr_rjRule', 'GIA_RULE'), 'releaseAllowed == false', 'refused (rule cited)')
    e1, x1 = task(s, 'gr_fa', 'FA concurrence | वित्तीय सलाहकार की सहमति', 'faSeat', 'ifms-gia-uc/fa',
                  ('faDecision', ['faConcur', 'faNotConcur']), ['remarks*'], '5WD')
    s.flow(g, e1, None, 'allowed', default=True)
    fg = s.xgw('gr_faGw')
    s.flow(x1, fg)
    san = core(s, 'gr_sanction', 'Sanction (core)', 'sanction')
    rej = rejection(p, s, 'gr_rj')
    s.flow(fg, san, "faDecision == 'faConcur'", 'concur')
    s.flow(fg, rej, None, 'not concurred', default=True)
    bill_ = s.call('gr_bill', 'Release bill (catalogue GIA)', 'sangam-ifms-bill')
    outcome(s, 'gr_sanGw', 'sanctionOutcome', {'SANCTIONED': bill_}, rej)
    s.flow(san, 'gr_sanGw')
    uc = s.svc('gr_uc', 'Open the UC case (linked)', C['LINK'], {'processKey': 'sangam-ifms-uc', 'linkType': 'utilisation-certificate',
                                                                    'inherit': 'grantee,amount,granteeType'})
    s.seq(bill_, uc, end_ok(p, s, 'gr_end'))
    s.side_exits('gr')
    u = pk.proc('sangam-ifms-uc', 'Utilisation certificate', '', start={'materialFields': 'grantee,amount'})
    s = u.root
    a = s.start('uc_start', 'UC due', initiator=None)
    dd = s.setv('uc_defaults', 'Configuration', defaults='ucDue=P365D;granteeSeat=HOO-1;programmeSeat=FA-1;ucForm=GFR-12A')
    w = s.catch_msg('uc_received', 'UC submitted by the grantee', 'sangam.ifms.ucSubmitted')
    tm = s.catch_timer('uc_overdue', 'UC overdue (12 months after the year)', duration='P365D')
    eg = s.egw('uc_wait')
    s.seq(a, dd, eg)
    s.flow(eg, w)
    s.flow(eg, tm)
    e2, x2 = task(s, 'uc_review', 'Review UC | उपयोगिता प्रमाण पत्र की जाँच', 'programmeSeat', 'ifms-gia-uc/uc-review',
                  ('ucDecision', ['accepted', 'furtherAction']), ['ucForm', 'remarks*'], '10WD')
    s.flow(w, e2)
    e3, x3 = task(s, 'uc_default', 'UC not received: remind or blacklist | उपयोगिता प्रमाण पत्र अप्राप्त', 'programmeSeat',
                  'ifms-gia-uc/uc-default', ('defaultDecision', ['remind', 'blacklist']), ['remarks*'], '5WD')
    s.flow(tm, e3)
    rg = s.xgw('uc_defGw')
    s.flow(x3, rg)
    s.flow(rg, eg, "defaultDecision == 'remind'", 'remind')
    s.flow(rg, s.end('uc_blacklisted', 'Grantee blacklisted', listeners=[u.item_listener('REJECTED')]), None, 'blacklist', default=True)
    ag = s.xgw('uc_accGw')
    s.flow(x2, ag)
    s.flow(ag, end_ok(u, s, 'uc_end', 'UC accepted'), "ucDecision == 'accepted'", 'accepted')
    s.flow(ag, eg, None, 'further action', default=True)
    pk.dmn('ifms-gia-release', 'Grant release rules', 'FIRST',
           [('Recurring', 'recurringGrant', 'boolean'), ('Release % of sanction', 'releasePercent', 'double'),
            ('UC status', 'ucStatus', 'string'), ('Unspent balance', 'unspentBalance', 'double'), ('Amount', 'amount', 'double')],
           [('releaseAllowed', 'Allowed', 'boolean'), ('ruleRef', 'Rule', 'string')],
           [(['true', '> 75', 'not("AUDITED")', '-', '-'], ['false', q('GFR 2017 r.238(2): above 75% needs UC and audited statement')], ''),
            (['true', '-', 'not("PROVISIONAL","SUBMITTED","AUDITED")', '-', '-'], ['false', q('GFR 2017 r.238(2): provisional UC first')], ''),
            (['-', '-', '-', '> 0', '-'], ['true', q('GFR 2017 r.230(7): unspent balance considered (net release)')], 'warn'),
            (['-', '-', '-', '-', '-'], ['true', q('GFR 2017 r.230')], '')],
           doc='Thresholds from the GOI profile grants.seventyFivePercentRule; States override by tenant DMN.')
    authority(pk, 'ifms-gia-authority', [('-', 'CAA', 'department has full powers where scheme rules have MoF consent (DFPR r.17)')], '')
    # DFPR 2024 r.17 (corpus S03, IFC-085, P): full powers to departments for grants-in-aid where the scheme rules were
    # framed with Finance Ministry consent and the sanction carries a certificate to that effect.
    ifms_powers(pk, 'ifms-gia-powers', 'GIA',
                [('CAA', None, 'full powers for grants-in-aid under scheme rules framed with Finance Ministry consent, '
                               'DFPR 2024 r.17')])
    seeds(pk, 'PAO-OFFICE', PAO_SEATS, 'gr')


# ================================================================================================ SNA-SPARSH
def sna_sparsh():
    pk = Pack('gov.ifms.sna-sparsh', 'ifms-sna-sparsh', 'SNA-SPARSH just-in-time release', 'एसएनए-स्पर्श समय पर विमुक्ति',
              'IFMS', 'Mother sanction per SLS (one active, lapse 23:59 on 31 March IST) held as a ledger limit; state '
              'treasury pushes IA payment files with centre / state / top-up shares; daily sanction by the PD checker '
              '(DSC), Pr.AO payment authorisation, PAO pass, batch to RBI before the 15:00 cut-off (next business day '
              'after it), reissue tag on re-push, SLS reconciliation. 3-role chain by default, 7-role chain by profile.',
              ['IFC-157', 'IFC-158', 'IFC-159', 'IFC-160', 'IFC-161', 'IFC-242', 'IFC-144', 'IFS-011', 'IFS-012', 'IFS-013',
               'IFS-014', 'IFS-015', 'IFS-016', 'IFS-017', 'IFS-018', 'IFS-019', 'IFS-020', 'IFS-021', 'IFS-022', 'IFS-023',
               'IFS-024', 'IFS-035', 'IFS-051', 'IFS-052', 'IFS-029', 'IFS-RJ-003', 'IFS-MP-004', 'IFS-BR-004', 'IFS-HR-001',
               'IFS-KA-005', 'IFS-SK-003', 'IFS-WB-004', 'IFS-OD-007', 'IFS-OD-008', 'IFS-CT-002', 'IFS-JH-006',
               'IFS-AR-006', 'IFS-NL-005', 'GA-09'] + SIDE,
              connectors=CONFIG_CONNECTORS + [ledger_conn('available', 'Mother sanction balance'),
                                               ledger_conn('consume', 'Daily sanction against the mother sanction'),
                                               ('rail.ekuber', 'c', 'G-RBI', 'Batch to RBI'),
                                               ('pfms.childSanction', 'a', 'G-PFMS', 'Child sanction / DN-CN notifications')])
    p = pk.proc('sangam-ifms-sna-sparsh-release', 'SNA-SPARSH daily release', '',
                start={'materialFields': 'slsCode,amount,centreShare,stateShare'})
    s = p.root
    st = s.start('ss_start', 'IA payment file from the state treasury', form='ifms-sna-sparsh/claim')
    p.forms.append(dict(key='ifms-sna-sparsh/claim', name='SNA-SPARSH claim | एसएनए-स्पर्श दावा', decision=None,
                        fields=['slsCode*', 'motherSanctionNo*', 'amount*', 'centreShare*', 'stateShare*', 'documents'], doc=None))
    d = s.setv('ss_defaults', 'Configuration', defaults='amount=0;centreShare=0;stateShare=0;ledgerId=SNA;'
               'fundsAvailable=999999999999.0;pdCheckerSeat=FA-1;prAoSeat=PRAO-1;paoSeat=PAO-1;reissueTag=false;'
               'executionConnector=rail.ekuber;executionStep=rbiBatch;reconcileRequired=true;caseType=SNA_SPARSH;'
               'routingDecisionKey=ifms-sna-authority;outputTemplate=sna-release-advice;appealAllowed=false;chainId=SNA_SPARSH_3')
    ms = s.conn('ss_mother', 'Mother sanction balance (one active per SLS)', 'ledger.available', 'available', 'query',
                'ledgerId,slsCode,motherSanctionNo', 'funds')
    s.seq(st, d, ms)
    g = s.xgw('ss_msGw', 'Within mother sanction?')
    s.flow(ms, g)
    s.flow(g, reject_with(p, s, 'ss_rjMs', 'NO_ACTIVE_MOTHER_SANCTION'), 'fundsAvailable < amount', 'no / exhausted mother sanction')
    e1, x1 = task(s, 'ss_pd', 'Daily sanction (DSC) | दैनिक स्वीकृति (डीएससी)', 'pdCheckerSeat', 'ifms-sna-sparsh/daily',
                  ('pdDecision', ['signed', 'returnBill']), ['slsCode', 'amount', 'dscSerial'], '1WD')
    s.flow(g, e1, None, 'within', default=True)
    e2, x2 = task(s, 'ss_prao', 'Payment authorisation (DSC) | भुगतान प्राधिकार', 'prAoSeat', 'ifms-sna-sparsh/authorise',
                  ('praoDecision', ['signed', 'returnBill']), ['dscSerial', 'remarks'], '1WD', sod=True)
    e3, x3 = task(s, 'ss_pao', 'PAO pre-check and pass | पीएओ पूर्व-जाँच', 'paoSeat', 'ifms-sna-sparsh/pao',
                  ('paoDecision', ['passBill', 'returnBill']), ['dscSerial', 'remarks'], '1WD', sod=True)
    pg = s.xgw('ss_pdGw')
    s.flow(x1, pg)
    s.flow(pg, e2, "pdDecision == 'signed'", 'signed')
    ret = s.notify('ss_returned', 'Returned to the state (PD returns the file)', 'sna-returned', 'inapp', 'pdCheckerSeat')
    s.flow(pg, ret, None, 'returned', default=True)
    ag = s.xgw('ss_praoGw')
    s.flow(x2, ag)
    s.flow(ag, e3, "praoDecision == 'signed'", 'authorised')
    s.flow(ag, ret, None, 'returned', default=True)
    cut = s.svc('ss_cutoff', 'Processing day under the 15:00 cut-off', C['SLA'],
                {'op': 'DEADLINE', 'clock': 'snaCutoff', 'spec': '1WD'})
    cg = s.xgw('ss_paoGw')
    s.flow(x3, cg)
    s.flow(cg, cut, "paoDecision == 'passBill'", 'passed')
    s.flow(cg, ret, None, 'returned', default=True)
    ex = core(s, 'ss_batch', 'Batch to RBI (core execution)', 'execution')
    con = s.conn('ss_consume', 'Consume mother sanction', 'ledger.consume', 'consume', 'submit', 'ledgerId,slsCode,amount,operationId',
                 'con', step='consume')
    s.flow(cut, ex)
    fail = s.setv('ss_reissue', 'Re-push with reissue tag', values='reissueTag=true')
    outcome(s, 'ss_exGw', 'executionOutcome', {'DONE': con}, fail)
    s.flow(ex, 'ss_exGw')
    s.flow(fail, ex)
    cl = core(s, 'ss_close', 'Reconciliation per SLS and closure (core)', 'closure')
    s.seq(con, cl, end_ok(p, s, 'ss_end'))
    s.flow(ret, s.end('ss_endRet', 'Returned'))
    s.side_exits('ss')
    authority(pk, 'ifms-sna-authority', [('-', 'PR_AO', 'Pr.AO payment authorisation (CGA SOP 11-08-2025)')], '')
    seeds(pk, 'PAO-OFFICE', PAO_SEATS, 'ss')


# ================================================================================================ receipts
def receipts():
    pk = Pack('gov.ifms.receipts', 'ifms-receipts', 'Receipts: challan / e-receipt lifecycle and scroll reconciliation',
              'प्राप्तियाँ: चालान जीवनचक्र और स्क्रॉल मिलान', 'IFMS',
              'Challan (GRN / CPIN) generated with the receipt heads and apportionment rules of the profile, paid through '
              'the portal / bank (CIN / UTR), matched with the e-scroll or RBI credit notification, booked to the heads; '
              'unaccounted credits follow the SOP (trace, book or refund); short deposits rejected; card settlement '
              'within T+n; refund and defacement.',
              ['IFC-126', 'IFC-127', 'IFC-128', 'IFC-129', 'IFC-130', 'IFC-131', 'IFC-132', 'IFC-133', 'IFC-134', 'IFC-135',
               'IFC-136', 'IFC-137', 'IFC-139', 'IFC-141', 'IFS-027', 'IFS-028', 'IFS-055', 'IFS-RJ-008', 'IFS-PB-001',
               'IFS-JH-003', 'IFS-TN-004', 'IFS-WB-003', 'IFS-AP-006', 'IFS-UP-004', 'IFS-AS-005', 'IFS-MN-004', 'IFS-DL-003',
               'IFS-KL-008', 'IFS-MP-005', 'GA-11', 'GP-31', 'GP-55'],
              connectors=CONFIG_CONNECTORS + [('receipt.portal', 'b', 'G-PG', 'Challan / payment portal (Bharatkosh, GRAS, GRIPS ...)'),
                                               ('treasury.scroll', 'a', 'G-PFMS', 'Bank / RBI e-scroll'),
                                               ('receipt.refund', 'c', 'G-PG', 'Refund of a deposit')])
    p = pk.proc('sangam-ifms-receipt', 'Receipt (challan)', '', start={'materialFields': 'challanNo,receiptHead,amount'})
    s = p.root
    st = s.start('rc_start', 'Challan generated', form='ifms-receipts/challan')
    p.forms.append(dict(key='ifms-receipts/challan', name='Challan | चालान', decision=None,
                        fields=['receiptHead*', 'amount*', 'payee*', 'challanNo', 'documents'], doc=None))
    d = s.setv('rc_defaults', 'Configuration', defaults='amount=0;receiptDeskSeat=AAO-1;scrollMatch=MATCHED;scrollAmount=0')
    num = s.conn('rc_number', 'Challan number (series)', 'config.series', 'series.next', 'submit', 'receiptHead', 'chl', step='number')
    paid = s.catch_msg('rc_paid', 'Payment confirmed by the portal (CIN / UTR)', 'sangam.ifms.challanPaid')
    sc = s.conn('rc_scroll', 'Match with the e-scroll', 'treasury.scroll', 'match', 'query', 'challanNo,amount,utr', 'scroll')
    s.seq(st, d, num, paid, sc)
    g = s.xgw('rc_matchGw', 'Matched?')
    s.flow(sc, g)
    book = s.svc('rc_book', 'Receipt voucher', C['DOC'], {'template': 'receipt-voucher'})
    s.flow(g, book, "scrollMatch == 'MATCHED'", 'matched')
    e1, x1 = task(s, 'rc_resolve', 'Resolve receipt discrepancy | प्राप्ति विसंगति का निवारण', 'receiptDeskSeat',
                  'ifms-receipts/resolve', ('receiptDecision', ['book', 'refund', 'unaccounted']),
                  ['challanNo', 'utr', 'discrepancyCause*', 'remarks'], '3WD')
    s.flow(g, e1, None, 'short / unmatched', default=True)
    rg = s.xgw('rc_resGw')
    s.flow(x1, rg)
    s.flow(rg, book, "receiptDecision == 'book'", 'book')
    rf = s.conn('rc_refund', 'Refund', 'receipt.refund', 'refund', 'submit', 'challanNo,amount', 'refund', step='refund')
    s.flow(rg, rf, "receiptDecision == 'refund'", 'refund')
    s.flow(rg, s.end('rc_unacc', 'Unaccounted: SOP case'), None, 'unaccounted', default=True)
    s.flow(rf, s.end('rc_refunded', 'Refunded'))
    s.flow(book, end_ok(p, s, 'rc_end', 'Accounted'))
    seeds(pk, 'PAO-OFFICE', PAO_SEATS, 'rc')


# ================================================================================================ pension, GPF, advances
def gpf_advances():
    pk = Pack('gov.ifms.gpf', 'ifms-gpf', 'GPF advance, withdrawal and final payment', 'जीपीएफ अग्रिम, आहरण और अंतिम भुगतान',
              'IFMS', 'GPF advance / withdrawal by DMN (purpose, balance share, service), sanction within the profile SLA '
              '(15 days [U]), payment as a gov.ifms.bill of type GPF; final payment on retirement; subscription ceiling '
              'from the profile.',
              ['IFC-171', 'IFC-026', 'IFC-254', 'IFS-NL-002'] + SIDE, connectors=CONFIG_CONNECTORS,
              depends=['sangam.core', 'gov.ifms.pao-chain', 'gov.ifms.bill'])
    p = pk.proc('sangam-ifms-gpf', 'GPF advance / withdrawal', '', start={'materialFields': 'gpfAccount,amount,gpfPurpose'})
    s = p.root
    st = s.start('gp_start', 'Application', form='ifms-gpf/application')
    p.forms.append(dict(key='ifms-gpf/application', name='GPF application | जीपीएफ आवेदन', decision=None,
                        fields=['gpfAccount*', 'gpfPurpose*', 'amount*', 'gpfBalance', 'qualifyingService', 'documents'], doc=None))
    d = s.setv('gp_defaults', 'Configuration', defaults='amount=0;gpfBalance=0;qualifyingService=0;gpfPurpose=OTHER;'
               'billType=GPF_WITHDRAWAL;caseType=GPF;routingDecisionKey=ifms-gpf-authority;powersDecisionKey=ifms-gpf-powers;scrutinySeat=DDO-1;'
               'sanctionSla=15D;appealAllowed=false;outputTemplate=gpf-sanction')
    r = s.rule('gp_rules', 'GPF eligibility (DMN)', 'gpf-withdrawal')
    s.seq(st, d, r)
    g = s.xgw('gp_okGw')
    s.flow(r, g)
    s.flow(g, reject_with(p, s, 'gp_rjRule', 'GPF_NOT_ELIGIBLE'), 'gpfEligible == false', 'not eligible')
    san = core(s, 'gp_sanction', 'Sanction (core)', 'sanction')
    s.flow(g, san, None, 'eligible', default=True)
    rej = rejection(p, s, 'gp_rj')
    b = s.call('gp_bill', 'GPF bill', 'sangam-ifms-bill')
    outcome(s, 'gp_sanGw', 'sanctionOutcome', {'SANCTIONED': b}, rej)
    s.flow(san, 'gp_sanGw')
    s.flow(b, end_ok(p, s, 'gp_end'))
    s.side_exits('gp')
    pk.dmn('gpf-withdrawal', 'GPF advance / withdrawal eligibility', 'FIRST',
           [('Purpose', 'gpfPurpose', 'string'), ('Amount', 'amount', 'double'), ('Balance', 'gpfBalance', 'double'),
            ('Qualifying service', 'qualifyingService', 'double')],
           [('gpfEligible', 'Eligible', 'boolean'), ('ruleRef', 'Rule', 'string')],
           [(['-', '-', '<= 0', '-'], ['false', q('no balance')], ''),
            (['-', '> gpfBalance', '-', '-'], ['false', q('GPF (CS) Rules 1960: not above the balance')], ''),
            (['-', '-', '-', '-'], ['true', q('GPF (CS) Rules 1960 [U: purpose-wise limits to load]')], 'placeholder limits')])
    authority(pk, 'ifms-gpf-authority', [('-', 'HOO', 'Head of Office sanctions GPF withdrawals [U: department order]')], '')
    ifms_powers(pk, 'ifms-gpf-powers', 'GPF',
                [('HOO', None, 'Head of Office sanctions GPF advances / withdrawals within gpf-withdrawal (pack default)')],
                unverified='the GPF (CS) Rules 1960 sanctioning authority is not read (corpus IFC-171 [U: department '
                           'order]); confirm and deploy it as a tenant DMN override')
    seeds(pk, 'PAO-OFFICE', PAO_SEATS, 'gp')

    pk2 = Pack('gov.ifms.loans-advances', 'ifms-loans-advances', 'Loans and advances to employees', 'कर्मचारियों को ऋण और अग्रिम',
               'IFMS', 'HBA, computer and short-term advances: eligibility and amount by DMN, sanction, payment bill, '
               'recovery schedule as a RECEIVABLE ledger (instalments, interest), closure on full recovery.',
               ['IFC-180', 'IFC-107', 'IFC-210', 'IFC-025'] + SIDE,
               connectors=CONFIG_CONNECTORS + [ledger_conn('allocate', 'Raise the recovery demand (RECEIVABLE)')],
               depends=['sangam.core', 'gov.ifms.pao-chain', 'gov.ifms.bill'])
    p = pk2.proc('sangam-ifms-advance', 'Employee advance', '', start={'materialFields': 'advanceKind,amount,instalments'})
    s = p.root
    st = s.start('av_start', 'Application', form='ifms-loans-advances/application')
    p.forms.append(dict(key='ifms-loans-advances/application', name='Advance application | अग्रिम आवेदन', decision=None,
                        fields=['advanceKind*', 'amount*', 'instalments*', 'documents'], doc=None))
    d = s.setv('av_defaults', 'Configuration', defaults='amount=0;instalments=1;advanceKind=COMPUTER;basicPay=0;'
               'billType=HBA;caseType=ADVANCE;routingDecisionKey=ifms-advance-authority;powersDecisionKey=ifms-advance-powers;scrutinySeat=DDO-1;'
               'appealAllowed=false;outputTemplate=advance-sanction;ledgerId=RECOVERY')
    r = s.rule('av_rules', 'Advance limits (DMN)', 'ifms-advance-limits')
    san = core(s, 'av_sanction', 'Sanction (core)', 'sanction')
    rej = rejection(p, s, 'av_rj')
    b = s.call('av_bill', 'Advance bill', 'sangam-ifms-bill')
    dem = s.conn('av_demand', 'Recovery schedule (RECEIVABLE demand)', 'ledger.allocate', 'allocate', 'submit',
                 'ledgerId,amount,instalments', 'dem', step='demand')
    s.seq(st, d, r)
    g = s.xgw('av_okGw')
    s.flow(r, g)
    s.flow(g, reject_with(p, s, 'av_rjRule', 'ADVANCE_LIMIT'), 'advanceAllowed == false', 'above limit')
    s.flow(g, san, None, 'within limit', default=True)
    outcome(s, 'av_sanGw', 'sanctionOutcome', {'SANCTIONED': b}, rej)
    s.flow(san, 'av_sanGw')
    s.seq(b, dem, end_ok(p, s, 'av_end'))
    s.side_exits('av')
    pk2.dmn('ifms-advance-limits', 'Advance limits', 'FIRST', [('Kind', 'advanceKind', 'string'), ('Amount', 'amount', 'double')],
            [('advanceAllowed', 'Allowed', 'boolean'), ('ruleRef', 'Rule', 'string')],
            [([q('COMPUTER'), '> 50000'], ['false', q('[U] computer advance ceiling to confirm')], 'placeholder'),
             ([q('HBA'), '> 2500000'], ['false', q('[U] HBA ceiling (34 x basic pay, max 25 lakh) to confirm')], 'placeholder'),
             (['-', '-'], ['true', q('Compendium of Rules on Advances [U]')], '')])
    authority(pk2, 'ifms-advance-authority', [('-', 'HOO', 'Head of Office [U]')], '')
    ifms_powers(pk2, 'ifms-advance-powers', 'ADVANCE',
                [('HOO', None, 'Head of Office sanctions advances within ifms-advance-limits (pack default)')],
                unverified='the Compendium of Rules on Advances is not read (corpus IFC-025 / IFC-180 [U]); confirm the '
                           'sanctioning authority and its limit and deploy it as a tenant DMN override')
    seeds(pk2, 'PAO-OFFICE', PAO_SEATS, 'av')


# ================================================================================================ accounts
def accounts():
    pk = Pack('gov.ifms.monthly-accounts', 'ifms-monthly-accounts', 'Monthly accounts compilation and rendition',
              'मासिक लेखा संकलन और प्रस्तुति', 'IFMS',
              'Monthly on the profile day: PAO compiles (period lock on the ledger), pre-submission checks (expenditure '
              'without budget, suspense, mixed heads), Pr.AO consolidates and renders to the target (CGA / AG) by the '
              'profile due day (8th GOI, 5th WB / ML), e-DO by the 12th; DDO-level reconciliation.',
              ['IFC-140', 'IFC-199', 'IFC-200', 'IFC-201', 'IFC-206', 'IFC-207', 'IFC-208', 'IFC-209', 'IFC-211', 'IFC-212',
               'IFC-214', 'IFS-074', 'IFS-036', 'IFS-WB-005', 'IFS-WB-009', 'IFS-ML-008', 'IFS-CT-004', 'IFS-DL-002',
               'GA-24', 'GP-30'],
              connectors=CONFIG_CONNECTORS + [('accounts.render', 'a', 'G-AG', 'Render accounts (CGA / AG VLC / e-voucher / SFTP)')])
    p = pk.proc('sangam-ifms-monthly-accounts', 'Monthly accounts', '', start={'materialFields': 'accountsMonth'})
    s = p.root
    t = s.start('ma_timer', 'Monthly on the 1st', timer_cycle='0 0 6 1 * ?', initiator=None)
    m = s.start('ma_manual', 'Run now (message)', msg='sangam.ifms.compileNow', initiator=None)
    j = s.xgw('ma_join')
    s.flow(t, j)
    s.flow(m, j)
    d = s.setv('ma_defaults', 'Configuration', defaults='accountsMonth=previous;paoSeat=PAO-1;prAoSeat=PRAO-1;dueSpec=8D;'
               'executionConnector=accounts.render;executionStep=render;reconcileRequired=false')
    e1, x1 = task(s, 'ma_compile', 'Compile and check the monthly account | मासिक लेखा संकलन', 'paoSeat',
                  'ifms-monthly-accounts/compile', ('compileDecision', ['compiled']), ['accountsMonth', 'remarks'], '5WD')
    e2, x2 = task(s, 'ma_consolidate', 'Consolidate and approve | समेकन और अनुमोदन', 'prAoSeat',
                  'ifms-monthly-accounts/consolidate', ('consDecision', ['approve', 'returnBill']), ['remarks'], '2WD', sod=True)
    ex = core(s, 'ma_render', 'Render to CGA / AG (core execution)', 'execution')
    s.seq(j, d, e1)
    s.seq(x1, e2)
    g = s.xgw('ma_consGw')
    s.flow(x2, g)
    s.flow(g, e1, "consDecision == 'returnBill'", 'returned')
    s.flow(g, ex, None, 'approved', default=True)
    s.flow(ex, s.end('ma_end', 'Rendered'))
    seeds(pk, 'PAO-OFFICE', PAO_SEATS, 'ma')

    pk2 = Pack('gov.ifms.year-end-closing', 'ifms-year-end-closing', 'Year-end closing and annual accounts',
               'वर्षांत समापन और वार्षिक लेखे', 'IFMS',
               'March cuts from the profile (preliminary, supplementary I / II) as extra fiscal periods, statement of '
               'central transactions, Appropriation Accounts per grant from the ledger (original, supplementary, '
               're-appropriation, actuals, savings / excess) with explanatory notes, CAA signature, audit; Finance '
               'Accounts data; excess-grant regularisation input.',
               ['IFC-202', 'IFC-203', 'IFC-204', 'IFC-205', 'IFC-051', 'IFC-213', 'IFC-250', 'IFS-043', 'IFS-SK-004', 'GA-25'],
               connectors=CONFIG_CONNECTORS + [('accounts.render', 'a', 'G-AG', 'Render annual accounts')])
    p = pk2.proc('sangam-ifms-year-end-closing', 'Year-end closing', '', start={'materialFields': 'fiscalYear,closingCut'})
    s = p.root
    st = s.start('yc_start', 'Closing cut opened', form='ifms-year-end-closing/cut')
    p.forms.append(dict(key='ifms-year-end-closing/cut', name='Closing cut | समापन चरण', decision=None,
                        fields=['fiscalYear*', 'closingCut*', 'remarks'], doc=None))
    d = s.setv('yc_defaults', 'Configuration', defaults='closingCut=MARCH_PRELIM;paoSeat=PAO-1;caaSeat=SECY-1;'
               'executionConnector=accounts.render;executionStep=render;reconcileRequired=false')
    e1, x1 = task(s, 'yc_compile', 'Compile the cut | चरण का संकलन', 'paoSeat', 'ifms-year-end-closing/compile',
                  ('compileDecision', ['compiled']), ['closingCut', 'remarks'], '10WD')
    appr = s.svc('yc_appr', 'Appropriation Accounts from the ledger', C['DOC'], {'template': 'appropriation-accounts'})
    e2, x2 = task(s, 'yc_sign', 'Sign Appropriation Accounts (CAA) | विनियोग लेखों पर हस्ताक्षर', 'caaSeat',
                  'ifms-year-end-closing/sign', ('signDecision', ['signed', 'returnBill']), ['dscSerial', 'remarks'], '10WD')
    ex = core(s, 'yc_render', 'Render (core execution)', 'execution')
    s.seq(st, d, e1)
    s.seq(x1, appr, e2)
    g = s.xgw('yc_signGw')
    s.flow(x2, g)
    s.flow(g, e1, "signDecision == 'returnBill'", 'returned')
    s.flow(g, ex, None, 'signed', default=True)
    s.flow(ex, s.end('yc_end', 'Closed'))
    seeds(pk2, 'PAO-OFFICE', PAO_SEATS, 'yc')


# ================================================================================================ scheme appraisal
def appraisal():
    pk = Pack('gov.ifms.scheme-appraisal', 'ifms-scheme-appraisal', 'Scheme / project appraisal and approval',
              'योजना / परियोजना मूल्यांकन और अनुमोदन', 'IFMS',
              'Appraisal forum and approval authority by total outlay (ladder DMN; thresholds are profile data: FA up to '
              '100 cr, SFC to 500 cr, EFC above; Secretary / Minister / Minister + FM / Cabinet), appraisal timeline with '
              'comments and final memo, committee meeting, approvals, Cabinet note above the top band; sunset with the '
              'Finance Commission cycle; outcome framework link.',
              ['IFC-080', 'IFC-081', 'IFC-082', 'IFC-083', 'IFC-053', 'IFC-054', 'IFC-076', 'IFC-084', 'GP-03', 'GP-06'] + SIDE,
              connectors=CONFIG_CONNECTORS)
    p = pk.proc('sangam-ifms-scheme-appraisal', 'Scheme appraisal', '', start={'materialFields': 'schemeName,totalOutlay'})
    s = p.root
    st = s.start('sa_start', 'Proposal (DPR / memo)', form='ifms-scheme-appraisal/proposal')
    p.forms.append(dict(key='ifms-scheme-appraisal/proposal', name='Scheme proposal | योजना प्रस्ताव', decision=None,
                        fields=['schemeName*', 'totalOutlay*', 'justification*', 'documents'], doc=None))
    d = s.setv('sa_defaults', 'Configuration', defaults='totalOutlay=0;amount=0;faSeat=FA-1;committeeSeats=FA-1,SECY-1;'
               'quorumRule=MAJORITY;committeeWindow=P10D;caseType=SCHEME;routingDecisionKey=ifms-scheme-approval;powersDecisionKey=ifms-scheme-powers;'
               'scrutinySeat=FA-1;outputTemplate=scheme-approval;appealAllowed=false;appraisalForum=FA')
    r = s.rule('sa_ladder', 'Appraisal forum and approval authority (DMN)', 'ifms-scheme-appraisal')
    amt = s.setv('sa_amount', 'Amount', values='amount=@totalOutlay')
    s.seq(st, d, r, amt)
    e1, x1 = task(s, 'sa_comments', 'Comments of DoE / NITI / ministries | टिप्पणियाँ', 'faSeat', 'ifms-scheme-appraisal/comments',
                  ('commentsDecision', ['forwardNext']), ['remarks*'], '20WD')
    s.flow(amt, e1)
    fg = s.xgw('sa_forumGw', 'Committee (SFC / EFC)?')
    s.flow(x1, fg)
    com = core(s, 'sa_committee', 'Appraisal committee (core)', 'committee')
    san = core(s, 'sa_sanction', 'Approval by the authority (core)', 'sanction')
    s.flow(fg, com, "appraisalForum != 'FA'", 'committee')
    s.flow(fg, san, None, 'FA appraisal', default=True)
    s.flow(com, san)
    rej = rejection(p, s, 'sa_rj')
    cl = core(s, 'sa_close', 'Approval order (core closure)', 'closure')
    outcome(s, 'sa_sanGw', 'sanctionOutcome', {'SANCTIONED': cl}, rej)
    s.flow(san, 'sa_sanGw')
    s.flow(cl, end_ok(p, s, 'sa_end'))
    s.side_exits('sa')
    pk.dmn('ifms-scheme-appraisal', 'Scheme appraisal ladder (DoE OM 05-08-2016)', 'FIRST',
           [('Total outlay (INR)', 'totalOutlay', 'double')],
           [('appraisalForum', 'Appraisal forum', 'string'), ('approvalAuthority', 'Approval authority', 'string'),
            ('ruleRef', 'Rule', 'string')],
           [(['<= 1000000000'], [q('FA'), q('SECRETARY'), q('OM 05-08-2016: up to 100 cr')], ''),
            (['<= 5000000000'], [q('SFC'), q('MINISTER'), q('OM 05-08-2016: 100-500 cr')], ''),
            (['<= 10000000000'], [q('EFC'), q('MINISTER_AND_FM'), q('OM 05-08-2016: 500-1000 cr')], ''),
            (['-'], [q('EFC'), q('CABINET'), q('OM 05-08-2016: above 1000 cr')], '')],
           doc='Thresholds mirror the GOI profile appraisal.ladder; a State loads its own ladder as a tenant override.')
    authority(pk, 'ifms-scheme-approval', [('<= 1000000000', 'CAA', 'Secretary'), ('-', 'FA', 'routed to the authority of the ladder')], '')
    ifms_powers(pk, 'ifms-scheme-powers', 'SCHEME',
                [('CAA', 1000000000, 'Secretary approves schemes up to 100 cr (ladder ifms-scheme-appraisal)')],
                unverified='the corpus verifies the appraisal forum by outlay (IFC-080) but not the approval thresholds '
                           '(Secretary / Minister / FM / Cabinet are not modelled, IFMS-CENTRAL gap 7); confirm from DFPR '
                           'App. III and deploy it as a tenant DMN override')
    seeds(pk, 'PAO-OFFICE', PAO_SEATS, 'sa')


# ================================================================================================ audit para settlement
def audit():
    pk = Pack('gov.ifms.audit-para-settlement', 'ifms-audit-para-settlement', 'Audit para settlement (ATN / internal audit)',
              'लेखापरीक्षा पैरा निपटान (एटीएन / आंतरिक लेखापरीक्षा)', 'IFMS',
              'C&AG para / internal-audit objection / e-bill post-check finding: action taken note within the profile '
              'deadline (4 months [U]), FA vetting, submission (APMS adapter), settlement or further action; recoveries '
              'ordered are linked to the next bill.',
              ['IFC-113', 'IFC-215', 'IFC-216', 'IFC-217', 'IFC-218', 'IFC-219', 'IFC-220', 'IFC-221', 'IFC-109', 'GP-21'] + SIDE,
              connectors=CONFIG_CONNECTORS + [('audit.apms', 'b', 'G-CAG', 'Audit para monitoring system')])
    p = pk.proc('sangam-ifms-audit-para', 'Audit para / ATN', '', start={'materialFields': 'paraNo'})
    s = p.root
    st = s.start('ap_start', 'Para / objection received', form='ifms-audit-para-settlement/para')
    p.forms.append(dict(key='ifms-audit-para-settlement/para', name='Audit para | लेखापरीक्षा पैरा', decision=None,
                        fields=['paraNo*', 'remarks*', 'documents'], doc=None))
    d = s.setv('ap_defaults', 'Configuration', defaults='ownerSeat=HOO-1;faSeat=FA-1;executionConnector=audit.apms;'
               'executionStep=atn;reconcileRequired=false')
    e1, x1 = task(s, 'ap_atn', 'Prepare action taken note | की गई कार्रवाई टिप्पणी', 'ownerSeat', 'ifms-audit-para-settlement/atn',
                  ('atnDecision', ['forwardNext']), ['atnText*', 'documents'], '80WD')
    e2, x2 = task(s, 'ap_fa', 'FA vetting | वित्तीय सलाहकार द्वारा जाँच', 'faSeat', 'ifms-audit-para-settlement/fa',
                  ('faDecision', ['faConcur', 'faNotConcur']), ['remarks*'], '5WD')
    ex = core(s, 'ap_submit', 'Submit ATN (core execution)', 'execution')
    res = s.catch_msg('ap_result', 'Audit decision', 'sangam.ifms.paraDecision')
    e3, x3 = task(s, 'ap_close', 'Record audit decision | लेखापरीक्षा निर्णय दर्ज करें', 'ownerSeat',
                  'ifms-audit-para-settlement/decision', ('paraDecision', ['settlePara', 'furtherAction']), ['remarks'], '5WD')
    s.seq(st, d, e1)
    s.seq(x1, e2)
    g = s.xgw('ap_faGw')
    s.flow(x2, g)
    s.flow(g, e1, "faDecision == 'faNotConcur'", 'revise')
    s.flow(g, ex, None, 'vetted', default=True)
    s.seq(ex, res, e3)
    g2 = s.xgw('ap_decGw')
    s.flow(x3, g2)
    s.flow(g2, e1, "paraDecision == 'furtherAction'", 'further action')
    s.flow(g2, end_ok(p, s, 'ap_end', 'Settled'), None, 'settled', default=True)
    s.side_exits('ap')
    seeds(pk, 'PAO-OFFICE', PAO_SEATS, 'ap')


def build():
    pao_chain()
    bill()
    allocation()
    reappropriation()
    formulation()
    supplementary()
    surrender()
    gia_uc()
    sna_sparsh()
    receipts()
    gpf_advances()
    accounts()
    appraisal()
    audit()
