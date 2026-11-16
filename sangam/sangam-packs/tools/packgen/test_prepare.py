# SPDX-License-Identifier: Apache-2.0
"""Tests of the DSC gate prepare task and the precedence check (owner decisions 382 and 385 (1), prepare.py).

Run: python -I -m unittest discover -s sangam/sangam-packs/tools/packgen -p "test_*.py"
"""
import unittest

import prepare
from core import dsc_gate
from lib import C, Proc


def gate(pid='t-gate', default_required='false'):
    """A process whose first user task is a DSC gate on slot docSlot (type NOTE), required by signNeeded."""
    p = Proc(pid, 'Gate')
    s = p.root
    st = s.start('g_start', initiator=None)
    d = s.setv('g_defaults', 'Defaults', defaults=f'signNeeded={default_required};other=x')
    t = s.user('g_sign', 'Sign', '${signSeat}',
               listeners=dsc_gate({'decisionVar': 'dec', 'approveValues': 'approve', 'requiredVar': 'signNeeded'},
                                  'docSlot', 'NOTE'))
    e = s.end('g_end')
    s.seq(st, d, t, e)
    return p


def caller(pid='t-caller', first='service', all_in=True):
    """start -> (service task | user task) -> call of t-gate -> end."""
    p = Proc(pid, 'Caller')
    s = p.root
    st = s.start('c_start')
    if first == 'user':
        f = s.user('c_fill', 'Fill', '${initiatorSeat}')
    else:
        f = s.setv('c_set', 'Set', values='a=b')
    c = s.call('c_call', 'Call the gate', 't-gate', all_in=all_in)
    e = s.end('c_end')
    s.seq(st, f, c, e)
    return p


def apply_(procs, seats):
    """prepare.apply with every test process in a pack whose form key prefix is 'tp'."""
    return prepare.apply(procs, seats, lambda p: 'tp')


def node(p, id_):
    for s in prepare.scopes(p.root):
        n = s.node(id_)
        if n is not None:
            return n
    return None


def flows_to(p, id_):
    return [f for s in prepare.scopes(p.root) for f in s.flows if f['tgt'] == id_]


class PrepareTaskTest(unittest.TestCase):

    def test_a_call_into_a_gate_with_no_user_task_before_it_gets_a_conditional_prepare_task(self):
        g, c = gate(), caller()
        inserted = apply_([g, c], lambda p: {})
        self.assertEqual({'t-caller': ['c_callPrepare']}, inserted)
        task = node(c, 'c_callPrepare')
        self.assertEqual('userTask', task['tag'])
        self.assertEqual('${initiatorSeat}', task['attrs']['operaton:candidateGroups'])
        self.assertEqual('exclusiveGateway', node(c, 'c_callPrepareGw')['tag'])
        into_gw = flows_to(c, 'c_callPrepareGw')
        self.assertEqual(['c_set'], [f['src'] for f in into_gw], 'the call\'s incoming flow now ends at the gateway')
        to_task = flows_to(c, 'c_callPrepare')
        self.assertEqual(["${execution.getVariable('signNeeded') == true}"], [f['cond'] for f in to_task])
        into_call = sorted(f['src'] for f in flows_to(c, 'c_call'))
        self.assertEqual(['c_callPrepare', 'c_callPrepareGw'], into_call)
        bypass = [f for f in flows_to(c, 'c_call') if f['src'] == 'c_callPrepareGw']
        self.assertTrue(bypass[0]['default'], 'without a needed signature the case goes straight to the call')

    def test_a_gate_whose_default_requires_a_signature_is_prepared_unless_the_caller_says_false(self):
        g, c = gate(default_required='true'), caller()
        apply_([g, c], lambda p: {})
        self.assertEqual(["${execution.getVariable('signNeeded') != false}"],
                         [f['cond'] for f in flows_to(c, 'c_callPrepare')])

    def test_a_user_task_of_the_caller_before_the_call_needs_no_prepare_task(self):
        g, c = gate(), caller(first='user')
        self.assertEqual({}, apply_([g, c], lambda p: {}))
        self.assertIsNone(node(c, 'c_callPrepare'))

    def test_a_pack_names_another_seat_in_its_data(self):
        g, c = gate(), caller()
        apply_([g, c], lambda p: {'c_call': '${clerkSeat}'} if p.pid == 't-caller' else {})
        self.assertEqual('${clerkSeat}', node(c, 'c_callPrepare')['attrs']['operaton:candidateGroups'])

    def test_a_seat_for_a_call_that_is_not_a_gate_call_stops_generation(self):
        g, c = gate(), caller()
        with self.assertRaises(SystemExit) as e:
            apply_([g, c], lambda p: {'c_nothing': '${clerkSeat}'} if p.pid == 't-caller' else {})
        self.assertIn('c_nothing', str(e.exception))

    def test_a_gate_call_without_in_all_is_refused_by_name(self):
        g, c = gate(), caller(all_in=False)
        with self.assertRaises(SystemExit) as e:
            apply_([g, c], lambda p: {})
        self.assertIn('t-caller c_call', str(e.exception))
        self.assertIn('in variables="all"', str(e.exception))

    def test_a_boundary_event_of_a_service_task_is_an_entry_that_needs_the_prepare_task_too(self):
        g = gate()
        p = Proc('t-boundary', 'Boundary')
        s = p.root
        st = s.start('b_start')
        u = s.user('b_fill', 'Fill', '${initiatorSeat}')
        w = s.setv('b_wait', 'Wait', values='a=b')
        c = s.call('b_call', 'Call the gate', 't-gate')
        e = s.end('b_end')
        s.seq(st, u, w, e)
        bd = s.boundary('b_timer', 'b_wait', 'Late', timer_duration='P1D')
        s.seq(bd, c, s.end('b_end2'))
        # the boundary sits on a service task reached after a user task: preceded, no prepare task
        self.assertEqual({}, apply_([g, p], lambda q: {}))
        # the same boundary on a service task reached with no user task before it needs one
        p2 = Proc('t-boundary2', 'Boundary 2')
        s2 = p2.root
        st2 = s2.start('b2_start')
        w2 = s2.setv('b2_wait', 'Wait', values='a=b')
        s2.seq(st2, w2, s2.end('b2_end'))
        c2 = s2.call('b2_call', 'Call the gate', 't-gate')
        bd2 = s2.boundary('b2_timer', 'b2_wait', 'Late', timer_duration='P1D')
        s2.seq(bd2, c2, s2.end('b2_end2'))
        self.assertEqual({'t-boundary2': ['b2_callPrepare']}, apply_([gate(), p2], lambda q: {}))

    def test_the_precedence_check_names_an_unpreceded_gate_call(self):
        g, c = gate(), caller()
        gated = {'t-gate': prepare.gate_specs(g)}
        with self.assertRaises(SystemExit) as e:
            prepare.check([g, c], gated)
        self.assertIn('t-caller c_call', str(e.exception))
        apply_([g, c], lambda p: {})
        prepare.check([g, c], gated)  # after the prepare task: passes

    def test_the_gate_specs_are_read_from_the_dsc_listener(self):
        self.assertEqual([{'task': 'g_sign', 'slot': 'docSlot', 'type': 'NOTE', 'required': 'signNeeded'}],
                         prepare.gate_specs(gate()))
        self.assertIn(C['DSC'], ''.join(node(gate(), 'g_sign')['ext']))

    def test_the_prepare_task_has_the_shared_form_requiring_the_gates_slot_read_only(self):
        # decision 387 (8): the form names the slot as a required field, so the task waits for the upload
        g, c = gate(), caller()
        apply_([g, c], lambda p: {})
        task = node(c, 'c_callPrepare')
        self.assertEqual('sangam:tp/prepare-note', task['attrs']['operaton:formKey'])
        self.assertEqual([{'key': 'tp/prepare-note', 'name': prepare.PREPARE_NAME, 'decision': None,
                           'fields': ['docSlot*'], 'doc': c.forms[0]['doc']}], c.forms)

    def test_one_form_for_gates_needing_their_slots_under_different_variables_stops_generation(self):
        gates = [{'task': 'a', 'slot': 's1', 'type': 'NOTE', 'required': 'x'},
                 {'task': 'b', 'slot': 's2', 'type': 'NOTE', 'required': 'y'}]
        with self.assertRaises(SystemExit) as e:
            prepare.form('tp', gates)
        self.assertIn('decision 387 (8)', str(e.exception))
        self.assertEqual(('tp/prepare-note-noting', ['s1*', 's2*']), prepare.form('tp', [
            dict(gates[0], required=None), dict(gates[1], required=None, type='NOTING')]))

    def test_the_prepare_task_refuses_a_consumed_or_unbound_slot_through_the_slot_ready_listener(self):
        # decisions 387 (9) and 390: the prepare task names each called gate's slot and type to the listener
        g, c = gate(), caller()
        apply_([g, c], lambda p: {})
        ext = ''.join(node(c, 'c_callPrepare')['ext'])
        self.assertIn('<operaton:taskListener event="complete" class="' + C['SLOT_READY'] + '">'
                      '<operaton:field name="slots"><operaton:string>docSlot:NOTE</operaton:string></operaton:field>'
                      '</operaton:taskListener>', ext)
        self.assertEqual('<operaton:taskListener event="complete" class="' + C['SLOT_READY'] + '">'
                         '<operaton:field name="slots"><operaton:string>s1:NOTE,s2:NOTING</operaton:string>'
                         '</operaton:field></operaton:taskListener>',
                         prepare.slot_ready([{'slot': 's2', 'type': 'NOTING'}, {'slot': 's1', 'type': 'NOTE'},
                                             {'slot': 's1', 'type': 'NOTE'}]))


if __name__ == '__main__':
    unittest.main()
