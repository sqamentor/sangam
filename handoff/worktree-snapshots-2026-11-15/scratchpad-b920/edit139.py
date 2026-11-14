import sys, os
D = sys.argv[1]
EDITS = {
    'gov1.py': [
        ("from lib import C, q\n", "from lib import C, q\nimport rounding\n"),
        ("""    ia = s.expr('p3_instalmentAmount', 'Instalment amount',
                "${execution.setVariable('instalmentAmount', amount / instalments)}")""",
         """    ia = s.instalment_calc('p3_instalmentAmount', 'Instalment amount (decision 139 policy)', 'instalmentAmount',
                           'amount', 'instalments', 'instalmentNo', rounding.declare(pk, 'p3-instalment-rounding'))"""),
    ],
    'gov2.py': [
        ("from lib import C, q\n", "from lib import C, q\nimport rounding\n"),
        ("""    amt = s.expr('gm_amount', 'Value', "${execution.setVariable('amount', quantity * unitPrice)}")""",
         """    amt = s.money_calc('gm_amount', 'Value (decision 139 policy)', 'amount', 'quantity * unitPrice',
                       rounding.declare(pk, 'gem-amount-rounding'))"""),
    ],
    'sec_fin.py': [
        ("""    fo = s.expr('lo_foir', 'Indicative FOIR',
                "${execution.setVariable('foirPct', (existingEmi + amount / tenureMonths) * 100 / monthlyIncome)}")""",
         """    fo = s.ratio_calc('lo_foir', 'Indicative FOIR (exact ratio)', 'foirPct',
                      '(existingEmi + amount / tenureMonths) * 100 / monthlyIncome')"""),
    ],
    'sec_more.py': [
        ("from lib import C, q\n", "from lib import C, q\nimport rounding\n"),
        ("""    pc = s.expr('dg_pct', 'Cover as % of the portfolio',
                "${execution.setVariable('dlgPct', dlgAmount * 100 / portfolioAmount)}")""",
         """    pc = s.ratio_calc('dg_pct', 'Cover as % of the portfolio (exact ratio)', 'dlgPct',
                      'dlgAmount * 100 / portfolioAmount')"""),
        ("""    cp = s.expr('gt_compute', 'TCS amount', "${execution.setVariable('tcsAmount', netSupplies * tcsRatePct / 100)}")""",
         """    cp = s.money_calc('gt_compute', 'TCS amount (decision 139 policy)', 'tcsAmount', 'netSupplies * tcsRatePct / 100',
                      rounding.declare(pk, 'sec-ecom-tcs-rounding'))"""),
        ("""    cp = s.expr('bl_compute', 'Bill amount', "${execution.setVariable('billAmount', fixedCharge + units * energyRate)}")""",
         """    cp = s.money_calc('bl_compute', 'Bill amount (decision 139 policy)', 'billAmount', 'fixedCharge + units * energyRate',
                      rounding.declare(pk, 'sec-util-bill-rounding'))"""),
        ("""    rc = s.expr('bl_recompute', 'Revised bill amount', "${execution.setVariable('billAmount', fixedCharge + units * energyRate)}")""",
         """    rc = s.money_calc('bl_recompute', 'Revised bill amount (decision 139 policy)', 'billAmount',
                      'fixedCharge + units * energyRate', rounding.declare(pk, 'sec-util-bill-rounding'))"""),
        ("""    cp = s.expr('pr_compute', 'Contributions',
                "${execution.setVariable('pfAmount', pfWages * (pfEmployeePct + pfEmployerPct) / 100)}")
    ce = s.expr('pr_esiCalc', 'ESI', "${execution.setVariable('esiAmount', esiWages * (esiEmployeePct + esiEmployerPct) / 100)}")""",
         """    # decision 139: each contribution is rounded separately by its own policy, then the shares are summed
    cp = s.money_calc('pr_compute', 'PF contributions (each share rounded, decision 139 policy)', 'pfAmount',
                      'round(pfWages * pfEmployeePct / 100) + round(pfWages * pfEmployerPct / 100)',
                      rounding.declare(pk, 'sec-hr-pf-rounding'))
    cee = s.money_calc('pr_esiEmployee', 'ESI employee share (decision 139 policy)', 'esiEmployeeAmount',
                       'esiWages * esiEmployeePct / 100', rounding.declare(pk, 'sec-hr-esi-employee-rounding'))
    cer = s.money_calc('pr_esiEmployer', 'ESI employer share (decision 139 policy)', 'esiEmployerAmount',
                       'esiWages * esiEmployerPct / 100', rounding.declare(pk, 'sec-hr-esi-employer-rounding'))
    ce = s.money_sum('pr_esiCalc', 'ESI (sum of the rounded shares)', 'esiAmount', 'esiEmployeeAmount + esiEmployerAmount')"""),
        ("    s.seq(t, d, inp, r, cp, ce, mk)\n", "    s.seq(t, d, inp, r, cp, cee, cer, ce, mk)\n"),
    ],
    'sec_other.py': [
        ("from lib import C, q\n", "from lib import C, q\nimport rounding\n"),
        ("""    am = s.expr('pp_amount', 'Value', "${execution.setVariable('amount', quantity * unitPrice)}")""",
         """    am = s.money_calc('pp_amount', 'Value (decision 139 policy)', 'amount', 'quantity * unitPrice',
                      rounding.declare(pk, 'sec-scm-p2p-amount-rounding'))"""),
        ("""    rc = s.expr('pp_recompute', 'Value at the awarded price', "${execution.setVariable('amount', quantity * unitPrice)}")""",
         """    rc = s.money_calc('pp_recompute', 'Value at the awarded price (decision 139 policy)', 'amount',
                      'quantity * unitPrice', rounding.declare(pk, 'sec-scm-p2p-amount-rounding'))"""),
    ],
    'universal.py': [
        ("from lib import C, q\n", "from lib import C, q\nimport rounding\n"),
        ("""    calc = s.expr('u13_calc', 'Refund on accepted quantity',
                  "${execution.setVariable('refundDue', refundAmount * acceptedQty / returnQty * (1 - restockingFeePct / 100))}")""",
         """    calc = s.money_calc('u13_calc', 'Refund on accepted quantity (decision 139 policy)', 'refundDue',
                        'refundAmount * acceptedQty / returnQty * (1 - restockingFeePct / 100)',
                        rounding.declare(pk, 'up13-refund-rounding'))"""),
    ],
}
for f, edits in EDITS.items():
    p = os.path.join(D, f)
    with open(p, encoding='utf-8', newline='') as fh:
        s = fh.read()
    crlf = '\r\n' in s
    if crlf:
        s = s.replace('\r\n', '\n')
    for old, new in edits:
        n = s.count(old)
        if n != 1:
            sys.exit(f'{f}: expected 1 match, found {n}: {old[:80]!r}')
        s = s.replace(old, new)
    if crlf:
        s = s.replace('\n', '\r\n')
    with open(p, 'w', encoding='utf-8', newline='') as fh:
        fh.write(s)
    print(f, 'ok', 'crlf' if crlf else 'lf')
