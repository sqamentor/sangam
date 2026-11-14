# SPDX-License-Identifier: Apache-2.0
"""sangam.compliance BPMN: the 12 process models of sangam-compliance (packs/compliance/*.bpmn), M-010b / SL-134.

packgen is their single source (ADR-D-20): main.py writes them into sangam-compliance/src/main/resources/packs/compliance
and --check compares them there. The rest of that pack (pack.json, seed.json, forms, packs.idx) stays in sangam-compliance.

The models are jurisdiction-neutral: every deadline arrives as a process variable that the jurisdiction pack computes
(dueAt, reportDueAt, ...), and every service step is a ComplianceStepDelegate step. Elements are written in declaration
order with their explicit, stable ids (GP-20), and the diagram layout (BPMN DI) comes from the packgen writer (lib.py).
User tasks are routed to the compliance role codes of seed.json (candidate groups). Seat routing is a separate item
(M-010c, after its evidence phase), so it is not part of these declarations yet.
"""
import os
from xml.sax.saxutils import escape

import lib

STEP = 'org.sangam.compliance.bpmn.ComplianceStepDelegate'
NAMESPACE = 'http://sangam.org/packs/compliance'
EXPORTER = 'sangam-compliance'
KIND = {'startEvent': 'event', 'endEvent': 'event', 'boundaryEvent': 'event', 'userTask': 'task',
        'serviceTask': 'task', 'exclusiveGateway': 'gateway', 'parallelGateway': 'gateway'}


class Model:
    """One compliance process: its elements in declaration order, plus the layout graph for lib.layout_scope."""

    def __init__(self, pid, name, exporter_version, doc):
        self.proc = lib.Proc(pid, name, doc)   # holds the layout graph (root scope); its own XML writer is not used
        self.exporter_version = exporter_version
        self.lines = []
        self.tags = {}
        self.defaults = {}

    def _node(self, tag, id, line, **extra):
        if id in self.tags or id == self.proc.pid:
            raise ValueError(f'duplicate id {id} in {self.proc.pid}')
        self.tags[id] = tag
        self.lines.append(line)
        if tag == 'boundaryEvent':
            self.proc.root.boundaries.append(dict(tag=tag, id=id, kind='event', **extra))
        else:
            self.proc.root.nodes.append(dict(tag=tag, id=id, kind=KIND[tag]))
        return id

    def start(self, id, name):
        return self._node('startEvent', id, f'<startEvent id="{id}" name="{lib.a(name)}"/>')

    def end(self, id, name):
        return self._node('endEvent', id, f'<endEvent id="{id}" name="{lib.a(name)}"/>')

    def pgw(self, id):
        return self._node('parallelGateway', id, f'<parallelGateway id="{id}"/>')

    def xgw(self, id, name, default):
        self.defaults[id] = default
        return self._node('exclusiveGateway', id,
                          f'<exclusiveGateway id="{id}" name="{lib.a(name)}" default="{default}"/>')

    def user(self, id, name, group, form):
        lib.check_group_expression(id, group)
        return self._node('userTask', id, f'<userTask id="{id}" name="{lib.a(name)}" '
                                          f'operaton:candidateGroups="{lib.a(group)}" '
                                          f'operaton:formKey="sangam:{lib.a(form)}"/>')

    def step(self, id, name, step):
        return self._node('serviceTask', id, f'<serviceTask id="{id}" name="{lib.a(name)}" operaton:class="{STEP}" '
                                             f'operaton:asyncBefore="true"><extensionElements><operaton:field '
                                             f'name="step"><operaton:string>{escape(step)}</operaton:string>'
                                             f'</operaton:field></extensionElements></serviceTask>')

    def timer(self, id, name, host, date):
        """A non-interrupting timer on a user task at the date in `date` (a variable computed from the pack)."""
        if self.tags.get(host) != 'userTask':
            raise ValueError(f'{self.proc.pid}/{id}: the host {host} must be a user task declared before its timer')
        return self._node('boundaryEvent', id, f'<boundaryEvent id="{id}" name="{lib.a(name)}" attachedToRef="{host}" '
                                               f'cancelActivity="false"><timerEventDefinition><timeDate '
                                               f'xsi:type="tFormalExpression">{escape(date)}</timeDate>'
                                               f'</timerEventDefinition></boundaryEvent>', host=host, name=name,
                          cancel=False)

    def flow(self, id, src, tgt, cond=None):
        if id in self.tags:
            raise ValueError(f'duplicate id {id} in {self.proc.pid}')
        self.tags[id] = 'sequenceFlow'
        self.proc.root.flows.append(dict(id=id, src=src, tgt=tgt, cond=cond, name=None, default=False, priority=None))
        if cond:
            self.lines.append(f'<sequenceFlow id="{id}" sourceRef="{src}" targetRef="{tgt}"><conditionExpression '
                              f'xsi:type="tFormalExpression">{escape(cond)}</conditionExpression></sequenceFlow>')
        else:
            self.lines.append(f'<sequenceFlow id="{id}" sourceRef="{src}" targetRef="{tgt}"/>')
        return id

    def _validate(self):
        pid = self.proc.pid
        nodes = {k for k, v in self.tags.items() if v != 'sequenceFlow'}
        for f in self.proc.root.flows:
            for end_ in (f['src'], f['tgt']):
                if end_ not in nodes:
                    raise ValueError(f'{pid}/{f["id"]}: unknown element {end_}')
        for gw, fid in self.defaults.items():
            if not any(f['id'] == fid and f['src'] == gw and not f['cond'] for f in self.proc.root.flows):
                raise ValueError(f'{pid}/{gw}: default {fid} is not an unconditional flow out of the gateway')
        if [t for t in self.tags.values()].count('startEvent') != 1:
            raise ValueError(f'{pid}: exactly one start event')

    def xml(self):
        self._validate()
        p = self.proc
        out = ['<?xml version="1.0" encoding="UTF-8"?>',
               '<!-- SPDX-License-Identifier: Apache-2.0 -->',
               f'<!-- {escape(p.name)}. Sangam compliance pack (jurisdiction-neutral): timelines arrive as process '
               'variables computed from the jurisdiction pack. Generated with its diagram layout by '
               'sangam-packs/tools/packgen (compliance.py): change the generator, then regenerate. -->',
               '<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" '
               'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" '
               'xmlns:operaton="http://operaton.org/schema/1.0/bpmn" '
               'xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" '
               'xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" '
               'xmlns:di="http://www.omg.org/spec/DD/20100524/DI" '
               f'id="defs_{p.pid.replace("-", "_")}" targetNamespace="{NAMESPACE}" exporter="{EXPORTER}" '
               f'exporterVersion="{self.exporter_version}">',
               f'  <process id="{p.pid}" name="{lib.a(p.name)}" isExecutable="true" '
               f'operaton:historyTimeToLive="3650" operaton:versionTag="1.0.0">',
               f'    <documentation>{escape(p.doc)}</documentation>']
        out.extend('    ' + line for line in self.lines)
        out.append('  </process>')
        lib.layout_proc(p)
        out.extend(lib.di_xml(p))
        out.append('</definitions>')
        return '\n'.join(out) + '\n'


def build():
    """The 12 models, in file-name order."""
    return sorted((f() for f in MODELS), key=lambda m: m.proc.pid)


def write_all(directory):
    """Writes every model as <directory>/<process id>.bpmn (LF, SL-56). Returns the file names."""
    os.makedirs(directory, exist_ok=True)
    names = []
    for m in build():
        name = m.proc.pid + '.bpmn'
        with open(os.path.join(directory, name), 'w', encoding='utf-8', newline='\n') as fh:
            fh.write(m.xml())
        names.append(name)
    return names
