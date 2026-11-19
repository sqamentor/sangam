# SPDX-License-Identifier: Apache-2.0
"""Lane ARCH-JURIS: the pack-specific money flows (money_out.py, part 2), one function per process, applied after the
sanction pass. Items of the lane (ARCH-JURIS-PROGRESS): 1 p3 instalment sanction, 2 SNA-SPARSH PAO / reissue,
3 e-mandate debit, 4 dividend, 5 procure-to-pay, 6 up14 exact payable loop, 7 proposal free-look refund and premium
entry (ip_premiumEnter), admission refund review, 8 marketplace commission and net settlement, 9 receipts refund /
remitter, 10 payroll per-employee salary batch (child sangam-sec-hr-salary-payment), 11 tender EMD refunds per bidder
(child sangam-tender-emd-refund); R1 payee pre-verification (bill, p1, intl), R2 DBT batch seal and retry sanction;
the zero-TCS month (gt_tcsGw). Elements are written as lib node dicts (tag, id, name, kind, attrs, ext, body); put()
adds or replaces in place, putf() a sequence flow, drop() removes, defaults() sets a gateway's default flow."""
from money_out import TOP_NAMES, defaults, drop, fields_xml, form, put, putb, putf, tlistener, xlistener  # noqa: F401

PORTS = {}
NEW = []


def port(pid):
    def reg(fn):
        PORTS[pid] = fn
        return fn
    return reg


def new(fn):
    NEW.append(fn)
    return fn


def apply(by_pid, by_pack):
    for fn in NEW:
        fn(by_pack, by_pid)
    for pid, fn in PORTS.items():
        if pid not in by_pid:
            raise SystemExit(f'money_out_ports: unknown process {pid}')
        fn(by_pid[pid])
    apply_data(by_pid, by_pack)


TOP_NAMES.update({'err_APPROVAL_INVALIDATED': 'APPROVAL_INVALIDATED'})


@port('sangam-dbt-batch')
def port_sangam_dbt_batch(p):
    putf(p, None, 'dbt_batch_f3', 'db_load', 'db_seal')
    putf(p, None, 'dbt_batch_f19', 'db_retryGw', 'db_retry_seal', c='${retryCount > 0}', name='resend')
    put(p, None, dict(tag='serviceTask', id='db_seal', name='Seal the batch items (count, total, fingerprints; owner decision E1)', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.BatchDelegate'}, ext=[fields_xml({'op': 'SEAL', 'currency': 'INR', 'purpose': 'DBT instalment', 'itemsVar': 'items', 'payeeField': 'bankAccount'})], body=[]))
    putf(p, None, 'db_seal_f', 'db_seal', 'db_sanction')
    put(p, None, dict(tag='serviceTask', id='db_retry_seal', name='Seal the batch items (count, total, fingerprints; owner decision E1)', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.BatchDelegate'}, ext=[fields_xml({'op': 'SEAL', 'currency': 'INR', 'purpose': 'DBT instalment', 'itemsVar': 'items', 'payeeField': 'bankAccount'})], body=[]))
    put(p, None, dict(tag='callActivity', id='db_retry_bsanction', name='Sanction the sealed batch (core sanction, owner decision E1)', kind='task', attrs={'calledElement': 'sangam-core-sanction'}, ext=['<operaton:in businessKey="#{execution.processBusinessKey}"/>', '<operaton:in variables="all"/>', '<operaton:in sourceExpression="${\'ESCALATE\'}" target="sanctionOnBreach"/>', '<operaton:out variables="all"/>'], body=[]))
    put(p, None, dict(tag='exclusiveGateway', id='db_retry_bsgw', name='Batch sanctioned?', kind='gateway', attrs={}, ext=[], body=[]))
    defaults(p, 'db_retry_bsgw', 'db_retry_bsgw_no')
    put(p, None, dict(tag='endEvent', id='db_retry_bnotReleased', name='Batch not released', kind='event', attrs={}, ext=[], body=[]))
    putf(p, None, 'db_retry_seal_f', 'db_retry_seal', 'db_retry_bsanction')
    putf(p, None, 'db_retry_bsanction_f', 'db_retry_bsanction', 'db_retry_bsgw')
    putf(p, None, 'db_retry_bsgw_yes', 'db_retry_bsgw', 'db_fanOut', c="${sanctionOutcome == 'SANCTIONED'}", name='sanctioned')
    putf(p, None, 'db_retry_bsgw_no', 'db_retry_bsgw', 'db_retry_bnotReleased', name='not sanctioned', default=True)


@port('sangam-dbt-beneficiary-payment')
def port_sangam_dbt_beneficiary_payment(p):
    put(p, None, dict(tag='endEvent', id='dp_endFailed', name='Failed, corrected for resend', kind='event', attrs={}, ext=[xlistener('end', 'org.sangam.packs.core.BatchItemOutcomeListener', {'outcome': 'FAILED', 'reasonVar': 'opReasonCode', 'dataVars': 'bankAccount,payeeAccount,ifsc,correctedAccount,correctedIfsc'})], body=[]))
    putf(p, None, 'dbt_beneficiary_payment_f4', 'dp_corrGw', 'dp_execute_release', default=True)
    putf(p, None, 'dbt_beneficiary_payment_f5', 'dp_applyFix', 'dp_execute_release')
    putf(p, None, 'dbt_beneficiary_payment_f11', 'dp_fixGw', 'dp_recordFix', c="${fixDecision == 'correct'}", name='correct')
    put(p, None, dict(tag='serviceTask', id='dp_recordFix', name="Record the corrected account for the next round's seal", kind='task', attrs={'operaton:class': 'org.sangam.packs.core.SetVariablesDelegate'}, ext=[fields_xml({'values': 'bankAccount=@correctedAccount;payeeAccount=@correctedAccount;ifsc=@correctedIfsc'})], body=[]))
    putf(p, None, 'dbt_beneficiary_payment_f13', 'dp_recordFix', 'dp_endFailed')
    put(p, None, dict(tag='serviceTask', id='dp_execute_release', name='Release under the batch sanction (owner decision E1)', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.BatchItemReleaseDelegate'}, ext=[], body=[]))
    putf(p, None, 'dp_execute_release_f', 'dp_execute_release', 'dp_execute')


@port('sangam-intl-pfm-execution')
def port_sangam_intl_pfm_execution(p):
    form(p, 'gov-intl-pfm-execution/request', 'Commitment request | प्रतिबद्धता अनुरोध', ['payeePartyId*'], labels={'payeePartyId': ('Payee party ID (verified party registry)', 'प्राप्तकर्ता पक्ष आईडी (सत्यापित पक्ष रजिस्टर)', 'string')})
    put(p, None, dict(tag='callActivity', id='pe_commit', name='Commitment approver (core sanction, delegation DMN)', kind='task', attrs={'calledElement': 'sangam-core-sanction'}, ext=[xlistener('start', 'org.sangam.packs.core.MoneyFieldsDelegate', {'payeeKeys': 'payeePartyId', 'purpose': 'Commitment payment to the supplier'}), '<operaton:in businessKey="#{execution.processBusinessKey}"/>', '<operaton:in variables="all"/>', '<operaton:out variables="all"/>'], body=[]))


@port('sangam-ifms-bill')
def port_sangam_ifms_bill(p):
    putf(p, None, 'ifms_bill_f42', 'bl_rail', 'bl_payeeVerifyPre')
    put(p, None, dict(tag='serviceTask', id='bl_payeeVerifyPre', name='Verified payee before the PAO pass', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.PayeeResolutionDelegate'}, ext=[fields_xml({'currency': 'INR'})], body=[]))
    put(p, None, dict(tag='exclusiveGateway', id='bl_payeePreGw', name='Payee verified?', kind='gateway', attrs={}, ext=[], body=[]))
    defaults(p, 'bl_payeePreGw', 'ifms_bill_f78')
    putf(p, None, 'ifms_bill_f77', 'bl_payeeVerifyPre', 'bl_payeePreGw')
    putf(p, None, 'ifms_bill_f79', 'bl_payeePreGw', 'bl_chain', c='${payeeVerified}', name='verified')
    putf(p, None, 'ifms_bill_f78', 'bl_payeePreGw', 'bl_payeeHold', name='not verified', default=True)
    putf(p, None, 'ifms_bill_f57', 'bl_payeeHoldGw', 'bl_payeeVerifyPre', c="${payeeHoldDecision == 'checkAgain'}", name='check again')
    putb(p, None, dict(tag='boundaryEvent', id='bl_payInvalidated', name='Payee account or payload changed after the PAO pass: fresh approval', host='bl_pay', kind='event', cancel=True, body=['<errorEventDefinition errorRef="err_APPROVAL_INVALIDATED"/>']))
    putf(p, None, 'ifms_bill_f76', 'bl_payInvalidated', 'bl_rail', name='approval invalidated')
    putf(p, None, 'ifms_bill_f63', 'bl_payCorrectGw', 'bl_rail', c="${payCorrectDecision == 'reissue'}", name='reissue after correction')


@port('sangam-ifms-pao-chain')
def port_sangam_ifms_pao_chain(p):
    put(p, None, dict(tag='userTask', id='pc_pao', name='PAO pass, object or return', kind='task', attrs={'operaton:candidateGroups': '${paoSeat}', 'operaton:formKey': 'sangam:ifms-pao-chain/pao', 'operaton:dueDate': '${pc_paoDueAt}'}, ext=[xlistener('start', 'org.sangam.packs.core.MoneyFieldsDelegate', {'payeeKeys': 'employeeId,partyId,payeePartyId', 'purpose': 'Payment of a passed bill to its verified payee'}), tlistener('complete', 'org.sangam.packs.core.SodGuardListener'), tlistener('complete', 'org.sangam.packs.core.ApprovalBindingListener', {'decisionVar': 'paoDecision', 'approveValues': 'passBill'}), tlistener('complete', 'org.sangam.packs.core.ActorRecorderListener', {'decisionVar': 'paoDecision'})], body=[]))
    drop(p, 'ifms_pao_chain_f33')


@port('sangam-ifms-receipt')
def port_sangam_ifms_receipt(p):
    form(p, 'ifms-receipts/resolve', 'Resolve receipt discrepancy | प्राप्ति विसंगति का निवारण', ['refundAmount*'], labels={'refundAmount': ('Amount to refund (INR)', 'वापसी योग्य राशि (रुपये)', 'decimal')})


@port('sangam-ifms-sna-sparsh-release')
def port_sangam_ifms_sna_sparsh_release(p):
    put(p, None, dict(tag='userTask', id='ss_pao', name='PAO pre-check and pass', kind='task', attrs={'operaton:candidateGroups': '${paoSeat}', 'operaton:formKey': 'sangam:ifms-sna-sparsh/pao', 'operaton:dueDate': '${ss_paoDueAt}'}, ext=[xlistener('start', 'org.sangam.packs.core.MoneyFieldsDelegate', {'payeeKeys': 'slsCode', 'purpose': 'SNA-SPARSH release to the scheme account of the SLS'}), tlistener('complete', 'org.sangam.packs.core.SodGuardListener'), tlistener('complete', 'org.sangam.packs.core.ApprovalBindingListener', {'decisionVar': 'paoDecision', 'approveValues': 'passBill'}), tlistener('complete', 'org.sangam.packs.core.ActorRecorderListener', {'decisionVar': 'paoDecision'})], body=[]))
    putf(p, None, 'ifms_sna_sparsh_release_f51', 'ss_reissue', 'ss_pao_slaPlan')


@port('sangam-land-acquisition')
def port_sangam_land_acquisition(p):
    putf(p, None, 'land_acquisition_f44', 'la_awardees', 'la_seal')
    put(p, None, dict(tag='serviceTask', id='la_seal', name='Seal the batch items (count, total, fingerprints; owner decision E1)', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.BatchDelegate'}, ext=[fields_xml({'op': 'SEAL', 'currency': 'INR', 'purpose': 'Land acquisition compensation', 'itemsVar': 'items'})], body=[]))
    put(p, None, dict(tag='callActivity', id='la_bsanction', name='Sanction the sealed batch (core sanction, owner decision E1)', kind='task', attrs={'calledElement': 'sangam-core-sanction'}, ext=['<operaton:in businessKey="#{execution.processBusinessKey}"/>', '<operaton:in variables="all"/>', '<operaton:in sourceExpression="${\'ESCALATE\'}" target="sanctionOnBreach"/>', '<operaton:out variables="all"/>'], body=[]))
    put(p, None, dict(tag='exclusiveGateway', id='la_bsgw', name='Batch sanctioned?', kind='gateway', attrs={}, ext=[], body=[]))
    defaults(p, 'la_bsgw', 'la_bsgw_no')
    put(p, None, dict(tag='endEvent', id='la_bnotReleased', name='Batch not released', kind='event', attrs={}, ext=[], body=[]))
    putf(p, None, 'la_seal_f', 'la_seal', 'la_bsanction')
    putf(p, None, 'la_bsanction_f', 'la_bsanction', 'la_bsgw')
    putf(p, None, 'la_bsgw_yes', 'la_bsgw', 'la_open', c="${sanctionOutcome == 'SANCTIONED'}", name='sanctioned')
    putf(p, None, 'la_bsgw_no', 'la_bsgw', 'la_bnotReleased', name='not sanctioned', default=True)


@port('sangam-land-compensation-payment')
def port_sangam_land_compensation_payment(p):
    putf(p, None, 'land_compensation_paymen_f2', 'lp_defaults', 'lp_pay_release')
    put(p, None, dict(tag='serviceTask', id='lp_pay_release', name='Release under the batch sanction (owner decision E1)', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.BatchItemReleaseDelegate'}, ext=[], body=[]))
    putf(p, None, 'lp_pay_release_f', 'lp_pay_release', 'lp_pay')


@port('sangam-p1-bill-payment')
def port_sangam_p1_bill_payment(p):
    form(p, 'p1-bill-payment/bill-draft', 'Bill for payment | भुगतान हेतु बिल', ['payeePartyId*'], labels={'payeePartyId': ('Payee party ID (verified party registry)', 'प्राप्तकर्ता पक्ष आईडी (सत्यापित पक्ष रजिस्टर)', 'string')})
    form(p, 'p1-bill-payment/revise', 'Revise bill | बिल संशोधित करें', ['payeePartyId*'], labels={'payeePartyId': ('Payee party ID (verified party registry)', 'प्राप्तकर्ता पक्ष आईडी (सत्यापित पक्ष रजिस्टर)', 'string')})
    put(p, None, dict(tag='callActivity', id='p1_sanction', name='Approver by amount band (core)', kind='task', attrs={'calledElement': 'sangam-core-sanction'}, ext=[xlistener('start', 'org.sangam.packs.core.MoneyFieldsDelegate', {'payeeKeys': 'payeePartyId', 'purpose': 'Bill payment to the payee'}), '<operaton:in businessKey="#{execution.processBusinessKey}"/>', '<operaton:in variables="all"/>', '<operaton:out variables="all"/>'], body=[]))
    put(p, None, dict(tag='userTask', id='p1_joint', name='Joint approval', kind='task', attrs={'operaton:candidateGroups': "${jointSeats.split(',')[loopCounter]}", 'operaton:formKey': 'sangam:p1-bill-payment/joint-approval'}, ext=[xlistener('start', 'org.sangam.packs.core.MoneyFieldsDelegate', {'payeeKeys': 'payeePartyId', 'purpose': 'Bill payment to the payee'}), '<operaton:properties><operaton:property name="sangam.maxCardinality" value="5"/></operaton:properties>', tlistener('complete', 'org.sangam.packs.core.SodGuardListener'), tlistener('complete', 'org.sangam.packs.core.ApprovalBindingListener', {'decisionVar': 'jointDecision', 'approveValues': 'approve', 'mode': 'JOINT'}), tlistener('complete', 'org.sangam.packs.core.ActorRecorderListener', {'decisionVar': 'jointDecision'})], body=['<multiInstanceLoopCharacteristics isSequential="false"><loopCardinality xsi:type="tFormalExpression">${jointSeatsCount}</loopCardinality></multiInstanceLoopCharacteristics>']))


@port('sangam-p3-grant-application')
def port_sangam_p3_grant_application(p):
    put(p, None, dict(tag='serviceTask', id='p3_amount', name='Entitlement is the sanction amount', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.SetVariablesDelegate'}, ext=[fields_xml({'values': 'amount=@entitlement;grantTotal=@entitlement'})], body=[]))
    put(p, None, dict(tag='serviceTask', id='p3_override', name='Apply override (reason recorded)', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.SetVariablesDelegate'}, ext=[fields_xml({'values': 'amount=@entitlementOverride;grantTotal=@entitlementOverride'})], body=[]))
    put(p, None, dict(tag='callActivity', id='p3_sanction', name='Sanction by limit (core)', kind='task', attrs={'calledElement': 'sangam-core-sanction'}, ext=[xlistener('start', 'org.sangam.packs.core.MoneyFieldsDelegate', {'amount': 'grantTotal', 'payeeKeys': 'beneficiaryId', 'purpose': 'Grant sanctioned to the beneficiary (total entitlement)'}), '<operaton:in businessKey="#{execution.processBusinessKey}"/>', '<operaton:in variables="all"/>', '<operaton:out variables="all"/>'], body=[]))
    put(p, None, dict(tag='serviceTask', id='p3_instalmentAmount', name='Instalment amount (decision 139 policy)', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.MoneyCalculationDelegate'}, ext=[fields_xml({'result': 'instalmentAmount', 'op': 'INSTALMENT', 'total': 'grantTotal', 'count': 'instalments', 'number': 'instalmentNo', 'policy': 'p3-instalment-rounding'})], body=[]))
    put(p, None, dict(tag='callActivity', id='p3_instSanction', name='Sanction of this instalment (core)', kind='task', attrs={'calledElement': 'sangam-core-sanction'}, ext=[xlistener('start', 'org.sangam.packs.core.MoneyFieldsDelegate', {'amount': 'instalmentAmount', 'payeeKeys': 'beneficiaryId', 'purpose': 'Grant instalment to the beneficiary'}), '<operaton:in businessKey="#{execution.processBusinessKey}"/>', '<operaton:in variables="all"/>', '<operaton:out variables="all"/>'], body=[]))
    put(p, None, dict(tag='exclusiveGateway', id='p3_instSanGw', name='Instalment sanctioned?', kind='gateway', attrs={}, ext=[], body=[]))
    defaults(p, 'p3_instSanGw', 'p3_grant_application_f1003')
    putf(p, None, 'p3_grant_application_f76', 'p3_stepSuffix', 'p3_instSanction')
    putf(p, None, 'p3_grant_application_f1001', 'p3_instSanction', 'p3_instSanGw')
    putf(p, None, 'p3_grant_application_f1002', 'p3_instSanGw', 'p3_disburse', c="${sanctionOutcome == 'SANCTIONED'}", name='SANCTIONED')
    putf(p, None, 'p3_grant_application_f1003', 'p3_instSanGw', 'p3_rj_reject', name='otherwise', default=True)


@port('sangam-pension-monthly')
def port_sangam_pension_monthly(p):
    putf(p, None, 'pension_monthly_f9', 'pm_load', 'pm_seal')
    put(p, None, dict(tag='serviceTask', id='pm_seal', name='Seal the batch items (count, total, fingerprints; owner decision E1)', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.BatchDelegate'}, ext=[fields_xml({'op': 'SEAL', 'currency': 'INR', 'purpose': 'Monthly pension', 'itemsVar': 'items', 'payeeField': 'bankAccount'})], body=[]))
    put(p, None, dict(tag='callActivity', id='pm_bsanction', name='Sanction the sealed batch (core sanction, owner decision E1)', kind='task', attrs={'calledElement': 'sangam-core-sanction'}, ext=['<operaton:in businessKey="#{execution.processBusinessKey}"/>', '<operaton:in variables="all"/>', '<operaton:in sourceExpression="${\'ESCALATE\'}" target="sanctionOnBreach"/>', '<operaton:out variables="all"/>'], body=[]))
    put(p, None, dict(tag='exclusiveGateway', id='pm_bsgw', name='Batch sanctioned?', kind='gateway', attrs={}, ext=[], body=[]))
    defaults(p, 'pm_bsgw', 'pm_bsgw_no')
    put(p, None, dict(tag='endEvent', id='pm_bnotReleased', name='Batch not released', kind='event', attrs={}, ext=[], body=[]))
    putf(p, None, 'pm_seal_f', 'pm_seal', 'pm_bsanction')
    putf(p, None, 'pm_bsanction_f', 'pm_bsanction', 'pm_bsgw')
    putf(p, None, 'pm_bsgw_yes', 'pm_bsgw', 'pm_open', c="${sanctionOutcome == 'SANCTIONED'}", name='sanctioned')
    putf(p, None, 'pm_bsgw_no', 'pm_bsgw', 'pm_bnotReleased', name='not sanctioned', default=True)


@port('sangam-pension-payment')
def port_sangam_pension_payment(p):
    putf(p, None, 'pension_payment_f4', 'pp_lifeGw', 'pp_pay_release', name='valid', default=True)
    putf(p, None, 'pension_payment_f6', 'pp_holdGw', 'pp_pay_release', c="${lifeDecision == 'proceed'}", name='certificate received')
    put(p, None, dict(tag='serviceTask', id='pp_pay_release', name='Release under the batch sanction (owner decision E1)', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.BatchItemReleaseDelegate'}, ext=[], body=[]))
    putf(p, None, 'pp_pay_release_f', 'pp_pay_release', 'pp_pay')


@port('sangam-sec-bank-emandate-debit')
def port_sangam_sec_bank_emandate_debit(p):
    put(p, None, dict(tag='serviceTask', id='ed_mandateMax', name='Registered mandate amount is the cap', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.SetVariablesDelegate'}, ext=[fields_xml({'values': 'mandateMax=@amount'})], body=[]))
    put(p, None, dict(tag='serviceTask', id='ed_debitAmount', name='Actual debit: the fixed amount, else the presented amount (none: held)', kind='task', attrs={'operaton:expression': "${execution.setVariable('debitAmount', execution.getVariable('amountType') == 'FIXED' ? mandateMax : execution.getVariable('debitAmount'))}"}, ext=[], body=[]))
    putf(p, None, 'sec_bank_emandate_debit_f901', 'ed_mandateMax', 'ed_debitAmount')
    putf(p, None, 'sec_bank_emandate_debit_f902', 'ed_debitAmount', 'ed_debit_sanction')
    putf(p, None, 'sec_bank_emandate_debit_f16', 'ed_afaGw', 'ed_mandateMax', name='within the no-AFA limit', default=True)
    putf(p, None, 'sec_bank_emandate_debit_f17', 'ed_afaMsg', 'ed_mandateMax')


@port('sangam-sec-bank-emandate')
def port_sangam_sec_bank_emandate(p):
    form(p, 'sec-bank-emandate/register', 'Mandate registration | अधिदेश पंजीकरण', ['payeePartyId*'], labels={'payeePartyId': ('Merchant (verified payee registry id)', 'व्यापारी (सत्यापित पानेवाला पंजी आईडी)', 'string')}, at='customerId')
    form(p, 'sec-bank-emandate/register', 'Mandate registration | अधिदेश पंजीकरण', ['amountType*'], labels={'amountType': ('Amount type (FIXED or MAX)', 'राशि का प्रकार (FIXED या MAX)', 'select')}, at='amount')
    put(p, None, dict(tag='serviceTask', id='em_spawn', name='Start the debit as a linked case', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.LinkedInstanceDelegate'}, ext=[fields_xml({'processKey': 'sangam-sec-bank-emandate-debit', 'linkType': 'debit', 'inherit': 'customerId,account,amount,merchantCategory,payeePartyId,amountType'})], body=[]))


@port('sangam-sec-bank-dea-claim')
def port_sangam_sec_bank_dea_claim(p):
    form(p, 'sec-bank-inoperative-deposits/claim', 'Reactivation / claim | पुनः सक्रियण / दावा', ['payeePartyId*'], labels={'payeePartyId': ('Payee party ID (verified party registry)', 'प्राप्तकर्ता पक्ष आईडी (सत्यापित पक्ष रजिस्टर)', 'string')})
    form(p, 'sec-bank-inoperative-deposits/verify', 'Verify KYC and entitlement (holder, nominee, legal heir) | केवाईसी एवं पात्रता सत्यापन', ['payableAmount*'], labels={'payableAmount': ('Amount payable with interest (INR)', 'ब्याज सहित देय राशि (रुपये)', 'money')})


@port('sangam-sec-bank-dormancy-account')
def port_sangam_sec_bank_dormancy_account(p):
    putf(p, None, 'sec_bank_dormancy_accoun_f15', 'da_apGw', 'da_transfer_release', c="${deaDecision == 'approve'}", name='approve')
    put(p, None, dict(tag='serviceTask', id='da_transfer_release', name='Release under the batch sanction (owner decision E1)', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.BatchItemReleaseDelegate'}, ext=[], body=[]))
    putf(p, None, 'da_transfer_release_f', 'da_transfer_release', 'da_transfer')


@port('sangam-sec-bank-dormancy-review')
def port_sangam_sec_bank_dormancy_review(p):
    putf(p, None, 'sec_bank_dormancy_review_f9', 'dv_load', 'dv_seal')
    put(p, None, dict(tag='serviceTask', id='dv_seal', name='Seal the batch items (count, total, fingerprints; owner decision E1)', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.BatchDelegate'}, ext=[fields_xml({'op': 'SEAL', 'currency': 'INR', 'purpose': 'Transfer to the DEA Fund', 'itemsVar': 'items'})], body=[]))
    put(p, None, dict(tag='callActivity', id='dv_bsanction', name='Sanction the sealed batch (core sanction, owner decision E1)', kind='task', attrs={'calledElement': 'sangam-core-sanction'}, ext=['<operaton:in businessKey="#{execution.processBusinessKey}"/>', '<operaton:in variables="all"/>', '<operaton:in sourceExpression="${\'ESCALATE\'}" target="sanctionOnBreach"/>', '<operaton:out variables="all"/>'], body=[]))
    put(p, None, dict(tag='exclusiveGateway', id='dv_bsgw', name='Batch sanctioned?', kind='gateway', attrs={}, ext=[], body=[]))
    defaults(p, 'dv_bsgw', 'dv_bsgw_no')
    put(p, None, dict(tag='endEvent', id='dv_bnotReleased', name='Batch not released', kind='event', attrs={}, ext=[], body=[]))
    putf(p, None, 'dv_seal_f', 'dv_seal', 'dv_bsanction')
    putf(p, None, 'dv_bsanction_f', 'dv_bsanction', 'dv_bsgw')
    putf(p, None, 'dv_bsgw_yes', 'dv_bsgw', 'dv_open', c="${sanctionOutcome == 'SANCTIONED'}", name='sanctioned')
    putf(p, None, 'dv_bsgw_no', 'dv_bsgw', 'dv_bnotReleased', name='not sanctioned', default=True)


@port('sangam-sec-bank-loan-origination')
def port_sangam_sec_bank_loan_origination(p):
    put(p, None, dict(tag='callActivity', id='lo_committee', name='Credit committee (core committee)', kind='task', attrs={'calledElement': 'sangam-core-committee'}, ext=[xlistener('start', 'org.sangam.packs.core.MoneyFieldsDelegate', {'payeeKeys': 'customerId', 'purpose': 'Loan disbursement to the borrower'}), '<operaton:in businessKey="#{execution.processBusinessKey}"/>', '<operaton:in variables="all"/>', '<operaton:out variables="all"/>'], body=[]))
    put(p, None, dict(tag='callActivity', id='lo_sanction', name='Sanction by delegated power (core sanction, SoD, signature)', kind='task', attrs={'calledElement': 'sangam-core-sanction'}, ext=[xlistener('start', 'org.sangam.packs.core.MoneyFieldsDelegate', {'payeeKeys': 'customerId', 'purpose': 'Loan disbursement to the borrower'}), '<operaton:in businessKey="#{execution.processBusinessKey}"/>', '<operaton:in variables="all"/>', '<operaton:out variables="all"/>'], body=[]))


@port('sangam-sec-bank-payment')
def port_sangam_sec_bank_payment(p):
    form(p, 'sec-bank-payment-dual-auth/initiate', 'Payment initiation | भुगतान प्रारंभ', ['payeePartyId*', 'remitterPartyId*'], labels={'payeePartyId': ('Payee party ID (verified party registry)', 'प्राप्तकर्ता पक्ष आईडी (सत्यापित पक्ष रजिस्टर)', 'string'), 'remitterPartyId': ('Remitter (verified registry id of the debited customer account)', 'प्रेषक (डेबिट खाते का सत्यापित पंजी आईडी)', 'string')})
    form(p, 'sec-bank-payment-dual-auth/correct', 'Correct beneficiary details | लाभार्थी विवरण सुधारें', ['payeePartyId*'], labels={'payeePartyId': ('Payee party ID (verified party registry)', 'प्राप्तकर्ता पक्ष आईडी (सत्यापित पक्ष रजिस्टर)', 'string')})
    put(p, None, dict(tag='callActivity', id='py_checker', name='Checker (core sanction)', kind='task', attrs={'calledElement': 'sangam-core-sanction'}, ext=[xlistener('start', 'org.sangam.packs.core.MoneyFieldsDelegate', {'payeeKeys': 'payeePartyId', 'purpose': 'Outward transfer to the beneficiary'}), '<operaton:in businessKey="#{execution.processBusinessKey}"/>', '<operaton:in variables="all"/>', '<operaton:out variables="all"/>'], body=[]))
    put(p, None, dict(tag='callActivity', id='py_authoriser', name='Authoriser (core sanction, different person and seat)', kind='task', attrs={'calledElement': 'sangam-core-sanction'}, ext=[xlistener('start', 'org.sangam.packs.core.MoneyFieldsDelegate', {'payeeKeys': 'payeePartyId', 'purpose': 'Outward transfer to the beneficiary'}), '<operaton:in businessKey="#{execution.processBusinessKey}"/>', '<operaton:in variables="all"/>', '<operaton:out variables="all"/>'], body=[]))
    put(p, 'py_return', dict(tag='serviceTask', id='py_retCredit', name='Credit back the remitter', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.ConnectorCallDelegate', 'operaton:asyncBefore': 'true'}, ext=[fields_xml({'connector': 'cbs.credit', 'operation': 'credit', 'mode': 'submit', 'inputs': 'payeeAccount,amount', 'resultPrefix': 'retCredit', 'step': 'return-credit'})], body=[]))
    putf(p, 'py_return', 'sec_bank_payment_f50', 'py_retStart', 'py_retCredit_sanction')


@port('sangam-sec-bank-txn-dispute')
def port_sangam_sec_bank_txn_dispute(p):
    form(p, 'sec-bank-txn-dispute/reverse', 'Reverse the failed debit | विफल नामे की प्रतिवर्ती प्रविष्टि', ['compensationAmount*'], labels={'compensationAmount': ('Compensation amount (INR)', 'क्षतिपूर्ति राशि (रुपये)', 'money')})


@port('sangam-sec-cap-dividend-holder')
def port_sangam_sec_cap_dividend_holder(p):
    putf(p, None, 'sec_cap_dividend_holder_f2', 'ch_defaults', 'ch_pay_release')
    put(p, None, dict(tag='serviceTask', id='ch_pay_release', name='Release under the batch sanction (owner decision E1)', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.BatchItemReleaseDelegate'}, ext=[], body=[]))
    putf(p, None, 'ch_pay_release_f', 'ch_pay_release', 'ch_pay')


@port('sangam-sec-cap-dividend')
def port_sangam_sec_cap_dividend(p):
    form(p, 'sec-cap-corporate-action/declare', 'Dividend declaration | लाभांश घोषणा', ['unpaidDividendPartyId*'], labels={'unpaidDividendPartyId': ('Unpaid dividend account of the company (verified payee registry id)', 'कंपनी का अदत्त लाभांश खाता (सत्यापित पंजी आईडी)', 'string')})
    form(p, 'sec-cap-corporate-action/review', 'Review payment outcome before the 30-day limit | 30 दिन की सीमा से पूर्व भुगतान की समीक्षा', ['unpaidAmount*'], labels={'unpaidAmount': ('Unpaid dividend to move (INR)', 'अदत्त लाभांश राशि (रुपये)', 'money')})
    putf(p, None, 'sec_cap_dividend_f14', 'cd_benpos', 'cd_seal')
    put(p, None, dict(tag='serviceTask', id='cd_seal', name='Seal the batch items (count, total, fingerprints; owner decision E1)', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.BatchDelegate'}, ext=[fields_xml({'op': 'SEAL', 'currency': 'INR', 'purpose': 'Dividend payment', 'itemsVar': 'items'})], body=[]))
    put(p, None, dict(tag='callActivity', id='cd_bsanction', name='Sanction the sealed batch (core sanction, owner decision E1)', kind='task', attrs={'calledElement': 'sangam-core-sanction'}, ext=['<operaton:in businessKey="#{execution.processBusinessKey}"/>', '<operaton:in variables="all"/>', '<operaton:in sourceExpression="${\'ESCALATE\'}" target="sanctionOnBreach"/>', '<operaton:out variables="all"/>'], body=[]))
    put(p, None, dict(tag='exclusiveGateway', id='cd_bsgw', name='Batch sanctioned?', kind='gateway', attrs={}, ext=[], body=[]))
    defaults(p, 'cd_bsgw', 'cd_bsgw_no')
    put(p, None, dict(tag='endEvent', id='cd_bnotReleased', name='Batch not released', kind='event', attrs={}, ext=[], body=[]))
    putf(p, None, 'cd_seal_f', 'cd_seal', 'cd_bsanction')
    putf(p, None, 'cd_bsanction_f', 'cd_bsanction', 'cd_bsgw')
    putf(p, None, 'cd_bsgw_yes', 'cd_bsgw', 'cd_open', c="${sanctionOutcome == 'SANCTIONED'}", name='sanctioned')
    putf(p, None, 'cd_bsgw_no', 'cd_bsgw', 'cd_bnotReleased', name='not sanctioned', default=True)


@port('sangam-sec-ecom-gst-tcs')
def port_sangam_sec_ecom_gst_tcs(p):
    putf(p, None, 'sec_ecom_gst_tcs_f18', 'gt_review_done', 'gt_tcsGw')
    put(p, None, dict(tag='exclusiveGateway', id='gt_tcsGw', name='TCS payable?', kind='gateway', attrs={}, ext=[], body=[]))
    defaults(p, 'gt_tcsGw', 'sec_ecom_gst_tcs_f24')
    putf(p, None, 'sec_ecom_gst_tcs_f23', 'gt_tcsGw', 'gt_deposit_sanction', c='${tcsAmount > 0}', name='TCS payable')
    putf(p, None, 'sec_ecom_gst_tcs_f24', 'gt_tcsGw', 'gt_gstr8', name='nil: nothing to deposit', default=True)


@port('sangam-sec-ecom-order')
def port_sangam_sec_ecom_order(p):
    form(p, 'sec-ecom-order-to-cash/commission-amount', 'Commission amount | कमीशन राशि', ['commissionAmountEntered*', 'remarks'], labels={'commissionAmountEntered': ('Commission (INR, whole paise next to the exact value)', 'कमीशन (रुपये, सटीक मान के निकट पूर्ण पैसे)', 'money')})
    put(p, None, dict(tag='serviceTask', id='eo_defaults', name='Configuration', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.SetVariablesDelegate'}, ext=[fields_xml({'defaults': 'sellerId=SELLER-1;paymentMode=PREPAID;category=GENERAL;sellerSla=2D;deliverySla=7D;returnWindow=P7D;sellerGroup=SELLER-DESK;settlementSeat=SETTLEMENT-DESK;executionConnector=pg.refund;executionStep=refund;verifyApproval=false;reconcileRequired=false;executionPayloadFields=orderId,amount;applicantRef=@customerId'})], body=[]))
    putf(p, None, 'eo_f9013', 'eo_net', 'eo_settle_sanction')
    putf(p, None, 'eo_f9011', 'eo_commissionCalc', 'eo_commissionCalc_heldGw')
    put(p, None, dict(tag='exclusiveGateway', id='eo_commissionCalc_heldGw', name='Exact in minor units?', kind='gateway', attrs={}, ext=[], body=[]))
    defaults(p, 'eo_commissionCalc_heldGw', 'eo_commissionCalc_heldGw_exact')
    putf(p, None, 'eo_commissionCalc_heldGw_exact', 'eo_commissionCalc_heldGw', 'eo_net', name='exact', default=True)
    putf(p, None, 'eo_commissionCalc_heldGw_held', 'eo_commissionCalc_heldGw', 'eo_commissionCalc_enter', c='${commissionAmountHeld == true}', name='held: reviewer enters the amount')
    put(p, None, dict(tag='userTask', id='eo_commissionCalc_enter', name='Enter the payable commissionAmount (exact value held, owner rule C4)', kind='task', attrs={'operaton:candidateGroups': '${settlementSeat}', 'operaton:formKey': 'sangam:sec-ecom-order-to-cash/commission-amount'}, ext=[tlistener('complete', 'org.sangam.packs.core.ActorRecorderListener')], body=[]))
    putf(p, None, 'eo_commissionCalc_enter_back', 'eo_commissionCalc_enter', 'eo_commissionCalc')
    putf(p, None, 'eo_f9012', 'eo_commGw', 'eo_settle_sanction', name='unknown: held at the sanction (no payable amount)', default=True)
    putf(p, None, 'eo_f9010', 'eo_commGw', 'eo_commissionCalc', c="${execution.getVariable('commissionPct') != null}", name='known')
    put(p, None, dict(tag='exclusiveGateway', id='eo_commGw', name='Commission rate known?', kind='gateway', attrs={}, ext=[], body=[]))
    defaults(p, 'eo_commGw', 'eo_f9012')
    put(p, None, dict(tag='serviceTask', id='eo_commissionCalc', name='Commission = order value x commission %, exact (owner rule C4)', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.MoneyCalculationDelegate'}, ext=[fields_xml({'result': 'commissionAmount', 'op': 'EXACT', 'formula': 'amount * commissionPct / 100', 'min': '0', 'max': 'amount'})], body=[]))
    put(p, None, dict(tag='serviceTask', id='eo_net', name='Seller payable = order value less commission', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.MoneyCalculationDelegate'}, ext=[fields_xml({'result': 'payableAmount', 'op': 'SUM', 'formula': 'amount - commissionAmount'})], body=[]))
    putf(p, None, 'sec_ecom_order_f45', 'eo_settleCfg', 'eo_commGw')
    putf(p, 'eo_cancel', 'sec_ecom_order_f48', 'eo_cxCfg', 'eo_cxRefund_sanction')


@port('sangam-sec-ecom-return')
def port_sangam_sec_ecom_return(p):
    form(p, 'sec-ecom-return-refund/request', 'Return request | वापसी अनुरोध', ['originalPaymentRef*'], labels={'originalPaymentRef': ('Original payment reference (capture)', 'मूल भुगतान संदर्भ (प्राप्ति)', 'string')})


@port('sangam-sec-edu-admission')
def port_sangam_sec_edu_admission(p):
    form(p, 'sec-edu-admission/refund-review', 'Withdrawal refund: processing fee | प्रत्याहरण वापसी: प्रसंस्करण शुल्क', ['processingFee*', 'remarks'], labels={'processingFee': ('Processing fee deducted (INR, 0 if none)', 'काटा गया प्रसंस्करण शुल्क (रुपये, न हो तो 0)', 'money')})
    form(p, 'sec-edu-admission/refund-amount', 'Withdrawal refund: payable amount | प्रत्याहरण वापसी: देय राशि', ['refundAmountEntered*', 'remarks'], labels={'refundAmountEntered': ('Refund payable (INR, whole paise next to the exact value)', 'देय वापसी (रुपये, सटीक मान के निकट पूर्ण पैसे)', 'money')})
    put(p, None, dict(tag='callActivity', id='ed_fee', name='Collect fee (core execution)', kind='task', attrs={'calledElement': 'sangam-core-execution'}, ext=[xlistener('end', 'org.sangam.packs.core.CaptureRefunds$Record', {'amount': 'feeAmount', 'snapshotVar': 'feeCaptureRef'}), '<operaton:in businessKey="#{execution.processBusinessKey}"/>', '<operaton:in variables="all"/>', '<operaton:out variables="all"/>'], body=[]))
    put(p, 'ed_withdraw', dict(tag='serviceTask', id='ed_wdCfg', name='Refund settings', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.SetVariablesDelegate'}, ext=[fields_xml({'values': 'executionConnector=pg.refund;executionStep=withdrawal-refund;executionPayloadFields=applicantName,refundPct,amount,payeeAccount'})], body=[]))
    put(p, 'ed_withdraw', dict(tag='serviceTask', id='ed_wdAmount', name='Refund = fee x refund % less the processing fee, exact (owner ruling A, C4)', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.MoneyCalculationDelegate'}, ext=[fields_xml({'result': 'refundAmount', 'op': 'EXACT', 'formula': 'feeAmount * refundPct / 100 - processingFee', 'min': '0', 'max': 'feeAmount'})], body=[]))
    putf(p, 'ed_withdraw', 'sec_edu_admission_f34', 'ed_wdCfg', 'ed_wdReview')
    put(p, 'ed_withdraw', dict(tag='userTask', id='ed_wdReview', name='Refund review: processing fee', kind='task', attrs={'operaton:candidateGroups': '${scrutinySeat}', 'operaton:formKey': 'sangam:sec-edu-admission/refund-review'}, ext=[tlistener('complete', 'org.sangam.packs.core.ActorRecorderListener')], body=[]))
    putf(p, 'ed_withdraw', 'ed_wdReview_f', 'ed_wdReview', 'ed_wdAmount')
    putf(p, 'ed_withdraw', 'ed_f9010', 'ed_wdAmount', 'ed_wdAmount_heldGw')
    put(p, 'ed_withdraw', dict(tag='exclusiveGateway', id='ed_wdAmount_heldGw', name='Exact in minor units?', kind='gateway', attrs={}, ext=[], body=[]))
    defaults(p, 'ed_wdAmount_heldGw', 'ed_wdAmount_heldGw_exact')
    putf(p, 'ed_withdraw', 'ed_wdAmount_heldGw_exact', 'ed_wdAmount_heldGw', 'ed_wdRefund_sanction', name='exact', default=True)
    putf(p, 'ed_withdraw', 'ed_wdAmount_heldGw_held', 'ed_wdAmount_heldGw', 'ed_wdAmount_enter', c='${refundAmountHeld == true}', name='held: reviewer enters the amount')
    put(p, 'ed_withdraw', dict(tag='userTask', id='ed_wdAmount_enter', name='Enter the payable refundAmount (exact value held, owner rule C4)', kind='task', attrs={'operaton:candidateGroups': '${scrutinySeat}', 'operaton:formKey': 'sangam:sec-edu-admission/refund-amount'}, ext=[tlistener('complete', 'org.sangam.packs.core.ActorRecorderListener')], body=[]))
    putf(p, 'ed_withdraw', 'ed_wdAmount_enter_back', 'ed_wdAmount_enter', 'ed_wdAmount')


@port('sangam-sec-edu-scholarship')
def port_sangam_sec_edu_scholarship(p):
    form(p, 'sec-edu-scholarship/apply', 'Scholarship application | छात्रवृत्ति आवेदन', ['payeePartyId*'], labels={'payeePartyId': ('Payee party ID (verified party registry)', 'प्राप्तकर्ता पक्ष आईडी (सत्यापित पक्ष रजिस्टर)', 'string')})
    put(p, None, dict(tag='callActivity', id='sc_l2', name='District / state verification and sanction (L2, core sanction)', kind='task', attrs={'calledElement': 'sangam-core-sanction'}, ext=[xlistener('start', 'org.sangam.packs.core.MoneyFieldsDelegate', {'payeeKeys': 'payeePartyId', 'purpose': 'Scholarship paid by direct benefit transfer'}), '<operaton:in businessKey="#{execution.processBusinessKey}"/>', '<operaton:in variables="all"/>', '<operaton:out variables="all"/>'], body=[]))


@port('sangam-sec-hr-payroll')
def port_sangam_sec_hr_payroll(p):
    put(p, None, dict(tag='serviceTask', id='pr_defaults', name='Configuration', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.SetVariablesDelegate'}, ext=[fields_xml({'defaults': 'period=CURRENT;caseType=PAYROLL;routingDecisionKey=sec-hr-payroll-authority;powersDecisionKey=sec-hr-payroll-powers;appealAllowed=false;payrollSeat=PAYROLL-MAKER'})], body=[]))
    put(p, None, dict(tag='callActivity', id='pr_approve', name='Sanction the sealed salary batch (core sanction, owner decision E1)', kind='task', attrs={'calledElement': 'sangam-core-sanction'}, ext=['<operaton:in businessKey="#{execution.processBusinessKey}"/>', '<operaton:in variables="all"/>', '<operaton:in sourceExpression="${\'ESCALATE\'}" target="sanctionOnBreach"/>', '<operaton:out variables="all"/>'], body=[]))
    put(p, None, dict(tag='parallelGateway', id='pr_fork', name='Returns by the 15th (PF, then ESI: each on its own sanction, one at a time)', kind='gateway', attrs={}, ext=[], body=[]))
    put(p, None, dict(tag='callActivity', id='pr_pf_paySanction', name='Approve release of this payment (core sanction, owner decision A1)', kind='task', attrs={'calledElement': 'sangam-core-sanction'}, ext=[xlistener('start', 'org.sangam.packs.core.MoneyFieldsDelegate', {'amount': 'pfAmount', 'statutoryParty': 'EPFO', 'purpose': 'PF contribution (ECR) to EPFO'}), '<operaton:in businessKey="#{execution.processBusinessKey}"/>', '<operaton:in variables="all"/>', '<operaton:in sourceExpression="${true}" target="moneyOutRelease"/>', '<operaton:in sourceExpression="${\'ESCALATE\'}" target="sanctionOnBreach"/>', '<operaton:out variables="all"/>'], body=[]))
    put(p, None, dict(tag='exclusiveGateway', id='pr_pf_paySgw', name='Sanctioned?', kind='gateway', attrs={}, ext=[], body=[]))
    defaults(p, 'pr_pf_paySgw', 'pr_pf_f9002')
    put(p, None, dict(tag='endEvent', id='pr_pf_payNotReleased', name='Not released', kind='event', attrs={}, ext=[], body=[]))
    put(p, None, dict(tag='callActivity', id='pr_esi_paySanction', name='Approve release of this payment (core sanction, owner decision A1)', kind='task', attrs={'calledElement': 'sangam-core-sanction'}, ext=[xlistener('start', 'org.sangam.packs.core.MoneyFieldsDelegate', {'amount': 'esiAmount', 'statutoryParty': 'ESIC', 'purpose': 'ESI contribution to ESIC'}), '<operaton:in businessKey="#{execution.processBusinessKey}"/>', '<operaton:in variables="all"/>', '<operaton:in sourceExpression="${true}" target="moneyOutRelease"/>', '<operaton:in sourceExpression="${\'ESCALATE\'}" target="sanctionOnBreach"/>', '<operaton:out variables="all"/>'], body=[]))
    put(p, None, dict(tag='exclusiveGateway', id='pr_esi_paySgw', name='Sanctioned?', kind='gateway', attrs={}, ext=[], body=[]))
    defaults(p, 'pr_esi_paySgw', 'pr_esi_f9002')
    put(p, None, dict(tag='endEvent', id='pr_esi_payNotReleased', name='Not released', kind='event', attrs={}, ext=[], body=[]))
    putf(p, None, 'pr_esi_f9002', 'pr_esi_paySgw', 'pr_esi_payNotReleased', name='not sanctioned', default=True)
    putf(p, None, 'pr_esi_f9001', 'pr_esi_paySgw', 'pr_esiFile', c="${sanctionOutcome == 'SANCTIONED'}", name='sanctioned')
    putf(p, None, 'pr_esi_f9000', 'pr_esi_paySanction', 'pr_esi_paySgw')
    putf(p, None, 'pr_pf_f9002', 'pr_pf_paySgw', 'pr_pf_payNotReleased', name='not sanctioned', default=True)
    putf(p, None, 'pr_pf_f9001', 'pr_pf_paySgw', 'pr_pfFile', c="${sanctionOutcome == 'SANCTIONED'}", name='sanctioned')
    putf(p, None, 'pr_pf_f9000', 'pr_pf_paySanction', 'pr_pf_paySgw')
    putf(p, None, 'sec_hr_payroll_f13', 'pr_make', 'pr_salaryItems')
    put(p, None, dict(tag='serviceTask', id='pr_salaryItems', name='Salary items: net pay per employee to the registry account (owner E1, E3, M2)', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.BatchItemsDelegate'}, ext=[fields_xml({'source': 'inputsRegister', 'keyField': 'employeeId', 'amount': 'grossWages - pfEmployee - esiEmployee - otherDeductions', 'payeeKey': 'employeeId', 'itemsVar': 'salaryItems'})], body=[]))
    putf(p, None, 'pr_salaryItems_f', 'pr_salaryItems', 'pr_seal')
    put(p, None, dict(tag='serviceTask', id='pr_seal', name='Seal the salary register (count, total, fingerprints; owner decision E1)', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.BatchDelegate'}, ext=[fields_xml({'op': 'SEAL', 'currency': 'INR', 'purpose': 'Salary for the period', 'itemsVar': 'salaryItems', 'payeeField': 'payeeAccount'})], body=[]))
    putf(p, None, 'pr_seal_f', 'pr_seal', 'pr_approve')
    putf(p, None, 'sec_hr_payroll_f14', 'pr_sanGw', 'pr_open', c="${sanctionOutcome == 'SANCTIONED'}", name='SANCTIONED')
    put(p, None, dict(tag='serviceTask', id='pr_open', name='Open the salary round', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.BatchDelegate'}, ext=[fields_xml({'op': 'OPEN', 'itemsVar': 'salaryItems'})], body=[]))
    putf(p, None, 'pr_open_f', 'pr_open', 'pr_fanOut')
    put(p, None, dict(tag='serviceTask', id='pr_fanOut', name='Start one salary payment per employee (chunk)', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.BatchDelegate', 'operaton:asyncBefore': 'true'}, ext=[fields_xml({'op': 'FAN_OUT', 'itemsVar': 'salaryItems', 'childProcessKey': 'sangam-sec-hr-salary-payment', 'chunkSize': '200', 'inherit': 'period'})], body=[]))
    putf(p, None, 'pr_fanOut_f', 'pr_fanOut', 'pr_fanGw')
    put(p, None, dict(tag='exclusiveGateway', id='pr_fanGw', name='All started?', kind='gateway', attrs={}, ext=[], body=[]))
    defaults(p, 'pr_fanGw', 'pr_fanGw_all')
    putf(p, None, 'pr_fanGw_next', 'pr_fanGw', 'pr_fanOut', c='${!fanOutDone}', name='next chunk')
    putf(p, None, 'pr_fanGw_all', 'pr_fanGw', 'pr_poll', name='all started', default=True)
    put(p, None, dict(tag='intermediateCatchEvent', id='pr_poll', name='Poll salary outcomes', kind='event', attrs={}, ext=[], body=['<timerEventDefinition><timeDuration xsi:type="tFormalExpression">PT5M</timeDuration></timerEventDefinition>']))
    putf(p, None, 'pr_poll_f', 'pr_poll', 'pr_aggregate')
    put(p, None, dict(tag='serviceTask', id='pr_aggregate', name='Aggregate salary outcomes', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.BatchDelegate', 'operaton:asyncBefore': 'true'}, ext=[fields_xml({'op': 'AGGREGATE'})], body=[]))
    putf(p, None, 'pr_aggregate_f', 'pr_aggregate', 'pr_doneGw')
    put(p, None, dict(tag='exclusiveGateway', id='pr_doneGw', name='All salaries done?', kind='gateway', attrs={}, ext=[], body=[]))
    defaults(p, 'pr_doneGw', 'sec_hr_payroll_f17')
    putf(p, None, 'pr_doneGw_wait', 'pr_doneGw', 'pr_poll', c='${!batchComplete}', name='not yet')
    putf(p, None, 'sec_hr_payroll_f17', 'pr_doneGw', 'pr_fork', name='complete', default=True)
    putf(p, None, 'sec_hr_payroll_f27', 'pr_pf_done', 'pr_pf_paySanction')
    putf(p, None, 'sec_hr_payroll_f28', 'pr_pfFile', 'pr_esi_slaPlan')
    putf(p, None, 'sec_hr_payroll_f38', 'pr_esi_done', 'pr_esi_paySanction')
    drop(p, 'pr_pay')
    drop(p, 'sec_hr_payroll_f37')


@new
def new_sec_hr_salary_payment(by_pack, by_pid):
    p = by_pack['sec.hr.payroll'].proc('sangam-sec-hr-salary-payment', 'Salary payment for one employee', start={'materialFields': 'key,employeeId,amount,payeeAccount,currency,purpose'})
    put(p, None, dict(tag='startEvent', id='sp_start', name='Employee', kind='event', attrs={}, ext=[], body=[]))
    put(p, None, dict(tag='serviceTask', id='sp_defaults', name='Configuration', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.SetVariablesDelegate'}, ext=[fields_xml({'defaults': 'executionConnector=bank.transfer;executionStep=salary;verifyApproval=false;reconcileRequired=true;executionPayloadFields=employeeId,amount,payeeAccount'})], body=[]))
    put(p, None, dict(tag='serviceTask', id='sp_release', name='Release under the batch sanction (owner decision E1)', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.BatchItemReleaseDelegate'}, ext=[], body=[]))
    put(p, None, dict(tag='callActivity', id='sp_pay', name='Pay the salary (core execution)', kind='task', attrs={'calledElement': 'sangam-core-execution'}, ext=['<operaton:in businessKey="#{execution.processBusinessKey}"/>', '<operaton:in variables="all"/>', '<operaton:out variables="all"/>'], body=[]))
    put(p, None, dict(tag='exclusiveGateway', id='sp_exGw', name='executionOutcome?', kind='gateway', attrs={}, ext=[], body=[]))
    put(p, None, dict(tag='endEvent', id='sp_endPaid', name='Paid', kind='event', attrs={}, ext=[xlistener('end', 'org.sangam.packs.core.BatchItemOutcomeListener', {'outcome': 'SUCCESS'})], body=[]))
    put(p, None, dict(tag='endEvent', id='sp_endFailed', name='Failed', kind='event', attrs={}, ext=[xlistener('end', 'org.sangam.packs.core.BatchItemOutcomeListener', {'outcome': 'FAILED', 'reasonVar': 'opReasonCode'})], body=[]))
    putf(p, None, 'sp_f1', 'sp_start', 'sp_defaults')
    putf(p, None, 'sp_f2', 'sp_defaults', 'sp_release')
    putf(p, None, 'sp_f3', 'sp_release', 'sp_pay')
    putf(p, None, 'sp_f6', 'sp_pay', 'sp_exGw')
    putf(p, None, 'sp_f4', 'sp_exGw', 'sp_endPaid', c="${executionOutcome == 'DONE'}", name='DONE')
    putf(p, None, 'sp_f5', 'sp_exGw', 'sp_endFailed', name='otherwise', default=True)


@port('sangam-sec-ins-death-claim')
def port_sangam_sec_ins_death_claim(p):
    form(p, 'sec-ins-death-claim/intimation', 'Death claim intimation | मृत्यु दावा सूचना', ['payeePartyId*'], labels={'payeePartyId': ('Payee party ID (verified party registry)', 'प्राप्तकर्ता पक्ष आईडी (सत्यापित पक्ष रजिस्टर)', 'string')})
    put(p, None, dict(tag='callActivity', id='dc_sanction', name='Settlement sanction (core sanction)', kind='task', attrs={'calledElement': 'sangam-core-sanction'}, ext=[xlistener('start', 'org.sangam.packs.core.MoneyFieldsDelegate', {'payeeKeys': 'payeePartyId', 'purpose': 'Death claim paid to the nominee or legal heir'}), '<operaton:in businessKey="#{execution.processBusinessKey}"/>', '<operaton:in variables="all"/>', '<operaton:out variables="all"/>'], body=[]))


@port('sangam-sec-ins-health-claim')
def port_sangam_sec_ins_health_claim(p):
    form(p, 'sec-ins-health-claim/assess', 'Assess reimbursement claim | प्रतिपूर्ति दावे का आकलन', ['payeePartyId*'], labels={'payeePartyId': ('Payee party ID (verified party registry)', 'प्राप्तकर्ता पक्ष आईडी (सत्यापित पक्ष रजिस्टर)', 'string')})


@port('sangam-sec-ins-motor-claim')
def port_sangam_sec_ins_motor_claim(p):
    form(p, 'sec-ins-motor-claim/assess', 'Assess claim | दावे का आकलन', ['payeePartyId*'], labels={'payeePartyId': ('Payee party ID (verified party registry)', 'प्राप्तकर्ता पक्ष आईडी (सत्यापित पक्ष रजिस्टर)', 'string')})
    put(p, None, dict(tag='callActivity', id='mc_sanction', name='Settlement sanction (core sanction, 7 days)', kind='task', attrs={'calledElement': 'sangam-core-sanction'}, ext=[xlistener('start', 'org.sangam.packs.core.MoneyFieldsDelegate', {'payeeKeys': 'payeePartyId', 'purpose': 'Motor claim paid to the garage or the insured'}), '<operaton:in businessKey="#{execution.processBusinessKey}"/>', '<operaton:in variables="all"/>', '<operaton:out variables="all"/>'], body=[]))


@port('sangam-sec-ins-proposal')
def port_sangam_sec_ins_proposal(p):
    form(p, 'sec-ins-proposal-underwriting/free-look-refund', 'Free-look refund: deductions | फ्री-लुक वापसी: कटौतियाँ', ['riskPremiumDeduction*', 'medicalCostDeduction*', 'stampDutyDeduction*', 'remarks'], labels={'riskPremiumDeduction': ('Proportionate risk premium (INR)', 'आनुपातिक जोखिम प्रीमियम (रुपये)', 'money'), 'medicalCostDeduction': ('Medical examination cost (INR)', 'चिकित्सा जाँच लागत (रुपये)', 'money'), 'stampDutyDeduction': ('Stamp duty (INR)', 'स्टांप शुल्क (रुपये)', 'money')})
    form(p, 'sec-ins-proposal-underwriting/premium', 'Premium to collect | संग्रहीत किया जाने वाला प्रीमियम', ['premiumAmount*', 'remarks'], labels={'premiumAmount': ('Premium (INR)', 'प्रीमियम (रुपये)', 'money')})
    put(p, None, dict(tag='serviceTask', id='ip_defaults', name='Configuration', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.SetVariablesDelegate'}, ext=[fields_xml({'defaults': 'customerName=@customerId;productCode=TERM;sumAssured=100000000;age=35;smoker=false;medicalDisclosures=NONE;uwSeat=UNDERWRITER;executionConnector=pg.collect;executionStep=premium;verifyApproval=false;reconcileRequired=false;executionPayloadFields=customerName,premiumAmount;applicantRef=@customerId;appealAllowed=false'})], body=[]))
    put(p, None, dict(tag='callActivity', id='ip_premium', name='Collect premium once (core execution)', kind='task', attrs={'calledElement': 'sangam-core-execution'}, ext=[xlistener('end', 'org.sangam.packs.core.CaptureRefunds$Record', {'amount': 'premiumAmount', 'snapshotVar': 'premiumCaptureRef'}), '<operaton:in businessKey="#{execution.processBusinessKey}"/>', '<operaton:in variables="all"/>', '<operaton:out variables="all"/>'], body=[]))
    put(p, None, dict(tag='serviceTask', id='ip_refund', name='Refund premium less risk premium, medicals and stamp duty', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.ConnectorCallDelegate', 'operaton:asyncBefore': 'true'}, ext=[fields_xml({'connector': 'pg.refund', 'operation': 'refund', 'mode': 'submit', 'inputs': 'customerName,amount,payeeAccount', 'resultPrefix': 'flRefund', 'step': 'free-look-refund'})], body=[]))
    put(p, None, dict(tag='userTask', id='ip_flDeductions', name='Free-look deductions (risk premium, medicals, stamp duty)', kind='task', attrs={'operaton:candidateGroups': '${uwSeat}', 'operaton:formKey': 'sangam:sec-ins-proposal-underwriting/free-look-refund'}, ext=[tlistener('complete', 'org.sangam.packs.core.ActorRecorderListener')], body=[]))
    put(p, None, dict(tag='serviceTask', id='ip_flRefundAmount', name='Refund = premium less the deductions', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.MoneyCalculationDelegate'}, ext=[fields_xml({'result': 'refundAmount', 'op': 'SUM', 'formula': 'premiumAmount - riskPremiumDeduction - medicalCostDeduction - stampDutyDeduction'})], body=[]))
    putf(p, None, 'ip_f9011', 'ip_flRefundAmount', 'ip_refund_sanction')
    putf(p, None, 'ip_f9010', 'ip_flDeductions', 'ip_flRefundAmount')
    put(p, None, dict(tag='exclusiveGateway', id='ip_premiumGw', name='Premium entered?', kind='gateway', attrs={}, ext=[], body=[]))
    defaults(p, 'ip_premiumGw', 'ip_premiumGw_enter')
    putf(p, None, 'ip_premiumGw_yes', 'ip_premiumGw', 'ip_premium', c="${execution.getVariable('premiumAmount') != null && premiumAmount > 0}", name='premium entered')
    putf(p, None, 'ip_premiumGw_enter', 'ip_premiumGw', 'ip_premiumEnter', name='no premium: a person enters it', default=True)
    put(p, None, dict(tag='userTask', id='ip_premiumEnter', name='Enter the premium (no rating source; DEC-5)', kind='task', attrs={'operaton:candidateGroups': '${uwSeat}', 'operaton:formKey': 'sangam:sec-ins-proposal-underwriting/premium'}, ext=[tlistener('complete', 'org.sangam.packs.core.ActorRecorderListener')], body=[]))
    putf(p, None, 'ip_premiumEnter_f', 'ip_premiumEnter', 'ip_premiumGw')
    putf(p, None, 'sec_ins_proposal_f35', 'ip_uwGw', 'ip_premiumGw', c="${uwDecision == 'accept'}", name='accept')
    putf(p, None, 'sec_ins_proposal_f36', 'ip_consGw', 'ip_premiumGw', c="${counterDecision == 'accept'}", name='accepted')
    putf(p, None, 'sec_ins_proposal_f49', 'ip_flCancel', 'ip_flDeductions')


@port('sangam-sec-nbfc-digital-loan')
def port_sangam_sec_nbfc_digital_loan(p):
    put(p, None, dict(tag='callActivity', id='dl_sanction', name='Credit officer of the RE sanctions (core sanction)', kind='task', attrs={'calledElement': 'sangam-core-sanction'}, ext=[xlistener('start', 'org.sangam.packs.core.MoneyFieldsDelegate', {'payeeKeys': 'customerId', 'purpose': 'Loan disbursal to the borrower account'}), '<operaton:in businessKey="#{execution.processBusinessKey}"/>', '<operaton:in variables="all"/>', '<operaton:out variables="all"/>'], body=[]))


@port('sangam-sec-scm-p2p')
def port_sangam_sec_scm_p2p(p):
    form(p, 'sec-scm-procure-to-pay/requisition', 'Purchase requisition | क्रय माँग-पत्र', ['payeePartyId'], labels={'payeePartyId': ('Supplier (verified payee registry id; required for a direct purchase)', 'आपूर्तिकर्ता (सत्यापित पंजी आईडी)', 'string')}, at='supplierName')
    form(p, 'sec-scm-procure-to-pay/rfq', 'Collect quotations and comparative statement | कोटेशन एवं तुलनात्मक विवरण', ['payeePartyId*'], labels={'payeePartyId': ('Awarded supplier (verified payee registry id)', 'चयनित आपूर्तिकर्ता (सत्यापित पंजी आईडी)', 'string')}, at='supplierName')
    put(p, None, dict(tag='serviceTask', id='pp_payable', name='Payable: accepted quantity at the awarded price (decision 139 policy)', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.MoneyCalculationDelegate'}, ext=[fields_xml({'result': 'payableAmount', 'formula': 'grnQty * unitPrice', 'policy': 'sec-scm-p2p-amount-rounding'})], body=[]))
    put(p, None, dict(tag='callActivity', id='pp_paySanction', name='Approve release of this payment (core sanction, owner decision A1)', kind='task', attrs={'calledElement': 'sangam-core-sanction'}, ext=[xlistener('start', 'org.sangam.packs.core.MoneyFieldsDelegate', {'amount': 'payableAmount', 'payeeKeys': 'payeePartyId', 'purpose': 'Payment to the supplier for the accepted goods'}), '<operaton:in businessKey="#{execution.processBusinessKey}"/>', '<operaton:in variables="all"/>', '<operaton:in sourceExpression="${true}" target="moneyOutRelease"/>', '<operaton:in sourceExpression="${\'ESCALATE\'}" target="sanctionOnBreach"/>', '<operaton:out variables="all"/>'], body=[]))
    put(p, None, dict(tag='exclusiveGateway', id='pp_paySgw', name='Sanctioned?', kind='gateway', attrs={}, ext=[], body=[]))
    defaults(p, 'pp_paySgw', 'pp_f9002')
    put(p, None, dict(tag='endEvent', id='pp_payNotReleased', name='Not released', kind='event', attrs={}, ext=[], body=[]))
    putf(p, None, 'pp_f9002', 'pp_paySgw', 'pp_payNotReleased', name='not sanctioned', default=True)
    putf(p, None, 'pp_f9001', 'pp_paySgw', 'pp_pay', c="${sanctionOutcome == 'SANCTIONED'}", name='sanctioned')
    putf(p, None, 'pp_f9000', 'pp_paySanction', 'pp_paySgw')
    putf(p, None, 'pp_f9003', 'pp_payable', 'pp_release_slaPlan')
    putf(p, None, 'sec_scm_p2p_f43', 'pp_payCfg', 'pp_payable')
    putf(p, None, 'sec_scm_p2p_f44', 'pp_release_done', 'pp_paySanction')


@port('sangam-sec-util-connection')
def port_sangam_sec_util_connection(p):
    form(p, 'sec-util-new-connection/apply', 'New connection application | नए कनेक्शन हेतु आवेदन', ['payeePartyId*'], labels={'payeePartyId': ('Payee party ID (verified party registry)', 'प्राप्तकर्ता पक्ष आईडी (सत्यापित पक्ष रजिस्टर)', 'string')})


@new
def new_tender_emd_refund(by_pack, by_pid):
    p = by_pack['gov.tender'].proc('sangam-tender-emd-refund', 'EMD refund to one bidder', start={'materialFields': 'key,tenderId,bidderId,emdCaptureRef,amount,payeeAccount,currency,purpose'})
    put(p, None, dict(tag='startEvent', id='te_start', name='Bidder', kind='event', attrs={}, ext=[], body=[]))
    put(p, None, dict(tag='serviceTask', id='te_refund', name='Refund the EMD to its source (released against the EMD capture, owner decision M5)', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.ConnectorCallDelegate', 'operaton:asyncBefore': 'true'}, ext=[xlistener('start', 'org.sangam.packs.core.CaptureRefunds$Release', {'captureVar': 'emdCaptureRef', 'purpose': "EMD refund to the bidder's source"}), fields_xml({'connector': 'emd.refund', 'operation': 'refund', 'mode': 'submit', 'inputs': 'tenderId,bidderId,amount,payeeAccount', 'resultPrefix': 'emdRefund', 'step': 'emd-refund'})], body=[]))
    put(p, None, dict(tag='endEvent', id='te_end', name='Refunded', kind='event', attrs={}, ext=[xlistener('end', 'org.sangam.packs.core.BatchItemOutcomeListener', {'outcome': 'SUCCESS'})], body=[]))
    putf(p, None, 'te_f1', 'te_start', 'te_refund')
    putf(p, None, 'te_f2', 'te_refund', 'te_end')


@port('sangam-tender')
def port_sangam_tender(p):
    putf(p, None, 'tender_f23', 'td_bidsGw', 'td_emdRegister', c="${eprocStatus == 'COMPLETED' && eprocBidsCount != null && (eprocBidsCount > 0)}", name='bids received')
    putf(p, None, 'tender_f46', 'td_retenderCount', 'td_emdRoundGw')
    put(p, None, dict(tag='exclusiveGateway', id='td_emdRoundGw', name='EMDs held this round?', kind='gateway', attrs={}, ext=[], body=[]))
    defaults(p, 'td_emdRoundGw', 'td_emdRoundGw_none')
    putf(p, None, 'td_emdRoundGw_none', 'td_emdRoundGw', 'td_retenderGw', name='no bids', default=True)
    putf(p, None, 'td_emdRoundGw_yes', 'td_emdRoundGw', 'td_emdAllItems', c="${execution.getVariable('emdRegister') != null}", name='refund every EMD')
    put(p, None, dict(tag='serviceTask', id='td_emdAllItems', name='Refund items: every bidder of the round, to the EMD source', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.BatchItemsDelegate'}, ext=[fields_xml({'source': 'emdRegister', 'keyField': 'bidderId', 'amount': 'emdAmount', 'captureField': 'emdCaptureRef', 'itemsVar': 'emdRefundItems'})], body=[]))
    putf(p, None, 'td_emdAllItems_f', 'td_emdAllItems', 'td_emdAllRound')
    put(p, None, dict(tag='serviceTask', id='td_emdAllRound', name='Refund round number', kind='task', attrs={'operaton:expression': "${execution.setVariable('batchRound', retenderCount)}"}, ext=[], body=[]))
    putf(p, None, 'td_emdAllRound_f', 'td_emdAllRound', 'td_emdAllOpen')
    put(p, None, dict(tag='serviceTask', id='td_emdAllOpen', name='Open the EMD refund round', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.BatchDelegate'}, ext=[fields_xml({'op': 'OPEN', 'itemsVar': 'emdRefundItems'})], body=[]))
    putf(p, None, 'td_emdAllOpen_f', 'td_emdAllOpen', 'td_emdAllFanOut')
    put(p, None, dict(tag='serviceTask', id='td_emdAllFanOut', name='Start one EMD refund per bidder (chunk)', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.BatchDelegate', 'operaton:asyncBefore': 'true'}, ext=[fields_xml({'op': 'FAN_OUT', 'itemsVar': 'emdRefundItems', 'childProcessKey': 'sangam-tender-emd-refund', 'chunkSize': '200', 'inherit': 'tenderId'})], body=[]))
    putf(p, None, 'td_emdAllFanOut_f', 'td_emdAllFanOut', 'td_emdAllGw')
    put(p, None, dict(tag='exclusiveGateway', id='td_emdAllGw', name='All started?', kind='gateway', attrs={}, ext=[], body=[]))
    defaults(p, 'td_emdAllGw', 'td_emdAllGw_all')
    putf(p, None, 'td_emdAllGw_next', 'td_emdAllGw', 'td_emdAllFanOut', c='${!fanOutDone}', name='next chunk')
    putf(p, None, 'td_emdAllGw_all', 'td_emdAllGw', 'td_emdAllDone', name='all started', default=True)
    put(p, None, dict(tag='serviceTask', id='td_emdAllDone', name='Round refunded', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.SetVariablesDelegate'}, ext=[fields_xml({'values': 'emdRegister=null'})], body=[]))
    putf(p, None, 'td_emdAllDone_f', 'td_emdAllDone', 'td_retenderGw')
    putf(p, None, 'tender_f49', 'td_retenderGw', 'td_failed', name='give up', default=True)
    put(p, None, dict(tag='serviceTask', id='td_emdUnsuccessful', name='Refund items: unsuccessful bidders, to the EMD source (owner decision M5)', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.BatchItemsDelegate'}, ext=[fields_xml({'source': 'emdRegister', 'keyField': 'bidderId', 'amount': 'emdAmount', 'captureField': 'emdCaptureRef', 'excludeKeyVar': 'contractorId', 'itemsVar': 'emdRefundItems'})], body=[]))
    putf(p, None, 'td_emdUnsuccessful_f', 'td_emdUnsuccessful', 'td_emdUnsRound')
    put(p, None, dict(tag='serviceTask', id='td_emdUnsRound', name='Refund round number', kind='task', attrs={'operaton:expression': "${execution.setVariable('batchRound', retenderCount + 1)}"}, ext=[], body=[]))
    putf(p, None, 'td_emdUnsRound_f', 'td_emdUnsRound', 'td_emdUnsOpen')
    put(p, None, dict(tag='serviceTask', id='td_emdUnsOpen', name='Open the EMD refund round', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.BatchDelegate'}, ext=[fields_xml({'op': 'OPEN', 'itemsVar': 'emdRefundItems'})], body=[]))
    putf(p, None, 'td_emdUnsOpen_f', 'td_emdUnsOpen', 'td_emdUnsFanOut')
    put(p, None, dict(tag='serviceTask', id='td_emdUnsFanOut', name='Start one EMD refund per bidder (chunk)', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.BatchDelegate', 'operaton:asyncBefore': 'true'}, ext=[fields_xml({'op': 'FAN_OUT', 'itemsVar': 'emdRefundItems', 'childProcessKey': 'sangam-tender-emd-refund', 'chunkSize': '200', 'inherit': 'tenderId'})], body=[]))
    putf(p, None, 'td_emdUnsFanOut_f', 'td_emdUnsFanOut', 'td_emdUnsGw')
    put(p, None, dict(tag='exclusiveGateway', id='td_emdUnsGw', name='All started?', kind='gateway', attrs={}, ext=[], body=[]))
    defaults(p, 'td_emdUnsGw', 'td_emdUnsGw_all')
    putf(p, None, 'td_emdUnsGw_next', 'td_emdUnsGw', 'td_emdUnsFanOut', c='${!fanOutDone}', name='next chunk')
    putf(p, None, 'td_emdUnsGw_all', 'td_emdUnsGw', 'td_awarded', name='all started', default=True)
    put(p, None, dict(tag='serviceTask', id='td_emdRegister', name='Bidders and their EMD captures (e-procurement register)', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.ConnectorCallDelegate', 'operaton:asyncBefore': 'true'}, ext=[fields_xml({'connector': 'eproc.emdRegister', 'operation': 'register', 'mode': 'query', 'inputs': 'tenderId', 'resultPrefix': 'emd', 'documents': 'register'})], body=[]))
    putf(p, None, 'td_emdRegister_f', 'td_emdRegister', 'td_emdItems')
    put(p, None, dict(tag='serviceTask', id='td_emdItems', name='Record each EMD capture (owner decision M5; no register: held)', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.BatchItemsDelegate'}, ext=[fields_xml({'source': 'emdRegister', 'keyField': 'bidderId', 'amount': 'emdAmount', 'captureField': 'emdCaptureRef', 'recordCapture': 'true', 'itemsVar': 'emdItems'})], body=[]))
    putf(p, None, 'td_emdItems_f', 'td_emdItems', 'td_openingLock')
    drop(p, 'tender_a21')
    drop(p, 'tender_f50')
    drop(p, 'td_bidsComp')
    drop(p, 'td_refundEmd')
    drop(p, 'tender_f56')
    drop(p, 'td_compensate')


@port('sangam-up02-saga')
def port_sangam_up02_saga(p):
    put(p, None, dict(tag='callActivity', id='u2_charge', name='Charge (core execution)', kind='task', attrs={'calledElement': 'sangam-core-execution'}, ext=[xlistener('end', 'org.sangam.packs.core.CaptureRefunds$Record', {'snapshotVar': 'chargeCaptureRef'}), '<operaton:in businessKey="#{execution.processBusinessKey}"/>', '<operaton:in variables="all"/>', '<operaton:out variables="all"/>'], body=[]))
    put(p, None, dict(tag='serviceTask', id='u2_charge_undo', name='Undo: refund', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.ConnectorCallDelegate', 'operaton:asyncBefore': 'true', 'isForCompensation': 'true'}, ext=[xlistener('start', 'org.sangam.packs.core.CaptureRefunds$Release', {'captureVar': 'chargeCaptureRef', 'purpose': 'Saga compensation: refund of the charge to its source'}), fields_xml({'connector': 'payment.refund', 'operation': 'refund', 'mode': 'submit', 'inputs': 'orderId,amount', 'resultPrefix': 'u2_chargeUndo', 'step': 'refund-compensation'})], body=[]))


@port('sangam-up13-returns')
def port_sangam_up13_returns(p):
    form(p, 'up13-returns-refunds/request', 'Return request | वापसी अनुरोध', ['originalPaymentRef*'], labels={'originalPaymentRef': ('Original payment reference (capture)', 'मूल भुगतान संदर्भ (प्राप्ति)', 'string')})


@port('sangam-up14-three-way-match')
def port_sangam_up14_three_way_match(p):
    form(p, 'up14-three-way-match/invoice', 'Supplier invoice | आपूर्तिकर्ता बीजक', ['payeePartyId*'], labels={'payeePartyId': ('Supplier (verified payee registry id)', 'आपूर्तिकर्ता (सत्यापित पंजी आईडी)', 'string')})
    form(p, 'up14-three-way-match/payable-amount', 'Payable amount | देय राशि', ['payableAmountEntered*', 'remarks'], labels={'payableAmountEntered': ('Payable (INR, whole paise next to the exact value)', 'देय (रुपये, सटीक मान के निकट पूर्ण पैसे)', 'money')})
    put(p, None, dict(tag='serviceTask', id='u14_payable', name='Payable: invoiced quantity at the invoice price, exact (owner rule C4)', kind='task', attrs={'operaton:class': 'org.sangam.packs.core.MoneyCalculationDelegate'}, ext=[fields_xml({'result': 'payableAmount', 'op': 'EXACT', 'formula': 'invoiceQty * invoicePrice', 'min': '0'})], body=[]))
    putf(p, None, 'up14_f9003', 'u14_payable', 'u14_payable_heldGw')
    put(p, None, dict(tag='exclusiveGateway', id='u14_payable_heldGw', name='Exact in minor units?', kind='gateway', attrs={}, ext=[], body=[]))
    defaults(p, 'u14_payable_heldGw', 'u14_payable_heldGw_exact')
    putf(p, None, 'u14_payable_heldGw_exact', 'u14_payable_heldGw', 'u14_post_sanction', name='exact', default=True)
    putf(p, None, 'u14_payable_heldGw_held', 'u14_payable_heldGw', 'u14_payable_enter', c='${payableAmountHeld == true}', name='held: reviewer enters the amount')
    put(p, None, dict(tag='userTask', id='u14_payable_enter', name='Enter the payable payableAmount (exact value held, owner rule C4)', kind='task', attrs={'operaton:candidateGroups': '${apSeat}', 'operaton:formKey': 'sangam:up14-three-way-match/payable-amount'}, ext=[tlistener('complete', 'org.sangam.packs.core.ActorRecorderListener')], body=[]))
    putf(p, None, 'u14_payable_enter_back', 'u14_payable_enter', 'u14_payable')
    putf(p, None, 'up14_three_way_match_f4', 'u14_gw', 'u14_payable', c="${matchResult != 'EXCEPTION'}", name='match / within tolerance')
    putf(p, None, 'up14_three_way_match_f7', 'u14_eg', 'u14_payable', c="${exceptionDecision == 'approve'}", name='approve')


# ---- pack and process data of the lane (not elements) ------------------------------------------------------------

# owner decision E3: the money-out processes bind the payee account, currency and purpose to the sanctioned payload
MATERIAL = {
    'sangam-dbt-beneficiary-payment': 'key,bankAccount,ifsc,amount,payeeAccount,currency,purpose',
    'sangam-ifms-receipt': 'challanNo,receiptHead,amount,payeeAccount,currency,purpose',
    'sangam-p1-bill-payment': 'payee,account,ifsc,amount,budgetHead,sanctionRef,payeeAccount,currency,purpose',
    'sangam-p3-grant-application': 'beneficiaryId,bankAccount,ifsc,amount,schemeId,payeeAccount,currency,purpose',
    'sangam-sec-bank-emandate-debit': 'customerId,amount,payeeAccount,currency,purpose',
}

# money variables a pack computes or enters outside a form field named as money (pack.json money.variables)
MONEY_VARS = {
    'sec.ecom.order-to-cash': ['commissionAmount', 'payableAmount'],
    'sec.edu.admission': ['feeAmount', 'refundAmount'],
    'sec.ins.proposal-underwriting': ['medicalCostDeduction', 'refundAmount', 'riskPremiumDeduction',
                                      'stampDutyDeduction'],
    'sec.scm.procure-to-pay': ['payableAmount'],
    'universal.up14-three-way-match': ['payableAmount'],
}

# owner decision M1: why a pack declares its currency (pack.json money.currencyReason)
CURRENCY_REASON = {
    'sec.bank.inoperative-deposits': "owner decision M1: the pack applies RBI's Depositor Education and Awareness "
                                     "(DEA) Fund scheme, which is in rupees, so the pack declares INR",
    'sec.edu.admission': 'owner decision M1: the pack applies the UGC refund schedule and DigiLocker / APAAR, Indian '
                         'systems in rupees, so the pack declares INR',
    'sec.edu.examination': 'owner decision M1: the pack publishes to DigiLocker / NAD with ABC credits, Indian systems '
                           'in rupees, so the pack declares INR',
}

# item 11: the e-procurement register of bidders and their EMD captures (after eproc.evaluate)
CONNECTORS = {
    'gov.tender': [('eproc.emdRegister', 'a', '', 'Bidders and their EMD captures', 'eproc.evaluate')],
}


def apply_data(by_pid, by_pack):
    for pid, mf in MATERIAL.items():
        by_pid[pid].start['materialFields'] = mf
    for pk, names in MONEY_VARS.items():
        by_pack[pk].money_vars.update(names)
    for pk, reason in CURRENCY_REASON.items():
        by_pack[pk].currency_reason = reason
    for pk, rows in CONNECTORS.items():
        conns = by_pack[pk].connectors
        for cid, cls, tag, purpose, after in rows:
            i = next(k for k, c in enumerate(conns) if c[0] == after)
            conns.insert(i + 1, (cid, cls, tag, purpose))