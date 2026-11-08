# SPDX-License-Identifier: Apache-2.0
"""Money in packs (SD-398 / SG-20, ADR-D-26): integer minor units of the pack currency (paise for INR, cents for USD).

The one place packgen turns an amount into what a pack ships. Pack definitions are written in MAJOR units (rupees, as
the rules and delegations state them: 25 lakh is 2500000); the generator emits integer MINOR units (250000000) for:

- a DMN column typed ``'money'`` (lib.dmn): every literal of its input tests and outputs; an output that is an
  expression must round once to a whole minor unit (``floor(`` / ``ceiling(`` / ``decimal(`` ..., ``round half``);
- ``setv`` values / defaults, acceptance ``vars`` and start defaults whose variable name is money (``is_money``);
- an amount inside a FEEL expression or condition: write ``M(9000)`` (returns the minor-unit literal).

Naming convention (the contract the build guard ``PackMoneyGuardTest`` checks on the shipped files): a variable is
money when its camelCase name ends in one of ``MONEY_SUFFIXES`` (``amount``, ``lastPay``, ``powerLimit``,
``authBandMax`` ...) or is listed in ``MONEY_EXTRA``, unless it is in ``NOT_MONEY`` (a balance of leave days, a rate in
per cent). Its value is always a whole number of minor units (Java ``Long``); a money DMN column has typeRef
``number`` with integer literals only; a money form field has type ``money`` with the currency and its exponent.
"""
import re
from decimal import Decimal

# ISO 4217 minor-unit exponents of the currencies packs use (Currency.getDefaultFractionDigits()).
EXPONENT = {'INR': 2, 'USD': 2}
DEFAULT_CURRENCY = 'INR'

MONEY_SUFFIXES = ('Amount', 'Amt', 'Fee', 'Fees', 'Price', 'Cost', 'Value', 'Balance', 'Income', 'Pay', 'Salary',
                  'Wages', 'Emoluments', 'Pension', 'Gratuity', 'Limit', 'Ceiling', 'Outlay',
                  'Entitlement', 'Worth', 'Charge', 'Compensation', 'Premium', 'Assured', 'Insured', 'Supplies',
                  'Penalty', 'Refund', 'BandMax', 'Quote', 'Loss', 'Emi', 'Charges')
# money names the suffix rule does not catch
MONEY_EXTRA = {'energyRate', 'entitlementOverride', 'dividendPerShare', 'estimatedPatientShare', 'centreShare',
               'stateShare', 'fundsAvailable', 'budgetAvailable', 'ldRecoverable', 'eraPatientResponsibility',
               'jointApprovalThreshold', 'committeeThreshold', 'crilcThreshold', 'ppdrThreshold'}
# names the suffix rule catches that are not money (with the unit they are in)
NOT_MONEY = {
    'leaveBalance': 'days of leave',
    'escalationIndex': 'per cent',
    'totalLoss': 'flag (boolean): the vehicle is a total loss',
    'criticalValue': 'flag (boolean): a critical laboratory value is present',
    'noMonetaryLimit': 'flag (boolean): the power has no money ceiling',
    'eligibleForPension': 'flag (boolean): pension (not only gratuity) is due',
}
_SUFFIX = re.compile('(?:^|[a-z0-9])(' + '|'.join(MONEY_SUFFIXES) + ')$')


def is_money(name):
    """True when the variable `name` is money under the naming convention (module doc)."""
    n = str(name)
    if n in NOT_MONEY:
        return False
    if n in MONEY_EXTRA:
        return True
    low = n[0].upper() + n[1:] if n else n
    return bool(_SUFFIX.search(low)) or n.lower() in {s.lower() for s in MONEY_SUFFIXES}


def convention():
    """The naming convention as data (written to META-INF/sangam/money.json for the Java build guard)."""
    return {'unit': 'MINOR', 'exponent': EXPONENT, 'defaultCurrency': DEFAULT_CURRENCY,
            'suffixes': list(MONEY_SUFFIXES), 'extra': sorted(MONEY_EXTRA), 'notMoney': dict(sorted(NOT_MONEY.items()))}


def minor(major, currency=DEFAULT_CURRENCY):
    """Exact conversion of an amount in major units (int, str or Decimal; never a float) to integer minor units.
    Raises when the amount is finer than the currency's minor unit (never rounds: SD-398)."""
    if isinstance(major, float):
        raise TypeError(f'money {major!r}: write the amount as int / str / Decimal, never a binary float')
    d = Decimal(str(major).replace(',', '').replace('_', ''))
    m = d.scaleb(EXPONENT[currency])
    if m != m.to_integral_value():
        raise ValueError(f'money {major!r} {currency}: finer than the minor unit')
    return int(m)


def M(major, currency=DEFAULT_CURRENCY):
    """The FEEL literal (minor units) of an amount written in major units, for use inside expressions."""
    return str(minor(major, currency))


_NUM = re.compile(r'(?<![\w.])-?\d+(?:\.\d+)?(?![\w.])')
_WORDS = re.compile(r'[A-Za-z_]\w*')
_TEST_WORDS = {'null', 'not', 'true', 'false'}
ROUNDING = ('floor(', 'ceiling(', 'decimal(', 'round half up(', 'round half down(', 'round up(', 'round down(')


def is_literal(entry):
    """A unary test or output with no variable reference (only numbers, comparators, ranges, lists, '-')."""
    return not [w for w in _WORDS.findall(str(entry)) if w not in _TEST_WORDS]


def convert_literal(entry, currency=DEFAULT_CURRENCY):
    """Every number of a literal money entry, major to minor units."""
    return _NUM.sub(lambda m: M(m.group(0), currency), str(entry))


def column_entry(entry, where, output, currency=DEFAULT_CURRENCY):
    """The shipped text of one entry of a money DMN column (`where` names it in errors)."""
    s = str(entry).strip()
    if s in ('', '-'):
        return s
    if is_literal(s):
        return convert_literal(s, currency)
    # an expression: its amounts are written with M(); an output rounds once to a whole minor unit
    if output and not s.startswith(ROUNDING) and not re.fullmatch(r'[A-Za-z_]\w*', s):
        raise ValueError(f'{where}: money output expression {s!r} must round once to a whole minor unit '
                         f'(wrap it in floor( / ceiling( / decimal(x, 0) ...) or be a single money variable')
    return s


def convert_assignments(spec, where, currency=DEFAULT_CURRENCY):
    """`name=value;...` (SetVariablesDelegate): a numeric value of a money name, major to minor units."""
    if not spec:
        return spec
    out = []
    for pair in spec.split(';'):
        k, eq, v = pair.partition('=')
        if eq and is_money(k.strip()) and re.fullmatch(r'-?\d+(?:\.\d+)?', v.strip()):
            v = M(v.strip(), currency)
        out.append(k + eq + v)
    return ';'.join(out)


def convert_run(run, where, currency=DEFAULT_CURRENCY):
    """An acceptance run (acceptance_runs.py): the money answers of each task, major to minor units."""
    out = dict(run)
    if 'answers' in out:
        out['answers'] = {t: dict(a, vars=convert_vars(a.get('vars'), f'{where} {t}', currency)) if 'vars' in a else a
                          for t, a in out['answers'].items()}
    return out


def convert_vars(vars, where, currency=DEFAULT_CURRENCY):
    """Acceptance / start variables: a money value, major to minor units (an int, never a float)."""
    out = {}
    for k, v in (vars or {}).items():
        if is_money(k) and isinstance(v, (int, float, str, Decimal)) and not isinstance(v, bool) \
                and re.fullmatch(r'-?[\d,_]+(?:\.\d+)?', str(v)):
            if isinstance(v, float):
                raise TypeError(f'{where}: money variable {k} = {v!r} is a binary float; write int / str')
            v = minor(v, currency)
        out[k] = v
    return out
