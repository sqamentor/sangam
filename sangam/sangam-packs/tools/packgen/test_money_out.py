# SPDX-License-Identifier: Apache-2.0
"""Lane ARCH-JURIS: the money-out pass (money_out.py)."""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import money_out  # noqa: E402
from lib import Proc  # noqa: E402


def proc():
    p = Proc('sangam-t-pay', 'Pay')
    s = p.root
    s.start('t_start')
    s.user('t_review', 'Review', 'DESK')
    s.svc('t_pay', 'Pay', 'org.sangam.packs.core.ConnectorCallDelegate', {'connector': 'bank.transfer'})
    s.end('t_end')
    s.seq('t_start', 't_review', 't_pay', 't_end')
    s.flow('t_review', 't_pay', 'x == 1', 'again')
    return p


class SanctionPassTest(unittest.TestCase):

    def test_every_flow_into_the_money_out_step_reaches_the_sanction_first(self):
        p = proc()
        money_out.sanction(p, 't_pay', {'amount': 'amount', 'payeeKeys': 'payeePartyId', 'purpose': 'Pay'})
        s = p.root
        into_pay = [f for f in s.flows if f['tgt'] == 't_pay']
        self.assertEqual([('t_pay_sgw', "${sanctionOutcome == 'SANCTIONED'}")],
                         [(f['src'], f['cond']) for f in into_pay])
        self.assertEqual(2, len([f for f in s.flows if f['src'] == 't_review' and f['tgt'] == 't_pay_sanction']))
        no = next(f for f in s.flows if f['id'] == 't_pay_sgw_no')
        self.assertTrue(no['default'])
        self.assertEqual('t_pay_notReleased', no['tgt'])

    def test_the_sanction_binds_the_money_fields_and_is_released_only_on_sanctioned(self):
        p = proc()
        money_out.sanction(p, 't_pay', {'amount': 'amount', 'purpose': 'Pay'})
        xml = p.xml()
        self.assertIn('calledElement="sangam-core-sanction"', xml)
        self.assertIn('class="org.sangam.packs.core.MoneyFieldsDelegate"', xml)
        self.assertIn('target="moneyOutRelease"', xml)
        self.assertIn("sangam-core-sanction", p.calls)
        ids = [n['id'] for n in p.root.nodes]
        self.assertLess(ids.index('t_pay_sanction'), ids.index('t_pay'))

    def test_an_unknown_money_out_step_stops_generation(self):
        with self.assertRaises(SystemExit):
            money_out.sanction(proc(), 't_missing', {'amount': 'amount'})

    def test_every_table_row_names_a_generated_process_and_step(self):
        import main  # noqa: F401 (imports every pack module)
        import packs
        main.core.build()
        for mod in (main.gov1, main.gov2, main.gov2_works, main.gov3, main.gov_ifms, main.gov_intl, main.gov_pfm,
                    main.gov_pfm_g, main.universal, main.billing_pack, main.sector):
            mod.build()
        by_pid = {p.pid: p for pk in packs.ALL for p in pk.procs}
        for pid, rows in money_out.SANCTIONS.items():
            self.assertIn(pid, by_pid)
            for row in rows:
                self.assertIsNotNone(money_out.find(by_pid[pid], row[0]), f'{pid}/{row[0]}')


if __name__ == '__main__':
    unittest.main()
