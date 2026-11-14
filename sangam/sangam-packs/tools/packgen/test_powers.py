# SPDX-License-Identifier: Apache-2.0
"""Tests of the ruleCaseType derivation of powers.py (owner decision 222).

Run: python -I -m unittest discover -s sangam/sangam-packs/tools/packgen -p "test_*.py"
"""
import unittest

import powers

HEAD = ('<?xml version="1.0" encoding="UTF-8"?>'
        '<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" '
        'xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t"><process id="p" isExecutable="true">')
TAIL = '</process></definitions>'


def setv(id_, values=None, defaults=None):
    fields = ''
    if defaults is not None:
        fields += f'<operaton:field name="defaults"><operaton:string>{defaults}</operaton:string></operaton:field>'
    if values is not None:
        fields += f'<operaton:field name="values"><operaton:string>{values}</operaton:string></operaton:field>'
    return (f'<serviceTask id="{id_}" operaton:class="org.sangam.packs.core.SetVariablesDelegate">'
            f'<extensionElements>{fields}</extensionElements></serviceTask>')


def call(id_):
    return f'<callActivity id="{id_}" calledElement="sangam-core-sanction"/>'


def flow(id_, src, dst):
    return f'<sequenceFlow id="{id_}" sourceRef="{src}" targetRef="{dst}"/>'


def derive(body):
    problems = set()
    result = powers.case_types(HEAD + body + TAIL, problems)
    return result, problems


class CaseTypeDerivationTest(unittest.TestCase):

    def test_no_assignment_is_the_core_default(self):
        r, p = derive('<startEvent id="s"/>' + call('c') + flow('f1', 's', 'c'))
        self.assertEqual({'c': {'GENERAL'}}, r)
        self.assertFalse(p)

    def test_values_replace_and_defaults_fill_only_an_unset_value(self):
        r, _ = derive('<startEvent id="s"/>' + setv('d', defaults='caseType=A') + setv('v', values='caseType=B')
                      + call('c1') + setv('d2', defaults='caseType=C') + call('c2')
                      + flow('f1', 's', 'd') + flow('f2', 'd', 'c1') + flow('f3', 'c1', 'v') + flow('f4', 'v', 'd2')
                      + flow('f5', 'd2', 'c2'))
        self.assertEqual({'A'}, r['c1'])
        self.assertEqual({'B'}, r['c2'], 'a later default never replaces a value already set')

    def test_branches_that_meet_before_a_call_are_ambiguous(self):
        r, _ = derive('<startEvent id="s"/><exclusiveGateway id="g"/>' + setv('a', values='caseType=A')
                      + setv('b', values='caseType=B') + '<exclusiveGateway id="m"/>' + call('c')
                      + flow('f1', 's', 'g') + flow('f2', 'g', 'a') + flow('f3', 'g', 'b') + flow('f4', 'a', 'm')
                      + flow('f5', 'b', 'm') + flow('f6', 'm', 'c'))
        self.assertEqual({'A', 'B'}, r['c'])

    def test_one_branch_without_an_assignment_brings_the_default(self):
        r, _ = derive('<startEvent id="s"/><exclusiveGateway id="g"/>' + setv('a', values='caseType=A')
                      + '<exclusiveGateway id="m"/>' + call('c')
                      + flow('f1', 's', 'g') + flow('f2', 'g', 'a') + flow('f3', 'g', 'm') + flow('f4', 'a', 'm')
                      + flow('f5', 'm', 'c'))
        self.assertEqual({'A', 'GENERAL'}, r['c'])

    def test_a_start_event_that_is_a_loop_target_still_starts_unset(self):
        r, _ = derive('<startEvent id="s"/>' + setv('d', defaults='caseType=A') + '<exclusiveGateway id="g"/>'
                      + call('c') + flow('f1', 's', 'd') + flow('f2', 'd', 'g') + flow('f3', 'g', 's')
                      + flow('f4', 'g', 'c'))
        self.assertEqual({'A'}, r['c'])

    def test_a_loop_that_reassigns_reaches_the_call_with_both_values(self):
        r, _ = derive('<startEvent id="s"/>' + setv('a', values='caseType=A') + '<exclusiveGateway id="m"/>'
                      + call('c') + setv('b', values='caseType=B')
                      + flow('f1', 's', 'a') + flow('f2', 'a', 'm') + flow('f3', 'm', 'c') + flow('f4', 'c', 'b')
                      + flow('f5', 'b', 'm'))
        self.assertEqual({'A', 'B'}, r['c'])

    def test_an_embedded_subprocess_inherits_and_returns_the_value(self):
        r, _ = derive('<startEvent id="s"/>' + setv('a', values='caseType=A')
                      + '<subProcess id="sp"><startEvent id="ss"/>' + call('inner') + setv('b', values='caseType=B')
                      + '<endEvent id="se"/>' + flow('g1', 'ss', 'inner') + flow('g2', 'inner', 'b')
                      + flow('g3', 'b', 'se') + '</subProcess>' + call('after')
                      + flow('f1', 's', 'a') + flow('f2', 'a', 'sp') + flow('f3', 'sp', 'after'))
        self.assertEqual({'A'}, r['inner'])
        self.assertEqual({'B'}, r['after'])

    def test_an_event_subprocess_may_start_in_any_state_of_its_scope(self):
        r, _ = derive('<startEvent id="s"/>' + setv('a', values='caseType=A') + '<userTask id="t"/>'
                      + '<endEvent id="e"/>' + flow('f1', 's', 'a') + flow('f2', 'a', 't') + flow('f3', 't', 'e')
                      + '<subProcess id="ev" triggeredByEvent="true"><startEvent id="es"/>' + call('c')
                      + flow('g1', 'es', 'c') + '</subProcess>')
        self.assertEqual({'A', 'GENERAL'}, r['c'])

    def test_a_copied_value_is_a_problem(self):
        _, p = derive('<startEvent id="s"/>' + setv('a', values='caseType=@kind') + call('c')
                      + flow('f1', 's', 'a') + flow('f2', 'a', 'c'))
        self.assertEqual(1, len(p))
        self.assertIn('copies caseType', next(iter(p)))


class DeclareTest(unittest.TestCase):

    class Pack:
        id = 'test.pack'
        currency = 'INR'
        money_vars = ()

    KEYED = '<inputExpression id="ie" typeRef="string"><text>caseType</text></inputExpression>'
    UNKEYED = '<inputExpression id="ie" typeRef="string"><text>seatRole</text></inputExpression>'

    def xml(self, assign):
        return {'p': HEAD + '<startEvent id="s"/>' + assign + call('c')
                     + flow('f1', 's', 'a' if assign else 'c') + (flow('f2', 'a', 'c') if assign else '') + TAIL}

    def test_a_keyed_table_gets_the_case_type(self):
        missing = set()
        block = powers.declare(self.Pack, self.xml(setv('a', values='caseType=GRANT')),
                               {'sangam-core-powers': self.KEYED}, missing)
        self.assertEqual({'c': {'monetary': False, 'limitTable': 'sangam-core-powers', 'ruleCaseType': 'GRANT'}},
                         block)
        self.assertFalse(missing)

    def test_a_table_without_a_case_type_input_gets_none(self):
        missing = set()
        block = powers.declare(self.Pack, self.xml(setv('a', values='caseType=GRANT')),
                               {'sangam-core-powers': self.UNKEYED}, missing)
        self.assertNotIn('ruleCaseType', block['c'])

    def test_an_ambiguous_case_type_stops_generation(self):
        missing = set()
        body = ('<startEvent id="s"/><exclusiveGateway id="g"/>' + setv('a', values='caseType=A')
                + setv('b', values='caseType=B') + '<exclusiveGateway id="m"/>' + call('c')
                + flow('f1', 's', 'g') + flow('f2', 'g', 'a') + flow('f3', 'g', 'b') + flow('f4', 'a', 'm')
                + flow('f5', 'b', 'm') + flow('f6', 'm', 'c'))
        block = powers.declare(self.Pack, {'p': HEAD + body + TAIL}, {'sangam-core-powers': self.KEYED}, missing)
        self.assertEqual({}, block)
        self.assertEqual(1, len(missing))
        self.assertIn("['A', 'B']", next(iter(missing)))


if __name__ == '__main__':
    unittest.main()
