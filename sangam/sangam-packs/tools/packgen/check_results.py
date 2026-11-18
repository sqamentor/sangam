# SPDX-License-Identifier: Apache-2.0
"""SL-115 guard: no pack default pre-fills the result of a check.

ConnectorCallDelegate always writes <prefix>Status but writes a result variable (<prefix><Key>) only when the answer
carries it. A `defaults` assignment (SetVariablesDelegate fills only an empty variable) of such a variable therefore
stands in for the check whenever the check fails, is never answered or answers without that key: the case goes on as if
the check had passed (SL-113: fundsAvailable=999999999999 in the IFMS packs). Checks are written with
common.checked_query / check_conditions instead (clear before the call, explicit fail-closed "not confirmed" branch).

The guard runs over every generated BPMN (packs.write_all): a non-null `defaults` assignment of a variable named
<prefix><Upper...>, where <prefix> is the result prefix of a connector task of the same process or of a process it calls
(variables="all" carries the default in), stops the generation unless the (process, variable) pair is listed below with a
reason. The lists are written to META-INF/sangam/check-results.json; CheckResultGuardTest asserts the same property over
the shipped BPMN.
"""
import re

# Reviewed: the variable shares a connector's result prefix by name only. It is a connector input, configuration or a
# value that no gateway trusts as a check outcome. (process id, variable) -> reason.
ALLOWED = {
    ('sangam-asset-lifecycle', 'auctionWindow'): 'configuration (auction window), not an eauction.list answer',
    ('sangam-budget-reappropriation', 'budgetHead'): 'the source head copied from fromHead (configuration); the '
                                                     'ledger.available answer is budgetAvailable',
    ('sangam-budget-reappropriation', 'referralQuestion'): 'input of the called core referral (referral.send), not '
                                                           'its answer',
    ('sangam-ifms-reappropriation', 'referralQuestion'): 'input of the called core referral (referral.send), not its '
                                                         'answer',
    ('sangam-ifms-supplementary', 'referralQuestion'): 'input of the called core referral (referral.send), not its '
                                                       'answer',
    ('sangam-consultancy', 'eoiWindow'): 'configuration (EOI window), not a cppp.publish answer',
    ('sangam-consultancy', 'rfpWindow'): 'configuration (RFP window), not a cppp.publish answer',
    ('sangam-core-referral', 'referralSla'): 'configuration (reply SLA), not a referral.send answer',
    ('sangam-ifms-bill', 'payeeAccount'): 'input of registry.party payee.resolve (entered on the bill form)',
    ('sangam-ifms-bill', 'docPaymentRail'): 'catalogue attribute of the bill type used only to choose the rail '
                                            '(ifms-rail-connector DMN maps DEFAULT); not a validation outcome',
    ('sangam-ifms-pao-chain', 'tokenSeries'): 'input of config.series series.next (series name)',
    ('sangam-ifms-pao-chain', 'tokenNo'): 'empty placeholder of the token material field; the series.next effect '
                                          'answer, not a check outcome that a gateway trusts',
    ('sangam-sec-bank-aml-alert', 'strSla'): 'configuration (STR filing SLA), not an fiu.finnet answer',
    ('sangam-sec-bank-collections', 'crilcThreshold'): 'configuration (CRILC exposure threshold)',
    ('sangam-sec-bank-collections', 'loanAccountNo'): 'input of lms.status (copied from customerId)',
    ('sangam-sec-bank-emandate', 'mandateType'): 'the mandate type the customer chose (input of npci.mandate)',
    ('sangam-sec-bank-loan-origination', 'kfsValidity'): 'configuration / credit-policy DMN output, not a docs.kfs '
                                                         'answer',
    ('sangam-sec-ecom-seller-onboarding', 'sellerName'): 'input of marketplace.createSeller (copied from supplierName)',
    ('sangam-sec-health-abdm-consent', 'hiTypes'): 'input of abdm.hiRequest (requested record types)',
    ('sangam-sec-health-abdm-consent', 'consentValidity'): 'input of abdm.consentRequest (requested validity)',
    ('sangam-sec-health-ot', 'otSeat'): 'configuration (OT seat), not an ot.book answer',
    ('sangam-sec-health-pharmacy', 'prescriberRegNo'): 'input of nmr.verify (the registration number to verify)',
    ('sangam-sec-ins-intermediary', 'examPassed'): 'set by the sangam.exam.result message, not by exam.book; the '
                                                   'default false sends a result without it to a retake (fail-closed)',
    ('sangam-sec-scm-p2p', 'poQty'): 'input of the three-way match (copied from quantity), not an erp.createPo answer',
    ('sangam-sec-scm-p2p', 'poPrice'): 'input of the three-way match (copied from unitPrice), not an erp.createPo '
                                       'answer',
    ('sangam-sec-tel-sim-swap', 'swapReason'): 'the reason the subscriber gave (input of hlr.swap)',
    ('sangam-sec-ushealth-claim', 'eraTraceNumber'): 'input focus of x12.remit (the trace to decode, X12Connector '
                                                     'parseFocused) echoed back; not a pass / fail outcome',
    ('sangam-sec-ushealth-remittance', 'eraTraceNumber'): 'input focus of x12.remit (the trace to decode) echoed back; '
                                                          "'no-trace' is the sentinel the trace gateway sends to the "
                                                          'exception queue',
}

# Open questions to the integrator: accepted by the guard only until answered; the generator prints them on every run
# and CheckResultGuardTest lists them. Empty since the integrator's SL-115 decisions (Q-B6-1..5 fixed: an unconfirmed
# result goes to the accountable human step and never triggers an irreversible or adverse automatic action).
PENDING = {}

_PROC = re.compile(r'<process\b[^>]*\bid="([^"]+)"[^>]*>(.*?)</process>', re.S)
_TASK = re.compile(r'<serviceTask\b[^>]*\bid="([^"]+)"[^>]*>(.*?)</serviceTask>', re.S)
_FIELD = re.compile(r'<operaton:field name="(\w+)"><operaton:string>([^<]*)</operaton:string>')
_CALL = re.compile(r'calledElement="([^"]+)"')

_PROCS = {}  # process id -> (path, {result prefix: connector}, [(task, variable, value)], {called process ids})


def collect(path, xml):
    """Records the connector result prefixes, defaults and calls of every process of one generated BPMN."""
    for pid, body in _PROC.findall(xml):
        prefixes, defaults = {}, []
        for tid, inner in _TASK.findall(body):
            f = dict(_FIELD.findall(inner))
            if f.get('connector'):
                c = f['connector']
                prefixes[f.get('resultPrefix') or c[c.rfind('.') + 1:]] = c
            for pair in (f.get('defaults') or '').split(';'):
                if '=' in pair:
                    name, value = pair.split('=', 1)
                    defaults.append((tid, name.strip(), value.strip()))
        _PROCS[pid] = (path, prefixes, defaults, set(_CALL.findall(body)))


def _reach(pid):
    seen, todo = {pid}, [pid]
    while todo:
        for c in _PROCS.get(todo.pop(), (None, {}, [], set()))[3]:
            if c not in seen:
                seen.add(c)
                todo.append(c)
    return seen


def prefilled():
    """[(process, path, task, variable, value, prefix, connector)]: every non-null default of a connector result."""
    out = []
    for pid, (path, _, defaults, _) in sorted(_PROCS.items()):
        prefixes = {}
        for q in sorted(_reach(pid)):
            for pre, conn in _PROCS.get(q, (None, {}, [], set()))[1].items():
                prefixes.setdefault(pre, conn)
        for tid, name, value in defaults:
            if value == 'null':
                continue  # a null default fills nothing
            for pre, conn in prefixes.items():
                if name.startswith(pre) and len(name) > len(pre) and name[len(pre)].isupper():
                    out.append((pid, path, tid, name, value, pre, conn))
                    break
    return out


def check(problems):
    """Adds one problem per default that pre-fills a check result and is neither ALLOWED nor PENDING, and one per
    stale list entry; prints the PENDING questions. Returns the (process, variable) pairs found."""
    found = set()
    for pid, path, tid, name, value, pre, conn in prefilled():
        found.add((pid, name))
        if (pid, name) not in ALLOWED and (pid, name) not in PENDING:
            problems.add(f'SL-115 default {name}={value} pre-fills the result of {conn} (prefix {pre}) in {path} '
                         f'task {tid}: use common.checked_query / check_conditions, or list it in '
                         f'check_results.ALLOWED with a reason')
    for key in sorted(set(ALLOWED) | set(PENDING)):
        if key not in found:
            problems.add(f'SL-115 stale check_results entry {key}: no such default any more')
    for key, q in sorted(PENDING.items()):
        print(f'SL-115 pending {key[0]} {key[1]}: {q}')
    return found


def catalogue():
    """The reviewed lists as written to META-INF/sangam/check-results.json."""
    def rows(d, k):
        return [{'process': p, 'variable': v, k: t} for (p, v), t in sorted(d.items())]
    return {'allowed': rows(ALLOWED, 'reason'), 'pending': rows(PENDING, 'question')}
