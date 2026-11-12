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


def version_of(pack_id, declared):
    """The version a pack ships as: NEXT for a BUMPED pack still declaring the RELEASED version, else as declared."""
    if pack_id in BUMPED and declared == RELEASED:
        return NEXT
    return declared
