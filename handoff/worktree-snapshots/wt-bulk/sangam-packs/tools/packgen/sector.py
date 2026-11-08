# SPDX-License-Identifier: Apache-2.0
"""Sector packs (SECTORS workstream): banking, NBFC / fintech, capital markets, insurance, healthcare, e-commerce,
telecom, supply chain / manufacturing, education, utilities, HR and legal / privacy.

Requirements are numbered SEC-<SECTOR>-nnn in ../../../../spike/results/SECTOR-WORKFLOWS.md. Pack ids are
`sec.<sector>.<slug>` (directory `sec-<sector>-<slug>`, process ids `sangam-sec-<sector>-<slug>...`), kept apart
from the government packs (`gov.*`, `sangam.core`) and the universal packs (`universal.*`). Every sector pack reuses
the approval core (scrutiny, sanction with SoD / power / payload binding, execution with operationId and
UNKNOWN_OUTCOME, closure, rejection with appeal, cancellation side exits) and reaches external systems only through
ConnectorGateway (`conn` steps and the core execution block). Large sets use one instance per item (GP-09).
"""
from lib import C, q
from packs import Pack
import sector_forms  # noqa: F401  (registers the sector labels and decision options)

SIDE = ['GP-14', 'VI.5']


def spack(sector, slug, en, hi, domain, desc, ids, connectors=(), depends=('sangam.core',), main=None):
    return Pack(f'sec.{sector}.{slug}', f'sec-{sector}-{slug}', en, hi, domain, desc, list(ids),
                connectors=list(connectors), depends=list(depends), main=main)


def start_form(p, s, sid, name, key, title, fields):
    st = s.start(sid, name, form=key)
    p.forms.append(dict(key=key, name=title, decision=None, fields=list(fields), doc=None))
    return st


def clock(s, id, clk, spec):
    """Regulatory clock: <clk>DueAt from a spec (nWD, nD, nH, nMIN or ISO)."""
    return s.svc(id, 'Regulatory clock: ' + clk, C['SLA'], {'op': 'DEADLINE', 'clock': clk, 'spec': spec})


def at_date(s, id, clk, date_var, lead='PT0S'):
    """<clk>DueAt = <date_var> minus lead (now + 60 s when the date is missing or past)."""
    return s.svc(id, 'Due date: ' + clk, C['SLA'], {'op': 'BEFORE', 'clock': clk, 'dateVar': date_var, 'spec': lead})


def powers(pk, key, rows, doc='K6 / IX.5 rule 3. SYNTHETIC limits: replace with the board-approved delegation of '
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


def authority(pk, key, name, rows, var='amount', label='Amount (INR)', typ='double', doc=''):
    """Competent role by one input (default the amount). rows: [(test, role, note)]."""
    pk.dmn(key, name, 'FIRST', [(label, var, typ)], [('competentRole', 'Competent role', 'string')],
           [([t], [q(r)], n) for t, r, n in rows], doc=doc or 'SYNTHETIC bands; replace with the board policy.')


def maybe(s, id, cond, body_entry, body_exit, label):
    """Optional step: if cond then body else skip; returns (gateway, merge)."""
    g = s.xgw(id + 'Gw', label)
    m = s.xgw(id + 'Merge')
    s.flow(g, body_entry, cond, 'yes')
    s.flow(g, m, None, 'no', default=True)
    s.flow(body_exit, m)
    return g, m


def build():
    import sec_fin
    import sec_other
    import sec_more
    sec_fin.build()
    sec_other.build()
    sec_more.build()
    import sec_ushealth  # US-HEALTH workstream: sec.ushealth.* (English + Spanish)
    sec_ushealth.build()
