# SPDX-License-Identifier: Apache-2.0
"""Owner decision 193: the power-limit metadata of every core sanction call, declared in the pack manifest.

Whether a sanction is monetary, its currency and its limit-table key are declared in the deployed pack manifest (the
case-type definition), generated here and versioned with the pack; nothing in a request or process variable can change
them. PowerLimitGuardListener reads them through org.sangam.core.power.ManifestOperationMetadata from the pack.json
that PackDeployer deploys with the pack (packs.write_all lists it as a resource of every pack with a `powers` block).

One entry per call activity into `sangam-core-sanction`, keyed by the call activity id (the action), in the format
ManifestOperationMetadata.parse accepts: {"monetary": bool, "currency": ISO 4217 (monetary only), "limitTable": key}.

Derivation from the calling process, the same facts the listener used to read from variables, so no authority is
broadened:
  * limitTable: the `powersDecisionKey` the calling process sets (at most one per process, else generation stops), or
    the core sanction's own default when it sets none;
  * monetary: the pack declares `amount` as a money variable, or the calling process assigns `amount` anything but the
    literal 0 (a reference such as @estimateAmount, or a value); `amount=0` is the existing "no amount" marker. A
    process that assigns no amount while another process of the same pack does is ambiguous and stops generation;
  * currency: the pack currency (money.py), for a monetary action only;
  * ruleCaseType (owner decision 222): the value of `caseType` in force when the call is entered, declared only when
    the limit table has a `caseType` input. It is found by a forward dataflow over the calling process (see
    case_types): a SetVariables `values` assignment replaces the value, a `defaults` assignment applies only where
    none is set yet, and a path with no assignment reaches the core sanction's own default GENERAL. A call that can be
    entered with more than one value, or a value copied from another variable (`caseType=@x`), stops generation:
    the case type the table is consulted with is a fixed property of the action, never a start variable.
"""
import re
import xml.etree.ElementTree as ET

CORE_SANCTION = 'sangam-core-sanction'
CORE_DEFAULT_TABLE = 'sangam-core-powers'  # core.sanction() defaults powersDecisionKey to this table
CORE_DEFAULT_CASE_TYPE = 'GENERAL'  # core.sanction() sn_defaults: caseType=GENERAL when the caller set none

_BPMN = '{http://www.omg.org/spec/BPMN/20100524/MODEL}'
_OPERATON = '{http://operaton.org/schema/1.0/bpmn}'
_SET_VARIABLES = 'org.sangam.packs.core.SetVariablesDelegate'
_UNSET = '\0unset'  # no caseType assigned yet on this path
_NOT_FLOW_NODES = {'sequenceFlow', 'textAnnotation', 'association', 'dataObject', 'dataObjectReference',
                   'dataStoreReference', 'laneSet', 'documentation', 'extensionElements', 'ioSpecification'}
_DMN_CASE_TYPE_INPUT = re.compile(r'<inputExpression\b[^>]*>\s*<text>\s*caseType\s*</text>')

_CALL = re.compile(r'<callActivity\b[^>]*>')
_ATTR = re.compile(r'\b(id|calledElement)="([^"]*)"')
_TABLE = re.compile(r'powersDecisionKey=([^;<"&]+)')
_AMOUNT = re.compile(r'(?<![A-Za-z0-9_])amount=([^;<"&]*)')


def sanction_calls(xml):
    """The ids of the call activities into the core sanction, in document order."""
    out = []
    for tag in _CALL.findall(xml):
        attrs = dict(_ATTR.findall(tag))
        if attrs.get('calledElement') == CORE_SANCTION:
            out.append(attrs['id'])
    return out


def _assigns_amount(xml):
    values = [v.strip() for v in _AMOUNT.findall(xml)]
    return any(v not in ('', '0') for v in values), bool(values)


def _local(tag):
    return tag.rsplit('}', 1)[-1]


def _case_type_assignments(owner):
    """[(kind, value)] of the caseType assignments a SetVariables task or listener on `owner` makes, in run order."""
    start, body, end = _case_type_phases(owner)
    return start + body + end


def _case_type_phases(owner):
    """(start listeners, the element itself, end and take listeners): each a [(kind, value)] list in run order.

    SetVariablesDelegate applies `defaults` before `values`; start listeners run before the element, end listeners
    after it.
    """
    def fields(holder):
        out = []
        by_name = {}
        for f in holder.findall(_OPERATON + 'field'):
            s = f.find(_OPERATON + 'string')
            text = f.get('stringValue') or (s.text if s is not None else None) or ''
            by_name[f.get('name')] = text
        for kind in ('defaults', 'values'):
            for part in by_name.get(kind, '').split(';'):
                name, eq, value = part.partition('=')
                if eq and name.strip() == 'caseType':
                    out.append((kind, value.strip()))
        return out

    ext = owner.find(_BPMN + 'extensionElements')
    listeners = [] if ext is None else ext.findall(_OPERATON + 'executionListener')
    start, body, end = [], [], []
    for lst in listeners:
        if lst.get('class') == _SET_VARIABLES and lst.get('event', 'start') == 'start':
            start += fields(lst)
    if owner.get(_OPERATON + 'class') == _SET_VARIABLES and ext is not None:
        body += fields(ext)
    for lst in listeners:
        if lst.get('class') == _SET_VARIABLES and lst.get('event') in ('end', 'take'):
            end += fields(lst)
    return start, body, end


def _apply(state, assignments):
    for kind, value in assignments:
        if kind == 'values':
            state = frozenset([value])
        else:
            state = frozenset(value if v == _UNSET else v for v in state)
    return state


def case_types(xml, problems):
    """{call activity id: set of caseType values} for every call into the core sanction in one process.

    Forward dataflow to a fixpoint (loops converge because the value sets only grow): a process start has no value,
    an embedded subprocess starts with the value its element was entered with and leaves with the values of its end
    events, an event subprocess may start in any state of its enclosing scope, a boundary event fires with the state
    its activity was entered or left with. A path that reaches the call with no value uses the core default GENERAL.
    Problems (a copied value, an assignment on a sequence flow) are added to `problems`.
    """
    proc = ET.fromstring(xml).find(_BPMN + 'process')
    nodes, scope_of, incoming, outgoing = {}, {}, {}, set()

    def walk(scope):
        for ch in scope:
            name = _local(ch.tag)
            if name == 'sequenceFlow':
                incoming.setdefault(ch.get('targetRef'), []).append(ch.get('sourceRef'))
                outgoing.add(ch.get('sourceRef'))
                if _case_type_assignments(ch):
                    problems.add(f'sequence flow {ch.get("id")} assigns caseType; assign it in a task')
                continue
            if name in _NOT_FLOW_NODES or ch.get('id') is None:
                continue
            nodes[ch.get('id')] = ch
            scope_of[ch.get('id')] = scope
            if name == 'subProcess':
                walk(ch)
    walk(proc)
    for nid, el in nodes.items():
        for kind, value in _case_type_assignments(el):
            if value.startswith('@'):
                problems.add(f'{nid} copies caseType from another variable ({value}); declare a fixed value')

    state_in = {nid: frozenset() for nid in nodes}
    state_out = {nid: frozenset() for nid in nodes}

    def entry(nid):
        el = nodes[nid]
        if _local(el.tag) == 'boundaryEvent':
            att = el.get('attachedToRef')
            return state_in[att] | state_out[att]
        preds = incoming.get(nid, [])
        flowed = frozenset().union(*(state_out[p] for p in preds))
        if preds and _local(el.tag) != 'startEvent':  # a start event may also be a loop target (revise and resubmit)
            return flowed
        return flowed | scope_entry(nid)

    def scope_entry(nid):
        scope = scope_of[nid]
        if scope is proc:
            return frozenset([_UNSET])
        if scope.get('triggeredByEvent') == 'true':  # may start in any state of the enclosing scope
            parent = scope_of[scope.get('id')]
            sibling = [k for k, s in scope_of.items() if s is parent and k != scope.get('id')]
            return frozenset().union(state_in[scope.get('id')], *(state_in[k] | state_out[k] for k in sibling))
        sub = scope.get('id')
        return _apply(state_in[sub], _case_type_phases(nodes[sub])[0])  # after the subprocess's start listeners

    def leave(nid, entered):
        el = nodes[nid]
        start, body, end = _case_type_phases(el)
        if _local(el.tag) == 'subProcess' and el.get('triggeredByEvent') != 'true':
            inner = [k for k, s in scope_of.items() if s is el]
            ends = [k for k in inner if _local(nodes[k].tag) == 'endEvent']
            ends = ends or [k for k in inner if k not in outgoing and _local(nodes[k].tag) != 'boundaryEvent']
            inside = frozenset().union(*(state_out[k] for k in ends)) if ends else _apply(entered, start)
            return _apply(inside, body + end)
        return _apply(entered, start + body + end)

    changed = True
    while changed:
        changed = False
        for nid in nodes:
            i = entry(nid)
            o = leave(nid, i)
            if i != state_in[nid] or o != state_out[nid]:
                state_in[nid], state_out[nid] = i, o
                changed = True
    return {nid: {CORE_DEFAULT_CASE_TYPE if v == _UNSET else v
                  for v in _apply(state_in[nid], _case_type_phases(el)[0])}  # the in-mapping follows start listeners
            for nid, el in nodes.items()
            if _local(el.tag) == 'callActivity' and el.get('calledElement') == CORE_SANCTION}


def has_case_type_input(dmn_xml):
    """True when the decision table reads a `caseType` input."""
    return bool(_DMN_CASE_TYPE_INPUT.search(dmn_xml or ''))


def declare(pk, xmls, tables, missing):
    """The `powers` block of pack `pk` from its processes' generated XML {pid: xml}; adds problems to `missing`.

    `tables` maps every limit table the pack may use (its own and sangam.core's) to its DMN XML.
    """
    any_amount = any(_assigns_amount(x)[0] for x in xmls.values())
    block = {}
    for pid, xml in xmls.items():
        calls = sanction_calls(xml)
        if not calls:
            continue
        problems = set()
        rule_case_types = case_types(xml, problems)
        for problem in sorted(problems):
            missing.add(f'{pk.id} {pid}: {problem}')
        if problems:
            continue
        keys = sorted({t.strip() for t in _TABLE.findall(xml)})
        if len(keys) > 1:
            missing.add(f'{pk.id} {pid}: several powersDecisionKey values {keys}; declare one table per process')
            continue
        table = keys[0] if keys else CORE_DEFAULT_TABLE
        if table not in tables:
            missing.add(f'{pk.id} {pid}: limit table {table} is shipped neither by the pack nor by sangam.core')
            continue
        nonzero, assigned = _assigns_amount(xml)
        monetary = 'amount' in pk.money_vars or nonzero
        if not monetary and not assigned and any_amount:
            missing.add(f'{pk.id} {pid}: the sanction process assigns no amount while another process of the pack '
                        f'does; declare whether the sanction is monetary')
            continue
        keyed_by_case_type = has_case_type_input(tables[table])
        for action in calls:
            if action in block:
                missing.add(f'{pk.id}: sanction call id {action} is not unique')
                continue
            entry = {'monetary': monetary}
            if monetary:
                entry['currency'] = pk.currency
            entry['limitTable'] = table
            if keyed_by_case_type:
                values = sorted(rule_case_types[action])
                if len(values) != 1:
                    missing.add(f'{pk.id} {pid}: sanction call {action} can be entered with caseType {values}; '
                                f'give each case type its own call activity (decision 222)')
                    continue
                entry['ruleCaseType'] = values[0]
            block[action] = entry
    return block
