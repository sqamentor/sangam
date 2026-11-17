# SPDX-License-Identifier: Apache-2.0
"""IFMS SD-544: no visible word "demo", "dummy" or "(fictional)" in pack seed data (seat holders, seat, office and role
names), since the seeds are what the Seats and Assignments screens show. R8-present."""
import glob
import json
import os
import re
import unittest

PACKS = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'src', 'main',
                                      'resources', 'packs'))
BARRED = re.compile(r'demo|dummy|fictional', re.IGNORECASE)


def visible_values(seed):
    for h in seed.get('holdings', []):
        yield 'holding person', h.get('person', '')
    for key in ('seats', 'roles', 'offices'):
        for row in seed.get(key, []):
            yield key + ' id', str(row.get('id', row.get('code', '')))
            for text in (row.get('name') or {}).values():
                yield key + ' name', text


class SeedWordsTest(unittest.TestCase):
    def test_no_barred_word_in_any_seed(self):
        seeds = sorted(glob.glob(os.path.join(PACKS, '*', 'seed.json')))
        self.assertTrue(seeds, 'no seed.json found under ' + PACKS)
        hits = []
        for path in seeds:
            with open(path, encoding='utf-8') as f:
                seed = json.load(f)
            for what, value in visible_values(seed):
                if BARRED.search(value):
                    hits.append(f'{os.path.basename(os.path.dirname(path))}: {what} {value!r}')
        self.assertEqual([], hits)


if __name__ == '__main__':
    unittest.main()
