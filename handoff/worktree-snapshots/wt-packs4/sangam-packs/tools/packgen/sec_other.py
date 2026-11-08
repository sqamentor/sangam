# SPDX-License-Identifier: Apache-2.0
"""Sector packs outside financial services: healthcare (sec.health.*), e-commerce (sec.ecom.*), telecom (sec.tel.*),
supply chain and manufacturing (sec.scm.*, sec.mfg.*), education (sec.edu.*), utilities (sec.util.*), HR (sec.hr.*)
and legal / privacy (sec.legal.*). References: spike/results/SECTOR-WORKFLOWS.md. Values marked SYNTHETIC are pilot
values replaced by the tenant's policy (GP-37)."""
from lib import C, q
from money import M  # noqa: F401  (amounts inside FEEL expressions, minor units)
from common import core, outcome, end_ok, rejection, reject_with, revise
from gov2 import simple_seeds
from sector import SIDE, spack, start_form, clock, at_date, powers, authority, maybe


# ============================================================================================ healthcare
def health_admission():
    pk = spack('health', 'admission-discharge', 'Patient admission to discharge', 'रोगी भर्ती से डिस्चार्ज तक', 'Healthcare',
               'Registration with ABHA verification, triage for emergencies (stabilisation is never held up for '
               'payment), estimate of charges, informed consent, payer route (self, insurance pre-authorisation, '
               'PM-JAY beneficiary identification), inpatient care, discharge with summary and final bill in '
               'parallel, LAMA, transfer or death (MCCD and civil registration), discharge summary linked to the '
               'ABHA as an ABDM care context (FHIR R4).',
               ['SEC-HEALTH-001', 'SEC-HEALTH-002', 'SEC-HEALTH-003', 'SEC-HEALTH-004', 'GP-06', 'GP-15', 'UP-04',
                'UP-17'] + SIDE,
               [('abdm.abhaVerify', 'a', '', 'ABHA verification'), ('tpa.preauthRequest', 'b', '', 'Pre-authorisation'),
                ('pmjay.bis', 'a', '', 'PM-JAY beneficiary identification'),
                ('abdm.linkCareContext', 'a', '', 'Link care context (HIP)'), ('crs.report', 'a', '', 'Death report (CRS)')])
    p = pk.proc('sangam-sec-health-admission', 'Admission to discharge', '',
                start={'materialFields': 'patientName,abhaNumber,payerType'})
    s = p.root
    st = start_form(p, s, 'ad_start', 'Patient arrives', 'sec-health-admission-discharge/register',
                    'Patient registration | रोगी पंजीकरण',
                    ['patientName*', 'abhaNumber', 'admissionType*', 'payerType*', 'wardType', 'applicantRef'])
    d = s.setv('ad_defaults', 'Configuration',
               defaults='patientName=@customerId;abhaNumber=null;admissionType=EMERGENCY;payerType=SELF;wardType=GENERAL;'
                        'consentGiven=false;erSeat=ER-DOCTOR;admitSeat=ADMISSION-DESK;doctorSeat=TREATING-DOCTOR;'
                        'billingSeat=BILLING;applicantRef=@patientName')
    ab = s.conn('ad_abha', 'Verify ABHA', 'abdm.abhaVerify', 'verify', 'query', 'abhaNumber', 'abha')
    g, m = maybe(s, 'ad_abhaOpt', 'abhaNumber != null', ab, ab, 'ABHA given?')
    s.seq(st, d, g)
    eg = s.xgw('ad_erGw', 'Emergency?')
    s.flow(m, eg)
    tr = s.user('ad_triage', 'Triage and stabilise | ट्राइएज एवं स्थिरीकरण', '${erSeat}', form='sec-health-admission-discharge/triage',
                decision=None, fields=['triageLevel*', 'clinicalNotes'], priority='90')
    s.flow(eg, tr, "admissionType == 'EMERGENCY'", 'emergency')
    adm = s.user('ad_admit', 'Admit: bed, estimate of charges, consent | भर्ती: बिस्तर, अनुमानित व्यय, सहमति', '${admitSeat}',
                 form='sec-health-admission-discharge/admit', decision=None,
                 fields=['wardType*', 'estimatedCost*', 'consentGiven*', 'documents'])
    s.flow(eg, adm, None, 'planned', default=True)
    s.flow(tr, adm)
    cg = s.xgw('ad_consentGw', 'Consent recorded?')
    s.flow(adm, cg)
    s.flow(cg, adm, '!consentGiven', 'no: obtain consent')
    pg = s.xgw('ad_payerGw', 'Payer?')
    s.flow(cg, pg, None, 'yes', default=True)
    pa = s.conn('ad_preauth', 'Pre-authorisation request to the insurer / TPA (NHCX)', 'tpa.preauthRequest', 'preauth',
                'submit', 'patientName,estimatedCost', 'preauth', step='preauth')
    bis = s.conn('ad_bis', 'PM-JAY beneficiary identification and pre-authorisation', 'pmjay.bis', 'identify', 'submit',
                 'patientName,abhaNumber', 'pmjay', step='pmjay')
    care = s.user('ad_care', 'Inpatient care: orders and notes | अंतःरोगी देखभाल: आदेश एवं टिप्पणियाँ', '${doctorSeat}',
                  form='sec-health-admission-discharge/care',
                  decision=('careOutcome', ['discharge', 'transfer', 'lama', 'death']),
                  fields=['clinicalNotes', 'diagnosis'])
    s.flow(pg, pa, "payerType == 'INSURANCE'", 'insurance')
    s.flow(pg, bis, "payerType == 'PMJAY'", 'PM-JAY')
    s.flow(pg, care, None, 'self / corporate', default=True)
    s.flow(pa, care)
    s.flow(bis, care)
    og = s.xgw('ad_outGw', 'Outcome?')
    s.flow(care, og)
    fk = s.pgw('ad_fork', 'Discharge in parallel')
    jn = s.pgw('ad_join')
    s.flow(og, fk, "careOutcome == 'discharge'", 'discharge')
    for sid, en, hi, fl in (('doctorSeat', 'Discharge summary', 'डिस्चार्ज सारांश', ['dischargeSummary*']),
                            ('billingSeat', 'Final bill (itemised)', 'अंतिम बिल (मदवार)', ['finalBillAmount*'])):
        t = s.user('ad_' + sid, f'{en} | {hi}', '${' + sid + '}', form='sec-health-admission-discharge/' + sid.replace('Seat', ''),
                   decision=None, fields=fl)
        s.flow(fk, t)
        s.flow(t, jn)
    lk = s.conn('ad_link', 'Link discharge summary to ABHA (care context)', 'abdm.linkCareContext', 'link', 'submit',
                'abhaNumber,dischargeSummary', 'hip', step='link')
    g2, m2 = maybe(s, 'ad_linkOpt', 'abhaNumber != null', lk, lk, 'ABHA linked?')
    s.flow(jn, g2)
    s.flow(m2, end_ok(p, s, 'ad_end', 'Discharged'))
    la = s.user('ad_lama', 'Record LAMA / DAMA with risks explained | लामा दर्ज करें', '${doctorSeat}',
                form='sec-health-admission-discharge/lama', decision=None, fields=['documents*', 'clinicalNotes'])
    s.flow(og, la, "careOutcome == 'lama'", 'LAMA')
    s.flow(la, s.end('ad_endLama', 'Left against medical advice'))
    tf = s.user('ad_transfer', 'Transfer with referral note | रेफरल नोट सहित स्थानांतरण', '${doctorSeat}',
                form='sec-health-admission-discharge/transfer', decision=None, fields=['dischargeSummary*'])
    s.flow(og, tf, "careOutcome == 'transfer'", 'transfer')
    s.flow(tf, s.end('ad_endTransfer', 'Transferred'))
    de = s.user('ad_death', 'Death summary and MCCD | मृत्यु सारांश एवं एमसीसीडी', '${doctorSeat}',
                form='sec-health-admission-discharge/death', decision=None, fields=['dischargeSummary*', 'documents'])
    cr = s.conn('ad_crs', 'Report the death for civil registration (21 days)', 'crs.report', 'death', 'submit',
                'patientName', 'crs', step='crs')
    s.flow(og, de, None, 'death', default=True)
    s.seq(de, cr, s.end('ad_endDeath', 'Death reported'))
    s.side_exits('ad')
    simple_seeds(pk, 'HOSPITAL', [
        ('ER-DOCTOR', 'ERDOCTOR', 'MED-SUPT', 'Emergency physician', 'आपातकालीन चिकित्सक'),
        ('ADMISSION-DESK', 'ADMISSION', 'MED-SUPT', 'Admission desk', 'भर्ती डेस्क'),
        ('TREATING-DOCTOR', 'DOCTOR', 'MED-SUPT', 'Treating doctor', 'उपचारकर्ता चिकित्सक'),
        ('BILLING', 'BILLING', 'MED-SUPT', 'Billing', 'बिलिंग'),
        ('MED-SUPT', 'MS', None, 'Medical superintendent', 'चिकित्सा अधीक्षक')], 'had')


def health_abdm_consent():
    pk = spack('health', 'abdm-consent', 'ABDM consent and health information exchange', 'एबीडीएम सहमति एवं स्वास्थ्य सूचना आदान-प्रदान',
               'Healthcare',
               'Health Information User flow: consent request through the consent manager (purpose, HI types, date '
               'range, expiry), patient grants or denies, data request to the HIPs with encrypted transfer, '
               'clinician review, records purged at consent expiry or revocation; break-the-glass access with '
               'post-facto review; every step audited (DPDP data fiduciary duties).',
               ['SEC-HEALTH-006', 'SEC-HEALTH-007', 'SEC-HEALTH-008', 'SEC-LEGAL-001', 'UP-17', 'GP-15', 'GP-52'],
               [('abdm.consentRequest', 'b', '', 'Consent request (HIU to CM)'),
                ('abdm.hiRequest', 'b', '', 'Health information request'), ('records.purge', 'a', '', 'Purge fetched data')])
    p = pk.proc('sangam-sec-health-abdm-consent', 'ABDM consent and data fetch', '',
                start={'materialFields': 'abhaNumber,purposeOfUse,hiTypes'})
    s = p.root
    st = start_form(p, s, 'hx_start', 'Clinician requests records', 'sec-health-abdm-consent/request',
                    'Request health records | स्वास्थ्य अभिलेख का अनुरोध',
                    ['patientName*', 'abhaNumber*', 'purposeOfUse*', 'hiTypes*', 'consentValidity'])
    d = s.setv('hx_defaults', 'Configuration', defaults='abhaNumber=@customerId;purposeOfUse=CAREMGT;'
               'hiTypes=DiagnosticReport,Prescription;consentValidity=P30D;clinicianSeat=TREATING-DOCTOR')
    bg = s.xgw('hx_btgGw', 'Break the glass?')
    s.seq(st, d, bg)
    bt = s.user('hx_btg', 'Emergency access justification | आपातकालीन पहुँच का औचित्य', '${clinicianSeat}',
                form='sec-health-abdm-consent/btg', decision=None, fields=['justification*'])
    rv = s.user('hx_btgReview', 'Post-facto review of emergency access | आपातकालीन पहुँच की पश्चात समीक्षा', 'PRIVACY-OFFICER',
                form='sec-health-abdm-consent/btg-review', decision=('btgDecision', ['accept', 'reject']),
                fields=['remarks*'], sod=True)
    s.flow(bg, bt, "purposeOfUse == 'BTG'", 'emergency')
    s.flow(bt, rv)
    s.flow(rv, s.end('hx_endBtg', 'Emergency access reviewed'))
    cr = s.conn('hx_consent', 'Consent request to the consent manager', 'abdm.consentRequest', 'request', 'submit',
                'abhaNumber,purposeOfUse,hiTypes,consentValidity', 'consent', step='consent-request')
    s.flow(bg, cr, None, 'normal', default=True)
    w = s.egw('hx_wait', 'Granted, denied or expired')
    s.flow(cr, w)
    gr = s.catch_msg('hx_granted', 'Consent granted (artefact per HIP)', 'sangam.abdm.consentGranted')
    dn = s.catch_msg('hx_denied', 'Consent denied', 'sangam.abdm.consentDenied')
    ex = s.catch_timer('hx_expired', 'Request expired', duration='P7D')
    for x in (gr, dn, ex):
        s.flow(w, x)
    s.flow(dn, s.end('hx_endDenied', 'Denied'))
    s.flow(ex, s.end('hx_endExpired', 'Request expired'))
    hr = s.conn('hx_hiRequest', 'Data request to HIPs (encryption keys)', 'abdm.hiRequest', 'fetch', 'submit',
                'abhaNumber,hiTypes', 'hi', step='hi-request')
    s.flow(gr, hr)
    w2 = s.egw('hx_dataWait', 'Data pushed or timeout')
    s.flow(hr, w2)
    dp = s.catch_msg('hx_pushed', 'Data pushed by HIP', 'sangam.abdm.dataPushed')
    dt = s.catch_timer('hx_dataTimeout', 'No data in time', duration='PT1H')
    s.flow(w2, dp)
    s.flow(w2, dt)
    s.flow(dt, s.end('hx_endNoData', 'HIP did not respond'))
    rvw = s.user('hx_review', 'Review linked records | लिंक किए गए अभिलेखों की समीक्षा', '${clinicianSeat}',
                 form='sec-health-abdm-consent/review', decision=None, fields=['clinicalNotes'])
    exp = clock(s, 'hx_expiry', 'consent', '${consentValidity}')
    s.seq(dp, rvw, exp)
    w3 = s.egw('hx_life', 'Expiry or revocation')
    s.flow(exp, w3)
    ce = s.catch_timer('hx_consentEnd', 'Consent expired', date='${consentDueAt}')
    cv = s.catch_msg('hx_revoked', 'Consent revoked', 'sangam.abdm.consentRevoked')
    s.flow(w3, ce)
    s.flow(w3, cv)
    pj = s.xgw('hx_purgeJoin')
    s.flow(ce, pj)
    s.flow(cv, pj)
    pu = s.conn('hx_purge', 'Purge fetched records', 'records.purge', 'purge', 'submit', 'abhaNumber', 'purge', step='purge')
    s.seq(pj, pu, end_ok(p, s, 'hx_end', 'Consent lifecycle closed'))
    simple_seeds(pk, 'HIU', [('TREATING-DOCTOR', 'DOCTOR', 'PRIVACY-OFFICER', 'Treating doctor', 'उपचारकर्ता चिकित्सक'),
                             ('PRIVACY-OFFICER', 'PRIVACY', None, 'Privacy officer', 'गोपनीयता अधिकारी')], 'hhx')


def health_lab():
    pk = spack('health', 'lab-order', 'Laboratory order to report', 'प्रयोगशाला आदेश से रिपोर्ट तक', 'Healthcare',
               'Order (CPOE) with TAT by priority, sample collection and barcode, accession with rejection and '
               're-collection, analysis and technical validation, pathologist authorisation, critical values called '
               'to the clinician with read-back within the policy time (NABH), report released as a FHIR '
               'DiagnosticReport and linked to ABHA; amended reports with reason.',
               ['SEC-HEALTH-009', 'SEC-HEALTH-010', 'GP-06', 'UP-04', 'UP-18'],
               [('lis.accession', 'a', '', 'LIS accession'), ('lis.report', 'a', '', 'Report release'),
                ('abdm.linkCareContext', 'a', '', 'Link to ABHA')])
    p = pk.proc('sangam-sec-health-lab-order', 'Lab order', '', start={'materialFields': 'patientName,testCodes,priority'})
    s = p.root
    st = start_form(p, s, 'lb_start', 'Order placed', 'sec-health-lab-order/order', 'Laboratory order | प्रयोगशाला आदेश',
                    ['patientName*', 'testCodes*', 'priority*', 'clinicalNotes'])
    d = s.setv('lb_defaults', 'Configuration', defaults='patientName=@customerId;testCodes=CBC;criticalValue=false;'
               'phleboSeat=PHLEBOTOMY;techSeat=LAB-TECH;pathSeat=PATHOLOGIST')
    r = s.rule('lb_tat', 'TAT by priority (DMN)', 'sec-health-lab-tat')
    s.seq(st, d, r)
    col = s.user('lb_collect', 'Collect sample and label | नमूना संग्रह एवं लेबल', '${phleboSeat}',
                 form='sec-health-lab-order/collect', decision=('collectDecision', ['collected', 'reschedule']),
                 fields=['sampleBarcode*', 'remarks'])
    s.flow(r, col)
    cg = s.xgw('lb_colGw')
    s.flow(col, cg)
    s.flow(cg, col, "collectDecision == 'reschedule'", 'patient unavailable')
    acc = s.conn('lb_accession', 'Accession in LIS', 'lis.accession', 'accession', 'submit', 'sampleBarcode,testCodes',
                 'acc', step='accession')
    s.flow(cg, acc, None, 'collected', default=True)
    chk = s.user('lb_check', 'Sample acceptance check | नमूना स्वीकृति जाँच', '${techSeat}', form='sec-health-lab-order/check',
                 decision=('sampleDecision', ['accept', 'reject']), fields=['remarks'])
    s.flow(acc, chk)
    sg = s.xgw('lb_sampleGw')
    s.flow(chk, sg)
    s.flow(sg, col, "sampleDecision == 'reject'", 'haemolysed / mislabelled: re-collect')
    re_, rd = s.sla_task('lb_run', 'Run test and technical validation | परीक्षण एवं तकनीकी सत्यापन', 'techSeat',
                         form='sec-health-lab-order/run', decision=None, fields=['results*', 'criticalValue'],
                         spec='${tatSpec}')
    s.flow(sg, re_, None, 'accept', default=True)
    au = s.user('lb_authorise', 'Pathologist authorisation | रोगविज्ञानी अनुमोदन', '${pathSeat}',
                form='sec-health-lab-order/authorise', decision=('authDecision', ['release', 'repeat']),
                fields=['results', 'remarks'], sod=True)
    s.flow(rd, au)
    ag = s.xgw('lb_authGw')
    s.flow(au, ag)
    s.flow(ag, re_, "authDecision == 'repeat'", 'repeat')
    ce, cd = s.sla_task('lb_critical', 'Call critical value to the clinician (read-back) | गंभीर मान की सूचना',
                        'pathSeat', form='sec-health-lab-order/critical', decision=None, fields=['remarks*'],
                        spec='${criticalSpec}', on_breach='ESCALATE')
    g2 = s.xgw('lb_critGw', 'Critical value?')
    m2 = s.xgw('lb_critMerge')
    s.flow(ag, g2, None, 'release', default=True)
    s.flow(g2, ce, 'criticalValue', 'yes')
    s.flow(g2, m2, None, 'no', default=True)
    s.flow(cd, m2)
    rel = s.conn('lb_release', 'Release report (FHIR DiagnosticReport)', 'lis.report', 'release', 'submit',
                 'patientName,testCodes,results', 'report', step='release')
    lk = s.conn('lb_link', 'Link to ABHA', 'abdm.linkCareContext', 'link', 'submit', 'patientName', 'hip', step='link')
    s.seq(m2, rel, lk, end_ok(p, s, 'lb_end', 'Reported'))
    am = s.evsub('lb_amend', 'Amended report')
    a1 = am.start('lb_amStart', 'Amendment requested', msg='sangam.lab.amend', interrupting=False)
    a2 = am.user('lb_amendTask', 'Issue amended report with reason | कारण सहित संशोधित रिपोर्ट', '${pathSeat}',
                 form='sec-health-lab-order/amend', decision=None, fields=['results*', 'remarks*'])
    a3 = am.notify('lb_amNotify', 'Tell the clinician', 'lab-report-amended', 'inapp', 'initiatorSeat')
    am.seq(a1, a2, a3, am.end('lb_amEnd', 'Amended'))
    pk.dmn('sec-health-lab-tat', 'Laboratory TAT (SYNTHETIC hospital policy)', 'FIRST', [('Priority', 'priority', 'string')],
           [('tatSpec', 'TAT', 'string'), ('criticalSpec', 'Critical value call within', 'string')],
           [([q('P1')], [q('1H'), q('30MIN')], 'STAT'), (['-'], [q('6H'), q('30MIN')], 'routine')])
    simple_seeds(pk, 'LAB', [('PHLEBOTOMY', 'PHLEBO', 'LAB-TECH', 'Phlebotomist', 'फ्लेबोटोमिस्ट'),
                             ('LAB-TECH', 'TECH', 'PATHOLOGIST', 'Laboratory technologist', 'प्रयोगशाला तकनीशियन'),
                             ('PATHOLOGIST', 'PATH', None, 'Pathologist', 'रोगविज्ञानी')], 'hlb')


def health_pharmacy():
    pk = spack('health', 'pharmacy-dispense', 'Pharmacy dispensing with schedule controls', 'अनुसूची नियंत्रण सहित औषधि वितरण',
               'Healthcare',
               'Prescription check by schedule (Drugs and Cosmetics Rules: H, H1, X; NDPS): prescriber verified on '
               'the National Medical Register, pharmacist review (dose, interactions, allergy) with clarification '
               'from the prescriber, second pharmacist for X / NDPS, issue by batch and expiry, Schedule H1 '
               'register entry retained for three years.',
               ['SEC-HEALTH-011', 'SEC-HEALTH-012', 'UP-05', 'GP-16'],
               [('nmr.verify', 'a', '', 'National Medical Register'), ('inventory.issue', 'a', '', 'Stock issue (FEFO)'),
                ('docs.register', 'a', '', 'H1 / X register entry')])
    p = pk.proc('sangam-sec-health-pharmacy', 'Dispensing', '', start={'materialFields': 'patientName,prescriptionRef,drugSchedule'})
    s = p.root
    st = start_form(p, s, 'ph_start', 'Prescription presented', 'sec-health-pharmacy-dispense/prescription',
                    'Prescription | पर्चा', ['patientName*', 'prescriptionRef*', 'drugSchedule*', 'prescriberRegNo', 'quantity'])
    d = s.setv('ph_defaults', 'Configuration', defaults='patientName=@customerId;prescriptionRef=@customerId;'
               'drugSchedule=H;prescriberRegNo=NMR-X;prescriberValid=true;pharmacistSeat=PHARMACIST')
    r = s.rule('ph_rule', 'Schedule controls (DMN)', 'sec-health-drug-control')
    s.seq(st, d, r)
    nm = s.conn('ph_nmr', 'Verify prescriber on NMR', 'nmr.verify', 'verify', 'query', 'prescriberRegNo', 'prescriber')
    g, m = maybe(s, 'ph_rxOpt', 'prescriptionRequired', nm, nm, 'Prescription drug?')
    s.flow(r, g)
    vg = s.xgw('ph_validGw', 'Prescriber valid?')
    s.flow(m, vg)
    s.flow(vg, reject_with(p, s, 'ph_rjRx', 'PRESCRIBER_NOT_VERIFIED'), 'prescriptionRequired && !prescriberValid', 'no')
    pv = s.user('ph_review', 'Pharmacist review | फार्मासिस्ट समीक्षा', '${pharmacistSeat}',
                form='sec-health-pharmacy-dispense/review', decision=('rxDecision', ['dispense', 'clarify', 'refuse']),
                fields=['remarks'])
    s.flow(vg, pv, None, 'yes', default=True)
    pg = s.xgw('ph_revGw')
    s.flow(pv, pg)
    ask = s.notify('ph_ask', 'Ask the prescriber', 'rx-clarification', 'inapp', 'initiatorSeat')
    rp = s.catch_msg('ph_answer', 'Prescriber answers', 'sangam.rx.clarified')
    s.flow(pg, ask, "rxDecision == 'clarify'", 'clarify')
    s.seq(ask, rp, pv)
    s.flow(pg, s.end('ph_endRefused', 'Refused (reasons recorded)'), "rxDecision == 'refuse'", 'refuse')
    dc = s.user('ph_second', 'Second pharmacist check (X / NDPS) | द्वितीय फार्मासिस्ट जाँच', 'PHARMACIST-2',
                form='sec-health-pharmacy-dispense/second', decision=None, fields=['remarks*'], sod=True)
    g2, m2 = maybe(s, 'ph_dual', 'dualCheck', dc, dc, 'Dual check?')
    s.flow(pg, g2, None, 'dispense', default=True)
    iss = s.conn('ph_issue', 'Issue stock by batch and expiry', 'inventory.issue', 'issue', 'submit',
                 'prescriptionRef,quantity', 'stock', step='issue')
    s.flow(m2, iss)
    reg = s.conn('ph_register', 'Register entry (retain 3 years)', 'docs.register', 'h1', 'submit',
                 'patientName,prescriberRegNo,prescriptionRef,quantity', 'register', step='register')
    g3, m3 = maybe(s, 'ph_regOpt', 'registerRequired', reg, reg, 'Register required?')
    s.flow(iss, g3)
    s.flow(m3, end_ok(p, s, 'ph_end', 'Dispensed'))
    pk.dmn('sec-health-drug-control', 'Drug schedule controls', 'FIRST', [('Schedule', 'drugSchedule', 'string')],
           [('prescriptionRequired', 'Prescription', 'boolean'), ('registerRequired', 'Register', 'boolean'),
            ('dualCheck', 'Second pharmacist', 'boolean')],
           [([q('OTC')], ['false', 'false', 'false'], ''), ([q('H')], ['true', 'false', 'false'], ''),
            ([q('H1')], ['true', 'true', 'false'], 'H1 register: patient, prescriber, drug, quantity'),
            ([q('X'), ], ['true', 'true', 'true'], 'Schedule X: copy retained, dual check'),
            (['-'], ['true', 'true', 'true'], 'NDPS and anything unknown: strictest')])
    simple_seeds(pk, 'PHARMACY', [('PHARMACIST', 'PHARMACIST', 'CHIEF-PHARMACIST', 'Registered pharmacist', 'पंजीकृत फार्मासिस्ट'),
                                  ('PHARMACIST-2', 'PHARMACIST', 'CHIEF-PHARMACIST', 'Second pharmacist', 'द्वितीय फार्मासिस्ट'),
                                  ('CHIEF-PHARMACIST', 'CHIEF', None, 'Chief pharmacist', 'मुख्य फार्मासिस्ट')], 'hph')


# ============================================================================================ e-commerce
def ecom_order():
    pk = spack('ecom', 'order-to-cash', 'Marketplace order to cash', 'मार्केटप्लेस आदेश से नकद प्राप्ति तक', 'E-commerce',
               'Order with fraud screen, payment authorised once, seller acceptance within the SLA (auto-cancel and '
               'refund on breach), shipment, delivery, RTO and delay handling, COD remittance, seller settlement '
               'after the return window less commission; consumer cancellation before shipment without a charge '
               'the entity does not itself bear (E-Commerce Rules r.4(10)); refunds to the source.',
               ['SEC-ECOM-001', 'SEC-ECOM-002', 'SEC-ECOM-003', 'UP-01', 'UP-02', 'UP-12', 'GP-12', 'GP-13', 'GP-15'],
               [('fraud.score', 'a', '', 'Risk screen'), ('pg.authorise', 'b', '', 'Payment authorisation'),
                ('pg.refund', 'a', '', 'Refund to source'), ('logistics.ship', 'a', '', 'Shipment'),
                ('logistics.codRemit', 'a', '', 'COD remittance'), ('nodal.settle', 'c', '', 'Seller settlement')])
    p = pk.proc('sangam-sec-ecom-order', 'Marketplace order', '',
                start={'materialFields': 'orderId,customerId,sellerId,amount,paymentMode'})
    s = p.root
    st = start_form(p, s, 'eo_start', 'Order placed', 'sec-ecom-order-to-cash/order', 'Order | आदेश',
                    ['orderId*', 'customerId*', 'sellerId*', 'amount*', 'paymentMode*', 'category', 'pincode'])
    d = s.setv('eo_defaults', 'Configuration',
               defaults='sellerId=SELLER-1;paymentMode=PREPAID;category=GENERAL;riskHold=false;sellerSla=2D;'
                        'deliverySla=7D;returnWindow=P7D;sellerGroup=SELLER-DESK;executionConnector=pg.refund;'
                        'executionStep=refund;verifyApproval=false;reconcileRequired=false;'
                        'executionPayloadFields=orderId,amount;applicantRef=@customerId')
    fr = s.conn('eo_fraud', 'Fraud screen', 'fraud.score', 'score', 'query', 'orderId,customerId,amount', 'risk')
    hg = s.xgw('eo_holdGw', 'Risky?')
    s.seq(st, d, fr, hg)
    hv = s.user('eo_review', 'Review risky order | जोखिमपूर्ण आदेश की समीक्षा', 'RISK-DESK', form='sec-ecom-order-to-cash/review',
                decision=('riskDecision', ['approve', 'reject']), fields=['remarks*'])
    s.flow(hg, hv, 'riskHold', 'hold')
    s.flow(hv, s.xgw('eo_revGw'))
    s.flow('eo_revGw', s.end('eo_endRiskRejected', 'Cancelled by risk review'), "riskDecision == 'reject'", 'reject')
    pg = s.xgw('eo_payGw', 'Prepaid?')
    s.flow(hg, pg, None, 'clear', default=True)
    s.flow('eo_revGw', pg, None, 'approve', default=True)
    au = s.conn('eo_auth', 'Authorise payment', 'pg.authorise', 'authorise', 'submit', 'orderId,amount', 'pay',
                step='authorise', error='PAYMENT_FAILED')
    s.flow(pg, au, "paymentMode == 'PREPAID'", 'prepaid')
    pf = s.boundary('eo_payFail', au, 'Payment failed', error='PAYMENT_FAILED')
    s.flow(pf, s.end('eo_endPayFailed', 'Payment failed'))
    se, sd = s.sla_task('eo_seller', 'Seller confirms and packs | विक्रेता द्वारा पुष्टि एवं पैकिंग', 'sellerGroup',
                        form='sec-ecom-order-to-cash/seller', decision=('sellerDecision', ['accept', 'reject']),
                        fields=['remarks'], spec='${sellerSla}', on_breach='DEEMED_REFUSAL', approve='accept',
                        refuse='reject', max_esc=0)
    s.flow(au, se)
    s.flow(pg, se, None, 'COD', default=True)
    sgw = s.xgw('eo_sellerGw')
    s.flow(sd, sgw)
    rf = core(s, 'eo_refund', 'Refund to the source (core execution)', 'execution')
    rfg = s.xgw('eo_refundGw', 'Refund needed?')
    s.flow(sgw, rfg, "sellerDecision != 'accept'", 'rejected / not confirmed')
    s.flow(rfg, rf, "paymentMode == 'PREPAID'", 'prepaid')
    can = s.end('eo_endCancelled', 'Cancelled')
    s.flow(rfg, can, None, 'COD', default=True)
    s.flow(rf, can)
    sh = s.conn('eo_ship', 'Create shipment', 'logistics.ship', 'ship', 'submit', 'orderId,pincode', 'ship', step='ship')
    s.flow(sgw, sh, None, 'accepted', default=True)
    dc = clock(s, 'eo_delClock', 'delivery', '${deliverySla}')
    w = s.egw('eo_wait', 'Delivered, RTO or late')
    s.seq(sh, dc, w)
    dv = s.catch_msg('eo_delivered', 'Delivered', 'sangam.order.delivered')
    rto = s.catch_msg('eo_rto', 'Returned to origin (failed delivery)', 'sangam.order.rto')
    lt = s.catch_timer('eo_late', 'Delivery promise missed', date='${deliveryDueAt}')
    for x in (dv, rto, lt):
        s.flow(w, x)
    inv = s.user('eo_investigate', 'Investigate delayed shipment | विलंबित शिपमेंट की जाँच', 'LOGISTICS-DESK',
                 form='sec-ecom-order-to-cash/delay', decision=None, fields=['remarks*'])
    ext = clock(s, 'eo_delClock2', 'delivery', '3D')
    s.seq(lt, inv, ext, w)
    s.flow(rto, rfg)
    cod = s.conn('eo_cod', 'COD remittance', 'logistics.codRemit', 'remit', 'submit', 'orderId,amount', 'cod', step='cod')
    g2, m2 = maybe(s, 'eo_codOpt', "paymentMode == 'COD'", cod, cod, 'COD?')
    s.flow(dv, g2)
    rw = s.catch_timer('eo_returnWindow', 'Return window over', duration='${returnWindow}')
    cm = s.rule('eo_commission', 'Commission (DMN)', 'sec-ecom-commission')
    st2 = s.setv('eo_settleCfg', 'Settlement', values='executionConnector=nodal.settle;executionStep=seller-settlement;'
                 'executionPayloadFields=sellerId,amount,commissionPct')
    pay = core(s, 'eo_settle', 'Settle with the seller (core execution)', 'execution')
    s.seq(m2, rw, cm, st2, pay, end_ok(p, s, 'eo_end', 'Settled'))
    cx = s.evsub('eo_cancel', 'Cancelled by the consumer before shipment')
    c1 = cx.start('eo_cxStart', 'Cancellation request', msg='sangam.order.cancel', interrupting=True)
    c2 = cx.setv('eo_cxCfg', 'Refund settings', values='executionConnector=pg.refund;executionStep=cancel-refund')
    c3 = cx.call('eo_cxRefund', 'Refund in full (core execution)', 'sangam-core-execution')
    cx.seq(c1, c2, c3, cx.end('eo_cxEnd', 'Cancelled by the consumer'))
    pk.dmn('sec-ecom-commission', 'Marketplace commission (SYNTHETIC)', 'FIRST', [('Category', 'category', 'string')],
           [('commissionPct', 'Commission %', 'integer')],
           [([q('ELECTRONICS')], ['8'], ''), ([q('FASHION')], ['18'], ''), (['-'], ['12'], '')])
    simple_seeds(pk, 'MARKETPLACE', [('SELLER-DESK', 'SELLER', None, 'Seller panel (external)', 'विक्रेता पैनल (बाह्य)'),
                                     ('RISK-DESK', 'RISK', None, 'Risk desk', 'जोखिम डेस्क'),
                                     ('LOGISTICS-DESK', 'LOGISTICS', None, 'Logistics desk', 'लॉजिस्टिक्स डेस्क')], 'eeo')


def ecom_return():
    pk = spack('ecom', 'return-refund', 'Return, replacement and refund', 'वापसी, प्रतिस्थापन एवं धनवापसी', 'E-commerce',
               'Return policy by reason and category (displayed before purchase), pickup with failure handling, '
               'quality check, seller dispute arbitrated by the marketplace, replacement or refund to the original '
               'payment method once by operationId within the refund timeline.',
               ['SEC-ECOM-004', 'SEC-ECOM-005', 'UP-13', 'GP-13', 'GP-12'],
               [('shipping.pickup', 'a', '', 'Reverse pickup'), ('order.replacement', 'a', '', 'Replacement order'),
                ('pg.refund', 'a', '', 'Refund to source')])
    p = pk.proc('sangam-sec-ecom-return', 'Return and refund', '', start={'materialFields': 'orderId,returnReason,refundAmount'})
    s = p.root
    st = start_form(p, s, 'er_start', 'Return requested', 'sec-ecom-return-refund/request', 'Return request | वापसी अनुरोध',
                    ['orderId*', 'customerId*', 'returnReason*', 'refundAmount*', 'resolutionPref*', 'photos'])
    d = s.setv('er_defaults', 'Configuration',
               defaults='returnReason=DAMAGED;resolutionPref=REFUND;category=GENERAL;qcSeat=RETURNS-QC;'
                        'executionConnector=pg.refund;executionStep=refund;verifyApproval=false;reconcileRequired=false;'
                        'executionPayloadFields=orderId,refundAmount;applicantRef=@customerId')
    r = s.rule('er_policy', 'Return policy (DMN)', 'sec-ecom-return-policy')
    g = s.xgw('er_gw', 'Eligible?')
    s.seq(st, d, r, g)
    nn = s.notify('er_notEligible', 'Not eligible: reason given', 'return-not-eligible', 'sms,email', 'applicantRef')
    s.flow(g, nn, '!returnEligible', 'no')
    s.flow(nn, s.end('er_endIneligible', 'Not eligible'))
    pu = s.conn('er_pickup', 'Arrange reverse pickup', 'shipping.pickup', 'pickup', 'submit', 'orderId', 'pickup', step='pickup')
    s.flow(g, pu, None, 'yes', default=True)
    w = s.egw('er_wait', 'Received or pickup failed')
    s.flow(pu, w)
    rc = s.catch_msg('er_received', 'Received at the warehouse', 'sangam.returnReceived')
    tf = s.catch_timer('er_pickFail', 'Pickup not done', duration='P10D')
    s.flow(w, rc)
    s.flow(w, tf)
    s.flow(tf, s.notify('er_pickNotify', 'Pickup failed: reschedule or self-ship', 'pickup-failed', 'sms,email', 'applicantRef'))
    s.flow('er_pickNotify', s.end('er_endPickFailed', 'Pickup failed'))
    qc = s.user('er_qc', 'Quality check | गुणवत्ता जाँच', '${qcSeat}', form='sec-ecom-return-refund/qc',
                decision=('qcDecision', ['accept', 'reject']), fields=['photos', 'remarks*'])
    s.flow(rc, qc)
    qg = s.xgw('er_qcGw')
    s.flow(qc, qg)
    ds = s.user('er_dispute', 'Marketplace arbitration of the seller dispute | विक्रेता विवाद पर मध्यस्थता', 'MARKETPLACE-ARB',
                form='sec-ecom-return-refund/dispute', decision=('disputeDecision', ['customer', 'seller']),
                fields=['remarks*'], sod=True)
    s.flow(qg, ds, "qcDecision == 'reject'", 'QC failed')
    dg = s.xgw('er_dispGw')
    s.flow(ds, dg)
    s.flow(dg, s.end('er_endRejected', 'Return rejected: item sent back'), "disputeDecision == 'seller'", 'seller')
    rg = s.xgw('er_resGw', 'Refund or replacement?')
    s.flow(qg, rg, None, 'accept', default=True)
    s.flow(dg, rg, None, 'customer', default=True)
    rp = s.conn('er_replace', 'Create replacement order', 'order.replacement', 'create', 'submit', 'orderId', 'repl',
                step='replacement')
    s.flow(rg, rp, "resolutionPref == 'REPLACE'", 'replace')
    s.flow(rp, end_ok(p, s, 'er_endReplaced', 'Replaced'))
    rf = core(s, 'er_refund', 'Refund to the original method (core execution)', 'execution')
    s.flow(rg, rf, None, 'refund', default=True)
    s.flow(rf, end_ok(p, s, 'er_end', 'Refunded'))
    pk.dmn('sec-ecom-return-policy', 'Return policy (SYNTHETIC; shown to the consumer before purchase)', 'FIRST',
           [('Reason', 'returnReason', 'string'), ('Category', 'category', 'string')],
           [('returnEligible', 'Eligible', 'boolean')],
           [(['-', q('PERISHABLE')], ['false'], 'perishables not returnable unless damaged on arrival'),
            ([q('CHANGED_MIND'), q('INNERWEAR')], ['false'], ''), (['-', '-'], ['true'], '')])
    simple_seeds(pk, 'RETURNS', [('RETURNS-QC', 'QC', 'MARKETPLACE-ARB', 'Returns quality check', 'वापसी गुणवत्ता जाँच'),
                                 ('MARKETPLACE-ARB', 'ARBITER', None, 'Marketplace dispute desk', 'मार्केटप्लेस विवाद डेस्क')], 'eer')


def ecom_seller():
    pk = spack('ecom', 'seller-onboarding', 'Marketplace seller onboarding', 'मार्केटप्लेस विक्रेता पंजीकरण', 'E-commerce',
               'Seller KYC (GSTIN, PAN, bank account by penny drop), category licences (FSSAI, drug licence, BIS) '
               'by rule, review with clarification rounds through the seller portal, maker-checker approval, seller '
               'agreement, publication of the seller\'s name, address, grievance officer and ratings on the '
               'platform (E-Commerce Rules r.5 and r.6), country of origin captured per listing.',
               ['SEC-ECOM-006', 'SEC-ECOM-008', 'UP-22', 'UP-05', 'GP-16'],
               [('gst.verify', 'a', '', 'GSTIN status'), ('pan.verify', 'a', '', 'PAN'), ('bene.validate', 'a', '', 'Penny drop'),
                ('marketplace.createSeller', 'a', '', 'Seller account'), ('catalogue.publishSeller', 'a', '', 'Seller profile')])
    p = pk.proc('sangam-sec-ecom-seller-onboarding', 'Seller onboarding', '',
                start={'materialFields': 'sellerName,gstin,pan,category'})
    s = p.root
    st = start_form(p, s, 'es_start', 'Seller applies', 'sec-ecom-seller-onboarding/apply', 'Seller registration | विक्रेता पंजीकरण',
                    ['sellerName*', 'gstin*', 'pan*', 'bankAccount*', 'ifsc*', 'grievanceOfficerName*', 'category',
                     'documents'])
    d = s.setv('es_defaults', 'Configuration', defaults='sellerName=@supplierName;gstin=X;category=GENERAL;gstActive=true;'
               'beneNameMatch=true;reviewSeat=SELLER-ONBOARDING')
    gv = s.conn('es_gst', 'Verify GSTIN', 'gst.verify', 'verify', 'query', 'gstin', 'gst')
    pv = s.conn('es_pan', 'Verify PAN', 'pan.verify', 'verify', 'query', 'pan,sellerName', 'pan')
    bv = s.conn('es_bank', 'Penny-drop bank account', 'bene.validate', 'lookup', 'query', 'bankAccount,ifsc,sellerName', 'bene')
    g = s.xgw('es_kycGw', 'KYC passed?')
    s.seq(st, d, gv, pv, bv, g)
    rv = revise(p, s, 'es_correct', 'Correct registration details | पंजीकरण विवरण सुधारें',
                ['gstin*', 'pan*', 'bankAccount*', 'ifsc*'], gv)
    s.flow(g, rv, '!gstActive || !beneNameMatch', 'no')
    r = s.rule('es_docs', 'Category licences required (DMN)', 'sec-ecom-seller-docs')
    s.flow(g, r, None, 'yes', default=True)
    re_, rd = s.sla_task('es_review', 'Review licences and agreement | अनुज्ञप्ति एवं अनुबंध की समीक्षा', 'reviewSeat',
                         form='sec-ecom-seller-onboarding/review', decision=('sellerDecision', ['accept', 'clarify', 'reject']),
                         fields=['docsRequired', 'remarks*'], spec='3WD')
    s.flow(r, re_)
    rg = s.xgw('es_revGw')
    s.flow(rd, rg)
    grp = s.expr('es_group', 'Seller portal group', "${execution.setVariable('sellerGroup', 'external:'.concat(gstin))}")
    sp = s.user('es_portal', 'Upload requested documents | मांगे गए दस्तावेज़ अपलोड करें', '${sellerGroup}',
                form='sec-ecom-seller-onboarding/portal', decision=None, fields=['documents*', 'remarks'])
    s.flow(rg, grp, "sellerDecision == 'clarify'", 'clarify')
    s.seq(grp, sp, re_)
    s.flow(rg, s.end('es_endRejected', 'Rejected (reasons given)'), "sellerDecision == 'reject'", 'reject')
    ap = s.user('es_approve', 'Approve seller (checker) | विक्रेता अनुमोदन (जाँचकर्ता)', 'SELLER-APPROVER',
                form='sec-ecom-seller-onboarding/approve', decision=('approveDecision', ['approve', 'return']),
                fields=['remarks'], sod=True)
    s.flow(rg, ap, None, 'accept', default=True)
    ag = s.xgw('es_apGw')
    s.flow(ap, ag)
    s.flow(ag, re_, "approveDecision == 'return'", 'return')
    cr = s.conn('es_create', 'Create seller account', 'marketplace.createSeller', 'create', 'submit', 'sellerName,gstin',
                'seller', step='create')
    pb = s.conn('es_publish', 'Publish seller profile and grievance officer', 'catalogue.publishSeller', 'publish', 'submit',
                'sellerName,grievanceOfficerName', 'profile', step='publish')
    s.flow(ag, cr, None, 'approve', default=True)
    s.seq(cr, pb, end_ok(p, s, 'es_end', 'Seller live'))
    pk.dmn('sec-ecom-seller-docs', 'Licences by category', 'FIRST', [('Category', 'category', 'string')],
           [('docsRequired', 'Licences', 'string')],
           [([q('FOOD')], [q('FSSAI licence')], ''), ([q('PHARMA')], [q('Drug licence (Form 20 / 21)')], ''),
            ([q('ELECTRONICS')], [q('BIS registration (CRS)')], ''), (['-'], [q('none beyond KYC')], '')])
    simple_seeds(pk, 'SELLER-OPS', [('SELLER-ONBOARDING', 'ONBOARDING', 'SELLER-APPROVER', 'Seller onboarding desk', 'विक्रेता पंजीकरण डेस्क'),
                                    ('SELLER-APPROVER', 'APPROVER', None, 'Seller approver', 'विक्रेता अनुमोदक')], 'ees')


# ============================================================================================ telecom
def tel_mnp():
    pk = spack('tel', 'mnp-port-in', 'Mobile number portability (port-in)', 'मोबाइल नंबर पोर्टेबिलिटी (पोर्ट-इन)', 'Telecom',
               'Port-in request with a valid UPC, subscriber KYC (e-KYC / D-KYC), request to the MNP service '
               'provider, donor acceptance or rejection with the coded reason, porting window and activation within '
               'the TRAI timeline (intra / inter service area, corporate), escalation when late.',
               ['SEC-TEL-001', 'SEC-TEL-002', 'UP-12', 'GP-15'],
               [('dot.kyc', 'a', '', 'Subscriber e-KYC'), ('mnpsp.port', 'b', '', 'MNP service provider'),
                ('hlr.activate', 'a', '', 'Network activation')])
    p = pk.proc('sangam-sec-tel-mnp', 'Port-in', '', start={'materialFields': 'msisdn,upc,donorOperator,lsaType'})
    s = p.root
    st = start_form(p, s, 'mn_start', 'Port-in request', 'sec-tel-mnp-port-in/request', 'Port-in request | पोर्ट-इन अनुरोध',
                    ['msisdn*', 'customerName*', 'upc*', 'donorOperator*', 'lsaType*', 'customerType'])
    d = s.setv('mn_defaults', 'Configuration', defaults='msisdn=@customerId;upc=X;donorOperator=OP-X;lsaType=INTRA;'
               'customerType=INDIVIDUAL;kycOk=true;opsSeat=MNP-DESK;applicantRef=@msisdn')
    r = s.rule('mn_tl', 'Porting timeline (DMN)', 'sec-tel-mnp-timeline')
    ky = s.conn('mn_kyc', 'Subscriber KYC', 'dot.kyc', 'ekyc', 'query', 'msisdn,customerName', 'kyc')
    g = s.xgw('mn_kycGw', 'KYC ok?')
    s.seq(st, d, r, ky, g)
    s.flow(g, reject_with(p, s, 'mn_rjKyc', 'KYC_FAILED'), '!kycOk', 'no')
    cl = clock(s, 'mn_clock', 'port', '${portSpec}')
    rq = s.conn('mn_request', 'Port request to MNPSP', 'mnpsp.port', 'request', 'submit', 'msisdn,upc,donorOperator', 'port',
                step='port-request')
    s.flow(g, cl, None, 'yes', default=True)
    s.flow(cl, rq)
    w = s.egw('mn_wait', 'Donor response or timeline')
    s.flow(rq, w)
    ac = s.catch_msg('mn_accepted', 'Donor accepted', 'sangam.mnp.donorAccepted')
    rj = s.catch_msg('mn_rejected', 'Donor rejected (coded reason)', 'sangam.mnp.donorRejected')
    lt = s.catch_timer('mn_late', 'Timeline at risk', date='${portDueAt}')
    for x in (ac, rj, lt):
        s.flow(w, x)
    nr = s.notify('mn_rejNotify', 'Tell the subscriber the reason', 'mnp-rejected', 'sms', 'applicantRef')
    s.seq(rj, nr, s.end('mn_endRejected', 'Port rejected'))
    es = s.user('mn_escalate', 'Escalate delayed port to the MNPSP | विलंबित पोर्ट का एस्केलेशन', '${opsSeat}',
                form='sec-tel-mnp-port-in/escalate', decision=None, fields=['remarks*'])
    s.seq(lt, es, w)
    win = s.catch_msg('mn_window', 'Porting window', 'sangam.mnp.portWindow')
    act = s.conn('mn_activate', 'Activate on the recipient network', 'hlr.activate', 'activate', 'submit', 'msisdn', 'hlr',
                 step='activate')
    s.seq(ac, win, act, end_ok(p, s, 'mn_end', 'Ported in'))
    pk.dmn('sec-tel-mnp-timeline', 'Porting timeline (verify against the current TRAI MNP regulations)', 'FIRST',
           [('Service area', 'lsaType', 'string'), ('Customer type', 'customerType', 'string')],
           [('portSpec', 'Port within', 'string')],
           [(['-', q('CORPORATE')], [q('5WD')], 'corporate'), ([q('INTRA'), '-'], [q('3WD')], 'same service area'),
            (['-', '-'], [q('5WD')], 'another service area')])
    simple_seeds(pk, 'TELCO', [('MNP-DESK', 'MNP', None, 'MNP operations desk', 'एमएनपी संचालन डेस्क')], 'tmn')


# ============================================================================================ supply chain / manufacturing
def scm_p2p():
    pk = spack('scm', 'procure-to-pay', 'Procure to pay', 'क्रय से भुगतान तक', 'Supply chain',
               'Purchase requisition with budget check, sourcing mode by value (direct, RFQ with comparative '
               'statement, RFP), approval by delegated power, purchase order, goods receipt with quality inspection '
               'and rejection to the supplier, invoice matched by UP-14 (three-way match), payment released within '
               'the term (45 days for MSME suppliers, MSMED Act s.15) and paid by operationId.',
               ['SEC-SCM-001', 'SEC-SCM-002', 'SEC-SCM-003', 'UP-14', 'GP-01', 'GP-11', 'GP-12'] + SIDE,
               [('erp.budgetCheck', 'a', '', 'Budget'), ('erp.createPo', 'a', '', 'Purchase order'),
                ('supplier.sendPo', 'a', '', 'PO to supplier'), ('supplier.debitNote', 'a', '', 'Rejection / debit note'),
                ('bank.transfer', 'c', '', 'Supplier payment')],
               depends=('sangam.core', 'universal.up14-three-way-match'))
    p = pk.proc('sangam-sec-scm-p2p', 'Procure to pay', '',
                start={'materialFields': 'itemDescription,quantity,unitPrice,supplierName'})
    s = p.root
    st = start_form(p, s, 'pp_start', 'Requisition raised', 'sec-scm-procure-to-pay/requisition',
                    'Purchase requisition | क्रय माँग-पत्र',
                    ['itemDescription*', 'quantity*', 'unitPrice*', 'supplierName', 'msmeSupplier', 'justification'])
    d = s.setv('pp_defaults', 'Configuration',
               defaults='supplierName=SUPPLIER-1;msmeSupplier=false;budgetOk=true;caseType=PURCHASE;'
                        'routingDecisionKey=sec-scm-pr-authority;powersDecisionKey=sec-scm-powers;appealAllowed=false;'
                        'buyerSeat=BUYER;poQty=@quantity;poPrice=@unitPrice')
    am = s.expr('pp_amount', 'Value', "${execution.setVariable('amount', quantity * unitPrice)}")
    bc = s.conn('pp_budget', 'Budget check', 'erp.budgetCheck', 'check', 'query', 'amount', 'budget')
    g = s.xgw('pp_budGw', 'Budget available?')
    s.seq(st, d, am, bc, g)
    s.flow(g, reject_with(p, s, 'pp_rjBud', 'NO_BUDGET'), '!budgetOk', 'no')
    sm = s.rule('pp_mode', 'Sourcing mode (DMN)', 'sec-scm-sourcing')
    s.flow(g, sm, None, 'yes', default=True)
    mg = s.xgw('pp_modeGw', 'Quotations needed?')
    s.flow(sm, mg)
    rq = s.user('pp_rfq', 'Collect quotations and comparative statement | कोटेशन एवं तुलनात्मक विवरण', '${buyerSeat}',
                form='sec-scm-procure-to-pay/rfq', decision=('rfqDecision', ['award', 'retender']),
                fields=['supplierName*', 'unitPrice*', 'documents'])
    s.flow(mg, rq, "sourcingMode != 'DIRECT'", 'RFQ / RFP')
    s.flow(rq, s.xgw('pp_rfqGw'))
    s.flow('pp_rfqGw', rq, "rfqDecision == 'retender'", 'retender')
    rc = s.expr('pp_recompute', 'Value at the awarded price', "${execution.setVariable('amount', quantity * unitPrice)}")
    s.flow('pp_rfqGw', rc, None, 'award', default=True)
    san = core(s, 'pp_approve', 'Approve requisition (core sanction)', 'sanction')
    s.flow(mg, san, None, 'direct', default=True)
    s.flow(rc, san)
    rej = rejection(p, s, 'pp_rj')
    po = s.conn('pp_po', 'Create purchase order', 'erp.createPo', 'create', 'submit', 'itemDescription,quantity,unitPrice,supplierName',
                'po', step='po')
    outcome(s, 'pp_sanGw', 'sanctionOutcome', {'SANCTIONED': po}, rej)
    s.flow(san, 'pp_sanGw')
    sp = s.conn('pp_send', 'Send PO to the supplier', 'supplier.sendPo', 'send', 'submit', 'supplierName', 'poSend', step='po-send')
    grn = s.catch_msg('pp_grn', 'Goods received (GRN)', 'sangam.grn.posted')
    s.seq(po, sp, grn)
    qi = s.user('pp_qc', 'Quality inspection | गुणवत्ता निरीक्षण', 'QC-INSPECTOR', form='sec-scm-procure-to-pay/qc',
                decision=('qcDecision', ['accept', 'reject']), fields=['receivedQty*', 'acceptedQty*', 'remarks'])
    s.flow(grn, qi)
    qg = s.xgw('pp_qcGw')
    s.flow(qi, qg)
    dn = s.conn('pp_debit', 'Return rejected goods / debit note', 'supplier.debitNote', 'reject', 'submit',
                'supplierName,receivedQty,acceptedQty', 'debit', step='debit-note')
    s.flow(qg, dn, "qcDecision == 'reject'", 'reject')
    s.flow(dn, grn)
    gq = s.setv('pp_grnQty', 'Accepted quantity', values='grnQty=@acceptedQty')
    s.flow(qg, gq, None, 'accept', default=True)
    inv = s.catch_msg('pp_invoice', 'Invoice received', 'sangam.invoice.received')
    s.flow(gq, inv)
    m3 = s.call('pp_match', 'Three-way match (UP-14)', 'sangam-up14-three-way-match')
    s.flow(inv, m3)
    tr = s.rule('pp_terms', 'Payment term (DMN)', 'sec-scm-payment-terms')
    s.flow(m3, tr)
    pe, pd = s.sla_task('pp_release', 'Release payment | भुगतान जारी करें', 'treasurySeat', form='sec-scm-procure-to-pay/release',
                        decision=None, fields=['remarks'], spec='${paymentTermSpec}', on_breach='ESCALATE')
    ts = s.setv('pp_payCfg', 'Payment settings', values='treasurySeat=AP-TREASURY;executionConnector=bank.transfer;'
                'executionStep=supplier-payment;verifyApproval=false;reconcileRequired=true;'
                'executionPayloadFields=supplierName,amount')
    s.seq(tr, ts, pe)
    pay = core(s, 'pp_pay', 'Pay supplier (core execution)', 'execution')
    s.flow(pd, pay)
    s.flow(pay, end_ok(p, s, 'pp_end', 'Paid'))
    s.side_exits('pp')
    pk.dmn('sec-scm-sourcing', 'Sourcing mode by value (SYNTHETIC)', 'FIRST', [('Value', 'amount', 'money')],
           [('sourcingMode', 'Mode', 'string')],
           [(['<= 100000'], [q('DIRECT')], ''), (['<= 2500000'], [q('RFQ')], 'three quotations'), (['-'], [q('RFP')], '')])
    pk.dmn('sec-scm-payment-terms', 'Payment term', 'FIRST', [('MSME supplier', 'msmeSupplier', 'boolean')],
           [('paymentTermSpec', 'Pay within', 'string')],
           [(['true'], [q('45D')], 'MSMED Act s.15: within 45 days of acceptance'), (['-'], [q('60D')], 'SYNTHETIC')])
    authority(pk, 'sec-scm-pr-authority', 'Requisition approver', [('<= 500000', 'DEPTHEAD', ''),
                                                                    ('<= 5000000', 'PROCHEAD', ''), ('-', 'CFO', '')])
    powers(pk, 'sec-scm-powers', [('DEPTHEAD', 500000, ''), ('PROCHEAD', 5000000, ''), ('CFO', 1000000000, '')])
    simple_seeds(pk, 'PROCUREMENT', [
        ('BUYER', 'BUYER', 'PROC-HEAD', 'Buyer', 'क्रेता'), ('QC-INSPECTOR', 'QC', 'PROC-HEAD', 'Quality inspector', 'गुणवत्ता निरीक्षक'),
        ('AP-TREASURY', 'TREASURY', 'CFO', 'Accounts payable / treasury', 'देय लेखा / कोष'),
        ('DEPT-HEAD', 'DEPTHEAD', 'PROC-HEAD', 'Department head', 'विभाग प्रमुख'),
        ('PROC-HEAD', 'PROCHEAD', 'CFO', 'Head of procurement', 'क्रय प्रमुख'), ('CFO', 'CFO', None, 'Chief financial officer', 'मुख्य वित्त अधिकारी')],
        'spp')


def mfg_batch_release():
    pk = spack('mfg', 'batch-release', 'Manufacturing batch record review and release', 'विनिर्माण बैच अभिलेख समीक्षा एवं निर्गमन',
               'Manufacturing',
               'GMP batch release (Schedule M style): batch manufacturing record, in-process and finished-product '
               'QC, deviations opened as CAPA cases (UP-18), QA review with SoD from production, release or reject '
               'with e-signature bound to the batch record, release to the warehouse.',
               ['SEC-MFG-001', 'SEC-MFG-002', 'UP-18', 'GP-34', 'UP-05'],
               [('mes.batchRecord', 'a', '', 'Batch record'), ('wms.release', 'a', '', 'Release to warehouse')])
    p = pk.proc('sangam-sec-mfg-batch-release', 'Batch release', '', start={'materialFields': 'batchNo,productCode'})
    s = p.root
    st = start_form(p, s, 'br_start', 'Batch completed', 'sec-mfg-batch-release/batch', 'Batch completion | बैच पूर्णता',
                    ['batchNo*', 'productCode*', 'quantity', 'documents'])
    d = s.setv('br_defaults', 'Configuration', defaults='batchNo=@customerId;productCode=P-1;deviationFound=false')
    br = s.conn('br_record', 'Fetch batch manufacturing record', 'mes.batchRecord', 'record', 'query', 'batchNo', 'bmr')
    qc = s.user('br_qc', 'Finished-product QC | तैयार उत्पाद गुणवत्ता जाँच', 'QC-ANALYST', form='sec-mfg-batch-release/qc',
                decision=('qcDecision', ['accept', 'reject']), fields=['results*', 'deviationFound'])
    s.seq(st, d, br, qc)
    qg = s.xgw('br_qcGw')
    s.flow(qc, qg)
    dv = s.svc('br_deviation', 'Open deviation as a CAPA case (UP-18)', C['LINK'],
               {'processKey': 'sangam-up18-capa', 'linkType': 'deviation', 'inherit': 'batchNo,productCode'})
    g2, m2 = maybe(s, 'br_devOpt', 'deviationFound', dv, dv, 'Deviation?')
    s.flow(qg, g2, None, 'accept', default=True)
    qa = s.user('br_qa', 'QA review and release decision | क्यूए समीक्षा एवं निर्गमन निर्णय', 'QA-HEAD',
                form='sec-mfg-batch-release/qa', decision=('releaseDecision', ['release', 'reject']), fields=['remarks*'],
                sod=True, bind='release')
    s.flow(m2, qa)
    rg = s.xgw('br_relGw')
    s.flow(qa, rg)
    sg = s.svc('br_sign', 'E-sign release over the batch record', C['SIGN'], {'op': 'REQUEST', 'purpose': 'batch-release'},
               async_=True)
    wr = s.conn('br_wms', 'Release to warehouse', 'wms.release', 'release', 'submit', 'batchNo,quantity', 'wms', step='release')
    s.flow(rg, sg, "releaseDecision == 'release'", 'release')
    s.seq(sg, wr, end_ok(p, s, 'br_end', 'Released'))
    rj = s.end('br_endRejected', 'Batch rejected')
    s.flow(rg, rj, None, 'reject', default=True)
    s.flow(qg, rj, "qcDecision == 'reject'", 'QC failed')
    simple_seeds(pk, 'PLANT', [('QC-ANALYST', 'QC', 'QA-HEAD', 'QC analyst', 'गुणवत्ता नियंत्रण विश्लेषक'),
                               ('QA-HEAD', 'QAHEAD', None, 'Head of quality assurance', 'गुणवत्ता आश्वासन प्रमुख')], 'mbr')


# ============================================================================================ education
def edu_admission():
    pk = spack('edu', 'admission', 'Admission with fee refund on withdrawal', 'प्रवेश एवं प्रत्याहरण पर शुल्क वापसी', 'Education',
               'Application, marksheets pulled from DigiLocker / APAAR, eligibility by rule with reservation, document '
               'scrutiny (core), merit publication, offer accepted with fee within the window (lapse frees the '
               'seat), enrolment and ABC registration; withdrawal refunds by the UGC refund schedule (100 % less a '
               'capped processing fee 15 days or more before the last date of admission, then 90 / 80 / 50 / 0 %).',
               ['SEC-EDU-001', 'SEC-EDU-002', 'SEC-EDU-003', 'UP-22', 'GP-16', 'GP-26', 'GP-44'] + SIDE,
               [('digilocker.pull', 'a', '', 'Marksheets'), ('pg.collect', 'b', '', 'Fee'), ('pg.refund', 'a', '', 'Refund'),
                ('sis.enrol', 'a', '', 'Student information system'), ('abc.register', 'a', '', 'Academic Bank of Credits')])
    p = pk.proc('sangam-sec-edu-admission', 'Admission', '', start={'materialFields': 'applicantName,programme,score'})
    s = p.root
    st = start_form(p, s, 'ed_start', 'Application submitted', 'sec-edu-admission/apply', 'Admission application | प्रवेश आवेदन',
                    ['applicantName*', 'programme*', 'qualification*', 'score*', 'socialCategory*', 'applicantRef', 'documents'])
    d = s.setv('ed_defaults', 'Configuration',
               defaults='programme=BA;score=60;offered=true;daysBeforeLastDate=20;scrutinySeat=ADMISSION-OFFICE;'
                        'executionConnector=pg.collect;executionStep=fee;verifyApproval=false;reconcileRequired=false;'
                        'executionPayloadFields=applicantName,feeAmount;feeAmount=50000')
    dl = s.conn('ed_pull', 'Pull marksheets (DigiLocker / APAAR)', 'digilocker.pull', 'pull', 'query', 'applicantName', 'dl')
    el = s.rule('ed_elig', 'Eligibility (DMN)', 'sec-edu-eligibility')
    g = s.xgw('ed_eligGw', 'Eligible?')
    s.seq(st, d, dl, el, g)
    s.flow(g, reject_with(p, s, 'ed_rjElig', 'NOT_ELIGIBLE'), '!eligible', 'no')
    sc = core(s, 'ed_scrutiny', 'Document scrutiny (core)', 'scrutiny')
    s.flow(g, sc, None, 'yes', default=True)
    rej = rejection(p, s, 'ed_rj')
    mp = s.catch_msg('ed_merit', 'Merit list published', 'sangam.edu.meritPublished')
    outcome(s, 'ed_scrGw', 'scrutinyOutcome', {'ACCEPTED': mp}, rej)
    s.flow(sc, 'ed_scrGw')
    og = s.xgw('ed_offerGw', 'Offered a seat?')
    s.flow(mp, og)
    s.flow(og, s.end('ed_endNotOffered', 'Not offered (waitlist)'), '!offered', 'no')
    grp = s.expr('ed_group', 'Applicant group', "${execution.setVariable('applicantGroup', 'external:'.concat(applicantName))}")
    ae, ad = s.sla_task('ed_accept', 'Accept offer and pay fee | प्रस्ताव स्वीकार कर शुल्क जमा करें', 'applicantGroup',
                        form='sec-edu-admission/accept', decision=('offerDecision', ['accept', 'declined']),
                        fields=['remarks'], spec='7D', on_breach='DEEMED_REFUSAL', approve='accept', refuse='declined',
                        max_esc=0)
    s.flow(og, grp, None, 'yes', default=True)
    s.flow(grp, ae)
    ag = s.xgw('ed_accGw')
    s.flow(ad, ag)
    s.flow(ag, s.end('ed_endLapsed', 'Offer lapsed: seat released'), "offerDecision != 'accept'", 'no')
    fee = core(s, 'ed_fee', 'Collect fee (core execution)', 'execution')
    s.flow(ag, fee, None, 'accept', default=True)
    en = s.conn('ed_enrol', 'Enrol and register on ABC', 'sis.enrol', 'enrol', 'submit', 'applicantName,programme', 'sis',
                step='enrol')
    s.flow(fee, en)
    s.flow(en, end_ok(p, s, 'ed_end', 'Enrolled'))
    wd = s.evsub('ed_withdraw', 'Withdrawal of admission')
    w1 = wd.start('ed_wdStart', 'Withdrawal', msg='sangam.edu.withdraw', interrupting=True)
    w2 = wd.rule('ed_wdRule', 'Refund under the UGC schedule (DMN)', 'sec-edu-refund')
    w3 = wd.setv('ed_wdCfg', 'Refund settings', values='executionConnector=pg.refund;executionStep=withdrawal-refund;'
                 'executionPayloadFields=applicantName,refundPct,feeAmount')
    w4 = wd.call('ed_wdRefund', 'Refund (core execution)', 'sangam-core-execution')
    wd.seq(w1, w2, w3, w4, wd.end('ed_wdEnd', 'Withdrawn with refund'))
    pk.dmn('sec-edu-eligibility', 'Eligibility (SYNTHETIC programme rule)', 'FIRST',
           [('Score', 'score', 'number'), ('Category', 'socialCategory', 'string')], [('eligible', 'Eligible', 'boolean')],
           [(['>= 45', q('GEN')], ['true'], ''), (['>= 40', '-'], ['true'], 'relaxation for reserved categories'),
            (['-', '-'], ['false'], '')])
    pk.dmn('sec-edu-refund', 'Fee refund on withdrawal (UGC refund policy)', 'FIRST',
           [('Days before the last date of admission (negative after)', 'daysBeforeLastDate', 'integer')],
           [('refundPct', 'Refund %', 'integer')],
           [(['>= 15'], ['100'], '100 % less processing fee (capped)'), (['>= 0'], ['90'], 'less than 15 days before'),
            (['>= -15'], ['80'], 'within 15 days after'), (['>= -30'], ['50'], '16-30 days after'), (['-'], ['0'], 'beyond 30 days')])
    simple_seeds(pk, 'COLLEGE', [('ADMISSION-OFFICE', 'ADMISSION', None, 'Admission office', 'प्रवेश कार्यालय')], 'ead')


# ============================================================================================ utilities
def util_connection():
    pk = spack('util', 'new-connection', 'New electricity connection with standards of performance',
               'प्रदर्शन मानकों सहित नया विद्युत कनेक्शन', 'Utilities',
               'Electricity (Rights of Consumers) Rules 2020: application with two documents, connection within 7 '
               'days (metro), 15 days (other municipal) or 30 days (rural) where no new works are needed; site '
               'inspection, estimate and demand note when works are needed, fee paid once, meter installed and '
               'energised; delay beyond the standard credits the compensation notified by the SERC.',
               ['SEC-UTIL-001', 'SEC-UTIL-002', 'GP-06', 'GP-12', 'GP-17'] + SIDE,
               [('pg.collect', 'b', '', 'Connection charges'), ('billing.credit', 'a', '', 'Compensation credit'),
                ('mdm.install', 'a', '', 'Meter data management; keeps the business key as the connection case of the consumer')])
    p = pk.proc('sangam-sec-util-connection', 'New connection', '', start={'materialFields': 'applicantName,premisesAddress,loadKw'})
    s = p.root
    st = start_form(p, s, 'uc_start', 'Application', 'sec-util-new-connection/apply', 'New connection application | नए कनेक्शन हेतु आवेदन',
                    ['applicantName*', 'premisesAddress*', 'areaType*', 'loadKw*', 'category', 'applicantRef', 'documents'])
    d = s.setv('uc_defaults', 'Configuration',
               defaults='areaType=MUNICIPAL;loadKw=5;worksRequired=false;compensationDue=false;scrutinySeat=SUBDIV-CLERK;'
                        'scrutinySla=1WD;pauseOnClarification=true;executionConnector=pg.collect;executionStep=charges;'
                        'verifyApproval=false;reconcileRequired=false;executionPayloadFields=applicantName,loadKw;'
                        'appealAllowed=false')
    r = s.rule('uc_sop', 'Standard of performance (DMN)', 'sec-util-sop')
    ck = clock(s, 'uc_clock', 'connection', '${connSpec}')
    sc = core(s, 'uc_scrutiny', 'Document scrutiny (core)', 'scrutiny')
    s.seq(st, d, r, ck, sc)
    rej = rejection(p, s, 'uc_rj')
    ins = s.user('uc_inspect', 'Site inspection and feasibility | स्थल निरीक्षण एवं व्यवहार्यता', 'JE-FIELD',
                 form='sec-util-new-connection/inspect', decision=('feasibility', ['feasible', 'worksNeeded', 'notFeasible']),
                 fields=['findings*', 'photos'], due='${connectionDueAt}')
    outcome(s, 'uc_scrGw', 'scrutinyOutcome', {'ACCEPTED': ins}, rej)
    s.flow(sc, 'uc_scrGw')
    fg = s.xgw('uc_feasGw')
    s.flow(ins, fg)
    s.flow(fg, reject_with(p, s, 'uc_rjFeas', 'NOT_FEASIBLE'), "feasibility == 'notFeasible'", 'not feasible')
    est = s.user('uc_estimate', 'Estimate and demand note | प्राक्कलन एवं माँग पत्र', 'AE-SUBDIV',
                 form='sec-util-new-connection/estimate', decision=None, fields=['estimateAmount*', 'remarks'])
    s.flow(fg, est, "feasibility == 'worksNeeded'", 'works needed')
    fee = core(s, 'uc_fee', 'Collect charges (core execution)', 'execution')
    s.flow(fg, fee, None, 'feasible', default=True)
    s.flow(est, fee)
    inst = s.user('uc_install', 'Install meter and energise | मीटर स्थापना एवं विद्युत आपूर्ति', 'JE-FIELD',
                  form='sec-util-new-connection/install', decision=None, fields=['meterNo*', 'photos'],
                  due='${connectionDueAt}')
    s.flow(fee, inst)
    for host in (ins, inst):
        b = s.boundary(host + '_late', host, 'Standard of performance missed', timer_date='${connectionDueAt}', cancel=False)
        f = s.setv(host + '_lateFlag', 'Compensation due', values='compensationDue=true')
        s.flow(b, f)
        s.flow(f, s.end(host + '_lateEnd', 'Recorded'))
    md = s.conn('uc_mdm', 'Register meter', 'mdm.install', 'register', 'submit', 'meterNo,applicantName,applicantRef', 'mdm', step='meter')
    # the credit needs a demand: held until the consumer's first bill names consumer, cycle and head
    cw = s.egw('uc_compWait', 'First bill or timeout')
    fb = s.catch_msg('uc_firstBill', 'First bill raised', 'sangam.util.firstBill')
    ft = s.catch_timer('uc_noBill', 'No bill in time', duration='P60D')
    s.flow(cw, fb)
    s.flow(cw, ft)
    cc = s.setv('uc_compCfg', 'Compensation credit inputs', values='amount=@compensationAmount')
    s.flow(fb, cc)
    cp = s.conn('uc_comp', 'Credit compensation in the next bill', 'billing.credit', 'credit', 'submit',
                'consumer,amount,head,cycle', 'comp', step='compensation')
    s.flow(cc, cp)
    cr = s.xgw('uc_compRes', 'Credited?')
    s.flow(cp, cr)
    cm = s.user('uc_compManual', 'Compensation not credited: credit by hand | क्षतिपूर्ति जमा नहीं हुई: स्वयं जमा करें', 'AE-SUBDIV',
                form='sec-util-new-connection/compensation-manual', decision=None, fields=['remarks*'])
    cj = s.xgw('uc_compJoin')
    s.flow(cr, cm, "compStatus == 'FAILED'", 'refused (no demand in that cycle yet)')
    s.flow(cr, cj, None, 'credited', default=True)
    s.flow(cm, cj)
    s.flow(ft, cm)
    s.flow(inst, md)
    g, m = maybe(s, 'uc_compOpt', 'compensationDue', cw, cj, 'Delayed?')
    s.flow(md, g)
    s.flow(m, end_ok(p, s, 'uc_end', 'Energised'))
    s.side_exits('uc')
    pk.dmn('sec-util-sop', 'Connection timeline by area (Electricity (Rights of Consumers) Rules 2020)', 'FIRST',
           [('Area', 'areaType', 'string')],
           [('connSpec', 'Connect within', 'string'), ('compensationAmount', 'Compensation (SYNTHETIC, SERC notifies)', 'money')],
           [([q('METRO')], [q('7D'), '1000'], ''), ([q('MUNICIPAL')], [q('15D'), '1000'], ''),
            (['-'], [q('30D'), '500'], 'rural / hilly')])
    simple_seeds(pk, 'SUBDIVISION', [('SUBDIV-CLERK', 'CLERK', 'AE-SUBDIV', 'Sub-division clerk', 'उप-संभाग लिपिक'),
                                     ('JE-FIELD', 'JE', 'AE-SUBDIV', 'Junior engineer', 'कनिष्ठ अभियंता'),
                                     ('AE-SUBDIV', 'AE', None, 'Assistant engineer', 'सहायक अभियंता')], 'uuc')


# ============================================================================================ HR
def hr_exit():
    pk = spack('hr', 'employee-exit', 'Employee exit and full and final settlement', 'कर्मचारी निकास एवं पूर्ण एवं अंतिम निपटान', 'HR',
               'Resignation accepted (or withdrawn), clearances in parallel on the last working day (access revoked, '
               'assets recovered, dues), wages settled within two working days (Code on Wages s.17(2)), gratuity '
               'within 30 days where payable, relieving and experience letters, PF exit.',
               ['SEC-HR-001', 'SEC-HR-002', 'UP-16', 'GP-05', 'GP-12'] + SIDE,
               [('iam.revokeAll', 'a', '', 'Revoke access'), ('bank.transfer', 'c', '', 'Settlement payment'),
                ('epfo.exit', 'a', '', 'PF exit'), ('docs.letter', 'a', '', 'Relieving letter')])
    p = pk.proc('sangam-sec-hr-exit', 'Employee exit', '', start={'materialFields': 'employeeId,exitType'})
    s = p.root
    st = start_form(p, s, 'hx_start', 'Exit initiated', 'sec-hr-employee-exit/initiate', 'Exit | निकास',
                    ['employeeId*', 'employeeName*', 'exitType*', 'lastWorkingDay', 'qualifyingService'])
    d = s.setv('hx_defaults', 'Configuration',
               defaults='exitType=RESIGNATION;qualifyingService=3;managerSeat=LINE-MANAGER;hrSeat=HR-PAYROLL;'
                        'executionConnector=bank.transfer;executionStep=fnf;verifyApproval=false;reconcileRequired=false;'
                        'executionPayloadFields=employeeId,fnfAmount;fnfAmount=0;gratuityAmount=0')
    ag = s.xgw('hx_typeGw', 'Resignation?')
    s.seq(st, d, ag)
    ac = s.user('hx_accept', 'Accept resignation | त्यागपत्र स्वीकार करें', '${managerSeat}', form='sec-hr-employee-exit/accept',
                decision=('resignDecision', ['accept', 'withdraw']), fields=['lastWorkingDay*', 'remarks'])
    s.flow(ag, ac, "exitType == 'RESIGNATION'", 'resignation')
    s.flow(ac, s.xgw('hx_accGw'))
    s.flow('hx_accGw', s.end('hx_endWithdrawn', 'Resignation withdrawn'), "resignDecision == 'withdraw'", 'withdrawn')
    lw = at_date(s, 'hx_lwd', 'lwd', 'lastWorkingDay')
    s.flow('hx_accGw', lw, None, 'accepted', default=True)
    s.flow(ag, lw, None, 'other exit', default=True)
    t = s.catch_timer('hx_lwdTimer', 'Last working day', date='${lwdDueAt}')
    xr = s.rule('hx_tat', 'Settlement periods (DMN)', 'sec-hr-exit-tat')
    fk = s.pgw('hx_fork', 'Clearances')
    jn = s.pgw('hx_join')
    s.seq(lw, t, xr, fk)
    rv = s.conn('hx_revoke', 'Revoke all access', 'iam.revokeAll', 'revoke', 'submit', 'employeeId', 'revoke', step='revoke')
    s.flow(fk, rv)
    s.flow(rv, jn)
    for sid, en, hi in (('IT-ASSETS', 'Recover assets', 'परिसंपत्तियों की वापसी'), ('FINANCE-DUES', 'Dues and advances', 'बकाया एवं अग्रिम')):
        tk = s.user('hx_' + sid.lower().replace('-', ''), f'{en} | {hi}', sid, form='sec-hr-employee-exit/' + sid.lower(),
                    decision=None, fields=['remarks'])
        s.flow(fk, tk)
        s.flow(tk, jn)
    fe, fd = s.sla_task('hx_fnf', 'Full and final settlement (2 working days) | पूर्ण एवं अंतिम निपटान (2 कार्य दिवस)', 'hrSeat',
                        form='sec-hr-employee-exit/fnf', decision=None, fields=['fnfAmount*', 'remarks'], spec='${fnfSpec}',
                        on_breach='ESCALATE')
    s.flow(jn, fe)
    pay = core(s, 'hx_pay', 'Pay settlement (core execution)', 'execution')
    s.flow(fd, pay)
    gg = s.xgw('hx_gratGw', 'Gratuity payable?')
    s.flow(pay, gg)
    ge, gd = s.sla_task('hx_gratuity', 'Gratuity (30 days) | ग्रेच्युटी (30 दिन)', 'hrSeat', form='sec-hr-employee-exit/gratuity',
                        decision=None, fields=['gratuityAmount*'], spec='${gratuitySpec}', on_breach='ESCALATE')
    s.flow(gg, ge, 'gratuityPayable', 'yes')
    gc = s.setv('hx_gratCfg', 'Gratuity payment', values='executionStep=gratuity;executionPayloadFields=employeeId,gratuityAmount')
    gp = s.call('hx_gratPay', 'Pay gratuity (core execution)', 'sangam-core-execution')
    s.seq(gd, gc, gp)
    lt = s.conn('hx_letter', 'Relieving and experience letters; PF exit', 'docs.letter', 'relieving', 'submit',
                'employeeId,employeeName', 'letter', step='letters')
    s.flow(gg, lt, None, 'no', default=True)
    s.flow(gp, lt)
    s.flow(lt, end_ok(p, s, 'hx_end', 'Exited'))
    s.side_exits('hx')
    pk.dmn('sec-hr-exit-tat', 'Exit settlement periods (Code on Wages s.17(2); Code on Social Security; verify)', 'FIRST',
           [('Exit type', 'exitType', 'string'), ('Qualifying service (years)', 'qualifyingService', 'number')],
           [('fnfSpec', 'Wages within', 'string'), ('gratuitySpec', 'Gratuity within', 'string'),
            ('gratuityPayable', 'Gratuity payable', 'boolean')],
           [([q('DEATH'), '-'], [q('2WD'), q('30D'), 'true'], 'death / disablement: no service condition'),
            (['-', '>= 5'], [q('2WD'), q('30D'), 'true'], '5 years (1 year for fixed-term employees, verify)'),
            (['-', '-'], [q('2WD'), q('30D'), 'false'], '')])
    simple_seeds(pk, 'HR', [('LINE-MANAGER', 'MANAGER', 'HR-PAYROLL', 'Line manager', 'प्रबंधक'),
                            ('IT-ASSETS', 'IT', 'HR-PAYROLL', 'IT asset desk', 'आईटी परिसंपत्ति डेस्क'),
                            ('FINANCE-DUES', 'FINANCE', 'HR-PAYROLL', 'Finance', 'वित्त'),
                            ('HR-PAYROLL', 'HR', None, 'HR payroll', 'मानव संसाधन वेतन')], 'hex')


def hr_posh():
    pk = spack('hr', 'posh-complaint', 'Sexual harassment complaint (POSH Act 2013)', 'यौन उत्पीड़न शिकायत (पॉश अधिनियम 2013)', 'HR',
               'Complaint to the Internal Committee within three months (extendable), copy to the respondent within '
               '7 working days, reply within 10 working days, conciliation on request (no monetary settlement), '
               'inquiry by the committee within 90 days, report within 10 days, employer action within 60 days, '
               'appeal within 90 days; confidentiality: no external notification.',
               ['SEC-HR-003', 'SEC-HR-004', 'GP-03', 'GP-22', 'GP-35'],
               [])
    p = pk.proc('sangam-sec-hr-posh', 'POSH complaint', '', ttl=3650, start={'materialFields': 'complainantRef,respondentId'})
    s = p.root
    st = start_form(p, s, 'po_start', 'Complaint to the Internal Committee', 'sec-hr-posh-complaint/complaint',
                    'Complaint to the Internal Committee | आंतरिक समिति को शिकायत',
                    ['complainantRef*', 'respondentId*', 'incidentDate', 'description*', 'conciliationRequested'])
    d = s.setv('po_defaults', 'Configuration', defaults='complainantRef=@customerId;respondentId=@employeeId;'
               'conciliationRequested=false;icSeat=IC-PRESIDING;committeeSeats=IC-PRESIDING,IC-MEMBER,IC-EXTERNAL;'
               'quorumRule=ALL')
    ad = s.user('po_admit', 'Admit complaint (limitation three months) | शिकायत ग्रहण (परिसीमा तीन माह)', '${icSeat}',
                form='sec-hr-posh-complaint/admit', decision=('admitDecision', ['admit', 'reject']), fields=['remarks*'])
    s.seq(st, d, ad)
    agw = s.xgw('po_admGw')
    s.flow(ad, agw)
    s.flow(agw, s.end('po_endNotAdmitted', 'Not admitted (reasons recorded)'), "admitDecision == 'reject'", 'reject')
    ce, cd = s.sla_task('po_copy', 'Send copy to the respondent (7 working days) | प्रतिवादी को प्रति (7 कार्य दिवस)', 'icSeat',
                        form='sec-hr-posh-complaint/copy', decision=None, fields=['remarks'], spec='7WD')
    s.flow(agw, ce, None, 'admit', default=True)
    w = s.egw('po_replyWait', 'Reply or 10 working days')
    rp = s.catch_msg('po_reply', 'Respondent replies', 'sangam.posh.reply')
    rt = clock(s, 'po_replyClock', 'reply', '10WD')
    s.seq(cd, rt, w)
    tt = s.catch_timer('po_replyLapse', 'No reply in time', date='${replyDueAt}')
    s.flow(w, rp)
    s.flow(w, tt)
    cj = s.xgw('po_concGw', 'Conciliation requested?')
    s.flow(rp, cj)
    s.flow(tt, cj)
    cn = s.user('po_conciliate', 'Conciliation (no monetary settlement) | सुलह (मौद्रिक समझौता नहीं)', '${icSeat}',
                form='sec-hr-posh-complaint/conciliation', decision=('concDecision', ['settled', 'failed']), fields=['remarks*'])
    s.flow(cj, cn, 'conciliationRequested', 'yes')
    s.flow(cn, s.xgw('po_concRes'))
    s.flow('po_concRes', s.end('po_endSettled', 'Settled by conciliation'), "concDecision == 'settled'", 'settled')
    ic = clock(s, 'po_inqClock', 'inquiry', '90D')
    s.flow(cj, ic, None, 'no', default=True)
    s.flow('po_concRes', ic, None, 'failed', default=True)
    inq = core(s, 'po_inquiry', 'Inquiry by the Internal Committee (core committee)', 'committee')
    s.flow(ic, inq)
    re_, rd = s.sla_task('po_report', 'Inquiry report to the employer (10 days) | नियोक्ता को जाँच रिपोर्ट (10 दिन)', 'icSeat',
                         form='sec-hr-posh-complaint/report', decision=None, fields=['findings*'], spec='10D')
    s.flow(inq, re_)
    ae, adn = s.sla_task('po_action', 'Employer acts on the report (60 days) | रिपोर्ट पर नियोक्ता की कार्रवाई (60 दिन)',
                         'employerSeat', form='sec-hr-posh-complaint/action', decision=None, fields=['actionTaken*'],
                         spec='60D', on_breach='ESCALATE')
    es = s.setv('po_employer', 'Employer', defaults='employerSeat=EMPLOYER')
    s.seq(rd, es, ae)
    aw = s.egw('po_appealWait', 'Appeal window')
    s.flow(adn, aw)
    ap = s.catch_msg('po_appeal', 'Appeal filed', 'sangam.appealFiled')
    at = s.catch_timer('po_appealLapse', '90 days over', duration='P90D')
    s.flow(aw, ap)
    s.flow(aw, at)
    s.flow(ap, s.end('po_endAppealed', 'Appeal before the appellate authority'))
    s.flow(at, end_ok(p, s, 'po_end', 'Closed'))
    simple_seeds(pk, 'IC', [('IC-PRESIDING', 'PRESIDING', None, 'Presiding officer (IC)', 'पीठासीन अधिकारी (आंतरिक समिति)'),
                            ('IC-MEMBER', 'MEMBER', None, 'IC member', 'आंतरिक समिति सदस्य'),
                            ('IC-EXTERNAL', 'EXTERNAL', None, 'External member (NGO)', 'बाह्य सदस्य (एनजीओ)'),
                            ('EMPLOYER', 'EMPLOYER', None, 'Employer', 'नियोक्ता')], 'hpo')


# ============================================================================================ legal / privacy
def legal_dpdp():
    pk = spack('legal', 'dpdp-rights', 'Data principal rights request (DPDP Act 2023)', 'डेटा प्रधान अधिकार अनुरोध (डीपीडीपी अधिनियम 2023)',
               'Legal / privacy',
               'Access, correction, erasure, nomination, consent withdrawal and grievance: identity verified, '
               'request routed by type, erasure checked against retention duties (PMLA five years, sector rules, '
               'legal hold) and propagated to processors, consent withdrawn through the consent manager, reply by '
               'the DPO within 90 days with the route to the Data Protection Board.',
               ['SEC-LEGAL-001', 'SEC-LEGAL-002', 'GP-35', 'GP-52', 'UP-17'],
               [('idv.verify', 'a', '', 'Identity verification'), ('records.summary', 'a', '', 'Personal data summary'),
                ('records.erase', 'a', '', 'Erasure'), ('processors.erase', 'a', '', 'Erasure at processors'),
                ('consent.withdraw', 'a', '', 'Consent manager')])
    p = pk.proc('sangam-sec-legal-dpdp-request', 'Data principal request', '',
                start={'materialFields': 'customerId,requestType'})
    s = p.root
    st = start_form(p, s, 'dr_start', 'Request received', 'sec-legal-dpdp-rights/request', 'Data principal request | डेटा प्रधान अनुरोध',
                    ['customerId*', 'requestType*', 'description', 'applicantRef'])
    d = s.setv('dr_defaults', 'Configuration', defaults='requestType=ACCESS;idVerified=true;dpoSeat=DPO;applicantRef=@customerId')
    iv = s.conn('dr_idv', 'Verify identity', 'idv.verify', 'verify', 'query', 'customerId', 'id')
    g = s.xgw('dr_idGw', 'Verified?')
    s.seq(st, d, iv, g)
    s.flow(g, s.end('dr_endUnverified', 'Identity not verified (data principal told)'), '!idVerified', 'no')
    tr = s.rule('dr_tat', 'Response period (DMN)', 'sec-legal-dpdp-tat')
    ck = clock(s, 'dr_clock', 'response', '${respondSpec}')
    tg = s.xgw('dr_typeGw', 'Request type?')
    s.flow(g, tr, None, 'yes', default=True)
    s.seq(tr, ck, tg)
    rj = s.xgw('dr_join')
    sm = s.conn('dr_summary', 'Summary of personal data and processing', 'records.summary', 'summary', 'query', 'customerId',
                'summary')
    s.flow(tg, sm, "requestType == 'ACCESS'", 'access')
    s.flow(sm, rj)
    er = s.user('dr_retention', 'Check retention duties before erasure | मिटाने से पूर्व प्रतिधारण दायित्व जाँचें', '${dpoSeat}',
                form='sec-legal-dpdp-rights/retention', decision=('eraseDecision', ['erase', 'retain']),
                fields=['legalHold', 'remarks*'])
    s.flow(tg, er, "requestType == 'ERASURE'", 'erasure')
    eg = s.xgw('dr_eraseGw')
    s.flow(er, eg)
    e1 = s.conn('dr_erase', 'Erase', 'records.erase', 'erase', 'submit', 'customerId', 'erase', step='erase')
    e2 = s.conn('dr_eraseProc', 'Erase at processors', 'processors.erase', 'erase', 'submit', 'customerId', 'peErase',
                step='processor-erase')
    s.flow(eg, e1, "eraseDecision == 'erase'", 'erase')
    s.seq(e1, e2, rj)
    s.flow(eg, rj, None, 'retain with reasons', default=True)
    cw = s.conn('dr_withdraw', 'Withdraw consent (consent manager)', 'consent.withdraw', 'withdraw', 'submit', 'customerId',
                'consentW', step='withdraw')
    s.flow(tg, cw, "requestType == 'WITHDRAW_CONSENT'", 'withdraw consent')
    s.flow(cw, rj)
    cr = s.user('dr_correct', 'Correct / complete / record nominee | सुधार / पूर्णता / नामिती दर्ज करें', '${dpoSeat}',
                form='sec-legal-dpdp-rights/correct', decision=None, fields=['remarks*', 'documents'])
    s.flow(tg, cr, None, 'correction / nomination / grievance', default=True)
    s.flow(cr, rj)
    re_, rd = s.sla_task('dr_reply', 'DPO reply (90 days) | डीपीओ उत्तर (90 दिन)', 'dpoSeat', form='sec-legal-dpdp-rights/reply',
                         decision=None, fields=['replyText*'], spec='${respondSpec}', on_breach='ESCALATE', bound_by_case='false')
    s.flow(rj, re_)
    n = s.notify('dr_notify', 'Reply with the route to the Data Protection Board', 'dpdp-reply', 'email,inapp', 'applicantRef')
    s.seq(rd, n, end_ok(p, s, 'dr_end', 'Answered'))
    pk.dmn('sec-legal-dpdp-tat', 'Data principal request period (DPDP Rules 2025 r.14; verify per right)', 'FIRST',
           [('Request type', 'requestType', 'string')], [('respondSpec', 'Respond within', 'string')],
           [(['-'], [q('90D')], 'grievance redressal within 90 days')])
    simple_seeds(pk, 'PRIVACY', [('DPO', 'DPO', 'GRO-PRIVACY', 'Data protection officer', 'डेटा संरक्षण अधिकारी'),
                                 ('GRO-PRIVACY', 'GRO', None, 'Grievance officer (privacy)', 'शिकायत अधिकारी (गोपनीयता)')], 'ldr')


def build():
    for f in (health_admission, health_abdm_consent, health_lab, health_pharmacy, ecom_order, ecom_return, ecom_seller,
              tel_mnp, scm_p2p, mfg_batch_release, edu_admission, util_connection, hr_exit, hr_posh, legal_dpdp):
        f()
