"""Evidence for M-010: for every BPMN under the given roots, check DI completeness and seat routing.

DI: every flow node, boundary event, subprocess, sequence flow and association inside each process has exactly
one BPMNShape/BPMNEdge; every DI element points at an existing element; bounds w,h > 0; edges >= 2 waypoints.
Seat routing: every userTask has candidateGroups (or assignee) and it is a ${...} expression (no literal role).
Also prints a semantic digest (process subtree with bpmndi removed) per file, for before/after comparison.
"""
import hashlib
import os
import sys
import xml.etree.ElementTree as ET

M = '{http://www.omg.org/spec/BPMN/20100524/MODEL}'
DI = '{http://www.omg.org/spec/BPMN/20100524/DI}'
DC = '{http://www.omg.org/spec/DD/20100524/DC}'
DDI = '{http://www.omg.org/spec/DD/20100524/DI}'
OP = '{http://operaton.org/schema/1.0/bpmn}'
SHAPED = {'startEvent', 'endEvent', 'intermediateCatchEvent', 'intermediateThrowEvent', 'boundaryEvent',
          'task', 'userTask', 'serviceTask', 'scriptTask', 'businessRuleTask', 'sendTask', 'receiveTask',
          'manualTask', 'callActivity', 'subProcess', 'transaction', 'exclusiveGateway', 'parallelGateway',
          'inclusiveGateway', 'eventBasedGateway', 'complexGateway', 'dataObjectReference', 'dataStoreReference',
          'textAnnotation'}
EDGED = {'sequenceFlow', 'association', 'messageFlow', 'dataInputAssociation', 'dataOutputAssociation'}


def canon(el):
    attrs = ''.join(f' {k}="{v}"' for k, v in sorted(el.attrib.items()))
    kids = ''.join(canon(c) for c in el)
    return f'<{el.tag}{attrs}>{(el.text or "").strip()}{kids}</{el.tag}>{(el.tail or "").strip()}'


def check(path):
    root = ET.parse(path).getroot()
    probs = []
    procs = root.findall(M + 'process')
    elems = {}
    for p in procs:
        for el in p.iter():
            local = el.tag.replace(M, '')
            if local in SHAPED or local in EDGED:
                elems[el.get('id')] = local
    di_targets = {}
    for d in root.iter():
        if d.tag in (DI + 'BPMNShape', DI + 'BPMNEdge'):
            be = d.get('bpmnElement')
            di_targets.setdefault(be, []).append(d)
            if be not in elems:
                probs.append(f'DI for unknown element {be}')
            if d.tag == DI + 'BPMNShape':
                b = d.find(DC + 'Bounds')
                if b is None or float(b.get('width', 0)) <= 0 or float(b.get('height', 0)) <= 0:
                    probs.append(f'bad bounds {be}')
            else:
                if len(d.findall(DDI + 'waypoint')) < 2:
                    probs.append(f'edge <2 waypoints {be}')
    for eid, kind in elems.items():
        n = len(di_targets.get(eid, []))
        if n != 1:
            probs.append(f'{kind} {eid}: {n} DI elements')
    planes = [pl.get('bpmnElement') for pl in root.iter(DI + 'BPMNPlane')]
    for p in procs:
        if p.get('id') not in planes:
            probs.append(f'process {p.get("id")} has no BPMNPlane')
    seat = []
    for p in procs:
        for ut in p.iter(M + 'userTask'):
            g = ut.get(OP + 'candidateGroups') or ut.get(OP + 'assignee') or ut.get(OP + 'candidateUsers')
            if not g:
                seat.append(f'userTask {ut.get("id")} has no assignment')
            elif not (g.startswith('${') or g.startswith('#{')):
                seat.append(f'userTask {ut.get("id")} literal group {g!r}')
    sem = hashlib.sha256(''.join(canon(p) for p in root if p.tag != DI + 'BPMNDiagram').encode()).hexdigest()
    return probs, seat, sem, len(elems)


def main():
    files = []
    for r in sys.argv[1:]:
        for d, _, fs in os.walk(r):
            files += [os.path.join(d, f) for f in fs if f.endswith('.bpmn') or f.endswith('.bpmn20.xml')]
    bad_di = bad_seat = 0
    for f in sorted(files):
        probs, seat, sem, n = check(f)
        bad_di += bool(probs)
        bad_seat += bool(seat)
        rel = f.replace('\\', '/')
        print(f'{sem[:16]} n={n} di={len(probs)} seat={len(seat)} {rel}')
        for x in (probs + seat)[:6]:
            print('    ', x)
    print(f'FILES {len(files)} with-DI-problems {bad_di} with-seat-problems {bad_seat}')


if __name__ == '__main__':
    main()
