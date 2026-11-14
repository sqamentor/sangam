import sys, pathlib
root = pathlib.Path(sys.argv[1])
pg = root / 'sangam-packs' / 'tools' / 'packgen'


def sub(p, old, new, count=1):
    t = p.read_text(encoding='utf-8')
    if new in t:
        return
    assert t.count(old) == count, (str(p), old, t.count(old))
    p.write_text(t.replace(old, new), encoding='utf-8', newline='')


# sector.powers: optional third input authLevels (row tuple may carry a levels test as 4th element)
sub(pg / 'sector.py', '''def powers(pk, key, rows, doc='K6 / IX.5 rule 3. SYNTHETIC limits: replace with the board-approved delegation of '
                              'financial powers of the regulated entity.'):
    pk.dmn(key, 'Delegated powers by role', 'FIRST',
           [('Seat role', 'seatRole', 'string'), ('Case type', 'caseType', 'string')],
           [('powerLimit', 'Power limit (INR)', 'double'), ('allowed', 'Allowed', 'boolean')],
           [([q(r), '-'], [str(lim), 'true'], n) for r, lim, n in rows] + [(['-', '-'], ['0', 'false'],
                                                                             'no delegated power')], doc=doc)
''', '''def powers(pk, key, rows, doc='K6 / IX.5 rule 3. SYNTHETIC limits: replace with the board-approved delegation of '
                              'financial powers of the regulated entity.'):
    """rows: (role, limit, note) or (role, limit, note, levelsTest). A levels test (for example '> 1') adds the input
    authLevels (the number of authorisation levels the case needs), so a role can hold a concurrence power at the first
    of several levels that differs from its power to approve alone. Rows without a test match any level count."""
    levels = any(len(r) > 3 for r in rows)
    ins = [('Seat role', 'seatRole', 'string'), ('Case type', 'caseType', 'string')]
    if levels:
        ins.append(('Authorisation levels', 'authLevels', 'integer'))

    def tests(r):
        t = [q(r[0]), '-']
        return t + [r[3] if len(r) > 3 else '-'] if levels else t
    pk.dmn(key, 'Delegated powers by role', 'FIRST', ins,
           [('powerLimit', 'Power limit (INR)', 'double'), ('allowed', 'Allowed', 'boolean')],
           [(tests(r), [str(r[1]), 'true'], r[2]) for r in rows]
           + [(['-'] * len(ins), ['0', 'false'], 'no delegated power')], doc=doc)
''')

sub(pg / 'sec_fin.py', '''    powers(pk, 'sec-bank-pay-powers', [('PAYCHECKER', 2500000, 'checker up to 25 lakh alone'),
                                       ('PAYAUTH', 100000000, ''), ('TREASURYHEAD', 10000000000, '')],
           doc='K6: checker power covers single-level payments; above it a second authoriser approves.')
''', '''    powers(pk, 'sec-bank-pay-powers', [
        ('PAYCHECKER', 100000000, 'checker as first of two levels (above the band, sec-bank-pay-limits): concurrence up '
                                  'to the authoriser band; the authoriser\\'s own power is checked at the second level. '
                                  'OWNER / BANK POLICY CONFIRMATION REQUIRED (SL-50)', '> 1'),
        ('PAYCHECKER', 2500000, 'checker up to 25 lakh alone'),
        ('PAYAUTH', 100000000, ''), ('TREASURYHEAD', 10000000000, '')],
           doc='K6: checker power covers single-level payments; above it the checker concurs and a second authoriser '
               'approves (SEC-BANK-PY-1).')
    pk.placeholder('sec-bank-pay-powers', 'PAYCHECKER concurrence limit when authLevels > 1 (100000000)',
                   'owner/bank policy confirmation required (SL-50): the checker concurrence limit above the single-level '
                   'band is not from a verified source; confirm it from the board-approved delegation of financial '
                   'powers and deploy it as a tenant DMN override')
''')

sub(pg / 'packs.py', '''        self.seed = None
        self.identifier_types = None
        ALL.append(self)
''', '''        self.seed = None
        self.identifier_types = None
        self.placeholders = []
        ALL.append(self)
''')
sub(pg / 'packs.py', '''    def accept(self, id, title, refs, given, when, then, vars=None, state=None):''',
    '''    def placeholder(self, decision, rule, reason):
        """A value shipped in a pack DMN that is not from a verified source. The readiness check reports it as an
        error for every tenant that enables the pack until the tenant deploys a confirmed override of `decision`."""
        self.placeholders.append(dict(decision=decision, rule=rule, reason=reason))

    def accept(self, id, title, refs, given, when, then, vars=None, state=None):''')
sub(pg / 'packs.py', '''            'calls': sorted({c for p in pk.procs for c in p.calls}),
        }
''', '''            'calls': sorted({c for p in pk.procs for c in p.calls}),
        }
        if pk.placeholders:
            manifest['placeholders'] = pk.placeholders
''')
print('ok')
