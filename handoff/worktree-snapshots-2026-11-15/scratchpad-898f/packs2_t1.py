import sys, pathlib
root = pathlib.Path(sys.argv[1]) / 'sangam-packs' / 'tools' / 'packgen'

def sub(name, old, new, count=1):
    p = root / name
    t = p.read_text(encoding='utf-8')
    if new in t:
        return
    assert t.count(old) == count, (name, old, t.count(old))
    p.write_text(t.replace(old, new), encoding='utf-8', newline='')

sub('lib.py', 'from xml.sax.saxutils import escape\n', 'from xml.sax.saxutils import escape\nimport re\n')
sub('lib.py', 'def a(v):', '''VAR_NAME = re.compile(r'^[a-z][A-Za-z0-9_]*$')
SEAT_ID_EXPR = re.compile(r'[A-Z0-9][A-Z0-9_-]*')


def check_seat_var(task_id, seat_var):
    """seatVar names the process variable that holds the seat id (lowerCamel), never the seat id itself: a seat id such
    as HOO-P inside ${...} is read by EL as a subtraction, and INVESTIGATOR is not a variable at all. Put the seat id in
    the pack defaults (for example verifySeat=HOO-P) and pass the variable name."""
    if not seat_var or not VAR_NAME.match(seat_var):
        raise ValueError(f'{task_id}: seat_var {seat_var!r} must be a lowerCamel variable name, not a seat id')


def check_group_expression(task_id, group):
    """candidateGroups expressions may only reference variables; a literal seat id inside ${...} is rejected."""
    for expr in re.findall(r'[$#]\\{([^}]*)\\}', group or ''):
        if SEAT_ID_EXPR.fullmatch(expr.strip()):
            raise ValueError(f'{task_id}: candidateGroups expression ${{{expr}}} is a seat id, not a variable')


def a(v):''')
sub('lib.py', "        attrs = {'operaton:candidateGroups': group}\n",
    "        check_group_expression(id, group)\n        attrs = {'operaton:candidateGroups': group}\n")
sub('lib.py', '        Returns (entry, exit) ids."""\n', '        Returns (entry, exit) ids."""\n        check_seat_var(id, seat_var)\n')
sub('gov2.py', 'appellateSeat=HOD-P;amount=0', 'appellateSeat=HOD-P;verifySeat=HOO-P;amount=0')
sub('gov2.py', "'Verify service record | सेवा अभिलेख का सत्यापन', 'HOO-P',", "'Verify service record | सेवा अभिलेख का सत्यापन', 'verifySeat',")
sub('sec_more.py', "handlerSeat=LIFE-CLAIMS')", "handlerSeat=LIFE-CLAIMS;investigatorSeat=INVESTIGATOR')")
sub('sec_more.py', "'Claim investigation | दावा अन्वेषण', 'INVESTIGATOR',", "'Claim investigation | दावा अन्वेषण', 'investigatorSeat',")
print('ok')
