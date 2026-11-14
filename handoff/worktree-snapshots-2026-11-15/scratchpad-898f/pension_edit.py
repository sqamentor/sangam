import pathlib
p = pathlib.Path('gov2.py')
t = p.read_text(encoding='utf-8')
def sub(old, new):
    global t
    assert t.count(old) == 1, old
    t = t.replace(old, new)
sub("""               'routingDecisionKey=pension-sanction-authority;executionConnector=ag.pensionCase;executionStep=ag;'""",
    """               'routingDecisionKey=pension-sanction-authority;powersDecisionKey=pension-powers;'
               'executionConnector=ag.pensionCase;executionStep=ag;'""")
sub("""           [('competentRole', 'Competent role', 'string')], [(['-'], [q('HOO')], 'Head of Office')])
""", """           [('competentRole', 'Competent role', 'string')], [(['-'], [q('HOO')], 'Head of Office')])
    # SL-30: the pension is the Head of Office's to process under the rules, not a delegated money power. CCS (Pension)
    # Rules 2021 (G.S.R. 868(E), corpus S45, verified pass 2: IFMS-CENTRAL IFC-174 r.56-60, IFC-179 r.44): the HoO
    # assesses the pension and completes Form 7 (r.59); the amount is fixed by r.44(1) (50 % of emoluments or of the
    # 10-month average, minimum 9,000, maximum 1,25,000 a month). The power check therefore admits any monthly pension
    # up to the r.44(1) maximum; a value above it is not a pension the rules allow and is refused.
    powers(pk, 'pension-powers', [(('HOO', 'PENSION'), 125000,
                                   'Head of Office: monthly pension up to the r.44(1) maximum (CCS (Pension) Rules 2021 '
                                   'r.59, r.44(1))')],
           doc='Pension sanction power (SL-30). CCS (Pension) Rules 2021 r.59 (HoO completes Form 7 and assesses the '
               'pension) and r.44(1) (maximum 1,25,000 a month); corpus S45, IFMS-CENTRAL IFC-174 / IFC-179 (verified).')
""")
sub("from common import core, outcome, end_ok, rejection, reject_with, withdraw, revise, batch_loop\n",
    "from common import core, outcome, end_ok, rejection, reject_with, withdraw, revise, batch_loop\nfrom sector import powers\n")
p.write_text(t, encoding='utf-8', newline='')
print('ok')
