# SPDX-License-Identifier: Apache-2.0
"""Tests of the release ledger's held content (owner decision 390 (2), releases.py).

Run: python -I -m unittest discover -s sangam/sangam-packs/tools/packgen -p "test_*.py"
"""
import json
import os
import shutil
import tempfile
import unittest

import releases

PACKS = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'src', 'main',
                                      'resources', 'packs'))


def pack_dir(root, name, pid, version, body):
    d = os.path.join(root, name)
    os.makedirs(os.path.join(d, 'forms'))
    with open(os.path.join(d, 'pack.json'), 'w', encoding='utf-8', newline='\n') as fh:
        json.dump({'id': pid, 'version': version}, fh)
    with open(os.path.join(d, 'forms', 'a.form.json'), 'wb') as fh:
        fh.write(body)
    return d


class HeldContentTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix='releases-')

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_the_repository_ships_no_held_version_with_other_content(self):
        # decision 390 (2): every pack whose content changed after HELD_AT ships under a new version
        self.assertEqual([], releases.stale(PACKS, releases.held()))

    def test_the_ledger_covers_every_pack_held_and_a_bumped_pack_ships_a_version_not_held(self):
        held = releases.held()
        self.assertGreater(len(held), 100)
        for pid, (frm, to) in releases.SHIPS.items():
            self.assertEqual(frm, held[pid]['version'], pid)
            self.assertNotEqual(frm, to, pid)

    def test_a_held_version_with_other_content_stops_generation(self):
        d = pack_dir(self.tmp, 'p', 'x.pack', '1.0.0', b'{"a": 1}\n')
        held = {'x.pack': {'version': '1.0.0', 'sha256': releases.digest(d)}}
        releases.check_held(self.tmp, held)  # unchanged: passes
        with open(os.path.join(d, 'forms', 'a.form.json'), 'wb') as fh:
            fh.write(b'{"a": 2}\n')
        with self.assertRaises(SystemExit) as e:
            releases.check_held(self.tmp, held)
        self.assertIn('x.pack@1.0.0', str(e.exception))
        # the same content under a new version is not held: it ships
        with open(os.path.join(d, 'pack.json'), 'w', encoding='utf-8', newline='\n') as fh:
            json.dump({'id': 'x.pack', 'version': '1.0.1'}, fh)
        self.assertEqual([], releases.stale(self.tmp, held))

    def test_a_new_file_in_a_held_pack_is_a_change_and_crlf_is_not(self):
        d = pack_dir(self.tmp, 'p', 'x.pack', '1.0.0', b'{"a": 1}\n')
        before = releases.digest(d)
        with open(os.path.join(d, 'forms', 'a.form.json'), 'wb') as fh:
            fh.write(b'{"a": 1}\r\n')
        self.assertEqual(before, releases.digest(d), 'a CRLF-only difference is not a change')
        with open(os.path.join(d, 'b.dmn'), 'wb') as fh:
            fh.write(b'<x/>')
        self.assertNotEqual(before, releases.digest(d))

    def test_ships_bumps_only_from_the_held_version(self):
        pid = 'gov.p2.file-movement'
        self.assertEqual(('1.0.1', '1.0.2'), releases.SHIPS[pid])
        self.assertEqual('1.0.2', releases.version_of(pid, '1.0.0'), 'BUMPED to 1.0.1, then SHIPS to 1.0.2')
        self.assertEqual('2.0.0', releases.version_of(pid, '2.0.0'), 'a pack declaring another version keeps it')
        self.assertEqual('1.0.1', releases.version_of('gov.work-order', '1.0.0'), 'not in SHIPS')


if __name__ == '__main__':
    unittest.main()
