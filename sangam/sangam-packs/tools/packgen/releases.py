# SPDX-License-Identifier: Apache-2.0
"""Pack release ledger (SL-54): a pack version, once an environment holds it, is immutable.

Cross-pack calls are pinned by versionTag = the callee pack's version (packs.pin_cross_pack_calls), and Operaton refuses
a call whose (key, versionTag, tenant) matches more than one definition. The engine side refuses a changed pack under an
already deployed version (PackDeployer, PACK_VERSION_IMMUTABLE); this ledger is the generator side: every pack whose
generated content differs from what environments hold ships under a new version, and Pack.__init__ applies it before any
model, form or pin is written, so every pin to a bumped pack names the new version.

BASELINE is the commit real environments hold (IFMS Trial 2 consumed it; JOINT-LEDGER section 5, 2026-11-09 03:05).
BUMPED lists every pack whose generated files differ from BASELINE, compared with LF-normalised bytes (a CRLF-only
difference is not a change), as of 2026-11-11; packs that are new since BASELINE are not listed (no environment holds
them). A pack is bumped only from the version BASELINE holds (RELEASED); a pack that already declares another version
keeps it.

Adding a pack here is how a future content change to a released pack version is shipped: bump its version, never
change the content of a version an environment holds.
"""

import hashlib
import json
import os

BASELINE = '15e4135'
RELEASED = '1.0.0'  # the version of every pack at BASELINE
NEXT = '1.0.1'  # the version every pack in BUMPED ships as

BUMPED = frozenset({
    'gov.assets',
    'gov.audit-para',
    'gov.consultancy',
    'gov.contract-management',
    'gov.court-case',
    'gov.dbt.batch',
    'gov.gem-purchase',
    'gov.grievance',
    'gov.ifms.audit-para-settlement',
    'gov.ifms.bill',
    'gov.ifms.budget-allocation',
    'gov.ifms.budget-formulation',
    'gov.ifms.budget-reappropriation',
    'gov.ifms.challan-reconciliation',
    'gov.ifms.gia-uc',
    'gov.ifms.gpf',
    'gov.ifms.loans-advances',
    'gov.ifms.monthly-accounts',
    'gov.ifms.pao-chain',
    'gov.ifms.reappropriation',
    'gov.ifms.receipts',
    'gov.ifms.scheme-appraisal',
    'gov.ifms.sna-sparsh',
    'gov.ifms.supplementary',
    'gov.ifms.surrender-lapse',
    'gov.ifms.year-end-closing',
    'gov.inspection',
    'gov.land-acquisition',
    'gov.leave',
    'gov.p1.bill-payment',
    'gov.p2.file-movement',
    'gov.p3.grant-sanction',
    'gov.p4.licence',
    'gov.pension',
    'gov.records',
    'gov.recruitment',
    'gov.rti',
    'gov.rtps',
    'gov.security-instrument',
    'gov.stores',
    'gov.tender',
    'gov.work-order',
    'gov.works',
    'sangam.core',
    'sec.bank.account-opening',
    'sec.bank.aml-str',
    'sec.bank.cheque-return',
    'sec.bank.collections',
    'sec.bank.emandate',
    'sec.bank.inoperative-deposits',
    'sec.bank.loan-closure',
    'sec.bank.loan-origination',
    'sec.bank.payment-dual-auth',
    'sec.bank.rekyc',
    'sec.bank.txn-dispute',
    'sec.cap.corporate-action',
    'sec.cap.investor-onboarding',
    'sec.cap.ipo-allotment',
    'sec.cap.surveillance-alert',
    'sec.ecom.gst-tcs',
    'sec.ecom.order-to-cash',
    'sec.ecom.return-refund',
    'sec.ecom.seller-onboarding',
    'sec.edu.admission',
    'sec.edu.examination',
    'sec.edu.scholarship',
    'sec.fin.complaint',
    'sec.fin.cyber-incident',
    'sec.health.abdm-consent',
    'sec.health.admission-discharge',
    'sec.health.blood-bank',
    'sec.health.lab-order',
    'sec.health.ot-scheduling',
    'sec.health.pharmacy-dispense',
    'sec.hr.appraisal',
    'sec.hr.employee-exit',
    'sec.hr.payroll',
    'sec.hr.posh-complaint',
    'sec.ins.death-claim',
    'sec.ins.health-claim',
    'sec.ins.intermediary-licensing',
    'sec.ins.motor-claim',
    'sec.ins.proposal-underwriting',
    'sec.ins.reinsurance',
    'sec.legal.dpdp-rights',
    'sec.legal.litigation',
    'sec.mfg.batch-release',
    'sec.mfg.mrp-planning',
    'sec.nbfc.digital-lending',
    'sec.nbfc.dlg-arrangement',
    'sec.scm.eway-bill',
    'sec.scm.procure-to-pay',
    'sec.tel.mnp-port-in',
    'sec.tel.sim-swap',
    'sec.tel.tcccpr',
    'sec.ushealth.access-request',
    'sec.ushealth.baa',
    'sec.ushealth.breach',
    'sec.ushealth.claim',
    'sec.ushealth.discharge',
    'sec.ushealth.eligibility',
    'sec.ushealth.gfe-ppdr',
    'sec.ushealth.prior-auth',
    'sec.util.disconnection',
    'sec.util.meter-to-cash',
    'sec.util.new-connection',
    'universal.billing-cycle',
    'universal.up01-stp-order',
    'universal.up02-saga',
    'universal.up03-event-correlation',
    'universal.up04-skills-routing',
    'universal.up05-four-eyes',
    'universal.up06-hitl-ai-review',
    'universal.up07-improvement-loop',
    'universal.up08-entity-lifecycle',
    'universal.up09-dynamic-case',
    'universal.up10-incident-swarming',
    'universal.up11-change-window',
    'universal.up12-order-decomposition',
    'universal.up13-returns-refunds',
    'universal.up14-three-way-match',
    'universal.up15-recertification',
    'universal.up16-provisioning',
    'universal.up17-prior-authorisation',
    'universal.up18-capa',
    'universal.up19-contract-lifecycle',
    'universal.up20-rpa',
    'universal.up21-iot-storm-control',
    'universal.up22-external-collaboration',
})


# Owner decision 390 (2): the content environments may hold, as one digest per pack (releases-held.json, {pack id:
# {version, sha256}}), taken at HELD_AT, the commit before decision 385 (1). generate() refuses a pack that ships a held
# version with other content (check_held), so a content change cannot ship without a new version. SHIPS bumps the packs
# whose content changed after HELD_AT (decisions 385 (1), 387 (8) and 390 (1): the prepare task, its form and its slot
# check): pack id -> (version at HELD_AT, version it ships as). When an environment takes a new build, the ledger moves:
# regenerate releases-held.json at that commit and empty SHIPS.
HELD_AT = '93498ab2'
HELD_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'releases-held.json')
# R7UI-F7-4: the compliance pack lives in sangam-compliance (packgen generates its BPMN, compliance.py; its pack.json is
# kept by hand) and is held the same way: releases-held.json records it at HELD_AT, check_held covers this root too.
# A regeneration of releases-held.json covers both roots.
COMPLIANCE_PACKS = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..',
                                                 'sangam-compliance', 'src', 'main', 'resources', 'packs'))
SHIPS = {pid: ('1.0.1', '1.0.2') for pid in (
    'gov.contract-management',
    'gov.dbt.batch',
    'gov.gem-purchase',
    'gov.ifms.budget-allocation',
    'gov.ifms.budget-reappropriation',
    'gov.ifms.gpf',
    'gov.ifms.loans-advances',
    'gov.leave',
    'gov.p1.bill-payment',
    'gov.p2.file-movement',
    'gov.p4.licence',
    'gov.recruitment',
    'gov.tender',
    'gov.works',
    'sec.bank.account-opening',
    'sec.bank.payment-dual-auth',
    'sec.cap.investor-onboarding',
    'sec.nbfc.digital-lending',
    'sec.nbfc.dlg-arrangement',
    'sec.scm.procure-to-pay',
    'universal.up05-four-eyes',
)}
SHIPS['gov.intl.pfm-execution'] = ('1.0.0', '1.0.1')
# UI-DEMO-7 (lane R7-ui, b82faa85): processes started by a service or a parent are not startable from the tasklist
# (operaton:isStartableInTasklist="false"), a content change of these held versions (decision 390 (2)).
SHIPS['gov.audit-para'] = ('1.0.1', '1.0.2')
SHIPS['gov.ifms.challan-reconciliation'] = ('1.0.1', '1.0.2')
SHIPS['gov.ifms.gia-uc'] = ('1.0.1', '1.0.2')
SHIPS['gov.ifms.pao-chain'] = ('1.0.1', '1.0.2')
SHIPS['gov.intl.pfm-budget-preparation'] = ('1.0.0', '1.0.1')
SHIPS['gov.land-acquisition'] = ('1.0.1', '1.0.2')
SHIPS['gov.pension'] = ('1.0.1', '1.0.2')
SHIPS['gov.stores'] = ('1.0.1', '1.0.2')
SHIPS['sangam.core'] = ('1.0.1', '1.0.2')
SHIPS['sec.bank.collections'] = ('1.0.1', '1.0.2')
SHIPS['sec.bank.emandate'] = ('1.0.1', '1.0.2')
SHIPS['sec.bank.inoperative-deposits'] = ('1.0.1', '1.0.2')
SHIPS['sec.bank.rekyc'] = ('1.0.1', '1.0.2')
SHIPS['sec.cap.corporate-action'] = ('1.0.1', '1.0.2')
SHIPS['sec.hr.appraisal'] = ('1.0.1', '1.0.2')
SHIPS['sec.mfg.mrp-planning'] = ('1.0.1', '1.0.2')
SHIPS['sec.ushealth.claim'] = ('1.0.1', '1.0.2')
SHIPS['sec.util.meter-to-cash'] = ('1.0.1', '1.0.2')
SHIPS['universal.billing-cycle'] = ('1.0.1', '1.0.2')
SHIPS['universal.up01-stp-order'] = ('1.0.1', '1.0.2')
SHIPS['universal.up03-event-correlation'] = ('1.0.1', '1.0.2')
SHIPS['universal.up12-order-decomposition'] = ('1.0.1', '1.0.2')
SHIPS['universal.up15-recertification'] = ('1.0.1', '1.0.2')
SHIPS['universal.up17-prior-authorisation'] = ('1.0.1', '1.0.2')
SHIPS['universal.up21-iot-storm-control'] = ('1.0.1', '1.0.2')
# R7UI-F7-4: the compliance models changed after HELD_AT (incident report completion guard, isStartableInTasklist).
# Its pack.json is kept by hand, so this entry is the ledger record of that bump; version_of does not apply to it.
SHIPS['sangam.compliance'] = ('1.1.0', '1.1.1')
SHIPS['gov.intl.pfm-breach-case'] = ('1.0.0', '1.0.1')
SHIPS['gov.intl.pfm-budget-accounting-reconciliation'] = ('1.0.0', '1.0.1')
SHIPS['gov.intl.pfm-cash-plan'] = ('1.0.0', '1.0.1')
SHIPS['gov.intl.pfm-debt-operation'] = ('1.0.0', '1.0.1')
SHIPS['gov.intl.pfm-do-not-pay'] = ('1.0.0', '1.0.1')
SHIPS['gov.intl.pfm-fiscal-transparency'] = ('1.0.0', '1.0.1')
SHIPS['gov.intl.pfm-pefa-evidence'] = ('1.0.0', '1.0.1')
SHIPS['gov.intl.pfm-procurement-complaint'] = ('1.0.0', '1.0.1')
SHIPS['gov.intl.pfm-replenishment'] = ('1.0.0', '1.0.1')
SHIPS['gov.intl.pfm-standard-reports'] = ('1.0.0', '1.0.1')
SHIPS['gov.intl.pfm-suspense-ageing'] = ('1.0.0', '1.0.1')
SHIPS['gov.intl.pfm-transfer-deadline'] = ('1.0.0', '1.0.1')
SHIPS['sec.eu.gdpr-dsr'] = ('1.0.0', '1.0.1')
SHIPS['sec.ushealth.patient-access'] = ('1.0.0', '1.0.1')
SHIPS['sec.ushealth.payer-to-payer'] = ('1.0.0', '1.0.1')
SHIPS['sec.ushealth.provider-access'] = ('1.0.0', '1.0.1')
SHIPS['gov.assets'] = ('1.0.1', '1.0.2')
SHIPS['gov.consultancy'] = ('1.0.1', '1.0.2')
SHIPS['gov.court-case'] = ('1.0.1', '1.0.2')
SHIPS['gov.grievance'] = ('1.0.1', '1.0.2')
SHIPS['gov.ifms.audit-para-settlement'] = ('1.0.1', '1.0.2')
# R7UI-F7-2 (decision 400 (2), lane R7-ui): a type or classification check without a result (profile binding missing)
# holds the case for a person instead of an EL incident at the validity gateway. 1.0.2 already carried other content
# and may be deployed in a dev environment, so the new content ships as 1.0.3 (decision 390 (4)).
SHIPS['gov.ifms.bill'] = ('1.0.1', '1.0.3')
SHIPS['gov.ifms.budget-formulation'] = ('1.0.1', '1.0.2')
SHIPS['gov.ifms.monthly-accounts'] = ('1.0.1', '1.0.2')
SHIPS['gov.ifms.reappropriation'] = ('1.0.1', '1.0.2')
SHIPS['gov.ifms.scheme-appraisal'] = ('1.0.1', '1.0.2')
SHIPS['gov.ifms.sna-sparsh'] = ('1.0.1', '1.0.2')
SHIPS['gov.ifms.supplementary'] = ('1.0.1', '1.0.2')
SHIPS['gov.ifms.surrender-lapse'] = ('1.0.1', '1.0.2')
SHIPS['gov.ifms.year-end-closing'] = ('1.0.1', '1.0.2')
SHIPS['gov.inspection'] = ('1.0.1', '1.0.2')
SHIPS['gov.p3.grant-sanction'] = ('1.0.1', '1.0.2')
SHIPS['gov.records'] = ('1.0.1', '1.0.2')
SHIPS['gov.rti'] = ('1.0.1', '1.0.2')
SHIPS['gov.rtps'] = ('1.0.1', '1.0.2')
SHIPS['gov.security-instrument'] = ('1.0.1', '1.0.2')
SHIPS['sec.bank.loan-closure'] = ('1.0.1', '1.0.2')
SHIPS['sec.bank.loan-origination'] = ('1.0.1', '1.0.2')
SHIPS['sec.bank.txn-dispute'] = ('1.0.1', '1.0.2')
SHIPS['sec.ecom.gst-tcs'] = ('1.0.1', '1.0.2')
SHIPS['sec.ecom.order-to-cash'] = ('1.0.1', '1.0.2')
SHIPS['sec.ecom.return-refund'] = ('1.0.1', '1.0.2')
SHIPS['sec.ecom.seller-onboarding'] = ('1.0.1', '1.0.2')
SHIPS['sec.edu.admission'] = ('1.0.1', '1.0.2')
# decision 394 (lane R7-ui): registrationWindow is an ISO-8601 duration from the cycle start (form type 'duration', the
# start listener checks it). 1.0.2 already carried other content on the integration branch and may be deployed in a
# dev environment, so the new content ships as 1.0.3 (decision 390 (4): a deployed version is immutable).
SHIPS['sec.edu.examination'] = ('1.0.1', '1.0.3')
SHIPS['sec.edu.scholarship'] = ('1.0.1', '1.0.2')
SHIPS['sec.health.admission-discharge'] = ('1.0.1', '1.0.2')
SHIPS['sec.health.ot-scheduling'] = ('1.0.1', '1.0.2')
SHIPS['sec.health.pharmacy-dispense'] = ('1.0.1', '1.0.2')
SHIPS['sec.hr.employee-exit'] = ('1.0.1', '1.0.2')
SHIPS['sec.hr.payroll'] = ('1.0.1', '1.0.2')
SHIPS['sec.hr.posh-complaint'] = ('1.0.1', '1.0.2')
SHIPS['sec.ins.death-claim'] = ('1.0.1', '1.0.2')
SHIPS['sec.ins.health-claim'] = ('1.0.1', '1.0.2')
SHIPS['sec.ins.intermediary-licensing'] = ('1.0.1', '1.0.2')
SHIPS['sec.ins.motor-claim'] = ('1.0.1', '1.0.2')
SHIPS['sec.ins.proposal-underwriting'] = ('1.0.1', '1.0.2')
SHIPS['sec.legal.litigation'] = ('1.0.1', '1.0.2')
SHIPS['sec.tel.mnp-port-in'] = ('1.0.1', '1.0.2')
SHIPS['sec.tel.sim-swap'] = ('1.0.1', '1.0.2')
SHIPS['sec.ushealth.access-request'] = ('1.0.1', '1.0.2')
SHIPS['sec.ushealth.baa'] = ('1.0.1', '1.0.2')
SHIPS['sec.ushealth.breach'] = ('1.0.1', '1.0.2')
SHIPS['sec.ushealth.discharge'] = ('1.0.1', '1.0.2')
SHIPS['sec.ushealth.eligibility'] = ('1.0.1', '1.0.2')
SHIPS['sec.ushealth.gfe-ppdr'] = ('1.0.1', '1.0.2')
SHIPS['sec.ushealth.prior-auth'] = ('1.0.1', '1.0.2')
SHIPS['sec.util.new-connection'] = ('1.0.1', '1.0.2')
SHIPS['universal.up02-saga'] = ('1.0.1', '1.0.2')
SHIPS['universal.up07-improvement-loop'] = ('1.0.1', '1.0.2')
SHIPS['universal.up11-change-window'] = ('1.0.1', '1.0.2')
SHIPS['universal.up13-returns-refunds'] = ('1.0.1', '1.0.2')
SHIPS['universal.up14-three-way-match'] = ('1.0.1', '1.0.2')
SHIPS['universal.up18-capa'] = ('1.0.1', '1.0.2')
SHIPS['universal.up19-contract-lifecycle'] = ('1.0.1', '1.0.2')


def version_of(pack_id, declared):
    """The version a pack ships as: NEXT for a BUMPED pack still declaring the RELEASED version, then the SHIPS bump of
    a pack still at its HELD_AT version; else as declared."""
    v = declared
    if pack_id in BUMPED and v == RELEASED:
        v = NEXT
    held, ships = SHIPS.get(pack_id, (None, None))
    if v == held:
        v = ships
    return v


def digest(pack_dir):
    """SHA-256 over the pack directory's files (path relative to it, then the SHA-256 of the LF-normalised bytes), in
    path order: the form releases-held.json records."""
    files = []
    for d, _, fs in os.walk(pack_dir):
        for f in fs:
            files.append(os.path.relpath(os.path.join(d, f), pack_dir).replace(os.sep, '/'))
    h = hashlib.sha256()
    for f in sorted(files):
        with open(os.path.join(pack_dir, f), 'rb') as fh:
            data = fh.read().replace(b'\r\n', b'\n')
        h.update(f.encode('utf-8') + b'\0' + hashlib.sha256(data).hexdigest().encode('ascii') + b'\n')
    return h.hexdigest()


def held(path=HELD_FILE):
    with open(path, encoding='utf-8') as fh:
        return json.load(fh)


def stale(packs_root, held_packs):
    """The packs under packs_root (one directory per pack, with pack.json) that ship a held version with content other
    than the held one: [(pack id, version)]."""
    out = []
    for name in sorted(os.listdir(packs_root)):
        d = os.path.join(packs_root, name)
        manifest = os.path.join(d, 'pack.json')
        if not os.path.isfile(manifest):
            continue
        with open(manifest, encoding='utf-8') as fh:
            m = json.load(fh)
        h = held_packs.get(m['id'])
        if h is not None and h['version'] == m['version'] and h['sha256'] != digest(d):
            out.append((m['id'], m['version']))
    return out


def check_held(packs_root, held_packs=None):
    """Stops generation naming every pack that ships a held version with other content (decision 390 (2))."""
    bad = stale(packs_root, held() if held_packs is None else held_packs)
    if bad:
        raise SystemExit('decision 390 (2): a pack version environments hold changed content; ship it under a new '
                         'version (releases.SHIPS): ' + ', '.join(f'{p}@{v}' for p, v in bad))
# R8-ifms-tests (IFT-1..IFT-4, integrator rulings under decisions 403 and 407): approval bound before core execution,
# execution outcome and closure (monthly accounts, year-end closing, audit para), terminal state CLOSED (year-end lapse,
# leave credit), GPF return to rework. 1.0.2 already carried other content and may be deployed in a dev environment,
# so the new content ships as 1.0.3 (decision 390 (4), as gov.ifms.bill).
for _pid in ('gov.ifms.monthly-accounts', 'gov.ifms.year-end-closing', 'gov.ifms.audit-para-settlement',
             'gov.ifms.surrender-lapse', 'gov.leave', 'gov.ifms.gpf'):
    SHIPS[_pid] = ('1.0.1', '1.0.3')
# SANGAM-PAYMENT-LOOP and SANGAM-PAYEE-VERIFIED v2 (lane L-packs, E-31 §6): the bill's refused / returned payment goes
# to a person (bl_payCorrect: reissue after correction or cancel) instead of an automatic reissue loop, and the
# verified payee version is resolved before the payment (bl_payeeVerify); GPF and advances declare employeeId.
# gov.ifms.bill and gov.ifms.gpf already ship other content as 1.0.3 on the integration branch, so the new content
# ships as 1.0.4; gov.ifms.loans-advances ships 1.0.3 (decision 390 (4): a deployed version is immutable).
SHIPS['gov.ifms.bill'] = ('1.0.1', '1.0.4')
SHIPS['gov.ifms.gpf'] = ('1.0.1', '1.0.4')
SHIPS['gov.ifms.loans-advances'] = ('1.0.1', '1.0.3')
# gov.ifms.gia-uc pins the bill (gr_bill calledElementVersionTag), so its content changes with the bill's bump: 1.0.3.
SHIPS['gov.ifms.gia-uc'] = ('1.0.1', '1.0.3')
# R8-ifms-tests IFT-5: approval bound at the PAO pass before core execution (bill PAO chain, SNA-SPARSH).
SHIPS['gov.ifms.pao-chain'] = ('1.0.1', '1.0.3')
SHIPS['gov.ifms.sna-sparsh'] = ('1.0.1', '1.0.3')
