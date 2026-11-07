# SPDX-License-Identifier: Apache-2.0
"""Head-of-account seeds from the official LMMH extract (tools/lmmh/data, see tools/lmmh/README.md).

No head is inferred here: every row comes from the extract of the CGA list and carries its page number. The parent-less
rows in standard_rows() are what ClassificationRegistry looks up today (it validates each segment on its own)."""
import csv as _csv
import os

SLIP = 'CS1127'
HEADER = ['scheme', 'segment', 'parent', 'code', 'name_en', 'flags', 'validFrom', 'validTo', 'source', 'status']
DATA = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'lmmh', 'data')


def _read(name):
    with open(os.path.join(DATA, name), encoding='utf-8', newline='') as f:
        return list(_csv.DictReader(line for line in f if not line.startswith('#')))


HEADS = _read('lmmh-heads.csv')
STANDARD = _read('lmmh-standard-minors.csv')


def _flags(**kw):
    return ';'.join('%s=%s' % (k, str(v).replace(';', ',').replace('=', '-')) for k, v in kw.items() if v)


def _row(segment, parent, r, flags):
    return ('HOA', segment, parent, r['code'], r['name'], flags, '', '', 'LMMH %s p.%s' % (SLIP, r['page']), 'V')


def major_rows():
    return [_row('major', '', r, _flags(division=r['division'], part=r['part'], sector=r['sector'], subSector=r['subSector'],
                                        group=r['group'], nature=r['nature'], minorRule=r['rule']))
            for r in HEADS if r['level'] == 'major']


def sub_major_rows():
    return [_row('subMajor', r['major'], r, _flags(minorRule=r['rule'])) for r in HEADS if r['level'] == 'subMajor']


def minor_rows():
    return [_row('minor', r['major'] + '-' + r['subMajor'], r, _flags(minorRule=r['rule'], subMajorRule=r['subMajorRule']))
            for r in HEADS if r['level'] == 'minor']


# Named in the General Directions, not in the illustrative table: (code, name, direction, page)
DIRECTIONS = [('900', 'Deduct-Refunds', '2.2', 16), ('901', 'Deduct-Receipts and Recoveries on Capital Account', '4.3', 21),
              ('902', 'Deduct - Amount met from Reserve Fund/Deposit Account', '3.4', 19),
              ('911', 'Deduct-Recoveries of Overpayments', '3.10', 20)]


def standard_rows():
    """Parent-less codes: the standard minor heads, then every other official code at that level."""
    out, seen = [], set()
    for s in STANDARD:
        if s['code'] == '900':
            continue  # the table's row reserves the range; see DIRECTIONS
        for code in s['code'].split('/'):
            seen.add(code)
            out.append(('HOA', 'minor', '', code, s['name'], _flags(standard='true', directions=s['directions']), '', '',
                        'LMMH %s p.%s' % (SLIP, s['page']), 'V'))
    for code, name, para, page in DIRECTIONS:
        seen.add(code)
        out.append(('HOA', 'minor', '', code, name, _flags(standard='true', directions=para), '', '',
                    'LMMH %s p.%s' % (SLIP, page), 'V'))
    for code in sorted({r['code'] for r in HEADS if r['level'] == 'minor'} - seen):
        out.append(('HOA', 'minor', '', code, 'Minor head %s (title varies by major head; see hoa-minor-heads.csv)' % code,
                    'index=true', '', '', 'LMMH %s' % SLIP, 'V'))
    out.append(('HOA', 'subMajor', '', '00', 'No sub-major head', '', '', '',
                'LMMH %s (minor heads directly under a major head)' % SLIP, 'P'))
    for code in sorted({r['code'] for r in HEADS if r['level'] == 'subMajor'} - {'00'}):
        out.append(('HOA', 'subMajor', '', code, 'Sub-major head %s (title varies by major head; see hoa-sub-major-heads.csv)' % code,
                    'index=true', '', '', 'LMMH %s' % SLIP, 'V'))
    return out
