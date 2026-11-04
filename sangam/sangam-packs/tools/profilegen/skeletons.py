# SPDX-License-Identifier: Apache-2.0
"""Per-government delegation-of-financial-powers and procurement-mode DMN tables (data skeletons).

Every government gets the same, complete table structure so that a State switches on by editing values, never by
adding structure. GOI values follow DFPR 2024 / GFR 2017 as researched; State values start as copies of the central
defaults and are marked [U] in each rule description and in the profile placeholders until the State's own rulebook is
loaded. The tables are deployed as tenant overrides (`ifms-financial-powers`, `ifms-procurement-mode`) with the tenant id
of the government; their contract matches sangam-security's DmnPowerEvaluator (maxAmount / allowed / ruleRef)."""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'packgen'))
from lib import dmn, q  # noqa: E402

CR, L = 10000000, 100000

# role, maxAmount (INR), central ruleRef
GOI_POWERS = [('DDO', 0, 'RPR 2022: DDO draws within a sanction; no sanction power of its own'),
              ('HOO', 5 * L, 'DFPR 2024 r.12(4) and department re-delegation order [U value]'),
              ('HOD', 50 * L, 'DFPR 2024 r.12(2) re-delegation to HoD [U value]'),
              ('SECRETARY', 100 * CR, 'DFPR 2024 r.11 (open / limited tender up to 100 cr)'),
              ('MINISTER', 0, 'DFPR 2024 r.11: above the Secretary; no upper limit in this table'),
              ('FA', 0, 'advice / concurrence only')]
GOI_PROC = [('<= 50000', 'false', 'DIRECT', 'GFR 2017 r.154 / r.149 (direct purchase up to 50,000)'),
            ('<= 1000000', 'false', 'GEM_L1', 'GFR 2017 r.149 (GeM L1 above 50,000 to 10 lakh)'),
            ('<= 5000000', 'false', 'LIMITED_TENDER', 'GFR 2017 (limited tender up to 50 lakh; delta IFC-188)'),
            ('<= 10000000', 'true', 'LIMITED_TENDER', 'scientific departments: limited tender up to 1 crore (delta IFC-188)'),
            ('-', '-', 'OPEN_TENDER', 'GFR 2017 r.161 (advertised tender)')]


def powers_dmn(gov, rows, rule_note):
    return dmn('ifms-financial-powers', 'Delegation of financial powers (' + gov + ')', 'FIRST',
               [('Seat role', 'seatRole', 'string'), ('Case type', 'caseType', 'string')],
               [('allowed', 'Allowed', 'boolean'), ('maxAmount', 'Power limit (INR)', 'double'), ('ruleRef', 'Rule', 'string')],
               [([q(r), '-'], ['true' if lim or r in ('MINISTER',) else 'false', str(lim if lim else (10 ** 15 if r == 'MINISTER' else 0)),
                               q(rule_note(ref))], rule_note(ref)) for r, lim, ref in rows]
               + [(['-', '-'], ['false', '0', q('no delegated power')], 'default deny')],
               doc='Contract: sangam-security DmnPowerEvaluator (maxAmount / allowed / ruleRef). ' + gov)


def proc_dmn(gov, rule_note):
    return dmn('ifms-procurement-mode', 'Procurement mode by value (' + gov + ')', 'FIRST',
               [('Estimated value (INR)', 'amount', 'double'), ('Scientific department', 'scientificDepartment', 'boolean')],
               [('procurementMode', 'Procurement mode', 'string'), ('ruleRef', 'Rule', 'string')],
               [([a, s], [q(m), q(rule_note(ref))], rule_note(ref)) for a, s, m, ref in GOI_PROC],
               doc='Procurement rule pack family per profile (IFS-031). ' + gov)


def write(root, gov, family_rule=None):
    """Writes the two tables for one government and returns the profile `delegation` section and placeholders."""
    base = os.path.join(root, 'in', 'dmn', gov)
    os.makedirs(base, exist_ok=True)
    if gov == 'GOI':
        note = lambda r: r  # noqa: E731
        ph = []
    else:
        note = lambda r: '[U] central default, replace from ' + (family_rule or "the State's own rulebook") + ' (was: ' + r + ')'  # noqa: E731
        ph = [{'path': 'delegation.tables', 'reason': '[U] financial-powers and procurement-mode tables are central defaults '
                                                    'until the State order is loaded'}]
    with open(os.path.join(base, 'ifms-financial-powers.dmn'), 'w', encoding='utf-8', newline='\n') as f:
        f.write(powers_dmn(gov, GOI_POWERS, note))
    with open(os.path.join(base, 'ifms-procurement-mode.dmn'), 'w', encoding='utf-8', newline='\n') as f:
        f.write(proc_dmn(gov, note))
    section = {'tables': [
        {'key': 'ifms-financial-powers', 'file': 'in/dmn/' + gov + '/ifms-financial-powers.dmn', 'deployAs': 'TENANT_OVERRIDE',
         'tenant': gov, 'inputs': ['seatRole', 'caseType'], 'outputs': ['allowed', 'maxAmount', 'ruleRef'],
         'status': 'V' if gov == 'GOI' else 'U'},
        {'key': 'ifms-procurement-mode', 'file': 'in/dmn/' + gov + '/ifms-procurement-mode.dmn', 'deployAs': 'TENANT_OVERRIDE',
         'tenant': gov, 'inputs': ['amount', 'scientificDepartment'], 'outputs': ['procurementMode', 'ruleRef'],
         'status': 'P' if gov == 'GOI' else 'U'}],
        'rows': {'financialPowers': [{'role': r, 'maxAmount': (lim if gov == 'GOI' else None), 'ruleRef': note(ref)}
                                     for r, lim, ref in GOI_POWERS],
                 'procurementThresholds': {'direct': '50000' if gov == 'GOI' else None, 'gemL1': '10L' if gov == 'GOI' else None,
                                           'limitedTender': '50L' if gov == 'GOI' else None,
                                           'limitedTenderScientific': '1CR' if gov == 'GOI' else None}}}
    if gov != 'GOI':
        ph.append({'path': 'delegation.rows', 'reason': '[U] values to load from the State delegation / procurement rules'})
    return section, ph
