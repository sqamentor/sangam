"""Builds a copy of packgen with the B9 hunks reverted (to diff the generated output before/after)."""
import re
import shutil
import sys

src, dst = sys.argv[1], sys.argv[2]
shutil.rmtree(dst, ignore_errors=True)
shutil.copytree(src, dst)


def edit(name, fn):
    p = dst + '/' + name
    s = open(p, encoding='utf-8', newline='').read()
    t = fn(s)
    assert t != s, name
    open(p, 'w', encoding='utf-8', newline='').write(t)


def gov1(s):
    a = s.index("    # J-P1-E09 (SL-32, IFMS R-102 v0.1): every joint seat")
    b = s.index("    ex = core(s, 'p1_execute'")
    s = s[:a] + """    joint = s.user('p1_joint', 'Joint approval | संयुक्त अनुमोदन', "${jointApprovalSeats.split(',')[loopCounter]}",
                   form='p1-bill-payment/joint-approval', decision=('jointDecision', ['approve', 'reject']),
                   # SL-55: 'reject' goes to the core rejection, which needs coded reasons
                   fields=['remarks', 'rejectionReasonCodes', 'rejectionRemarks', 'presentedPayloadHash'],
                   mi={'count': '2', 'max': 5}, sod=True, bind='approve',
                   joint=True)
""" + s[b:]
    s = s.replace("s.flow(jg, js, 'amount > jointApprovalThreshold', 'J-P1-E09')",
                  "s.flow(jg, joint, 'amount > jointApprovalThreshold', 'J-P1-E09')")
    a = s.index("    # the joint step starts strictly above jointApprovalThreshold")
    b = s.index("'CLOSED')", a) + len("'CLOSED')")
    s = s[:a] + """    pk.accept('J-P1-E09', 'Joint approval for high-value bills', ['T16', 'T17'], 'amount above jointApprovalThreshold',
              'two joint approvers approve', 'both approvals bound to the same payload hash',
              dict(bill, amount=12500000), None)""" + s[b:]
    return s


def lib(s):
    a = s.index("        if joint and not (self.node(id + 'Seats')")
    b = s.index("so no seat counts as two approvals)')", a) + len("so no seat counts as two approvals)')") + 1
    s = s[:a] + s[b:]
    s = s.replace('\n        joint=True only through joint_approval(), which resolves the seats first."""', '"""')
    a = s.index("    def joint_approval(")
    b = s.index("    def call(", a)
    return s[:a] + s[b:]


def runs(s):
    a = s.index("    # 1,25,00,000 is above jointApprovalThreshold")
    b = s.index("'jointSeatsCount': 1})),", a) + len("'jointSeatsCount': 1})),")
    return s[:a] + """    ('gov.p1.bill-payment', 'J-P1-E09'): dict(manual='domain question (owner / IFMS, integrator 2026-11-12): above 25,00,000 the approver is CE-ZONE and CE-ZONE is '
                                              'also a joint approval seat, so segregation of duties refuses the '
                                              'second act of the same seat (SodGuardListener)'),""" + s[b:]


def readme(s):
    return s.replace("power forward, joint seats without earlier actors', 'IX.5 r2, S07, J-P1-E07, J-P1-E09, J-P3-E05'",
                     "power forward', 'IX.5 r2, S07, J-P1-E07, J-P3-E05'")


edit('gov1.py', gov1)
edit('lib.py', lib)
edit('acceptance_runs.py', runs)
edit('readme.py', readme)
print('reverted copy at', dst)
