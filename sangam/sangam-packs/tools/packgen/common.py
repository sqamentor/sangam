# SPDX-License-Identifier: Apache-2.0
"""Composition helpers shared by the domain packs."""
from lib import C
from money import M  # noqa: F401  (amounts inside FEEL expressions, minor units)


def core(s, id, name, block, async_=False):
    return s.call(id, name, 'sangam-core-' + block, async_=async_)


# SL-55: the outcomes of the core blocks that no person decides, with the coded reason a rejection on them records.
# A rejection needs at least one coded reason (CaseRecordDelegate REJECT, 422 REASONS_REQUIRED); on these paths nobody
# is asked for one, so without a code the refusal repeats on every retry (an inbox result message looped every 5 s).
AUTO_REJECTION_REASONS = {
    'executionOutcome': {'FAILED': ('EXECUTION_FAILED', 'rejectionRemarks=@opReasonCode')},
    'scrutinyOutcome': {'LAPSED': ('CLARIFICATION_LAPSED', None)},
}


def outcome(s, id, var, mapping, default, label=None):
    """Routes on a core block's outcome variable. When the fallback is a rejection (rejection() without reasons),
    every automatic outcome of AUTO_REJECTION_REASONS the pack does not map itself is routed there through a step
    that sets its coded reason."""
    g = s.xgw(id, label or (var + '?'))
    for value, target in mapping.items():
        s.flow(g, target, f"{var} == '{value}'", value)
    if default in getattr(s, 'uncoded_rejections', ()):
        for value, (code, extra) in AUTO_REJECTION_REASONS.get(var, {}).items():
            if value not in mapping:
                st = s.setv(f'{id}_{value.lower()}Reason', 'Reason: ' + code,
                            values='rejectionReasonCodes=' + code + (';' + extra if extra else ''))
                s.flow(g, st, f"{var} == '{value}'", value)
                s.flow(st, default)
    s.flow(g, default, None, 'otherwise', default=True)
    return g


def end_ok(p, s, id, name='Closed', outcome='SUCCESS', data=None):
    return s.end(id, name, listeners=[p.item_listener(outcome, data_vars=data)])


def rejection(p, s, prefix, reopen=None, reasons=None):
    """Rejection with reasons and the appeal window; APPEAL_ALLOWED loops to `reopen` if given. Returns entry id."""
    entry = None
    if reasons:
        entry = s.setv(prefix + '_reasons', 'Reason codes', defaults='rejectionReasonCodes=' + reasons)
    rj = core(s, prefix + '_reject', 'Rejection with reasons (core)', 'rejection')
    if entry:
        s.flow(entry, rj)
    else:
        entry = rj
        # an entry that needs the caller to have set a coded reason (outcome() codes its automatic outcomes)
        s.__dict__.setdefault('uncoded_rejections', set()).add(rj)
    e = s.end(prefix + '_endRejected', 'Rejected', listeners=[p.item_listener('REJECTED',
                                                                                   reason_var='rejectionReasonCodes')])
    if reopen:
        g = s.xgw(prefix + '_appealGw', 'Appeal allowed?')
        s.flow(rj, g)
        s.flow(g, reopen, "rejectionOutcome == 'APPEAL_ALLOWED'", 'appeal allowed: re-verify')
        s.flow(g, e, None, 'final', default=True)
    else:
        s.flow(rj, e)
    return entry


def reject_with(p, s, prefix, code, reopen=None):
    """Rejection entry that forces a coded reason (used for automatic rejections)."""
    st = s.setv(prefix + '_code', 'Reason: ' + code, values='rejectionReasonCodes=' + code)
    r = rejection(p, s, prefix, reopen)
    s.flow(st, r)
    return st


def availability_query(s, id, name, connector, operation, inputs, prefix='funds'):
    """SL-113: an availability (funds / savings / sanction balance) query whose result is the only source of
    <prefix>Available. A values step clears the variable before every query (also on a loop back), so neither a pack
    default, nor a start variable, nor an earlier answer can stand in for the check. Returns (entry, query)."""
    clear = s.setv(id + '_clear', 'Clear the previous availability result', values=prefix + 'Available=null')
    q = s.conn(id, name, connector, operation, 'query', inputs, prefix)
    s.flow(clear, q)
    return clear, q


def availability_conditions(prefix='funds', amount='amount'):
    """SL-113: the two gateway conditions after availability_query, mutually exclusive and fail-closed.
    unconfirmed: the check did not complete, or completed without an amount (no default ever passes);
    short: the check completed and the available amount is below the requested amount.
    Anything else (completed, amount present and sufficient) is the gateway's default flow."""
    status, avail = prefix + 'Status', prefix + 'Available'
    unconfirmed = f"{status} != 'COMPLETED' || {avail} == null"
    short = f"{status} == 'COMPLETED' && {avail} != null && {avail} < {amount}"
    return unconfirmed, short


def checked_query(s, id, name, connector, operation, inputs, prefix, results, mode='query', step=None):
    """SL-115: a check (query, or the answer of a submit) whose result variables come only from its connector answer.
    ConnectorCallDelegate always writes <prefix>Status but writes a result variable only when the answer carries it,
    so a values step clears every result variable before each call (loop-backs included): neither a pack default
    (check_results.py refuses one), nor a start variable, nor an earlier answer can stand in for the check.
    The status is never cleared (a submit counts its attempts from it). Returns (entry, call); loop-backs target entry."""
    clear = s.setv(id + '_clear', 'Clear the previous check result', values=';'.join(r + '=null' for r in results))
    q = s.conn(id, name, connector, operation, mode, inputs, prefix, step=step)
    s.flow(clear, q)
    return clear, q


def check_conditions(checks, adverse=None):
    """SL-115: gateway conditions after checked_query, mutually exclusive and fail-closed.
    checks = [(prefix, [result variables])]. unconfirmed: a call did not complete, or completed without one of its
    results (no default ever passes); confirmed_adverse: every call completed with its results and `adverse` holds.
    Everything else (all confirmed, not adverse) is the gateway's default flow. Returns (unconfirmed, confirmed_adverse),
    confirmed_adverse None without `adverse`."""
    unconf, conf = [], []
    for prefix, results in checks:
        unconf.append(f"{prefix}Status != 'COMPLETED'")
        conf.append(f"{prefix}Status == 'COMPLETED'")
        for r in results:
            unconf.append(f'{r} == null')
            conf.append(f'{r} != null')
    return ' || '.join(unconf), (' && '.join(conf) + f' && ({adverse})' if adverse else None)


def unconfirmed_hold(p, s, id, name, seat, back_to, fields=('remarks*',)):
    """SL-115 (integrator decision): an unconfirmed check result never triggers an irreversible or adverse automatic
    action. Where the process has no accountable human step for it, this task holds the case for one: the holder
    establishes the answer at its source, then the check runs again (back_to = the check's clear step). Returns the
    task id."""
    t = s.user(id, name, seat, form=p.pack.dir + '/' + id.split('_', 1)[1], decision=None, fields=list(fields))
    s.flow(t, back_to)
    return t


def withdraw(p, s, prefix):
    """Initiator withdrawal from inside the flow (a revise task's withdraw option)."""
    k = s.setv(prefix + '_kind', 'Kind: withdrawal', values='cancelKind=WITHDRAWN')
    c = core(s, prefix + '_call', 'Withdraw (core)', 'cancellation')
    e = s.end(prefix + '_end', 'Withdrawn', listeners=[p.item_listener('WITHDRAWN')])
    s.seq(k, c, e)
    return k


def revise(p, s, id, name, fields, back_to, withdraw_prefix=None, seat='initiatorSeat', form=None):
    """Return to the initiator: correct and resubmit (version n+1, R43) or withdraw. Returns task id."""
    t = s.user(id, name, '${' + seat + '}', form=form or (p.pack.dir + '/' + id.split('_', 1)[1]),
               decision=(id + 'Decision', ['resubmit', 'withdraw']), fields=fields + ['remarks'])
    g = s.xgw(id + '_gw', 'Resubmit?')
    v = s.svc(id + '_version', 'New case version', C['CASE'], {'op': 'RESUBMIT'})
    s.flow(t, g)
    s.flow(g, v, f"{id}Decision == 'resubmit'", 'resubmit')
    s.flow(v, back_to)
    w = withdraw(p, s, withdraw_prefix or (id + '_wd'))
    s.flow(g, w, None, 'withdraw', default=True)
    return t


def batch_loop(p, s, prefix, child, poll='PT5M', chunk=200, inherit=None, items_var='items'):
    """GP-09 fan-out: open the round, start children in asynchronous chunks, poll the aggregate. Returns
    (entry, exit_when_complete)."""
    op = s.svc(prefix + '_open', 'Open round', C['BATCH'], {'op': 'OPEN', 'itemsVar': items_var})
    fo = s.svc(prefix + '_fanOut', 'Start one instance per item (chunk)', C['BATCH'],
               {'op': 'FAN_OUT', 'itemsVar': items_var, 'childProcessKey': child, 'chunkSize': str(chunk),
                'inherit': inherit}, async_=True)
    fg = s.xgw(prefix + '_fanGw', 'All started?')
    s.seq(op, fo, fg)
    s.flow(fg, fo, '!fanOutDone', 'next chunk')
    w = s.catch_timer(prefix + '_poll', 'Poll aggregate', duration=poll)
    s.flow(fg, w, None, 'all started', default=True)
    ag = s.svc(prefix + '_aggregate', 'Aggregate outcomes', C['BATCH'], {'op': 'AGGREGATE'}, async_=True)
    cg = s.xgw(prefix + '_doneGw', 'Round complete?')
    s.seq(w, ag, cg)
    s.flow(cg, w, '!batchComplete', 'not yet')
    return op, cg


def broadcast(s, prefix, message, selected_var=None, own_keys=True, chunk=200, round_to_open=None):
    """GP-09-safe broadcast of a stage message to every item of the round. Returns (entry, exit)."""
    n = s.svc(prefix + '_notify', 'Notify items: ' + message, C['BATCH'],
              {'op': 'NOTIFY_ITEMS', 'messageName': message, 'selectedVar': selected_var,
               'itemsAreBusinessKeys': 'true' if own_keys else 'false', 'chunkSize': str(chunk), 'round': '1',
               'roundToOpen': round_to_open}, async_=True)
    g = s.xgw(prefix + '_notifyGw', 'All notified?')
    s.flow(n, g)
    s.flow(g, n, '!notifyDone', 'next chunk')
    return n, g
