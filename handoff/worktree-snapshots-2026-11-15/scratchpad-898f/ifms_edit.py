import pathlib
p = pathlib.Path('gov_ifms.py')
t = p.read_text(encoding='utf-8')
def sub(old, new, n=1):
    global t
    assert t.count(old) == n, (old, t.count(old))
    t = t.replace(old, new)

sub("import forms\n", "import forms\nfrom sector import powers\n")
sub('''def task(s, id, name, seat, form, decision, fields, spec='5WD', sod=False):''',
'''def ifms_powers(pk, key, case, rows, unverified=None):
    """Delegated-power table of one IFMS case type (SL-31): rows (role, limit, citation); limit None = the rule confers
    the power without a money ceiling. `unverified` (reason) marks the table as a pack placeholder: the value is the
    pack's working default, not read from the rule text, and readiness reports PACK_PLACEHOLDER_UNCONFIRMED until the
    tenant deploys a confirmed override of `key`."""
    powers(pk, key, [((role, case), limit, note) for role, limit, note in rows],
           doc='Delegated powers for ' + case + ' (SL-31). ' + ('UNVERIFIED: ' + unverified if unverified
                                                               else 'Values cited from the rule text in the corpus.'))
    if unverified:
        for role, limit, _ in rows:
            pk.placeholder(key, f'{role} {case} ' + ('no monetary limit' if limit is None else f'up to {limit}'),
                           unverified)


def task(s, id, name, seat, form, decision, fields, spec='5WD', sod=False):''')

def key(route, pkey):
    sub(f"routingDecisionKey={route};", f"routingDecisionKey={route};powersDecisionKey={pkey};")

key('ifms-reapp-authority', 'ifms-reapp-powers')
key('ifms-formulation-authority', 'ifms-formulation-powers')
key('ifms-supplementary-authority', 'ifms-supplementary-powers')
key('ifms-surrender-authority', 'ifms-surrender-powers')
key('ifms-gia-authority', 'ifms-gia-powers')
key('ifms-gpf-authority', 'ifms-gpf-powers')
key('ifms-advance-authority', 'ifms-advance-powers')
key('ifms-scheme-approval', 'ifms-scheme-powers')

sub("""              'Re-appropriation power is not re-delegable (IFC-007).')
""", """              'Re-appropriation power is not re-delegable (IFC-007).')
    # DFPR 2024 r.10(9) (corpus S03, IFC-062, P): the CAA re-appropriates within the grant; the cases that need the
    # Finance Ministry, the C&AG or a report to Parliament are decided by ifms-reapp-rules, not by a money ceiling.
    ifms_powers(pk, 'ifms-reapp-powers', 'REAPPROPRIATION',
                [('CAA', None, 'CAA re-appropriation power, DFPR 2024 r.10(9); non-delegable r.12(2); '
                               'Finance Ministry cases by ifms-reapp-rules (r.10(10))')])
""")
sub("""    authority(pk, 'ifms-formulation-authority', [('-', 'CAA', 'Secretary approves the department estimates')], '')
""", """    authority(pk, 'ifms-formulation-authority', [('-', 'CAA', 'Secretary approves the department estimates')], '')
    ifms_powers(pk, 'ifms-formulation-powers', 'BUDGET_ESTIMATE',
                [('CAA', None, 'Secretary approves the department estimates (pack default)')],
                unverified='the approving authority of the estimates and the absence of a money ceiling are not read '
                           'from the rule text (corpus IFC-043 to IFC-053 are not verified for powers); confirm from the '
                           'budget circular / delegation order and deploy it as a tenant DMN override')
""")
sub("""    authority(pk, 'ifms-supplementary-authority', [('-', 'CAA', 'Secretary forwards to the budget division')], '')
""", """    authority(pk, 'ifms-supplementary-authority', [('-', 'CAA', 'Secretary forwards to the budget division')], '')
    ifms_powers(pk, 'ifms-supplementary-powers', 'SUPPLEMENTARY',
                [('CAA', None, 'Secretary approves the proposal for the budget division batch (pack default)')],
                unverified='the approving authority of a supplementary proposal is not read from the rule text; '
                           'confirm from the delegation order and deploy it as a tenant DMN override')
""")
sub("""    authority(pk, 'ifms-surrender-authority', [('-', 'CAA', 'surrender approved by the CAA, sent to MoF (GFR 62)')], '')
""", """    authority(pk, 'ifms-surrender-authority', [('-', 'CAA', 'surrender approved by the CAA, sent to MoF (GFR 62)')], '')
    ifms_powers(pk, 'ifms-surrender-powers', 'SURRENDER',
                [('CAA', None, 'surrender of savings to the Finance Ministry, GFR 2017 r.62(1)-(2) (pack default)')],
                unverified='GFR 62(1)-(2) (corpus IFC-069) requires the surrender but does not name the CAA as the '
                           'approving authority; confirm from the delegation order and deploy it as a tenant DMN override')
""")
sub("""    authority(pk, 'ifms-gia-authority', [('-', 'CAA', 'department has full powers where scheme rules have MoF consent (DFPR r.17)')], '')
""", """    authority(pk, 'ifms-gia-authority', [('-', 'CAA', 'department has full powers where scheme rules have MoF consent (DFPR r.17)')], '')
    # DFPR 2024 r.17 (corpus S03, IFC-085, P): full powers to departments for grants-in-aid where the scheme rules were
    # framed with Finance Ministry consent and the sanction carries a certificate to that effect.
    ifms_powers(pk, 'ifms-gia-powers', 'GIA',
                [('CAA', None, 'full powers for grants-in-aid under scheme rules framed with Finance Ministry consent, '
                               'DFPR 2024 r.17')])
""")
sub("""    authority(pk, 'ifms-gpf-authority', [('-', 'HOO', 'Head of Office sanctions GPF withdrawals [U: department order]')], '')
""", """    authority(pk, 'ifms-gpf-authority', [('-', 'HOO', 'Head of Office sanctions GPF withdrawals [U: department order]')], '')
    ifms_powers(pk, 'ifms-gpf-powers', 'GPF',
                [('HOO', None, 'Head of Office sanctions GPF advances / withdrawals within gpf-withdrawal (pack default)')],
                unverified='the GPF (CS) Rules 1960 sanctioning authority is not read (corpus IFC-171 [U: department '
                           'order]); confirm and deploy it as a tenant DMN override')
""")
sub("""    authority(pk2, 'ifms-advance-authority', [('-', 'HOO', 'Head of Office [U]')], '')
""", """    authority(pk2, 'ifms-advance-authority', [('-', 'HOO', 'Head of Office [U]')], '')
    ifms_powers(pk2, 'ifms-advance-powers', 'ADVANCE',
                [('HOO', None, 'Head of Office sanctions advances within ifms-advance-limits (pack default)')],
                unverified='the Compendium of Rules on Advances is not read (corpus IFC-025 / IFC-180 [U]); confirm the '
                           'sanctioning authority and its limit and deploy it as a tenant DMN override')
""")
sub("""    authority(pk, 'ifms-scheme-approval', [('<= 1000000000', 'CAA', 'Secretary'), ('-', 'FA', 'routed to the authority of the ladder')], '')
""", """    authority(pk, 'ifms-scheme-approval', [('<= 1000000000', 'CAA', 'Secretary'), ('-', 'FA', 'routed to the authority of the ladder')], '')
    ifms_powers(pk, 'ifms-scheme-powers', 'SCHEME',
                [('CAA', 1000000000, 'Secretary approves schemes up to 100 cr (ladder ifms-scheme-appraisal)')],
                unverified='the corpus verifies the appraisal forum by outlay (IFC-080) but not the approval thresholds '
                           '(Secretary / Minister / FM / Cabinet are not modelled, IFMS-CENTRAL gap 7); confirm from DFPR '
                           'App. III and deploy it as a tenant DMN override')
""")
p.write_text(t, encoding='utf-8', newline='')
print('ok')
