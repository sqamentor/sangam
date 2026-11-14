import sys

ROOT = sys.argv[1]


def edit(p, pairs):
    b = open(p, 'rb').read().decode('utf-8')
    for old, new in pairs:
        old = old.replace('\n', '\r\n')
        new = new.replace('\n', '\r\n')
        n = b.count(old)
        assert n == 1, (p, old[:70], n)
        b = b.replace(old, new)
    open(p, 'wb').write(b.encode('utf-8'))


edit(ROOT + '/packgen/gov_ifms.py', [
("""from money import M  # noqa: F401  (amounts inside FEEL expressions, minor units)
from sector import powers
""",
"""from money import M, major  # noqa: F401  (amounts inside FEEL expressions, minor units)
from sector import powers
import os as _os
import sys as _sys

# SL-102 (ADR-D-20 single source): the GOI money thresholds are profile data. tools/profilegen holds them (integer minor
# units of the profile currency, SD-398) and writes them to profiles/in/GOI.yaml; the pack DMNs below take the same
# values from that data instead of keeping a second copy. The profilegen modules are plain Python (no YAML), so packgen
# still runs without PyYAML (ci-build.yml runs it before installing PyYAML); appended, so packgen modules win any name.
_sys.path.append(_os.path.join(_os.path.dirname(_os.path.abspath(__file__)), '..', 'profilegen'))
from central import IN as _IN_PROFILE  # noqa: E402
from goi import GOI as _GOI_PROFILE, REAPP as _REAPP_PROFILE  # noqa: E402


def profile_major(pk, minor_units):
    \"\"\"A GOI profile amount (minor units of the profile currency) as the major-unit text a money DMN column is authored
    in (lib.dmn converts it back to the same minor units). The pack must be in the profile's currency, never assumed.\"\"\"
    ccy = _IN_PROFILE['locale']['currency']
    if pk.currency != ccy:
        raise ValueError(f'{pk.id}: pack currency {pk.currency} differs from the GOI profile currency {ccy}')
    return major(minor_units, ccy)
"""),
("""            (['-', '-', '-', '-', '-', '-', '-', '-', '-', q('EST_2CR'), '> 20000000', '-', '-'], ['true', 'true', 'false', q('GoI decision (1) para 3(v): 2 cr')], 'establishment group above 2 cr'),
            (['-', '-', '-', '-', '-', '-', '-', '-', '-', q('NONEST_5CR'), '> 50000000', '-', '-'], ['true', 'true', 'false', q('GoI decision (1) para 3(v): 5 cr')], 'group above 5 cr'),
            (['-', '-', '-', '-', '-', '-', '-', '-', '-', q('OTHER_15CR'), '> 150000000', '-', '-'], ['true', 'true', 'false', q('GoI decision (1) para 3(v): 15 cr')], 'other above 15 cr'),
            (['-', '-', '-', '-', '-', '-', '-', '-', '-', '-', '> 1000000000', '-', '-'], ['true', 'false', 'true', q('GoI decision (1) para 4: > 100 cr')], 'Parliament: above 100 cr'),""",
"""            (['-', '-', '-', '-', '-', '-', '-', '-', '-', q('EST_2CR'), '> ' + group('EST_2CR'), '-', '-'], ['true', 'true', 'false', q('GoI decision (1) para 3(v): 2 cr')], 'establishment group above 2 cr'),
            (['-', '-', '-', '-', '-', '-', '-', '-', '-', q('NONEST_5CR'), '> ' + group('NONEST_5CR'), '-', '-'], ['true', 'true', 'false', q('GoI decision (1) para 3(v): 5 cr')], 'group above 5 cr'),
            (['-', '-', '-', '-', '-', '-', '-', '-', '-', q('OTHER_15CR'), '> ' + group('OTHER_15CR'), '-', '-'], ['true', 'true', 'false', q('GoI decision (1) para 3(v): 15 cr')], 'other above 15 cr'),
            (['-', '-', '-', '-', '-', '-', '-', '-', '-', '-', '> ' + profile_major(pk, _REAPP_PROFILE['parliamentReport']['amountAbove']), '-', '-'], ['true', 'false', 'true', q('GoI decision (1) para 4: > 100 cr')], 'Parliament: above 100 cr'),"""),
("""    R = 'DFPR 2024 '
    pk.dmn('ifms-reapp-rules',""",
"""    R = 'DFPR 2024 '
    group = lambda g: profile_major(pk, _REAPP_PROFILE['objectGroupLimits'][g])  # noqa: E731  (SL-102: profile data)
    pk.dmn('ifms-reapp-rules',"""),
("""    pk.dmn('ifms-scheme-appraisal', 'Scheme appraisal ladder (DoE OM 05-08-2016)', 'FIRST',
           [('Total outlay (INR)', 'totalOutlay', 'money')],
           [('appraisalForum', 'Appraisal forum', 'string'), ('approvalAuthority', 'Approval authority', 'string'),
            ('ruleRef', 'Rule', 'string')],
           [(['<= 1000000000'], [q('FA'), q('SECRETARY'), q('OM 05-08-2016: up to 100 cr')], ''),
            (['<= 5000000000'], [q('SFC'), q('MINISTER'), q('OM 05-08-2016: 100-500 cr')], ''),
            (['<= 10000000000'], [q('EFC'), q('MINISTER_AND_FM'), q('OM 05-08-2016: 500-1000 cr')], ''),
            (['-'], [q('EFC'), q('CABINET'), q('OM 05-08-2016: above 1000 cr')], '')],
           doc='Thresholds mirror the GOI profile appraisal.ladder; a State loads its own ladder as a tenant override.')
    authority(pk, 'ifms-scheme-approval', [('<= 1000000000', 'CAA', 'Secretary'), ('-', 'FA', 'routed to the authority of the ladder')], '')
    ifms_powers(pk, 'ifms-scheme-powers', 'SCHEME',
                [('CAA', 1000000000, 'Secretary approves schemes up to 100 cr (ladder ifms-scheme-appraisal)')],""",
"""    # SL-102: bands, forums and authorities are the GOI profile appraisal.ladder (one source); the citations stay here
    ladder = _GOI_PROFILE['appraisal']['ladder']
    cites = ['OM 05-08-2016: up to 100 cr', 'OM 05-08-2016: 100-500 cr', 'OM 05-08-2016: 500-1000 cr',
             'OM 05-08-2016: above 1000 cr']
    if len(ladder) != len(cites) or any('upTo' not in b for b in ladder[:-1]) or 'upTo' in ladder[-1]:
        raise ValueError('GOI profile appraisal.ladder: expected 3 bands with upTo and an open top band, got ' + repr(ladder))
    if ladder[0]['approval'] != 'SECRETARY':
        raise ValueError('GOI profile appraisal.ladder: the first band is the Secretary power, got ' + repr(ladder[0]))
    secretary = profile_major(pk, ladder[0]['upTo'])
    pk.dmn('ifms-scheme-appraisal', 'Scheme appraisal ladder (DoE OM 05-08-2016)', 'FIRST',
           [('Total outlay (INR)', 'totalOutlay', 'money')],
           [('appraisalForum', 'Appraisal forum', 'string'), ('approvalAuthority', 'Approval authority', 'string'),
            ('ruleRef', 'Rule', 'string')],
           [(['<= ' + profile_major(pk, b['upTo']) if 'upTo' in b else '-'], [q(b['appraisal']), q(b['approval']), q(c)], '')
            for b, c in zip(ladder, cites)],
           doc='Thresholds mirror the GOI profile appraisal.ladder; a State loads its own ladder as a tenant override.')
    authority(pk, 'ifms-scheme-approval', [('<= ' + secretary, 'CAA', 'Secretary'), ('-', 'FA', 'routed to the authority of the ladder')], '')
    ifms_powers(pk, 'ifms-scheme-powers', 'SCHEME',
                [('CAA', int(secretary), 'Secretary approves schemes up to 100 cr (ladder ifms-scheme-appraisal)')],"""),
])

# skeletons.py: the procurement-mode DMN and the profile rows from one table (SL-102)
edit(ROOT + '/profilegen/skeletons.py', [
("""from central import in_money  # noqa: E402  (SL-102: profile amounts in the IN locale, minor units)
""",
"""from money import major  # noqa: E402  (minor units back to the major-unit text a money DMN column is authored in)
from central import IN, in_money  # noqa: E402  (SL-102: profile amounts in the IN locale, minor units)
"""),
("""GOI_PROC = [('<= 50000', 'false', 'DIRECT', 'GFR 2017 r.154 / r.149 (direct purchase up to 50,000)'),
            ('<= 1000000', 'false', 'GEM_L1', 'GFR 2017 r.149 (GeM L1 above 50,000 to 10 lakh)'),
            ('<= 5000000', 'false', 'LIMITED_TENDER', 'GFR 2017 (limited tender up to 50 lakh; delta IFC-188)'),
            ('<= 10000000', 'true', 'LIMITED_TENDER', 'scientific departments: limited tender up to 1 crore (delta IFC-188)'),
            ('-', '-', 'OPEN_TENDER', 'GFR 2017 r.161 (advertised tender)')]""",
"""# SL-102 (one source): the GOI procurement thresholds, in minor units of the IN locale currency. The profile writes them
# as delegation.rows.procurementThresholds and the ifms-procurement-mode DMN takes its bands from the same values.
GOI_PROC_LIMITS = {'direct': in_money('50000'), 'gemL1': in_money('10L'), 'limitedTender': in_money('50L'),
                   'limitedTenderScientific': in_money('1CR')}
_upto = lambda k: '<= ' + major(GOI_PROC_LIMITS[k], IN['locale']['currency'])  # noqa: E731
GOI_PROC = [(_upto('direct'), 'false', 'DIRECT', 'GFR 2017 r.154 / r.149 (direct purchase up to 50,000)'),
            (_upto('gemL1'), 'false', 'GEM_L1', 'GFR 2017 r.149 (GeM L1 above 50,000 to 10 lakh)'),
            (_upto('limitedTender'), 'false', 'LIMITED_TENDER', 'GFR 2017 (limited tender up to 50 lakh; delta IFC-188)'),
            (_upto('limitedTenderScientific'), 'true', 'LIMITED_TENDER',
             'scientific departments: limited tender up to 1 crore (delta IFC-188)'),
            ('-', '-', 'OPEN_TENDER', 'GFR 2017 r.161 (advertised tender)')]"""),
("""                 'procurementThresholds': {'direct': in_money('50000') if gov == 'GOI' else None,
                                           'gemL1': in_money('10L') if gov == 'GOI' else None,
                                           'limitedTender': in_money('50L') if gov == 'GOI' else None,
                                           'limitedTenderScientific': in_money('1CR') if gov == 'GOI' else None}}}""",
"""                 'procurementThresholds': {k: (v if gov == 'GOI' else None) for k, v in GOI_PROC_LIMITS.items()}}}"""),
])
print('ok')
