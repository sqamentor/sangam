# SPDX-License-Identifier: Apache-2.0
"""Tests of the compliance BPMN generator (compliance.py) and of its --check (main.compare_compliance), M-010b / SL-134.

Run: python -I -m unittest discover -s sangam/sangam-packs/tools/packgen -p "test_*.py"
"""
import hashlib
import os
import shutil
import tempfile
import unittest
import xml.etree.ElementTree as ET

import compliance
import lib
import main

M = '{http://www.omg.org/spec/BPMN/20100524/MODEL}'
BPMNDI = '{http://www.omg.org/spec/BPMN/20100524/DI}'
DC = '{http://www.omg.org/spec/DD/20100524/DC}'
DDI = '{http://www.omg.org/spec/DD/20100524/DI}'
SHAPED = {'startEvent', 'endEvent', 'boundaryEvent', 'userTask', 'serviceTask', 'exclusiveGateway', 'parallelGateway'}

# Semantic digests of the 12 models: sha256 over every child of <definitions> except bpmndi:BPMNDiagram, in canonical
# form (sorted attributes, stripped text). Baseline: the hand-written models before packgen took them over (B23 M-010
# di_before.txt, confirmed by B39 on checkpoint 0e53f91b); the takeover (M-010b) added the diagram only. M-010c then
# changed only operaton:candidateGroups of the 22 user tasks of all 12 models (role code -> ${<role>Seat}; the breach
# notification plan's two tasks in a second step, when BreachNotificationPlanner got its start). 2026-11-15: the incident
# report's u_follow got its completion guard (IncidentClosureGuardListener, OJ-06 step 8). 2026-11-16 (UI-DEMO-7): every
# process got operaton:isStartableInTasklist="false" (started only by its starter service). A deliberate semantic
# change of a model updates its digest here in the same change.
FROZEN = {
    'sangam-breach-incident.bpmn': 'd2f284ccfc1abd1f5f2316aaf91ee74acd02fbc14fc7f34a660d65a2d456a7cd',
    'sangam-breach-notification-plan.bpmn': 'd66956ef449c383eef0518d90f5d4b3f9ebbccedc8a225774a3bd10c54e90749',
    'sangam-dsr-access.bpmn': '5a341150809249d12b673c61a9867fa96540b1cb7a19337db6f125a6470104b5',
    'sangam-dsr-accounting.bpmn': '63f124be01413e615e4ccd186dc483cb999875cdbe833c731ee015934513560f',
    'sangam-dsr-automated-decision.bpmn': 'd2efe9116b0edf8222959bab3d973b743544b93a4a3fc04e9fa2d45f3a0ac258',
    'sangam-dsr-erasure.bpmn': '8058a8311048798f2e8850ea09feba5270a8764b5a8f0782f65fba64533667ff',
    'sangam-dsr-grievance.bpmn': 'd9139f8688b63372791d10a8104263cb9e15f66012031510bb7a4a9584e2145f',
    'sangam-dsr-nomination.bpmn': '507f51684d38bf191a01a7b859b4e1f7a89f286038645206fb9f249a73f655a4',
    'sangam-dsr-objection.bpmn': '060a984d9136e599fb8d0a8095414630b6552df30abf92fef4e1c8d788fb57af',
    'sangam-dsr-rectification.bpmn': 'a9608e7cbb3537fa651acd6e39abee34589e8493a9e99cba7d636b77304206ea',
    'sangam-dsr-restriction.bpmn': '6c90b3ee4d8e93ed426c8bb4442624713fc1e7e457d69e41edeea2699ea70829',
    'sangam-incident-report.bpmn': '717bd9c9ef1f99da3f473d75fcab5551c431f38d7561eca768684d59af23618b',
}


def canon(el):
    attrs = ''.join(f' {k}="{v}"' for k, v in sorted(el.attrib.items()))
    kids = ''.join(canon(c) for c in el)
    return f'<{el.tag}{attrs}>{(el.text or "").strip()}{kids}</{el.tag}>{(el.tail or "").strip()}'


def semantic_digest(root):
    return hashlib.sha256(''.join(canon(c) for c in root if c.tag != BPMNDI + 'BPMNDiagram').encode()).hexdigest()


class ComplianceModelsTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix='packgen-compliance-')
        cls.names = compliance.write_all(cls.tmp)
        cls.roots = {n: ET.parse(os.path.join(cls.tmp, n)).getroot() for n in cls.names}

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def test_the_twelve_models_are_generated(self):
        self.assertEqual(sorted(FROZEN), sorted(self.names))

    def test_the_models_match_their_recorded_semantics(self):
        for name, root in self.roots.items():
            self.assertEqual(FROZEN[name], semantic_digest(root), f'{name}: a semantic change not recorded in FROZEN')
            self.assertEqual(['process', 'BPMNDiagram'], [c.tag.split('}')[1] for c in root], name)
            self.assertEqual({'id', 'targetNamespace', 'exporter', 'exporterVersion'}, set(root.attrib), name)
            self.assertEqual('defs_' + root.find(M + 'process').get('id').replace('-', '_'), root.get('id'), name)
            self.assertEqual(compliance.NAMESPACE, root.get('targetNamespace'), name)
            self.assertEqual(compliance.EXPORTER, root.get('exporter'), name)

    def test_every_element_has_exactly_one_diagram_element(self):
        for name, root in self.roots.items():
            proc = root.find(M + 'process')
            shaped = {e.get('id') for e in proc if e.tag.split('}')[1] in SHAPED}
            edged = {e.get('id') for e in proc if e.tag == M + 'sequenceFlow'}
            planes = list(root.iter(BPMNDI + 'BPMNPlane'))
            self.assertEqual([proc.get('id')], [p.get('bpmnElement') for p in planes], name)
            shapes = [s.get('bpmnElement') for s in root.iter(BPMNDI + 'BPMNShape')]
            edges = [e.get('bpmnElement') for e in root.iter(BPMNDI + 'BPMNEdge')]
            self.assertEqual(sorted(shaped), sorted(shapes), f'{name}: one shape per node')
            self.assertEqual(sorted(edged), sorted(edges), f'{name}: one edge per sequence flow')
            for s in root.iter(BPMNDI + 'BPMNShape'):
                b = s.find(DC + 'Bounds')
                self.assertTrue(float(b.get('width')) > 0 and float(b.get('height')) > 0, s.get('id'))
            for e in root.iter(BPMNDI + 'BPMNEdge'):
                self.assertGreaterEqual(len(e.findall(DDI + 'waypoint')), 2, e.get('id'))

    def test_ids_are_unique_in_each_file(self):
        for name, root in self.roots.items():
            ids = [e.get('id') for e in root.iter() if e.get('id')]
            self.assertEqual(len(ids), len(set(ids)), name)

    def test_generation_is_deterministic(self):
        other = tempfile.mkdtemp(prefix='packgen-compliance-')
        try:
            compliance.write_all(other)
            for n in self.names:
                with open(os.path.join(self.tmp, n), 'rb') as a, open(os.path.join(other, n), 'rb') as b:
                    self.assertEqual(a.read(), b.read(), n)
        finally:
            shutil.rmtree(other, ignore_errors=True)

    def test_files_are_written_with_lf(self):
        for n in self.names:
            with open(os.path.join(self.tmp, n), 'rb') as fh:
                self.assertNotIn(b'\r\n', fh.read(), n)


class ComplianceModelValidationTest(unittest.TestCase):

    def test_a_timer_needs_a_user_task_declared_before_it(self):
        p = compliance.Model('sangam-x', 'X', '0.1.0', 'doc')
        p.start('start', 'Start')
        with self.assertRaises(ValueError):
            p.timer('t', 'Due', 'u_missing', '${dueAt}')

    def test_a_gateway_default_must_be_an_unconditional_flow_out_of_it(self):
        p = compliance.Model('sangam-x', 'X', '0.1.0', 'doc')
        p.start('start', 'Start')
        p.xgw('g', 'Which?', default='f_yes')
        p.end('end', 'End')
        p.flow('f1', 'start', 'g')
        p.flow('f_yes', 'g', 'end', '${yes}')
        with self.assertRaises(ValueError):
            p.xml()

    def test_a_flow_to_an_unknown_element_is_refused(self):
        p = compliance.Model('sangam-x', 'X', '0.1.0', 'doc')
        p.start('start', 'Start')
        p.flow('f1', 'start', 'nowhere')
        with self.assertRaises(ValueError):
            p.xml()

    def test_duplicate_ids_are_refused(self):
        p = compliance.Model('sangam-x', 'X', '0.1.0', 'doc')
        p.start('start', 'Start')
        with self.assertRaises(ValueError):
            p.end('start', 'End')

    def test_a_seat_id_inside_an_expression_is_refused(self):
        p = compliance.Model('sangam-x', 'X', '0.1.0', 'doc')
        with self.assertRaises(ValueError):
            p.user('u', 'Task', '${DPO}', 'compliance/x')

    def test_a_completion_guard_must_be_a_compliance_listener(self):
        p = compliance.Model('sangam-x', 'X', '0.1.0', 'doc')
        with self.assertRaises(ValueError):
            p.user('u', 'Task', compliance.seat('CISO'), 'compliance/x', complete_guard='org.example.Anything')

    def test_the_layout_writer_is_the_packgen_one(self):
        p = compliance.Model('sangam-x', 'X', '0.1.0', 'doc')
        p.start('start', 'Start')
        p.end('end', 'End')
        p.flow('f1', 'start', 'end')
        xml = p.xml()
        self.assertIn(f'<bpmndi:BPMNDiagram id="{p.proc.short()}_diagram">', xml)
        self.assertEqual('x', lib.Proc('sangam-x', 'X').short())


class IncidentClosureGuardTest(unittest.TestCase):
    """OJ-06 step 8: the incident follow-up task refuses a close without root cause and lessons at completion."""

    OP = '{http://operaton.org/schema/1.0/bpmn}'

    def test_only_the_incident_follow_up_task_carries_the_closure_guard(self):
        guarded = {}
        for m in compliance.build():
            root = ET.fromstring(m.xml())
            for u in root.iter(M + 'userTask'):
                listeners = [(x.get('event'), x.get('class')) for x in u.iter(self.OP + 'taskListener')]
                if listeners:
                    guarded[(m.proc.pid, u.get('id'))] = listeners
        self.assertEqual({('sangam-incident-report', 'u_follow'):
                          [('complete', 'org.sangam.compliance.bpmn.IncidentClosureGuardListener')]}, guarded)

    def test_the_guard_class_exists_in_sangam_compliance(self):
        here = os.path.dirname(os.path.abspath(__file__))
        for cls in compliance.GUARDS:
            src = os.path.normpath(os.path.join(here, '..', '..', '..', 'sangam-compliance', 'src', 'main', 'java',
                                                *cls.split('.')) + '.java')
            self.assertTrue(os.path.isfile(src), src)


class ComplianceSeatRoutingTest(unittest.TestCase):
    """M-010c: user tasks are offered to the seat variable of their compliance role, never to the role code."""

    def test_seat_variable_names_follow_compliance_config(self):
        self.assertEqual('${dpoSeat}', compliance.seat('DPO'))
        self.assertEqual('${cisoHeadSeat}', compliance.seat('CISO-HEAD'))
        self.assertEqual('${privacyOfficerSeat}', compliance.seat('PRIVACY-OFFICER'))
        with self.assertRaises(ValueError):
            compliance.seat(' ')

    def test_every_routed_task_names_a_seat_variable(self):
        expected = {
            'sangam-breach-incident': {'${dpoSeat}', '${cisoSeat}'},
            'sangam-incident-report': {'${cisoSeat}', '${cisoHeadSeat}'},
            'sangam-dsr-access': {'${dpoSeat}'}, 'sangam-dsr-rectification': {'${dpoSeat}'},
            'sangam-dsr-erasure': {'${recordsOfficerSeat}'},
            'sangam-dsr-grievance': {'${grievanceOfficerSeat}', '${dpoSeat}'},
            'sangam-dsr-nomination': {'${dpoSeat}'}, 'sangam-dsr-accounting': {'${privacyOfficerSeat}'},
            'sangam-dsr-automated-decision': {'${dpoSeat}'}, 'sangam-dsr-objection': {'${dpoSeat}'},
            'sangam-dsr-restriction': {'${privacyOfficerSeat}'},
            'sangam-breach-notification-plan': {'${privacyOfficerSeat}'},
        }
        groups = {}
        for m in compliance.build():
            root = ET.fromstring(m.xml())
            groups[m.proc.pid] = {u.get('{http://operaton.org/schema/1.0/bpmn}candidateGroups')
                                  for u in root.iter(M + 'userTask')}
        self.assertEqual(expected, groups)


class ComplianceCheckTest(unittest.TestCase):
    """--check covers the compliance models: a difference, a missing file and a stale file are each reported."""

    def setUp(self):
        self.gen = tempfile.mkdtemp(prefix='packgen-gen-')
        self.repo = tempfile.mkdtemp(prefix='packgen-repo-')
        compliance.write_all(self.gen)
        compliance.write_all(self.repo)

    def tearDown(self):
        shutil.rmtree(self.gen, ignore_errors=True)
        shutil.rmtree(self.repo, ignore_errors=True)

    def test_identical_files_pass(self):
        problems, n = main.compare_compliance(self.gen, self.repo)
        self.assertEqual([], problems)
        self.assertEqual(12, n)

    def test_differing_missing_and_stale_files_are_reported(self):
        with open(os.path.join(self.repo, 'sangam-dsr-access.bpmn'), 'a', encoding='utf-8', newline='\n') as fh:
            fh.write('<!-- hand edit -->\n')
        os.remove(os.path.join(self.repo, 'sangam-dsr-erasure.bpmn'))
        with open(os.path.join(self.repo, 'sangam-hand-written.bpmn'), 'w', encoding='utf-8', newline='\n') as fh:
            fh.write('<definitions/>\n')
        problems, _ = main.compare_compliance(self.gen, self.repo)
        rel = main.COMPLIANCE_REL
        self.assertIn(f'differs: {rel}/sangam-dsr-access.bpmn', problems)
        self.assertIn(f'missing in repo: {rel}/sangam-dsr-erasure.bpmn', problems)
        self.assertIn(f'stale in repo (not generated): {rel}/sangam-hand-written.bpmn', problems)
        self.assertEqual(3, len(problems))

    def test_only_bpmn_files_of_the_pack_directory_are_owned(self):
        os.makedirs(os.path.join(self.repo, 'forms'))
        for extra in ('pack.json', 'seed.json', os.path.join('forms', 'x.form.json')):
            with open(os.path.join(self.repo, extra), 'w', encoding='utf-8') as fh:
                fh.write('{}')
        problems, _ = main.compare_compliance(self.gen, self.repo)
        self.assertEqual([], problems)

    def test_the_committed_directory_is_the_compliance_pack(self):
        self.assertTrue(main.COMPLIANCE.replace('\\', '/').endswith(main.COMPLIANCE_REL))


if __name__ == '__main__':
    unittest.main()
