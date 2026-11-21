# SPDX-License-Identifier: Apache-2.0
"""Money in packs (SD-398 / SG-20, ADR-D-26): integer minor units of the pack currency (paise for INR, cents for USD).

The one place packgen turns an amount into what a pack ships. Pack definitions are written in MAJOR units (rupees, as
the rules and delegations state them: 25 lakh is 2500000); the generator emits integer MINOR units (250000000) for:

- a DMN column typed ``'money'`` (lib.dmn): every literal of its input tests and outputs; an output that is an
  expression gives the EXACT figure and must not round money (DEC-5: ``floor(`` / ``ceiling(`` / ``decimal(`` /
  ``round ...(`` around a money figure is refused); the Java money step uses it only when it is a whole minor unit
  and otherwise holds the case for a reviewer-entered amount (rounding rule not configured);
- ``setv`` values / defaults, acceptance ``vars`` and start defaults whose variable name is money (``is_money``);
- an amount inside a FEEL expression or condition: write ``M(9000)`` (returns the minor-unit literal);
- an amount inside a simulator directive of an acceptance scenario (``sim_ifms_budget = 'available=310000'``) when
  the variable it lands in is money (``convert_directive``).

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
               'jointApprovalThreshold', 'committeeThreshold', 'crilcThreshold', 'ppdrThreshold',
               # reviewer-entered payable amounts of op EXACT (DEC-5)
               'ldAmountEntered', 'escalationAmountEntered', 'pensionAmountEntered',
               'retirementGratuityEntered', 'familyPensionEntered'}
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


def major(minor_units, currency):
    """Exact inverse of ``minor``: an int of minor units (how a profile ships money, SL-102) as the major-unit text a
    pack definition is authored in, so a pack can take an amount from profile data. No default currency; never rounds."""
    if isinstance(minor_units, bool) or not isinstance(minor_units, int):
        raise TypeError(f'money {minor_units!r}: minor units are an int')
    text = format(Decimal(minor_units).scaleb(-EXPONENT[currency]), 'f')
    if '.' in text:
        text = text.rstrip('0').rstrip('.')
    assert minor(text, currency) == minor_units, (minor_units, text)
    return text


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
    # an expression: its amounts are written with M(); an output is exact and never rounds money (DEC-5)
    if output:
        for call in rounding_calls(s):
            if any(is_money(w) for w in _WORDS.findall(call)):
                raise ValueError(f'{where}: money output expression {s!r} rounds money ({call!r}); DEC-5: the DMN '
                                 f'gives the exact figure and the Java money step decides (hold when not a whole '
                                 f'minor unit)')
    return s


def rounding_calls(expr):
    """The argument text of every rounding call (``ROUNDING``) in `expr`, string literals removed."""
    t = re.sub(r'"(?:[^"\\]|\\.)*"', '""', str(expr))
    out = []
    for m in re.finditer(r'(?<![\w.])(' + '|'.join(re.escape(r[:-1]) for r in ROUNDING) + r')\s*\(', t):
        depth, i = 1, m.end()
        while i < len(t) and depth:
            depth += {'(': 1, ')': -1}.get(t[i], 0)
            i += 1
        out.append(t[m.end():i - 1])
    return out


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
    if 'steps' in out:  # SL-32: a step's variables (a change by another actor) are money like the answers
        out['steps'] = [dict(st, vars=convert_vars(st['vars'], f'{where} step {i + 1} {st["at"]}', currency))
                        if 'vars' in st else st for i, st in enumerate(out['steps'])]
    return out


SIM_PREFIX = 'sim_'


def sim_var(connector):
    """The simulator directive variable of a connector (Vars.simVar: sim_<connector, dots and dashes as _>)."""
    return SIM_PREFIX + connector.replace('.', '_').replace('-', '_')


def convert_directive(value, prefixes, where, currency=DEFAULT_CURRENCY):
    """A simulator directive ``key=value;...`` (its query / effect data): a number that lands in a money variable is
    major to minor units, like every other amount a pack definition writes. The variable is ``<prefix><Key>`` for
    each result prefix the pack calls the connector with (ConnectorCallDelegate); with no call, the key itself.
    A key that would be money under one prefix and not under another fails the generation (never guessed)."""
    if not isinstance(value, str):
        return value
    alts = parse_directive(value, where)
    if len(alts) == 1 and alts[0][1] is None:
        return _convert_pairs(value, prefixes, where, currency)
    return ' || '.join(_convert_pairs(base, prefixes, where, currency) + (' @ ' + sel if sel is not None else '')
                       for base, sel in alts)


SELECT = ' @ '
OR = '||'


def parse_directive(value, where):
    """The simulator directive grammar (SimulatorConnectorGateway.selected): alternatives separated by ``||``, each
    ``<directive>`` or ``<directive> @ <selector>|<selector>...``; a selector is ``field=value`` (a payload field) or
    a business-key suffix. Returns [(directive, selectors text or None)]; a malformed one fails the generation."""
    out = []
    for alt in value.split(OR):
        a = alt.strip()
        if not a:
            raise ValueError(f'{where}: simulator directive {value!r} has an empty alternative')
        padded = ' ' + a + ' '
        at = padded.find(SELECT)
        if at < 0:
            out.append((a, None))
            continue
        base, sel = padded[:at].strip(), padded[at + len(SELECT):].strip()
        tokens = [t.strip() for t in sel.split('|')] if sel else []
        if not tokens or any(not t for t in tokens):
            raise ValueError(f'{where}: simulator directive {value!r} has an empty selector')
        out.append((base, '|'.join(tokens)))
    return out


def _convert_pairs(value, prefixes, where, currency):
    if '=' not in value:
        return value
    out = []
    for pair in value.split(';'):
        k, eq, v = pair.partition('=')
        key = k.strip()
        if eq and key and re.fullmatch(r'-?[\d,_]+(?:\.\d+)?', v.strip()):
            targets = {p + key[0].upper() + key[1:] for p in prefixes} or {key}
            flags = {is_money(t) for t in targets}
            if len(flags) > 1:
                raise ValueError(f'{where}: directive key {key!r} lands in {sorted(targets)}, money and not money')
            if flags == {True}:
                v = M(v.strip(), currency)
        out.append(k + eq + v)
    return ';'.join(out)


def convert_sim_vars(vars, conn_prefixes, where, currency=DEFAULT_CURRENCY):
    """Every simulator directive among start variables (``sim_<connector>``), see ``convert_directive``."""
    by_var = {}
    for c, ps in conn_prefixes.items():
        by_var.setdefault(sim_var(c), set()).update(ps)
    return {k: convert_directive(v, by_var.get(k, set()), f'{where} {k}', currency) if k.startswith(SIM_PREFIX) else v
            for k, v in (vars or {}).items()}


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
