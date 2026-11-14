import glob, os, re, sys
import xml.etree.ElementTree as ET

ROOT = sys.argv[1]
B = '{http://www.omg.org/spec/BPMN/20100524/MODEL}'
O = '{http://operaton.org/schema/1.0/bpmn}'
UNSET = object()


def assigns(el):
    """(kind, value) of caseType set by a SetVariables task, else None."""
    if not (el.get(O + 'class') or '').endswith('SetVariablesDelegate'):
        return None
    for f in el.iter(O + 'field'):
        s = f.find(O + 'string')
        text = s.text if s is not None else ''
        for part in (text or '').split(';'):
            k, _, v = part.partition('=')
            if k.strip() == 'caseType':
                return f.get('name'), v.strip()
    return None


def analyse(xml):
    root = ET.fromstring(xml)
    proc = root.find(B + 'process')
    parent = {}
    elements = {}
    incoming = {}

    def walk(scope):
        for ch in scope:
            if ch.get('id'):
                elements[ch.get('id')] = ch
                parent[ch.get('id')] = scope
            if ch.tag == B + 'sequenceFlow':
                incoming.setdefault(ch.get('targetRef'), []).append(ch.get('sourceRef'))
            if ch.tag == B + 'subProcess':
                walk(ch)
    walk(proc)
    for eid, el in elements.items():
        if el.tag == B + 'boundaryEvent':
            incoming.setdefault(eid, []).append(el.get('attachedToRef'))

    memo = {}

    def before(eid, stack):
        """Possible caseType values in force when eid is entered."""
        if eid in memo:
            return memo[eid]
        if eid in stack:
            return set()  # loop back edge: contributes nothing new
        stack = stack | {eid}
        preds = incoming.get(eid, [])
        out = set()
        if not preds:
            sc = parent[eid]
            if sc.tag == B + 'subProcess':
                if sc.get('triggeredByEvent') == 'true':
                    out.add(('EVENT', sc.get('id')))
                else:
                    out |= before(sc.get('id'), stack)
            else:
                out.add(UNSET)
        for pr in preds:
            out |= after(pr, stack)
        memo[eid] = out
        return out

    def after(eid, stack):
        el = elements[eid]
        if el.tag == B + 'subProcess' and el.get('triggeredByEvent') != 'true':
            ends = [c.get('id') for c in el if c.tag == B + 'endEvent']
            res = set()
            for e in ends:
                res |= after(e, stack)
            return res or before(eid, stack)
        a = assigns(el)
        b = before(eid, stack)
        if a is None:
            return b
        kind, v = a
        if kind == 'values':
            return {v}
        return {v if x is UNSET else x for x in b}

    res = {}
    for eid, el in elements.items():
        if el.tag == B + 'callActivity' and el.get('calledElement') == 'sangam-core-sanction':
            memo.clear()
            res[eid] = before(eid, frozenset())
    return res


for f in sorted(glob.glob(os.path.join(ROOT, '**', '*.bpmn'), recursive=True)):
    xml = open(f, encoding='utf-8').read()
    if 'sangam-core-sanction"' not in xml or f.endswith('sangam-core-sanction.bpmn'):
        continue
    for call, vals in analyse(xml).items():
        shown = sorted('UNSET' if v is UNSET else str(v) for v in vals)
        print(os.path.relpath(f, ROOT), call, shown, 'AMBIG' if len(vals) != 1 else '')
