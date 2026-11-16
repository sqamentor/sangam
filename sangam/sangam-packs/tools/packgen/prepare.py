# SPDX-License-Identifier: Apache-2.0
"""Owner decisions 382 and 385 (1): the document a DSC gate signs is uploaded on a user task of the calling process.

A DSC gate (core.dsc_gate: the create and complete listeners of DscSignatureGuardListener on a user task) captures its
slot's document when its task is created. The slot is filled only by the server's document route, and only by the
holder of an open task of the instance (decisions 267 and 268). A call into a process that holds a DSC gate starts the
gate's process, whose first task is the gate itself, so the slot must be filled on the calling instance before the call
(the call copies it, in variables="all"). So, for every call activity into a gated process:

* the call passes in variables="all"; one that does not is refused by name (the gate would never see the slot);
* where some path from a start of the caller (a start event, an event sub-process start, a boundary event of a
  non-user activity) reaches the call without passing a user task of the caller, one generic, conditional "prepare"
  user task is put before the call: a gateway sends the case to it when the gate will need a signature (a required
  variable of the called gate is true, read from the caller with the called process's own default), else straight to
  the call. Its seat is the initiator's (${initiatorSeat}); a pack names another per call activity in
  Pack.prepare_seats (data, not code);
* afterwards every path to every such call passes a user task of the caller, or generation stops naming the process
  and the call (no allow-list).

Decision 387 (8): every prepare task has the one prepare form defined here (form()): its fields are the called gates'
slots, required and read-only, so the task cannot be completed (422 REQUIRED_FIELDS_MISSING, TaskFormRequirements)
until the officer has uploaded the document of each slot through the document route; the form never writes a slot.
A Sangam form is read from the deployment of the task's process (TaskFormRequirements), so the form is written into
each calling pack, under that pack's form key prefix like every pack form, from this single definition.

A call activity whose callee reaches a gated process only through calls of its own (gem-purchase gm_bid into the
tender, for example) needs nothing from its caller: the callee's own calls into the gate follow the same rule, so the
slot is filled on the callee's instance. Pairs a pack declares for the document route therefore follow in-all calls
only (packs.reached_gate_pairs).
"""
import re

from lib import C, tlistener

PREPARE_SUFFIX = 'Prepare'
PREPARE_GW_SUFFIX = 'PrepareGw'
PREPARE_NAME = 'Prepare the document to sign | हस्ताक्षर हेतु दस्तावेज़ तैयार करें'
DEFAULT_SEAT = '${initiatorSeat}'

_FIELD = re.compile(r'<operaton:field name="([A-Za-z]+)"><operaton:string>([^<]*)</operaton:string></operaton:field>')
_DSC_COMPLETE = re.compile(r'<operaton:taskListener event="complete" class="' + re.escape(C['DSC']) + r'">(.*?)'
                           r'</operaton:taskListener>')
_ALL_IN = '<operaton:in variables="all"/>'


def scopes(scope):
    """The scope and every event sub-process scope inside it."""
    yield scope
    for sub in scope.subs:
        yield from scopes(sub['scope'])


def gate_specs(proc):
    """The DSC gates of a process: [{slot, type, required}] read from the complete listeners core.dsc_gate wrote."""
    out = []
    for s in scopes(proc.root):
        for n in s.nodes:
            for x in n.get('ext', []):
                for m in _DSC_COMPLETE.finditer(x or ''):
                    f = dict(_FIELD.findall(m.group(1)))
                    out.append({'task': n['id'], 'slot': f.get('documentSlot'), 'type': f.get('documentType'),
                                'required': f.get('requiredVar')})
    return out


def defaults_of(proc):
    """name -> literal default of the process's SetVariablesDelegate defaults (the values a call does not pass)."""
    out = {}
    for s in scopes(proc.root):
        for n in s.nodes:
            if n.get('attrs', {}).get('operaton:class') != C['SET']:
                continue
            for x in n.get('ext', []):
                f = dict(_FIELD.findall(x or ''))
                for part in (f.get('defaults') or '').split(';'):
                    if '=' in part:
                        k, v = part.split('=', 1)
                        out.setdefault(k.strip(), v.strip())
    return out


def required_condition(gates, defaults):
    """The condition under which a called gate will need a DSC signature: one of its required variables is true (a
    variable the caller does not hold takes the called process's default)."""
    terms = []
    for var in sorted({g['required'] for g in gates if g['required']}):
        if defaults.get(var, 'false').lower() == 'true':
            terms.append(f"execution.getVariable('{var}') != false")
        else:
            terms.append(f"execution.getVariable('{var}') == true")
    if not terms:
        # a gate without a required variable always signs
        return None
    return '${' + ' || '.join(terms) + '}'


def unpreceded(scope):
    """The nodes of a scope that some path from an entry reaches without passing a user task of the scope. The default
    flow of a prepare gateway is not followed: it is taken only when the called gate will not need a signature."""
    by_src = {}
    for f in scope.flows:
        if not f.get('prepare_bypass'):
            by_src.setdefault(f['src'], []).append(f['tgt'])
    nodes = {n['id']: n for n in scope.nodes}
    entries = [n['id'] for n in scope.nodes if n['tag'] == 'startEvent']
    seen = set()
    todo = list(entries)
    while True:
        while todo:
            u = todo.pop()
            if u in seen:
                continue
            seen.add(u)
            if nodes.get(u, {}).get('tag') == 'userTask':
                continue  # a user task of the caller precedes what follows it
            todo.extend(by_src.get(u, []))
        # a boundary event of an activity reached without a user task is an entry too (a user task host is a user task)
        more = [b['id'] for b in scope.boundaries if b['host'] in seen and b['id'] not in seen
                and nodes.get(b['host'], {}).get('tag') != 'userTask']
        if not more:
            break
        todo.extend(more)
    return {u for u in seen if nodes.get(u, {}).get('tag') != 'userTask'}


def form(form_dir, gates):
    """Decision 387 (8): the shared prepare form of a call into a gated process: (form key, fields). The fields are the
    gates' slots, required (the gateway before the task sends the case there only when the gate will sign) and
    read-only (only the document route sets a slot). Gates with different required variables would need different
    slots under different conditions, which one form cannot say, so generation stops naming them."""
    conditions = {g['required'] for g in gates}
    if len(conditions) > 1:
        raise SystemExit(f'decision 387 (8): the DSC gates {sorted(g["task"] for g in gates)} need their slots under '
                         f'different variables {sorted(str(c) for c in conditions)}; one prepare form cannot say which '
                         f'slot is required')
    slots = sorted({g['slot'] for g in gates})
    if not all(slots):
        raise SystemExit(f'decision 387 (8): a DSC gate of {sorted(g["task"] for g in gates)} names no document slot')
    name = 'prepare-' + '-'.join(sorted({g['type'].lower().replace('_', '-') for g in gates}))
    return form_dir + '/' + name, [s + '*' for s in slots]


def apply(all_procs, prepare_seats_of, form_dir_of):
    """Inserts the prepare tasks, refuses a gate call without in-all, then checks precedence; returns the inserted
    prepare task ids by process id. prepare_seats_of(proc) -> {call activity id: seat expression}; form_dir_of(proc)
    -> the form key prefix of the proc's pack (decision 387 (8))."""
    by_id = {p.pid: p for p in all_procs}
    gated = {pid: gate_specs(p) for pid, p in by_id.items()}
    gated = {pid: g for pid, g in gated.items() if g}
    inserted = {}
    for p in all_procs:
        overrides = prepare_seats_of(p) or {}
        for s in scopes(p.root):
            calls = [n for n in s.nodes if n['tag'] == 'callActivity' and n['attrs'].get('calledElement') in gated]
            if not calls:
                continue
            for n in calls:
                if _ALL_IN not in n.get('ext', []):
                    raise SystemExit(f'decision 385: {p.pid} {n["id"]} calls the DSC gate process '
                                     f'{n["attrs"]["calledElement"]} without in variables="all", so the gate never '
                                     f'sees the document slot filled on {p.pid}')
            reach = unpreceded(s)
            for n in calls:
                if n['id'] not in reach:
                    continue
                callee = by_id[n['attrs']['calledElement']]
                when = required_condition(gated[callee.pid], defaults_of(callee))
                seat = overrides.get(n['id'], DEFAULT_SEAT)
                inserted.setdefault(p.pid, []).append(_insert(s, n['id'], seat, when, gated[callee.pid],
                                                              form_dir_of(p)))
        stray = set(overrides) - {n['id'] for s in scopes(p.root) for n in s.nodes
                                  if n['tag'] == 'callActivity' and n['attrs'].get('calledElement') in gated}
        if stray:
            raise SystemExit(f'decision 385: {p.pid}: prepare_seats names {sorted(stray)}, which are not calls into a '
                             f'DSC gate process')
    check(all_procs, gated)
    return inserted


def _insert(scope, call_id, seat, when, gates, form_dir):
    """[gateway -> (when) ] prepare user task -> call [; gateway -> (otherwise) call]. The incoming flows of the call
    now end at the gateway (or at the task, for a gate that always signs)."""
    gw_id = call_id + PREPARE_GW_SUFFIX
    task_id = call_id + PREPARE_SUFFIX
    types = ', '.join(sorted({g['type'] for g in gates}))
    entry = gw_id if when else task_id
    for f in scope.flows:
        if f['tgt'] == call_id:
            f['tgt'] = entry
    if when:
        scope.xgw(gw_id, 'Document to sign?')
    key, fields = form(form_dir, gates)
    task = scope.user(task_id, PREPARE_NAME, seat, form=key, fields=fields,
                      doc=f'Upload the {types} the next step signs with DSC (decisions 267, 382 and 385).')
    if when:
        scope.flow(gw_id, task, when, 'DSC signature needed')
        bypass = scope.flow(gw_id, call_id, None, 'no DSC signature', default=True)
        for f in scope.flows:
            if f['id'] == bypass:
                # taken only when the gate will not need a signature, so it needs no prepared document (check())
                f['prepare_bypass'] = True
    scope.flow(task, call_id)
    return task


def check(all_procs, gated):
    """Decision 382: no call into a DSC gate process is reached without a user task of the caller before it."""
    bad = []
    for p in all_procs:
        for s in scopes(p.root):
            reach = unpreceded(s)
            for n in s.nodes:
                if n['tag'] == 'callActivity' and n['attrs'].get('calledElement') in gated and n['id'] in reach:
                    bad.append(f'{p.pid} {n["id"]}')
    if bad:
        raise SystemExit('decision 382: a call into a DSC gate process with no user task of the caller before it: '
                         + ', '.join(bad))
