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

# Semantic digests of the 12 hand-written models before packgen took them over (B23 M-010 baseline, di_before.txt,
# confirmed by B39 on checkpoint 0e53f91b): sha256 over every child of <definitions> except bpmndi:BPMNDiagram, in
# canonical form (sorted attributes, stripped text). The takeover added the diagram only, so these must still match.
# A deliberate semantic change of a model (for example seat routing, M-010c) updates its digest here in the same change.
FROZEN = {
    'sangam-breach-incident.bpmn': 'f8db127153567e29c807a8eb919bf94d6d3a9bc95f049dfb67cca02791ff9fd7',
    'sangam-breach-notification-plan.bpmn': '1ad70fa70110882d7e1f6627eba3a4d5b9724729a9887ae5430888da02a31c43',
    'sangam-dsr-access.bpmn': '7ef669475a7ad0072dbc2dced9f69525820a034fe4a4e50c99c8b50d6ef12a35',
    'sangam-dsr-accounting.bpmn': '4e47a6dead775edbd09f7f4fc0cdb64626f07f3f4be1fc94da6a0eb0c536a30f',
    'sangam-dsr-automated-decision.bpmn': 'f65e41f753bd288304b83f120a3093bfad992e5fda8e785d23813aab8241f918',
    'sangam-dsr-erasure.bpmn': '2c8bc6d62e0995b588a8ac1fd77d3d28d22f96e9e6c77b2bb5555ee1c6313309',
    'sangam-dsr-grievance.bpmn': '1f57240b620fbadcc164984789c31400bfb41c57fd44c371cdd679123fe9ede1',
    'sangam-dsr-nomination.bpmn': '88278644f6e40ab6a4950caa02ac5c5856bcc2b92a0502d1f10e1d4bfe27ee5c',
    'sangam-dsr-objection.bpmn': '4e535992013c2482cc77d30101d6714631240d324648179e2acd07d6bedc3bf8',
    'sangam-dsr-rectification.bpmn': '779d4c102b2b9e2ca7623dc4d567b5850c558ad9a1d666d8059d785f933d6d13',
    'sangam-dsr-restriction.bpmn': '54e9e278d2b6916dcefda994327319a08311b382a05b5ebb02d93e1934a2a2ec',
    'sangam-incident-report.bpmn': '1abd8e463dd20d7b6a21529f51fb59b50ec6b37820fee2d866bda9116f05ef44',
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

    def test_the_takeover_added_only_the_diagram(self):
        for name, root in self.roots.items():
            self.assertEqual(FROZEN[name], semantic_digest(root), f'{name}: a non-DI change')
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

    def test_the_layout_writer_is_the_packgen_one(self):
        p = compliance.Model('sangam-x', 'X', '0.1.0', 'doc')
        p.start('start', 'Start')
        p.end('end', 'End')
        p.flow('f1', 'start', 'end')
        xml = p.xml()
        self.assertIn(f'<bpmndi:BPMNDiagram id="{p.proc.short()}_diagram">', xml)
        self.assertEqual('x', lib.Proc('sangam-x', 'X').short())


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
