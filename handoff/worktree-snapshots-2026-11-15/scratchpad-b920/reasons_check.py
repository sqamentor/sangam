"""Prototype of the SL-55 static check: every path into a rejection carries a coded reason."""
import os, re, sys, glob
import xml.etree.ElementTree as ET

ROOT = sys.argv[1]
BP = '{http://www.omg.org/spec/BPMN/20100524/MODEL}'
OP = '{http://operaton.org/schema/1.0/bpmn}'
EQ = re.compile(r"^\$\{\s*(\w+)\s*==\s*'([^']*)'\s*\}$")
NONE, HUMAN, CODED = 0, 1, 2
REJECTION = 'sangam-core-rejection'
WAITS = {'intermediateCatchEvent', 'eventBasedGateway', 'receiveTask', 'boundaryEvent'}


def local(t):
    return t.split('}', 1)[1]


def fields(el):
    out = {}
    ext = el.find(BP + 'extensionElements')
    for f in ([] if ext is None else ext.findall(OP + 'field')):
        s = f.find(OP + 'string')
        out[f.get('name')] = (s.text or '') if s is not None else (f.get('stringValue') or '')
    return out


import functools


@functools.lru_cache(maxsize=None)
def form_has_reasons(key):
    if not key or not key.startswith('sangam:'):
        return False
    pack, name = key[len('sangam:'):].split('/', 1)
    p = os.path.join(ROOT, pack, 'forms', name + '.form.json')
    return os.path.exists(p) and 'rejectionReasonCodes' in open(p, encoding='utf-8').read()


class Proc:
    def __init__(self, el, file):
        self.id, self.file, self.nodes, self.out, self.starts, self.boundary = el.get('id'), file, {}, {}, [], {}
        self.parent = {}
        self._walk(el, None)
        for sf in el.iter(BP + 'sequenceFlow'):
            self.out.setdefault(sf.get('sourceRef'), []).append(sf)
        self.side_ends = set()
        inc = {}
        for sf in el.iter(BP + 'sequenceFlow'):
            inc.setdefault(sf.get('targetRef'), []).append(sf.get('sourceRef'))
        for nid, n in self.nodes.items():
            if local(n.tag) == 'endEvent':
                todo, seen, side = [nid], set(), True
                while todo:
                    x = todo.pop()
                    if x in seen:
                        continue
                    seen.add(x)
                    xn = self.nodes.get(x)
                    if xn is not None and local(xn.tag) == 'boundaryEvent' and xn.get('cancelActivity') == 'false':
                        continue
                    if x not in inc:
                        side = False
                        break
                    todo.extend(inc[x])
                if side:
                    self.side_ends.add(nid)
        self.tested = set()
        for ce in el.iter(BP + 'conditionExpression'):
            m = EQ.match((ce.text or '').strip())
            if m:
                self.tested.add(m.group(1))

    def _walk(self, el, sub):
        for c in el:
            t = local(c.tag)
            if t in ('sequenceFlow', 'extensionElements', 'documentation', 'association', 'laneSet',
                     'multiInstanceLoopCharacteristics') or c.get('id') is None:
                continue
            self.nodes[c.get('id')] = c
            self.parent[c.get('id')] = sub
            if t == 'boundaryEvent':
                self.boundary.setdefault(c.get('attachedToRef'), []).append(c.get('id'))
            if t == 'startEvent' and (sub is None or self.nodes[sub].get('triggeredByEvent') == 'true'):
                self.starts.append(c.get('id'))
            if t == 'subProcess':
                self._walk(c, c.get('id'))


TESTED = set()
PROCS = {}


def _side_end(self, node):
    # an end event reached only from a non-interrupting boundary event does not complete the process
    return node in self.side_ends


Proc.side_end = _side_end
for f in glob.glob(os.path.join(ROOT, '**', '*.bpmn'), recursive=True):
    for p in ET.parse(f).getroot().iter(BP + 'process'):
        PROCS[p.get('id')] = Proc(p, os.path.relpath(f, ROOT))
        for ce in p.iter(BP + 'conditionExpression'):
            m = EQ.match((ce.text or '').strip())
            if m:
                TESTED.add(m.group(1))


def setv(el):
    if local(el.tag) != 'serviceTask' or not (el.get(OP + 'class') or '').endswith('SetVariablesDelegate'):
        return None
    f = fields(el)
    pairs = {}
    for kind in ('defaults', 'values'):
        for pair in (f.get(kind) or '').split(';'):
            if '=' in pair:
                k, v = pair.split('=', 1)
                pairs.setdefault(kind, {})[k.strip()] = v.strip()
    return pairs


def is_target(proc, el):
    t = local(el.tag)
    if t == 'callActivity' and el.get('calledElement') == REJECTION:
        return True
    return (t == 'serviceTask' and (el.get(OP + 'class') or '').endswith('CaseRecordDelegate')
            and fields(el).get('op') == 'REJECT' and proc.id != REJECTION)


def is_async(el):
    return el.get(OP + 'asyncBefore') == 'true' or el.get(OP + 'asyncAfter') == 'true'


SUMMARY = {}
PRED = {}


def trace(k):
    out = []
    while k and len(out) < 40:
        out.append(k[1][0] + ('*' if k[1][1] == 0 else ''))
        k = PRED.get(k)
    return list(reversed(out))


def run(proc, start_states, violations, depth=0, export=frozenset()):
    track = proc.tested | export
    """Forward walk; returns the set of (cov, binding) at the process-level end events."""
    ends = set()
    seen = set()
    stack = list(start_states)
    while stack:
        node, cov, bind, path = stack.pop()
        key = (node, cov, bind)
        if key in seen:
            continue
        seen.add(key)
        PRED.setdefault((proc.id, key), path)
        path = (proc.id, key)
        el = proc.nodes[node]
        t = local(el.tag)
        b = dict(bind)
        if is_target(proc, el) and cov == NONE and violations is not None:
            violations.append((proc.file, proc.id, node, trace(path)))
        # boundary events leave the activity before it completes: state on arrival, human cover lost
        for bid in proc.boundary.get(node, []):
            stack.append((bid, CODED if cov == CODED else NONE, bind, path))
        if t in WAITS or is_async(el):
            cov = CODED if cov == CODED else NONE
        results = [(cov, b)]
        if t == 'userTask':
            nc = CODED if cov == CODED else (HUMAN if form_has_reasons(el.get(OP + 'formKey')) else NONE)
            results = [(nc, b)]
        sv = setv(el)
        if sv is not None:
            vals = sv.get('values', {})
            dfl = sv.get('defaults', {})
            nc = cov
            if 'rejectionReasonCodes' in vals or 'rejectionReasonCodes' in dfl or vals.get('deemedDecision') == 'true':
                nc = CODED
            for k, v in vals.items():
                if k not in track:
                    continue
                if v not in ('null',) and not v.startswith('@'):
                    b[k] = v
                else:
                    b.pop(k, None)
            results = [(nc, b)]
        if t == 'callActivity':
            called = PROCS.get(el.get('calledElement'))
            if called is not None and depth < 6:
                results = []
                for c2, b2 in summary(called, cov, depth + 1, frozenset(track)):
                    nb = dict(b)
                    nb.update(dict(b2))
                    results.append((c2, nb))
        if t == 'subProcess' and el.get('triggeredByEvent') != 'true':
            inner = [i for i, par in proc.parent.items() if par == node and local(proc.nodes[i].tag) == 'startEvent']
            for c2, b2 in results:
                for i in inner:
                    stack.append((i, c2, frozenset(b2.items()), path))
            continue
        outs = proc.out.get(node, [])
        if not outs:
            par = proc.parent.get(node)
            if t == 'endEvent' and par is not None and proc.nodes[par].get('triggeredByEvent') != 'true':
                for c2, b2 in results:
                    for sf in proc.out.get(par, []):
                        stack.append((sf.get('targetRef'), c2, frozenset(b2.items()), path))
            elif t == 'endEvent' and par is None and not proc.side_end(node):
                for c2, b2 in results:
                    ends.add((c2, frozenset(b2.items())))
            continue
        for c2, b2 in results:
            for sf in outs:
                if passes(proc, el, sf, outs, b2):
                    c3 = CODED if (cond(sf) or '').replace(' ', '') == "${slaAction=='DEEMED'}" else c2
                    stack.append((sf.get('targetRef'), c3, frozenset(b2.items()), path))
    return ends


def cond(sf):
    ce = sf.find(BP + 'conditionExpression')
    return None if ce is None else (ce.text or '').strip()


def passes(proc, gw, sf, outs, b):
    if local(gw.tag) not in ('exclusiveGateway', 'inclusiveGateway'):
        return True
    c = cond(sf)
    if c is not None:
        m = EQ.match(c)
        return not (m and m.group(1) in b and b[m.group(1)] != m.group(2))
    if sf.get('id') == gw.get('default'):
        for o in outs:
            m = EQ.match(cond(o) or '')
            if m and b.get(m.group(1)) == m.group(2):
                return False
    return True


def summary(proc, cov, depth, export):
    key = (proc.id, cov, export)
    if key not in SUMMARY:
        SUMMARY[key] = set()  # recursion guard
        SUMMARY[key] = run(proc, [(s, cov, frozenset(), None) for s in proc.starts], None, depth, export)
    return SUMMARY[key]


viol = []
import time
for p in PROCS.values():
    t0 = time.time()
    run(p, [(s, NONE, frozenset(), None) for s in p.starts], viol)
    print(p.id, round(time.time() - t0, 2), flush=True)
uniq = {}
for f, pid, node, path in viol:
    uniq.setdefault((f, pid, node), path)
for (f, pid, node), path in sorted(uniq.items()):
    print(f'{f} [{pid}] -> {node}: ' + ' > '.join(path[-25:]))
print(len(uniq), 'violations')
print('targets', sum(1 for p in PROCS.values() for n in p.nodes.values() if is_target(p, n)))
