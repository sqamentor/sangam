# SPDX-License-Identifier: Apache-2.0
"""Bill-type crosswalk of the Rajasthan and Union catalogues: bill type -> form number -> catalogue id (profile data,
ADR-D-20; APPROVE-401 step 2b, joint-ledger rows JL-S-05 / JL-S-06).

Every catalogue row carries
- `id`            the Sangam-assigned, stable catalogue id (the `typeId` a process resolves; never reused);
- `formKind`      BILL for a bill type; the forms that are not bills (reports, challans, registers, schedules) are kept
                  out of `documentTypes` and listed under `formTemplates`, so `documentType.get` never resolves them;
- `formNumber`    jurisdiction-prefixed (`RJ:GA 84`, `GOI:RPR 29`), so two governments' numbers never collide;
- `billTypeCode`  the government system's own bill-type code where one is known;
- `provenance`    one label per crosswalk field: VERIFIED (name / number read in a held official document), INFERRED,
                  ASSUMED (a settings spelling no document gives), UNKNOWN.

Resolution is by catalogue id: several bill types share one form (all FVC sub-types are filed on GA 84; the Union's
RPR 29 carries advance, adjustment, fully vouched and supplier claims), so a form number alone can name several rows
and `documentType.get` then answers TYPE_AMBIGUOUS (it never picks one).

Source of the data (citations stay in these comments, SG-37): IFMS R-103 v0.1 of 2026-11-12, "Bill-type taxonomy and
crosswalk" (IFMS data, not an approval), sections (A) and (B); cross-read by Sangam against the IFMS text cache:
- GF&AR Vol II forms (in force, to 01.07.2026), RJ_RULES_gfar-vol2-forms.pdf.txt: l.5352-5366 (GA 36A-36O, 65A-65D,
  100A, 112A inserted from 01-01-2017), l.8944 (GA 110A-110D), l.9353-9385 (GA 120-120F, 121-121C and their titles),
  Appendix 7 forms index l.193-281 (GA 76, 77, 79, 81, 84-86, 92, 100, 103, 105, 110, 112, 116, 119);
- the FVC bill-type-wise process-flow manual, RJ_MANUAL_fvc_bill_type_wise_process_flow.pdf.txt l.17-50 (the eleven
  FVC kinds, 8 split into a / b: twelve sub-types);
- the 2024 draft forms book GT-13823 (a DRAFT, not in force: it would delete GA 36N, 36O and 76, which stay here) for
  the titles of GA 36B-36L (pp. 72-98), 65A-65D (pp. 127-137) and 110A-110D (pp. 208-216), whose order matches.
Second source: IFMS R-103 v0.2 of 2026-11-12 (second check pending; IFMS data, not an approval): it corrects v0.1 on
the Union rows and places the earlier Rajasthan rows (salary, security deposit, works, pension) and the GA 85 title.
The settings codes (`RJ-...`, `IN-...`) are IFMS settings-pack spellings: ASSUMED on every row (R-103 status rule).
The jurisdiction prefix is the R-103 suggestion (ASSUMED) adopted as Sangam's spelling.
"""

V, I, A, U = 'VERIFIED', 'INFERRED', 'ASSUMED', 'UNKNOWN'
LABELS = (V, I, A, U)
# the crosswalk fields every row labels (the test checks each one on every row)
FIELDS = ('formKind', 'formNumber', 'billTypeCode', 'typeId', 'name')

# typeId: the government holds no document-type catalogue id (R-103 (B): "typeId UNKNOWN on every row; the catalogue
# ids are Sangam's"). Coordinator decision Q6 (2026-11-12): the row id is Sangam-assigned, not taken from an
# authoritative source, which is ASSUMED in the profile convention (UNKNOWN would say no value exists).
TYPE_ID = A
CATALOGUE_NOTE = {
    'resolution': 'CATALOGUE_ID',
    'formNumberPrefix': '<government profile id>:',
    'provenanceLabels': list(LABELS),
    'provenanceFields': list(FIELDS),
    'note': 'A bill type is resolved by its catalogue id; one form can carry several bill types, so a form number alone '
            'may be ambiguous. typeId ASSUMED: the row id is the Sangam-assigned catalogue id; the government holds no '
            'document-type id. Forms that are not bills are listed under formTemplates and are never resolved as bill types.',
}


def present(rows):
    """Rows without null fields: an absent value is absent (its provenance label says UNKNOWN), never null, because a
    connector answer cannot carry a null value (documentType.get returns the row as the case's variables)."""
    return [{k: v for k, v in r.items() if v is not None} for r in rows]


def _row(id, name, form, form_st, code, code_st, name_st, family, extra=None):
    d = {'id': id, 'formKind': 'BILL', 'formNumber': form}
    if name:
        d['name'] = {'en': name}
    d['billTypeCode'] = code
    if family:
        d['family'] = family
    # a row R-103 names as a bill type with a VERIFIED name is a bill (VERIFIED), whatever form it is filed on
    d['provenance'] = {'formKind': V if name_st == V else I, 'formNumber': form_st, 'billTypeCode': code_st,
                       'typeId': TYPE_ID, 'name': name_st}
    if extra:
        d.update(extra)
    return d


# ------------------------------------------------------------------------------------------------ Rajasthan
RJF = 'RJ:GA '
RPWA = 'RJ:RPWA '  # Rajasthan public works account forms (PWF&AR)
CHAIN = {'approvalChain': 'TREASURY_STD', 'sanction': 'REFERENCE'}
# GA 36N, 36O, 76: in force (GF&AR Vol II l.5357, l.193); the 2024 draft forms book would delete them (R-103 F-2)
SUNSET = {'formNotice': 'in force; a draft forms book (2024, not notified) would delete this form'}


NOCODE = False  # R-103 gives no settings code for the row (UNKNOWN)


def rj(id, name, form, form_st=V, code=None, name_st=V, family='CONTINGENT', extra=None, series=None):
    """One Rajasthan bill type: `code` None means the settings code is the id spelled with hyphens (ASSUMED)."""
    code = None if code is NOCODE else (code or id.replace('_', '-'))
    e = dict(CHAIN)
    e.update(extra or {})
    return _row(id, name, None if form is None else (series or RJF) + form, U if form is None else form_st, code,
                A if code else U, name_st, family, e)


FVC = {'subTypeOf': 'RJ_FVC'}
EP, PC, AD, RF, GR = 'ESTABLISHMENT_PAY', 'PERSONAL_CLAIM', 'ADVANCE', 'REFUND', 'GRANT'

RJ_BILLS = [
    # FVC parent and its twelve sub-types (FVC manual l.17-50): all on GA 84 "Contingent (FVC) Bill" (GF&AR Vol II App. 7
    # l.212, rule 219); the parent's form is VERIFIED, each sub-type's form INFERRED (R-103 (B), (C) "which form an FVC
    # sub-type is filed on"). Names: the manual's own words.
    rj('RJ_FVC', 'Fully Vouched Contingent (FVC) Bill', '84'),
    rj('RJ_FVC_GENERAL', 'General FVC Bill', '84', I, extra=FVC),
    rj('RJ_FVC_WATER', 'Water Bill', '84', I, extra=FVC),
    rj('RJ_FVC_TELEPHONE', 'Telephone Bill', '84', I, extra=FVC),
    rj('RJ_FVC_IMPREST_RECOUP', 'Recoupment of imprest', '84', I, extra=FVC),
    rj('RJ_FVC_ADJUSTMENT', 'FVC Adjustment', '84', I, extra=FVC),
    rj('RJ_FVC_GOODS', 'Goods Purchase', '84', I, extra=FVC),
    rj('RJ_FVC_VEHICLE_HIRE', 'Vehicle Hiring', '84', I, extra=FVC),
    rj('RJ_FVC_MAN_WITH_MACHINE', 'Vendor Payment: Man with machine', '84', I, extra=FVC),
    rj('RJ_FVC_PETTY', 'Vendor Payment: Petty payment', '84', I, extra=FVC),
    rj('RJ_FVC_PROFESSIONAL', 'Professional & Special Services', '84', I, extra=FVC),
    rj('RJ_FVC_AMC', 'AMC', '84', I, extra=FVC),
    rj('RJ_FVC_TELEPHONE_PERIODICAL', 'Telephone/ Periodical', '84', I, extra=FVC),
    # electricity auto-FVC and election FVC: R-103 (A) INFERRED (from its R-38a manuals, not re-read)
    rj('RJ_FVC_ELECTRICITY', 'Electricity auto-FVC Bill', '84', I, name_st=I, extra=FVC),
    rj('RJ_ELECTION_FVC', 'Election FVC Bill', '84', I, name_st=I, extra=FVC),
    # IFMS R-103 v0.2 (second check pending): the in-force form page (GF&AR Vol II l.3833-3836) titles GA 85 "Abstract
    # Contingent Bill"; "Advance contingent bill" is only the old forms-index label (App. 7 l.214), kept as an alias
    rj('RJ_AC_BILL', 'Abstract Contingent Bill', '85', extra={'nameAliases': ['Advance contingent bill']}),
    rj('RJ_DC_BILL', 'Detailed Contingent Bill', '86'),
    # salary series GA 36, 36A-36Q (GF&AR Vol II l.5352-5366 insert 36A-36O; 36P / 36Q l.10334, 10380, 10532); titles of
    # 36B-36L from the draft pp. 72-98 in this order. R-103 gives the settings codes only for the first and the last of
    # 36B-36L: the nine between have no known code (UNKNOWN), their ids are Sangam's.
    # IFMS R-103 v0.2 (second check pending): the earlier RJ_SALARY row (Pay Manager source) is the regular monthly
    # salary bill GA 36 (form VERIFIED, merge INFERRED); it merges here with its source system, its id kept as a former id
    rj('RJ_SAL_REGULAR', 'Salary Bill', '36', family=EP,
       extra={'integrations': {'sourceSystem': 'PAY_MANAGER'}, 'formerIds': ['RJ_SALARY']}),
    rj('RJ_SAL_ARREAR', 'Salary Arrear Bill', '36A', family=EP),
    rj('RJ_SAL_DA_ARREAR', 'DA Arrear Bill', '36B', family=EP),
    rj('RJ_SAL_SURRENDER', 'Surrender Bill', '36C', code=NOCODE, family=EP),
    rj('RJ_SAL_SURRENDER_ARREAR', 'Surrender Arrear Bill', '36D', code=NOCODE, family=EP),
    rj('RJ_SAL_BONUS', 'Bonus Bill', '36E', code=NOCODE, family=EP),
    rj('RJ_SAL_LEAVE_ENCASH', 'Leave Encashment Bill', '36F', code=NOCODE, family=EP),
    rj('RJ_SAL_LEAVE_ENCASH_ARREAR', 'Leave Encashment Arrear Bill', '36G', code=NOCODE, family=EP),
    rj('RJ_SAL_REVERSE_DEPUTATION', 'Salary Bill - Reverse Deputation', '36H', code=NOCODE, family=EP),
    rj('RJ_SAL_REEMPLOY_FIX_PAY', 'Salary Bill - Re-employment: Fix Pay', '36I', code=NOCODE, family=EP),
    rj('RJ_SAL_REEMPLOY_PAY_MINUS_PENSION', 'Salary Bill - Re-employment: Pay Minus Pension', '36J', code=NOCODE, family=EP),
    rj('RJ_PAY_HONORARIUM_EMPLOYEE', 'Pay/Honorarium Bill: Govt. Employee', '36K', code=NOCODE, family=EP),
    rj('RJ_PAY_HONORARIUM_NOMINATED', 'Pay/Honorarium Bill: Nominated Members/ Authority', '36L', family=EP),
    rj('RJ_MEDICAL', 'Medical Bill', '36M', family=PC),
    rj('RJ_MEDICAL_ADVANCE', 'Medical-Advance Bill', '36N', family=AD, extra=SUNSET),
    rj('RJ_MEDICAL_ADVANCE_ADJ', 'Medical-Advance Adjustment Bill', '36O', family=AD, extra=SUNSET),
    rj('RJ_SAL_INTEREST_SUBV_HBA', 'Salary Bill: Interest Subvention on HBA', '36P', family=EP),
    rj('RJ_SAL_INTEREST_SUBV_CONVEYANCE', 'Salary Bill: Interest Subvention on Conveyance Advance', '36Q', family=EP),
    # TA / LTC series GA 65, 65A-65D (l.5360; titles draft pp. 124-137; 65D "LTC Bill-Advance" upgraded, R-103 F-3)
    rj('RJ_TA', 'TA Bill', '65', family=PC),
    rj('RJ_TA_ADVANCE', 'TA-Advance Bill', '65A', family=AD),
    rj('RJ_TA_ADVANCE_ADJ', 'TA-Advance Adjustment Bill', '65B', family=AD),
    rj('RJ_LTC', 'LTC Bill', '65C', family=PC),
    rj('RJ_LTC_ADVANCE', 'LTC Bill-Advance', '65D', family=AD),
    # GA 76: in force (App. 7 l.193 "Bill for withdrawal from P.F."); the R-103 name is the draft's
    rj('RJ_GPF_SI_NPS', 'GPF/SI/NPS Loan/Advance/Claim/Refund Bill', '76', family='PROVIDENT_FUND', extra=SUNSET),
    rj('RJ_REVENUE_REFUND', 'Revenue Refund Bill', '100', family=RF),
    rj('RJ_VAT_REFUND', 'VAT Refund Bill', '100A', family=RF),
    # IFMS R-103 v0.2 (second check pending): the earlier RJ_SD_REFUND (security deposit refund) is this bill for civil
    # departments (GF&AR Part I r.263, VERIFIED); PWD and Forest security deposits fall under PWF&AR and are not this row
    # (their form is not placed: UNKNOWN)
    rj('RJ_DEPOSIT_REPAYMENT', 'Deposit Repayment Bill', '103', family=RF,
       extra={'scope': 'civil departments (GF&AR Part I r.263), security deposits included; PWD and Forest security '
                       'deposits fall under PWF&AR and are not this bill type',
              'formerIds': ['RJ_SD_REFUND']}),
    rj('RJ_LAPSED_DEPOSIT_REFUND', 'Refund of lapse deposit bill', '105', family=RF),
    rj('RJ_GIA', 'Grant-in-Aid/Contribution Bill', '110', family=GR),
    # GA 110A-110D inserted by the order at l.8944; titles draft pp. 208-216 (R-103 F-3: one row split into four)
    rj('RJ_GIA_RIPS2010_INV', 'RIPS-2010 Subsidy Bill for Investment Subsidy', '110A', family=GR),
    rj('RJ_GIA_RIPS2010_EMP', 'RIPS-2010 Subsidy Bill for Employment Generation Subsidy', '110B', family=GR),
    rj('RJ_GIA_RIPS2014_INV', 'RIPS-2014 Subsidy Bill for Investment Subsidy', '110C', family=GR),
    rj('RJ_GIA_RIPS2014_EMP', 'RIPS-2014 Subsidy Bill for Employment Generation Subsidy', '110D', family=GR),
    rj('RJ_GIA_SNA_SPARSH', 'GIA/Contribution Bill (SNA SPARSH)', '110E', family=GR),
    rj('RJ_SCHOLARSHIP', 'Scholarship/Stipend Bill', '112', family=GR),
    rj('RJ_STIPEND_RESIDENT_DOCTOR_GOVT', 'Stipend Bill (Resident Doctor, Govt employee)', '112A', family=GR),
    # GA 120C-120E, 121C: titles from the in-force order, l.9353-9385
    rj('RJ_EKUBER_UNCREDITED_ADJ', 'Adjustment bill for e-Kuber uncredited items (DDO)', '120C', family='ADJUSTMENT'),
    rj('RJ_EKUBER_UNCREDITED', 'Miscellaneous Bill for e-Kuber uncredited items (Treasury)', '120D', family='ADJUSTMENT'),
    rj('RJ_EKUBER_REJECTED_UNPAID', 'e-Kuber Miscellaneous Bill for rejected and unpaid payments', '120E',
       family='ADJUSTMENT'),
    rj('RJ_GST_TDS', 'Bill for GST TDS', '121C', family='DEDUCTION'),
    # App. 7 l.195-281 (rough OCR): numbers and names VERIFIED, read again by Sangam in that index
    rj('RJ_ASSEMBLY_SALARY', 'Salary bill of Assembly', '77', family=EP),
    rj('RJ_ASSEMBLY_TA', 'TA Bill of Assembly/Speaker', '79', family=PC),
    rj('RJ_ZENANA_DEORI', 'Bill of allowances payable to inmates of zenana Deori and Rajmatas', '81', family='ALLOWANCE'),
    rj('RJ_SERVICE_POSTAGE', 'Bill for Service Postage stamps', '92'),
    rj('RJ_LOANS_ADV', 'Bill for Loans & Advances', '116', family=AD),
    rj('RJ_LOANS_ADV_DETAILED', 'Detailed bill for loans and advances', '119', family=AD),
    # SNA SOP names VERIFIED; no form named, GA 36A / 36F a guess (R-103 (B): form INFERRED)
    rj('RJ_SNA_SAL_ARREAR', 'SNA Salary Arrear Bill', '36A', I, family=EP),
    rj('RJ_SNA_LEAVE_ENCASH', 'SNA Leave Encashment Bill', '36F', I, family=EP),
    # names VERIFIED (PD advice and WAM manuals, R-38a); no form number in any held document (UNKNOWN)
    rj('RJ_PD_PAYMENT_ADVICE', 'PD payment advice', None, family='PUBLIC_ACCOUNT'),
    rj('RJ_WAM_BILL', 'WAM works bill', None, family='WORKS', extra={'integrations': {'sourceSystem': 'WAM'}}),
    # IFMS R-103 v0.2 (second check pending): works bills are a family of five RPWA forms (PWF&AR Part I r.418-424,
    # Part III forms index), VERIFIED; they replace the earlier single RJ_WORKS row. Which form WAM itself generates is
    # not known, so none of them carries the WAM source system (RJ_WAM_BILL stays, form UNKNOWN)
    rj('RJ_WORKS_FIRST_FINAL', 'First and Final Bill', '24', family='WORKS', series=RPWA),
    rj('RJ_WORKS_RUNNING', 'Running Account Bill', '26', family='WORKS', series=RPWA),
    rj('RJ_WORKS_RUNNING_LUMPSUM', 'Running Account Bill for intermediate payments on lumpsum contracts', '27A',
       family='WORKS', series=RPWA),
    rj('RJ_WORKS_FINAL', 'Final Bill', '27B', family='WORKS', series=RPWA),
    rj('RJ_WORKS_HAND_RECEIPT', 'Hand Receipt', '28', family='WORKS', series=RPWA),
]

# The two placeholders stay (R-103 (A), (B): "UNKNOWN placeholder, refused in production"): no held document names
# them, so each row is disabled. A disabled row is not in the catalogue (DocumentTypeCatalogue skips it), so a bill
# naming it fails TYPE_UNKNOWN in every environment until the tenant confirms the row with a profile override.
# Coordinator decision Q5 (2026-11-12, SG-37): the codes are generic Sangam codes; the consumer's own spellings carry
# its register number (RJ-BILLTYPE-TBD-1 = IFMS RJ-BILLTYPE-TBD-P438, RJ-SNA-BILLTYPE-TBD-1 = IFMS
# RJ-SNA-BILLTYPE-TBD-P438, register P-438) and stay in this comment only.
RJ_PLACEHOLDER_BILLS = [
    dict(_row('RJ_PARTIAL_PASSING_TBD', 'Bills covered by partial passing (to be confirmed)', None, U,
              'RJ-BILLTYPE-TBD-1', A, U, None, dict(CHAIN)), disabled=True),
    dict(_row('RJ_SNA_IA_BILL_TBD', 'SNA-SPARSH implementing-agency vendor / beneficiary bill (to be confirmed)', None, U,
              'RJ-SNA-BILLTYPE-TBD-1', A, U, None, dict(CHAIN)), disabled=True),
    # IFMS R-103 v0.2 (second check pending): PWD and Forest security deposits fall under PWF&AR, not GA 103; their
    # refund form is not known. Coordinator decision 2026-11-12: an explicit disabled placeholder (refused TYPE_UNKNOWN
    # until a tenant override confirms it), same pattern as the two rows above
    dict(_row('RJ_PWFAR_SD_REFUND_TBD', 'Security deposit refund, PWD and Forest (PWF&AR) (to be confirmed)', None, U,
              'RJ-PWFAR-SD-REFUND-TBD-1', A, I, 'REFUND', dict(CHAIN)), disabled=True),
]

# The rows the earlier research generated (states.py `docs`), placed by IFMS R-103 v0.2 (second check pending). No
# reference to these ids exists in any pack, test or acceptance file, so none is kept as a resolvable alias; each id is
# recorded as a former id (data only, never resolved) on the row that takes it over, and in billTypeCatalogue.retiredIds.
RJ_RETIRED = [
    {'id': 'RJ_SALARY', 'mergedInto': 'RJ_SAL_REGULAR'},
    {'id': 'RJ_SD_REFUND', 'mergedInto': 'RJ_DEPOSIT_REPAYMENT', 'note': 'civil departments only'},
    {'id': 'RJ_WORKS', 'replacedBy': ['RJ_WORKS_FIRST_FINAL', 'RJ_WORKS_RUNNING', 'RJ_WORKS_RUNNING_LUMPSUM',
                                      'RJ_WORKS_FINAL', 'RJ_WORKS_HAND_RECEIPT']},
    {'id': 'RJ_PENSION', 'movedTo': 'formTemplates', 'note': 'not a bill'},
]


def form(id, form_number, kind, name, number_st=V, kind_st=V, name_st=V):
    d = {'id': id, 'formKind': kind, 'formNumber': form_number}
    if name:
        d['name'] = {'en': name}
    d['provenance'] = {'formKind': kind_st, 'formNumber': number_st, 'name': name_st}
    return d


# Forms that are not bill types (R-103 (A) "Not bill types": GA-55 / GA 55-A REPORT; GA 120 / 120A / 120B challan,
# request, e-advice; GA 120F and GA 121 / 121A / 121B register or schedule). Titles of GA 120-121B from the in-force
# order (l.9353-9385). GA 55-A: R-103 names it with GA-55 as a report; no title held.
RJ_FORMS = [
    form('RJ_GA_120', RJF + '120', 'FORM', 'e-Kuber adjustment challan for rejected / failed transactions'),
    form('RJ_GA_120A', RJF + '120A', 'FORM', 'Request for payment of e-Kuber unsuccessful / failed transaction'),
    form('RJ_GA_120B', RJF + '120B', 'FORM', 'e-Advice for payment of e-Kuber unsuccessful / failed transaction'),
    form('RJ_GA_120F', RJF + '120F', 'FORM', 'Control Register of Transactions Rejected by RBI'),
    form('RJ_GA_121', RJF + '121', 'FORM', 'Schedule of amount booked under Suspense Head 8658-00-139-00-00'),
    form('RJ_GA_121A', RJF + '121A', 'FORM', 'Schedule for NEFT / RTGS to RBI'),
    form('RJ_GA_121B', RJF + '121B', 'FORM', 'Register to be maintained by DDO for Filing of GSTR-7'),
    form('RJ_GA_55A', RJF + '55-A', 'REPORT', None, number_st=I, kind_st=I, name_st=U),
    # IFMS R-103 v0.2 (second check pending): pension is not a bill; it is paid on a Pension Payment Order issued by the
    # Director, Pension and Pensioners' Welfare, through the Treasury / Pension Payment Officer and then a bank
    # (Rajasthan Treasury Rules 2012 Ch. VII: route VERIFIED); no pension bill form exists (formNumber UNKNOWN); the
    # disbursement modelling is INFERRED. formKind DISBURSEMENT: a payment made on an order, not a bill, report or form
    dict(form('RJ_PENSION', None, 'DISBURSEMENT', 'Pension payment on a Pension Payment Order (PPO)', U, I, I),
         family='PENSION', route='Pension Payment Order of the Director, Pension and Pensioners Welfare; paid through '
                                 'the Treasury / Pension Payment Officer and then a bank'),
]
for _f in RJ_FORMS:
    if _f['id'] == 'RJ_PENSION':
        _f['provenance']['route'] = V
# the GA-55 report row (states.py `reports`, SL-15: report V from two official manuals) gets its prefixed number and
# labels here; it has no name field (U)
RJ_REPORT_NUMBERS = {'GA_55': (RJF + '55', {'formKind': V, 'formNumber': V, 'name': U})}


def rajasthan(generated):
    """The RJ catalogue: the crosswalk rows; every earlier generated row is placed (RJ_RETIRED) or fails the build."""
    ids = {r['id'] for r in RJ_BILLS}
    retired = {x['id'] for x in RJ_RETIRED}
    out = [dict(r) for r in RJ_BILLS]
    for g in generated:
        if g['id'] in ids:
            continue  # RJ_FVC, RJ_GIA, RJ_GST_TDS: the crosswalk row replaces the earlier pseudo form number
        if g['id'] not in retired:
            raise ValueError('RJ catalogue row not placed in the crosswalk: ' + g['id'])
    out += [dict(r) for r in RJ_PLACEHOLDER_BILLS]
    return present(out)


def rajasthan_forms(reports):
    out = []
    for r in reports:
        r = dict(r)
        number, labels = RJ_REPORT_NUMBERS[r['id'].split('_', 1)[1]]
        r['formKind'] = r.pop('kind')
        r['formNumber'] = number
        r['provenance'] = dict(labels)
        out.append(r)
    return present(out + [dict(f) for f in RJ_FORMS])


# ------------------------------------------------------------------------------------------------ Union (GOI)
G = 'GOI:RPR '
# id: (formNumber, formNumber label, settings code). Source: IFMS R-103 v0.2 of 2026-11-12, second check pending (IFMS
# data, not an approval; each SI 2022 form page read once per pass), section (A) Central and "v0.2 answers". v0.2
# corrects v0.1 on 13B, 14 / 14A / 14B / 14C, 16 and 48: goi.py's numbers there were right, now VERIFIED by the form
# pages (the form pages win over SI para 2.7(2)(a), which still says RPR 14 for TA bills).
# - VERIFIED: PAY 13, NPS_NIL 13B (13A is the pay bill's inner sheet), TA_CONSOLIDATED 14, TA_TOUR 14A, TA_TRANSFER 14B,
#   LTC 14C, PAY_PRESIDENT_VP 15, TA_PRESIDENT_MINISTERS 16, MEDICAL_CONSOLIDATED 23, MEDICAL_CGHS 23A, MEDICAL_CSMA 23B,
#   CONTINGENT 29, IMPREST_RECOUPMENT 29A, REFUND_REVENUE 33, GIA 34, HBA and COMPUTER_ADVANCE 36, SHORT_ADVANCE 37,
#   GPF_WITHDRAWAL, GPF_FINAL and DLIS 42 (form title "withdrawal of Final Payment/advance from GPF/CPF or payment under
#   DLIS"), REFUND_DEPOSIT 43 Part C, CGEGIS 44, GIS_AIS 45, REFUND_LAPSED_DEPOSIT 46, CEA_OTHER_PERSONAL 48, GEM_EBILL 49,
#   RETIREMENT_BENEFITS 50, CF_WITHDRAWAL 51, REFUND_GST 52;
# - LPC_FIRST_PAY: an RPR 13 pay bill with the RPR 2 Last Pay Certificate attached (RPR 2 is not a bill), VERIFIED;
# - PAY_ARREAR: no separate form (a separate bill noted in the Pay Bill Register RPR 17); RPR 13 INFERRED;
# - PAY_MINISTERS: goi.py put the Ministers' pay on RPR 16; v0.2 reads RPR 15 as the pay bill of the President,
#   Vice-President and Ministers and RPR 16 as their TA bill, so the row moves to RPR 15 and the TA bill is its own row
#   TA_PRESIDENT_MINISTERS on RPR 16; both VERIFIED by IFMS's second read of the SI form pages (RPR 15 "PAY BILL",
#   para 2.4(3)(b); RPR 16 "TRAVELLING ALLOWANCE BILL", para 2.7(1)), 2026-11-12;
# - rows v0.2 does not name keep goi.py's value: a number goi.py cites is VERIFIED there (profile rule: only values marked
#   unverified are [U]); a goi.py placeholder number (formNumberStatus U) stays UNKNOWN; PFMS-SS is a Sangam spelling of
#   the SNA-SPARSH release, no form number (ASSUMED).
# Settings codes (IN-...) only where one code names exactly one Union row: IN-PAY-PRES-MIN (PAY_PRESIDENT_VP and
# PAY_MINISTERS), IN-ADV-LONG (HBA and COMPUTER_ADVANCE) and IN-GPF (GPF_WITHDRAWAL and GPF_FINAL) each cover two rows
# and stay UNKNOWN until the crosswalk is agreed.
GOI_XWALK = {
    'PAY': (G + '13', V, 'IN-PAY'),
    'PAY_ARREAR': (G + '13', I, 'IN-PAY-ARREAR'),
    'NPS_NIL': (G + '13B', V, 'IN-PAY-NIL-NPS'),
    'PAY_PRESIDENT_VP': (G + '15', V, None),
    # IFMS second read of the CGA SI 2022 (2026-11-12): RPR 15 form page "PAY BILL", para 2.4(3)(b) (pay of the
    # President, Vice-President and Ministers); RPR 16 form page "TRAVELLING ALLOWANCE BILL", para 2.7(1): VERIFIED
    'PAY_MINISTERS': (G + '15', V, None),
    'TA_PRESIDENT_MINISTERS': (G + '16', V, 'IN-TA-PRES-MIN'),
    'TA_CONSOLIDATED': (G + '14', V, 'IN-TA-CONSOLIDATED'),
    'TA_TOUR': (G + '14A', V, 'IN-TA-TOUR'),
    'TA_TRANSFER': (G + '14B', V, 'IN-TA-TRANSFER'),
    'LTC': (G + '14C', V, 'IN-LTC'),
    'MEDICAL_CGHS': (G + '23A', V, 'IN-MEDICAL-CGHS'),
    'MEDICAL_CSMA': (G + '23B', V, 'IN-MEDICAL-CSMA'),
    'MEDICAL_CONSOLIDATED': (G + '23', V, 'IN-MEDICAL-CONSOLIDATED'),
    'CEA_OTHER_PERSONAL': (G + '48', V, 'IN-OTHER-PERSONAL'),
    'CONTINGENT': (G + '29', V, 'IN-CONTINGENT'),
    'IMPREST_RECOUPMENT': (G + '29A', V, 'IN-IMPREST-RECOUP'),
    'GEM_EBILL': (G + '49', V, 'IN-GEM'),
    'VENDOR_INVOICE': (G + '29', V, None),
    'REFUND_REVENUE': (G + '33', V, 'IN-REVENUE-REFUND'),
    'REFUND_GST': (G + '52', V, 'IN-GST-REFUND'),
    'REFUND_DEPOSIT': (G + '43 Part C', V, 'IN-DEPOSIT-REFUND'),
    'REFUND_LAPSED_DEPOSIT': (G + '46', V, 'IN-LAPSED-DEPOSIT-REFUND'),
    'GIA': (G + '34', V, 'IN-GIA'),
    'HBA': (G + '36', V, None),
    'COMPUTER_ADVANCE': (G + '36', V, None),
    'SHORT_ADVANCE': (G + '37', V, 'IN-ADV-SHORT'),
    'GPF_WITHDRAWAL': (G + '42', V, None),
    'GPF_FINAL': (G + '42', V, None),
    'DLIS': (G + '42', V, 'IN-DLIS'),
    'CGEGIS': (G + '44', V, 'IN-CGEGIS'),
    'GIS_AIS': (G + '45', V, 'IN-GIS-AIS'),
    'RETIREMENT_BENEFITS': (G + '50', V, 'IN-RETIREMENT'),
    'CF_WITHDRAWAL': (G + '51', V, 'IN-CONTINGENCY-FUND'),
    'WORK_CHARGED_WAGES': (G + '13', V, None),
    'DEATH_ARREARS': (G + '26', V, None),
    'ENTERTAINMENT': (G + '29', V, None),
    'LPC_FIRST_PAY': (G + '13', V, 'IN-PAY-FIRST'),
    'SNA_SPARSH_RELEASE': ('GOI:PFMS-SS', A, None),
}
# Union forms that are not bill types (R-103 (A) Central "Not bill types"; v0.2: RPR 2 is the LPC form page l.11701,
# RPR 38 is a schedule, not a bill); RPR 9 and 100 carry no status in R-103 (INFERRED); CAM-nn registers not listed singly
GOI_FORMS = [
    form('GOI_RPR_2', G + '2', 'FORM', 'Last Pay Certificate (LPC)', V, V, V),
    form('GOI_RPR_9', G + '9', 'FORM', 'Bill Register', I, I, I),
    form('GOI_RPR_38', G + '38', 'FORM', None, V, V, U),
    form('GOI_RPR_100', G + '100', 'FORM', 'Sanction format', I, I, I),
]

def union(rows):
    """The Union catalogue with the crosswalk applied: every row keeps its id; formNumberStatus becomes the label."""
    out = []
    for r in rows:
        r = dict(r)
        old_status = r.pop('formNumberStatus', None)
        if r['id'] in GOI_XWALK:
            number, number_st, code = GOI_XWALK[r['id']]
        else:  # not named by R-103: goi.py's own placeholder number (every such row is formNumberStatus U)
            if old_status != 'U':
                raise ValueError('Union row with a cited form number not placed in the crosswalk: ' + r['id'])
            number, number_st, code = 'GOI:' + r['formNumber'], U, None
        out.append(dict({'id': r['id'], 'formKind': 'BILL', 'formNumber': number, 'billTypeCode': code},
                        **{k: v for k, v in r.items() if k not in ('id', 'formNumber')},
                        # names are goi.py's descriptions of the bill, not the forms' printed titles (INFERRED)
                        provenance={'formKind': V if number_st == V else I, 'formNumber': number_st,
                                    'billTypeCode': A if code else U, 'typeId': TYPE_ID, 'name': I}))
    return present(out)


# ------------------------------------------------------------------------------------------------ other States
# Coordinator decision Q8 (2026-11-12): one catalogue shape for every government (generic platform). The other States'
# rows come from the earlier research (states.py `docs`: id <code>_<form>, the State's own form number, a family);
# R-103 covers only Rajasthan and the Union, so these rows get the shape and labels from that research only:
# - formNumber: jurisdiction-prefixed; VERIFIED, the profile rule (only values marked under placeholders are [U]; none of
#   these numbers is). The label rests on that rule alone, under which the research recorded these rows; the State
#   sources were not re-read for this crosswalk (coordinator decision Q1, 2026-11-12: keep VERIFIED on that basis);
# - formKind: BILL for a row whose family is a bill family; a register is not a bill and goes to formTemplates (BR
#   BTC-FORM-8, family REGISTER, decision Q8); any other family (HP HPTR-5, DBT) stays a bill row labelled INFERRED;
# - billTypeCode and name: none known (absent, UNKNOWN); typeId: Sangam-assigned (ASSUMED).
BILL_FAMILIES = {'CONTINGENT', 'PERSONAL_CLAIM', 'ESTABLISHMENT_PAY', 'GRANT', 'ADVANCE', 'REFUND', 'INSURANCE'}
TEMPLATE_FAMILIES = {'REGISTER': 'FORM'}


def state_catalogue(code):
    """(bill catalogue builder, form-template builder) for a State whose rows come only from states.py `docs`."""
    def bills(generated):
        out = []
        for g in generated:
            if g.get('family') in TEMPLATE_FAMILIES:
                continue
            r = {'id': g['id'], 'formKind': 'BILL', 'formNumber': code + ':' + g['formNumber']}
            r.update({k: v for k, v in g.items() if k not in ('id', 'formNumber')})
            r['provenance'] = {'formKind': V if g.get('family') in BILL_FAMILIES else I, 'formNumber': V,
                               'billTypeCode': U, 'typeId': TYPE_ID, 'name': U}
            out.append(r)
        return present(out)

    def forms(generated, reports):
        if reports:
            raise ValueError(code + ': report rows have no form-number crosswalk yet')
        return present([{'id': g['id'], 'formKind': TEMPLATE_FAMILIES[g['family']],
                         'formNumber': code + ':' + g['formNumber'], 'family': g['family'],
                         'provenance': {'formKind': V, 'formNumber': V, 'name': U}}
                        for g in generated if g.get('family') in TEMPLATE_FAMILIES])
    return bills, forms


def catalogue(code):
    """(bills(generated rows), forms(generated rows, report rows)) for a government profile, applied by main.py."""
    if code == 'RJ':
        return rajasthan, lambda generated, reports: rajasthan_forms(reports)
    return state_catalogue(code)


def note(code):
    """The billTypeCatalogue block of a government profile (Rajasthan also lists its retired catalogue ids)."""
    d = dict(CATALOGUE_NOTE)
    if code == 'RJ':
        d['retiredIds'] = [dict(x) for x in RJ_RETIRED]
    return d
