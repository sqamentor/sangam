# SPDX-License-Identifier: Apache-2.0
"""Composition helpers shared by the domain packs."""
from lib import C
from money import M  # noqa: F401  (amounts inside FEEL expressions, minor units)


def core(s, id, name, block, async_=False):
    return s.call(id, name, 'sangam-core-' + block, async_=async_)


def outcome(s, id, var, mapping, default, label=None):
    g = s.xgw(id, label or (var + '?'))
    for value, target in mapping.items():
        s.flow(g, target, f"{var} == '{value}'", value)
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
