# SPDX-License-Identifier: Apache-2.0
"""Lane ARCH-JURIS (owner decisions A1, E1, M1-M5): the money-out pass over the generated processes.

1. sanction_pass: before each money-out step of SANCTIONS, the core sanction (a recorded human / delegated-power
   approval that binds the payload hash, MoneyFieldsDelegate naming the amount, payee and purpose) and a gateway that
   releases the money only on SANCTIONED; every flow that reached the step now reaches the sanction. The table was
   taken from the committed BPMNs of the lane (one row per <node>_sanction).
2. money_out_ports: the pack-specific money flows of the lane (payee pre-verification, batch seals and per-item
   release, exact-amount entry loops, refunds against their capture, the zero-TCS month, the premium entry), written
   with the helpers below over the built processes.

Runs after every pack module has built its processes and before packs.write_all (main.generate), so the manifests,
forms and power declarations are derived from the final processes."""
import re

import packs
from lib import C, SPAWN_FIELDS, Proc, Scope, fields_xml, tlistener, xlistener  # noqa: F401 (used by the ports)

MONEY_FIELDS = 'org.sangam.packs.core.MoneyFieldsDelegate'
# event definitions the ports refer to (id -> name), set by money_out_ports
TOP_NAMES = {}
A1_NAME = 'Approve release of money (core sanction, owner decision A1)'

# process -> [(money-out node, MoneyFieldsDelegate fields[, name=..., pre=[listeners before it]])]
SANCTIONS = {
    'sangam-ifms-receipt': [
        ('rc_refund', {'amount': 'refundAmount', 'captureVar': 'challanNo',
                       'purpose': 'Refund of a challan receipt to its depositor'}),
    ],
    'sangam-sec-bank-emandate-debit': [
        ('ed_debit', {'amount': 'debitAmount', 'cap': 'mandateMax', 'payeeKeys': 'payeePartyId',
                      'purpose': 'Mandate debit to the merchant'}),
    ],
    'sangam-sec-bank-dea-claim': [
        ('dc_credit', {'amount': 'payableAmount', 'payeeKeys': 'payeePartyId',
                       'purpose': 'Repayment of an unclaimed deposit with interest'}),
    ],
    'sangam-sec-bank-loan-closure': [
        ('lc_pay', {'amount': 'compensationAmount', 'payeeKeys': 'customerId',
                    'purpose': 'Compensation for delayed release of security documents'}),
    ],
    'sangam-sec-bank-payment': [
        ('py_retCredit', {'payeeKeys': 'remitterPartyId', 'purpose': 'Credit back a returned transfer to the remitter'},
         dict(pre=[xlistener('start', C['SET'], {'values': 'caseType=PAYMENT'})])),
    ],
    'sangam-sec-bank-txn-dispute': [
        ('dp_compensate', {'amount': 'compensationAmount', 'payeeKeys': 'customerId',
                           'purpose': 'Compensation for a turnaround-time breach'}),
        ('dp_reversal', {'captureVar': 'txnRef', 'purpose': 'Reversal of a failed debit to its source'}),
    ],
    'sangam-sec-cap-dividend': [
        ('cd_unpaid', {'amount': 'unpaidAmount', 'payeeKeys': 'unpaidDividendPartyId',
                       'purpose': 'Unpaid dividend to the company unpaid dividend account'}),
    ],
    'sangam-sec-ecom-gst-tcs': [
        ('gt_deposit', {'amount': 'tcsAmount', 'statutoryParty': 'GSTN',
                        'purpose': 'Monthly deposit of GST collected at source'}),
    ],
    'sangam-sec-ecom-order': [
        ('eo_cxRefund', {'captureVar': 'payRef', 'purpose': 'Refund in full on cancellation, to the source'}),
        ('eo_refund', {'captureVar': 'payRef', 'purpose': 'Refund of the order to its source'}),
        ('eo_settle', {'amount': 'payableAmount', 'payeeKeys': 'sellerId',
                       'purpose': 'Settlement to the seller net of the marketplace commission'}),
    ],
    'sangam-sec-ecom-return': [
        ('er_refund', {'amount': 'refundAmount', 'captureVar': 'originalPaymentRef',
                       'purpose': 'Return refund to the original payment'}),
    ],
    'sangam-sec-edu-admission': [
        ('ed_wdRefund', {'amount': 'refundAmount', 'cap': 'feeAmount', 'captureVar': 'feeCaptureRef',
                         'purpose': 'Withdrawal refund of the admission fee to its source'}),
    ],
    'sangam-sec-hr-exit': [
        ('hx_gratPay', {'amount': 'gratuityAmount', 'payeeKeys': 'employeeId', 'purpose': 'Gratuity'}),
        ('hx_pay', {'amount': 'fnfAmount', 'payeeKeys': 'employeeId', 'purpose': 'Full and final settlement'}),
    ],
    'sangam-sec-ins-health-claim': [
        ('hc_settle', {'amount': 'approvedAmount', 'payeeKeys': 'payeePartyId',
                       'purpose': 'Health claim settlement (hospital on cashless, insured on reimbursement)'}),
    ],
    'sangam-sec-ins-proposal': [
        ('ip_refund', {'amount': 'refundAmount', 'cap': 'premiumAmount', 'captureVar': 'premiumCaptureRef',
                       'purpose': 'Free-look refund of the premium to its source'}),
    ],
    'sangam-sec-util-connection': [
        ('uc_comp', {'amount': 'compensationAmount', 'payeeKeys': 'payeePartyId',
                     'purpose': 'Delay compensation credited to the consumer'}),
    ],
    'sangam-up13-returns': [
        ('u13_refund', {'amount': 'refundDue', 'captureVar': 'originalPaymentRef',
                        'purpose': 'Return refund to the original payment'}),
    ],
    'sangam-up14-three-way-match': [
        ('u14_post', {'amount': 'payableAmount', 'payeeKeys': 'payeePartyId',
                      'purpose': 'Supplier invoice payable after the three-way match'}),
    ],
}


# ---- lookup ------------------------------------------------------------------------------------------------------

def scopes(s):
    yield s
    for sub in s.subs:
        yield from scopes(sub['scope'])


def scope_of(p, parent):
    """The scope whose element id is parent (None: the process itself)."""
    for s in scopes(p.root):
        if (s.el or {}).get('id') == parent or (parent in (None, p.pid) and s.el is None):
            return s
    raise ValueError(f'{p.pid}: no scope {parent}')


def find(p, id):
    """(scope, kind, item) of element id: kind node / boundary / flow / sub; None when absent."""
    for s in scopes(p.root):
        for kind, items in (('node', s.nodes), ('boundary', s.boundaries), ('flow', s.flows), ('sub', s.subs)):
            for it in items:
                if it['id'] == id:
                    return s, kind, it
        for it in s.assocs:  # (id, source, target)
            if it[0] == id:
                return s, 'assoc', it
    return None


# ---- the sanction pass (1) ---------------------------------------------------------------------------------------

def sanction(p, node, money_fields, name=A1_NAME, pre=()):
    """Core sanction before money-out node `node` (owner decision A1): <node>_sanction, <node>_sgw, <node>_notReleased;
    the incoming flows of the node move to the sanction; the money moves only on sanctionOutcome == 'SANCTIONED'."""
    hit = find(p, node)
    if hit is None or hit[1] != 'node':
        raise SystemExit(f'money_out: {p.pid} has no money-out node {node}')
    s = hit[0]
    sid, gid, nid = node + '_sanction', node + '_sgw', node + '_notReleased'
    for f in s.flows:
        if f['tgt'] == node:
            f['tgt'] = sid
    call = s.call(sid, name, 'sangam-core-sanction',
                  ins={"${true}": 'moneyOutRelease', "${'ESCALATE'}": 'sanctionOnBreach'})
    s.node(call)['ext'][:0] = list(pre) + [xlistener('start', MONEY_FIELDS, money_fields)]
    s.xgw(gid, 'Sanctioned?')
    s.end(nid, 'Not released')
    _flow(s, sid + '_f', sid, gid)
    _flow(s, gid + '_yes', gid, node, c="${sanctionOutcome == 'SANCTIONED'}", name='sanctioned')
    _flow(s, gid + '_no', gid, nid, name='not sanctioned', default=True)
    # the sanction elements sit before the node they guard (layout follows the list order)
    added = [n for n in s.nodes if n['id'] in (sid, gid, nid)]
    rest = [n for n in s.nodes if n['id'] not in (sid, gid, nid)]
    i = next(k for k, n in enumerate(rest) if n['id'] == node)
    s.nodes[:] = rest[:i] + added + rest[i:]


def sanction_pass(by_pid):
    for pid, rows in SANCTIONS.items():
        if pid not in by_pid:
            raise SystemExit(f'money_out: SANCTIONS names unknown process {pid}')
        for row in rows:
            node, mf = row[0], row[1]
            kw = row[2] if len(row) > 2 else {}
            sanction(by_pid[pid], node, mf, **kw)


# ---- helpers for the pack-specific ports (2) -------------------------------------------------------------------

def _flow(s, id, src, tgt, c=None, name=None, default=False, priority=None):
    if id in s.proc.ids:
        raise ValueError(f'duplicate id {id} in {s.proc.pid}')
    s.proc.ids.add(id)
    s.flows.append(dict(id=id, src=src, tgt=tgt, cond=c, name=name, default=default, priority=priority))
    return id


_REF = re.compile(r'(message|error|escalation|signal)Ref="([^"]+)"')


def _register(p, el, top_names):
    """Keep the process's derived sets (calls, spawns, decisions, connectors, event definitions) true for el."""
    attrs = el.get('attrs', {})
    if el['tag'] == 'callActivity':
        p.calls.add(attrs['calledElement'])
    ext = ''.join(x for x in el.get('ext', []) if x)
    flds = dict(re.findall(r'<operaton:field name="(\w+)"><operaton:(?:string|expression)>([^<]*)<', ext))
    cls = attrs.get('operaton:class')
    for k in SPAWN_FIELDS.get(cls, ()):
        if flds.get(k):
            p.spawns.add(flds[k])
    if cls == C['DEC'] and flds.get('decisionKey'):
        p.decisions_used.add(flds['decisionKey'])
    if cls == C['MONEY'] and flds.get('policy'):
        p.decisions_used.add(flds['policy'])
    if cls == C['CALL'] and flds.get('connector'):
        conn = flds['connector']
        p.connectors.add((conn, flds.get('mode', 'query')))
        p.conn_prefixes.setdefault(conn, set()).add(flds.get('resultPrefix') or conn[conn.rfind('.') + 1:])
    for kind, ref in _REF.findall(''.join(el.get('body', []))):
        name = top_names.get(ref)
        if name is None:
            raise ValueError(f'{p.pid}: {ref} has no definition')
        getattr(p, {'message': 'messages', 'error': 'errors', 'escalation': 'escalations', 'signal': 'signals'}[kind])[
            ref] = name


def put(p, parent, el, top_names=None):
    """Add element el to scope `parent`, or replace the element of that id in place (same scope)."""
    el.setdefault('attrs', {})
    el.setdefault('ext', [])
    el.setdefault('body', [])
    hit = find(p, el['id'])
    if hit:
        s, kind, old = hit
        if kind != 'node':
            raise ValueError(f'{p.pid}: {el["id"]} is a {kind}')
        s.nodes[s.nodes.index(old)] = el
    else:
        s = scope_of(p, parent)
        p.ids.add(el['id'])
        s.nodes.append(el)
    _register(p, el, top_names or TOP_NAMES)
    if el['tag'] == 'userTask' and 'operaton:formKey' in el['attrs']:
        key = el['attrs']['operaton:formKey'].split(':', 1)[1]
        if not any(f['key'] == key for f in p.forms):
            raise ValueError(f'{p.pid}: form {key} of {el["id"]} is not declared (form() first)')
    return el['id']


def putb(p, parent, el, top_names=None):
    hit = find(p, el['id'])
    if hit:
        s, kind, old = hit
        s.boundaries[s.boundaries.index(old)] = el
    else:
        p.ids.add(el['id'])
        scope_of(p, parent).boundaries.append(el)
    _register(p, el, top_names or TOP_NAMES)


def putf(p, parent, id, src, tgt, c=None, name=None, default=False, priority=None):
    hit = find(p, id)
    if hit:
        s, kind, f = hit
        f.update(src=src, tgt=tgt, cond=c, name=name, default=default, priority=priority)
    else:
        _flow(scope_of(p, parent), id, src, tgt, c, name, default, priority)


def drop(p, id):
    hit = find(p, id)
    if hit is None:
        raise SystemExit(f'money_out: {p.pid} has no element {id} to drop')
    s, kind, it = hit
    {'node': s.nodes, 'boundary': s.boundaries, 'flow': s.flows, 'sub': s.subs, 'assoc': s.assocs}[kind].remove(it)
    p.ids.discard(id)


def defaults(p, gw, flow):
    """The default flow of gateway gw is `flow` (only that one)."""
    s = find(p, gw)[0]
    for f in s.flows:
        if f['src'] == gw:
            f['default'] = f['id'] == flow


def form(p, key, name, fields, decision=None, labels=None, doc=None, at=None):
    """Declare (or extend) form `key` of process p: fields as in Scope.user ('id*' = required); labels: field id ->
    (English, Hindi, type) for a label of this form only (packs.form_json); at: insert the fields after that field."""
    f = next((f for f in p.forms if f['key'] == key), None)
    if f is None:
        p.forms.append(dict(key=key, name=name, decision=decision, fields=list(fields), doc=doc,
                            labels=dict(labels or {})))
        return
    if at is None:
        f['fields'].extend(fields)
    elif at == '':
        f['fields'][0:0] = list(fields)
    else:
        i = next(k for k, x in enumerate(f['fields']) if x.rstrip('*') == at)
        f['fields'][i + 1:i + 1] = list(fields)
    f.setdefault('labels', {}).update(labels or {})


def apply(all_packs):
    import money_out_ports
    by_pid = {p.pid: p for pk in all_packs for p in pk.procs}
    sanction_pass(by_pid)
    money_out_ports.apply(by_pid, {pk.id: pk for pk in all_packs})
