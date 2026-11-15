# SPDX-License-Identifier: Apache-2.0
"""Rejection reason codes: the single source list (SL-55).

A rejection records at least one coded reason (CaseRecordDelegate REJECT, 422 REASONS_REQUIRED). Every code a pack
sets (a literal `rejectionReasonCodes=...` of a SetVariables step: reject_with, rejection(reasons=...), the automatic
outcome codes of common.AUTO_REJECTION_REASONS) must be listed here; packs.write_all refuses an unlisted one and writes
this list to META-INF/sangam/reason-codes.json, which ReasonCodeCatalogueTest checks against every generated pack.
"""

# code -> English label. Codes are stable identifiers: never rename one, add a new code instead.
CODES = {
    # set by the platform itself
    'DEEMED_REFUSAL': 'Deemed refusal: no decision within the statutory time (CaseRecordDelegate)',
    'EXECUTION_FAILED': 'The approved action could not be carried out: the external operation failed',
    'CLARIFICATION_LAPSED': 'The clarification sought was not received within the window',
    # finance (IFMS, budget)
    'ADVANCE_LIMIT': 'Advance above the admissible limit',
    'BILL_INVALID': 'Bill does not satisfy the rules of its bill type (rule cited)',
    'BUDGET_NOT_AVAILABLE': 'Budget not available under the head',
    'DUPLICATE_BILL': 'A bill for the same claim already exists',
    'DUPLICATE_CLAIM': 'Duplicate claim',
    'FA_NOT_CONCURRED': 'Financial Adviser did not concur',
    'FINANCE_NOT_CONCURRED': 'Finance did not concur',
    'FUNDS_NOT_CONFIRMED': 'Availability of funds could not be confirmed: the check failed or returned no amount',
    'GIA_RULE': 'Grant release not allowed by the grant-in-aid rules (rule cited)',
    'GPF_NOT_ELIGIBLE': 'Not eligible for the GPF advance or withdrawal',
    'MOF_NOT_APPROVED': 'Ministry of Finance / Budget Division did not approve',
    'NO_ACTIVE_MOTHER_SANCTION': 'No active (or an exhausted) mother sanction',
    'NO_BUDGET': 'No budget available',
    'NOT_INCLUDED_BY_BUDGET_DIVISION': 'Not included by the Budget Division in the supplementary batch',
    'PAYEE_EXCLUDED': 'Payee failed the integrity check (excluded, deceased or duplicate)',
    'PAYEE_NOT_CONFIRMED': 'Payee not confirmed',
    'REAPP_RULE': 'Re-appropriation not allowed by the rules (rule cited)',
    'SAVINGS_NOT_AVAILABLE': 'Savings not available in the source head',
    # entitlement, eligibility, verification
    'AGE_BELOW_60': 'Applicant below the qualifying age of 60',
    'DUPLICATE_BENEFICIARY': 'Beneficiary already receives the benefit',
    'ENTITLEMENT_NOT_ESTABLISHED': 'Entitlement not established',
    'FIELD_VERIFICATION_FAILED': 'Field verification failed',
    'INCOME_ABOVE_CEILING': 'Income above the ceiling',
    'INCOME_ABOVE_LIMIT': 'Income above the limit',
    'INSPECTION_UNSATISFACTORY': 'Inspection unsatisfactory',
    'INSTITUTE_VERIFICATION_FAILED': 'Not verified by the institute',
    'INSUFFICIENT_LEAVE_BALANCE': 'Insufficient leave balance',
    'MINOR_WITHOUT_GUARDIAN': 'Minor applicant without a guardian',
    'NOT_ELIGIBLE': 'Not eligible',
    'NOT_FEASIBLE': 'Not technically feasible',
    'PROCEEDINGS_LAPSED_S25': 'Proceedings lapsed (RFCTLARR Act 2013 s.25)',
    'SERVICE_NOT_VERIFIED': 'Service record not verified',
    'TENDER_NOT_AWARDED': 'Tender not awarded',
    # financial sector, insurance, telecom, health
    'CLAIM_FRAUD': 'Claim found fraudulent on investigation',
    'CLAIM_FRAUD_OR_NON_DISCLOSURE': 'Claim found fraudulent or with material non-disclosure on investigation',
    'COMMITTEE_DECLINED': 'Declined by the committee',
    'CREDIT_POLICY_DECLINE': 'Declined under the credit policy',
    'DLG_ABOVE_CAP_OR_FORM': 'Default loss guarantee above the cap or not in an allowed form',
    'KYC_FAILED': 'KYC failed',
    'KYC_NOT_COMPLIANT': 'KYC not compliant',
    'NOT_COVERED': 'Not covered by the policy',
    'PAN_INOPERATIVE': 'PAN inoperative',
    'POLICY_LAPSED': 'Policy lapsed',
    'POLICY_NOT_IN_FORCE': 'Policy not in force',
    'PRESCRIBER_NOT_VERIFIED': 'Prescriber not verified',
    'REVERIFICATION_FAILED': 'Re-verification failed',
    'SANCTIONS_MATCH': 'Match on a sanctions list',
    'VCIP_FAILED': 'Video customer identification failed',
    # SL-115: a check that did not complete, or completed without its result (no default stands in), per check family
    'BENEFICIARY_NAME_NOT_CONFIRMED': 'Beneficiary account / name validation could not be confirmed: the check failed '
                                      'or returned no result',
    'COVER_NOT_CONFIRMED': 'Policy cover could not be confirmed: the check failed or returned no result',
    'CREDIT_BUREAU_NOT_CONFIRMED': 'Credit bureau report could not be confirmed: the pull failed or returned no score',
    'DEPARTMENT_CLASS_NOT_CONFIRMED': 'Whether the department is scientific could not be confirmed from the bound '
                                      'configuration profile: the read failed or the profile has no value',
    'DUPLICATE_CHECK_NOT_CONFIRMED': 'The duplicate check could not be confirmed: it failed or returned no result',
    'GST_NOT_CONFIRMED': 'GSTIN verification could not be confirmed: the check failed or returned no result',
    'KYC_NOT_CONFIRMED': 'KYC status could not be confirmed: the check failed or returned no result',
    'PAN_NOT_CONFIRMED': 'PAN verification could not be confirmed: the check failed or returned no result',
    'PAYEE_CHECK_NOT_CONFIRMED': 'Payee integrity could not be confirmed: the check failed or returned no result',
    'PRESCRIBER_NOT_CONFIRMED': 'Prescriber registration could not be confirmed: the check failed or returned no result',
    'SCREENING_NOT_CONFIRMED': 'Sanctions / PEP screening could not be confirmed: the screen failed or returned no '
                               'result',
}

# Variables a pack copies into rejectionReasonCodes (`rejectionReasonCodes=@<variable>`), with where their values come
# from. 'decision' = a DMN decision of the same pack: its string outputs in this column are reason codes and must be
# listed above, except 'notReasons' (the value of a rule that does not reject). 'open' = values from outside this
# catalogue, named by 'vocabulary'.
DYNAMIC = {
    'eligibilityReasonCode': {'decision': 'p3-eligibility', 'notReasons': ['ELIGIBLE']},
    'exemptionSection': {'open': True, 'vocabulary': 'RTI Act 2005 s.8(1) / s.9 exemption clause chosen by the PIO'},
}


def catalogue():
    """The catalogue as written to META-INF/sangam/reason-codes.json."""
    return {
        'codes': [{'code': c, 'label': {'en': label}} for c, label in sorted(CODES.items())],
        'dynamic': [dict({'variable': v}, **src) for v, src in sorted(DYNAMIC.items())],
    }


def unknown(spec):
    """The codes of one `rejectionReasonCodes=` value (comma list, CaseRecordDelegate) that are not catalogued."""
    if spec.startswith('@'):
        return [] if spec[1:] in DYNAMIC else [spec]
    return [c.strip() for c in spec.split(',') if c.strip() and c.strip() not in CODES]
