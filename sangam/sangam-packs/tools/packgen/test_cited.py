# SPDX-License-Identifier: Apache-2.0
"""Tests of the citation guard of cited.py (decision 288; M-133 US-HLT-061 pack, decision 174).

Run: python -I -m unittest discover -s sangam/sangam-packs/tools/packgen -p "test_*.py"
"""
import unittest

import cited
from lib import q


class FakePack:
    id = 'test.pack'

    def __init__(self):
        self.tables, self.placeholders = [], []

    def dmn(self, did, name, hit, inputs, outputs, rules, doc=''):
        self.tables.append((did, outputs, rules))

    def placeholder(self, decision, rule, reason):
        self.placeholders.append((decision, rule, reason))


INPUTS = [('Category', 'category', 'string')]
OUTPUTS = [('scope', 'Scope', 'string')]
SOURCE = cited.CITED_NOT_READ + 'some rule (summary read)'
PLACEHOLDER = ('UNVERIFIED rule', 'primary text not read')


def demo_row(source=SOURCE, note='UNVERIFIED, SYNTHETIC_DEMO_ONLY: primary text not read', evidence=cited.UNVERIFIED,
             use=cited.DEMO_ONLY):
    return cited.row([q('A')], [q('IN')], evidence, use, note, source)


class CitedTableTest(unittest.TestCase):

    def write(self, rows, placeholder=PLACEHOLDER):
        pk = FakePack()
        cited.table(pk, 't', 'T', 'FIRST', INPUTS, OUTPUTS, rows, doc='d', placeholder=placeholder)
        return pk

    def test_a_cited_unverified_table_carries_the_columns_and_is_a_placeholder(self):
        pk = self.write([demo_row()])
        did, outputs, rules = pk.tables[0]
        self.assertEqual(['scope', 'evidenceStatus', 'permittedUse', 'evidenceNote', 'source'], [o[0] for o in outputs])
        self.assertEqual([q('IN'), q('UNVERIFIED'), q('SYNTHETIC_DEMO_ONLY'),
                          q('UNVERIFIED, SYNTHETIC_DEMO_ONLY: primary text not read'), q(SOURCE)], rules[0][1])
        self.assertEqual([('t',) + PLACEHOLDER], pk.placeholders)

    def test_a_row_without_a_citation_stops_the_generation(self):
        for source in ('', '   ', None):
            with self.assertRaisesRegex(ValueError, 'names its source'):
                self.write([demo_row(source=source)])

    def test_a_source_that_was_not_read_is_never_verified(self):
        with self.assertRaisesRegex(ValueError, 'not read is never VERIFIED'):
            self.write([demo_row(evidence=cited.VERIFIED)])

    def test_a_demonstration_value_is_never_verified(self):
        with self.assertRaisesRegex(ValueError, 'demonstration value is never VERIFIED'):
            self.write([demo_row(source=cited.DEMO_SOURCE, evidence=cited.VERIFIED)])

    def test_production_use_needs_verified_evidence(self):
        with self.assertRaisesRegex(ValueError, 'PRODUCTION needs VERIFIED'):
            self.write([demo_row(use=cited.PRODUCTION)])

    def test_a_row_records_why_it_has_its_evidence(self):
        with self.assertRaisesRegex(ValueError, 'evidenceNote'):
            self.write([demo_row(note='')])

    def test_an_unknown_evidence_status_stops_the_generation(self):
        with self.assertRaisesRegex(ValueError, 'VERIFIED or UNVERIFIED'):
            self.write([demo_row(evidence='PROBABLY')])

    def test_a_table_with_rows_not_for_production_declares_its_placeholder(self):
        with self.assertRaisesRegex(ValueError, 'declares its placeholder'):
            self.write([demo_row()], placeholder=None)

    def test_a_double_quote_cannot_break_the_feel_literal(self):
        with self.assertRaisesRegex(ValueError, 'double quote'):
            self.write([demo_row(note='UNVERIFIED "quoted"')])

    def test_outputs_cannot_shadow_the_evidence_columns(self):
        pk = FakePack()
        with self.assertRaisesRegex(ValueError, 'evidence and citation columns'):
            cited.table(pk, 't', 'T', 'FIRST', INPUTS, [('source', 'S', 'string')], [demo_row()], doc='d',
                        placeholder=PLACEHOLDER)

    def test_a_row_must_fit_the_columns(self):
        with self.assertRaisesRegex(ValueError, 'outputs for'):
            self.write([cited.row([q('A')], [q('IN'), q('EXTRA')], cited.UNVERIFIED, cited.DEMO_ONLY, 'n', SOURCE)])


class PatientAccessTablesTest(unittest.TestCase):
    """The shipped US-HLT-061 tables pass the guard and say what was and was not read."""

    def test_the_patient_access_sources_say_the_rule_text_was_not_read(self):
        import sec_ushealth
        self.assertTrue(sec_ushealth.SRC_061.startswith(cited.CITED_NOT_READ))
        self.assertIn('US-HLT-061', sec_ushealth.SRC_061)
        self.assertIn('spike/results/US-HEALTH-WORKFLOWS.md:102', sec_ushealth.SRC_061)
        for note in (sec_ushealth.PA_SCOPE_NOTE, sec_ushealth.PA_TIMES_NOTE, sec_ushealth.METRICS_NOTE):
            self.assertTrue(note.startswith('UNVERIFIED, SYNTHETIC_DEMO_ONLY (decision 288)'), note)
        self.assertIn('SYNTHETIC demonstration values', sec_ushealth.PA_TIMES_NOTE)
        self.assertIn('SYNTHETIC demonstration value', sec_ushealth.METRICS_NOTE)


if __name__ == '__main__':
    unittest.main()
