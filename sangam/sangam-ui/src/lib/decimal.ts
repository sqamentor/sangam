// SPDX-License-Identifier: Apache-2.0
/**
 * Owner decision 138 (exact decimals end to end), UI part: a decimal reaches the UI as text and leaves it as the same
 * text. It is never parsed into a JavaScript number (an IEEE double), so 0.1, 12345678901234567.89 or "10.10" keep every
 * digit, including trailing zeros that carry the scale. Comparison and formatting work on the digits with BigInt.
 *
 * - DecimalValue wraps the text. JSON.stringify writes the text as a JSON string (toJSON), String() gives the text,
 *   and toVariableMap sends it as an R33 TypedValue of its type ('Decimal', or 'BigInteger' for whole numbers that
 *   arrived as BigInteger). Which engine type id stores it is the server's business (decision 138, W4-E/W4-M).
 * - Accepted text: an optional sign, digits, an optional fraction, and an optional exponent (BigDecimal.toString can
 *   answer 1E+3). Anything else is not a decimal and is refused, never guessed.
 */

/** Plain or scientific decimal text: what BigDecimal.toString and toPlainString can produce, plus a leading '+'. */
export const DECIMAL_TEXT = /^[+-]?\d+(\.\d+)?([eE][+-]?\d+)?$/;
/** What a person may type: plain decimal text with an optional sign and fraction (no exponent). */
export const PLAIN_DECIMAL_TEXT = /^[+-]?(\d+(\.\d*)?|\.\d+)$/;

export type DecimalType = 'Decimal' | 'BigInteger';

export class DecimalValue {
  readonly text: string;
  readonly type: DecimalType;
  constructor(text: string, type: DecimalType = 'Decimal') {
    const s = String(text).trim();
    if (!DECIMAL_TEXT.test(s)) throw new RangeError(`not a decimal: ${JSON.stringify(text)}`);
    this.text = s;
    this.type = type;
  }
  toString(): string {
    return this.text;
  }
  toJSON(): string {
    return this.text;
  }
}

export function isDecimalValue(v: unknown): v is DecimalValue {
  return v instanceof DecimalValue;
}

/** The value as decimal text when it is one (DecimalValue, decimal string, safe integer); otherwise undefined. */
export function decimalText(v: unknown): string | undefined {
  if (v instanceof DecimalValue) return v.text;
  if (typeof v === 'string') return DECIMAL_TEXT.test(v.trim()) ? v.trim() : undefined;
  if (typeof v === 'number' && Number.isSafeInteger(v)) return String(v);
  if (typeof v === 'bigint') return v.toString();
  return undefined;
}

/** Sign, digits and scale of decimal text: value = sign * digits * 10^-scale (scale may be negative). */
export interface DecimalParts {
  negative: boolean;
  digits: bigint;
  scale: number;
}

export function parts(text: string): DecimalParts {
  const s = text.trim();
  if (!DECIMAL_TEXT.test(s)) throw new RangeError(`not a decimal: ${JSON.stringify(text)}`);
  const negative = s.startsWith('-');
  const body = s.replace(/^[+-]/, '');
  const [mant, exp = '0'] = body.split(/[eE]/);
  const [int, frac = ''] = mant.split('.');
  const digits = BigInt(int + frac);
  return { negative: negative && digits !== 0n, digits, scale: frac.length - Number(exp) };
}

/** Exact comparison of two decimals given as text or safe numbers: -1, 0 or 1. */
export function compareDecimal(a: string | number | DecimalValue, b: string | number | DecimalValue): -1 | 0 | 1 {
  const ta = decimalText(a);
  const tb = decimalText(b);
  if (ta === undefined || tb === undefined) {
    // a non-integer JS number (a schema min/max such as 0.5): its shortest round-trip text is exact for that double
    const fa = ta ?? (typeof a === 'number' && Number.isFinite(a) ? plainText(a) : undefined);
    const fb = tb ?? (typeof b === 'number' && Number.isFinite(b) ? plainText(b) : undefined);
    if (fa === undefined || fb === undefined) throw new RangeError('not comparable as decimals');
    return compareDecimal(fa, fb);
  }
  const pa = parts(ta);
  const pb = parts(tb);
  const scale = Math.max(pa.scale, pb.scale);
  const va = (pa.negative ? -1n : 1n) * pa.digits * 10n ** BigInt(scale - pa.scale);
  const vb = (pb.negative ? -1n : 1n) * pb.digits * 10n ** BigInt(scale - pb.scale);
  return va < vb ? -1 : va > vb ? 1 : 0;
}

/** A finite double's shortest round-trip text in plain notation (for schema bounds written as JSON numbers). */
function plainText(n: number): string {
  const s = String(n);
  if (!/[eE]/.test(s)) return s;
  const p = parts(s);
  return toPlain(p);
}

/** Decimal text in plain notation (no exponent), keeping the scale: 1.50E+2 -> 150, 1.5E-3 -> 0.0015. */
export function toPlainText(text: string): string {
  return toPlain(parts(text));
}

function toPlain(p: DecimalParts): string {
  let digits = p.digits.toString();
  let int: string;
  let frac = '';
  if (p.scale <= 0) {
    int = digits + '0'.repeat(-p.scale);
  } else {
    if (digits.length <= p.scale) digits = '0'.repeat(p.scale - digits.length + 1) + digits;
    int = digits.slice(0, digits.length - p.scale);
    frac = digits.slice(digits.length - p.scale);
  }
  return `${p.negative ? '-' : ''}${int}${frac ? `.${frac}` : ''}`;
}

/**
 * Owner decision 212: the canonical decimal text of a typed number, as Java writes
 * new BigDecimal(text).stripTrailingZeros().toPlainString() (zero as "0"). It is exactly the text the server's
 * PacketHasher already hashes for a JSON number (sangam-field evidence/PacketHasher.normalise), so a capture packet
 * hashes the same whether a number travels as JSON or as this text. Accepts what a person types (optional sign,
 * leading or trailing point) and BigDecimal.toString output; anything else throws RangeError.
 */
export function canonicalDecimalText(text: string): string {
  let s = String(text).trim();
  if (PLAIN_DECIMAL_TEXT.test(s)) s = s.replace(/^\+/, '').replace(/^(-?)\./, '$10.').replace(/\.$/, '');
  const p = parts(s);
  if (p.digits === 0n) return '0';
  let { digits, scale } = p;
  while (digits % 10n === 0n) {
    digits /= 10n;
    scale -= 1;
  }
  return toPlain({ negative: p.negative, digits, scale });
}

/** canonicalDecimalText, or undefined when the text is not a number. */
export function canonicalDecimalOrUndefined(text: string): string | undefined {
  try {
    return canonicalDecimalText(text);
  } catch {
    return undefined;
  }
}

/**
 * The value of a number input, without a float step: '' is no value; a whole number within the safe range stays a
 * number (sent as Long as before); a fraction or a larger whole number becomes a DecimalValue holding the digits that
 * were typed: leading zeros of the whole part carry no value and are dropped ("007.50" is 7.50, as "007" is 7), while
 * trailing zeros of the fraction carry the scale and stay (decision 138). Text that is not a number (a half-typed "-")
 * is kept as text so validation can say so.
 */
export function fromNumberInput(raw: string): number | DecimalValue | string | undefined {
  const s = raw.trim();
  if (s === '') return undefined;
  if (!PLAIN_DECIMAL_TEXT.test(s)) return s;
  const normalised = s
    .replace(/^\+/, '')
    .replace(/^(-?)\./, '$10.')
    .replace(/\.$/, '')
    .replace(/^(-?)0+(?=\d)/, '$1');
  if (/^-?\d+$/.test(normalised)) {
    const n = Number(normalised);
    if (Number.isSafeInteger(n) && String(n) === normalised) return n;
    return new DecimalValue(normalised, 'BigInteger');
  }
  return new DecimalValue(normalised);
}

/**
 * Owner decision 230: the canonical decimal text of a FieldTemplate NUMBER bound, as the contract pattern of
 * FieldTemplateItem min and max says (stripTrailingZeros().toPlainString(), zero as "0").
 */
export const CANONICAL_DECIMAL_TEXT = /^(0|-?(0\.[0-9]*[1-9]|[1-9][0-9]*(\.[0-9]*[1-9])?))$/;

/** Owner decision 242: the allowed range of a NUMBER item's scale (FieldTemplate.Item MIN_SCALE..MAX_SCALE). */
export const MIN_NUMBER_SCALE = 0;
export const MAX_NUMBER_SCALE = 10;

/**
 * Owner decision 242: decimal places of a number counted on its canonical form, as FieldTemplate.Item.decimalPlaces
 * does (12.50 has 1, 100 and 0 have 0, 1E+2 has 0). Throws RangeError for text that is not a number.
 */
export function decimalPlaces(text: string): number {
  const c = canonicalDecimalText(text);
  const dot = c.indexOf('.');
  return dot < 0 ? 0 : c.length - dot - 1;
}

/** Owner decision 242: whether a number has no more decimal places than scale allows (always true without a scale). */
export function withinScale(text: string, scale: number | null | undefined): boolean {
  return scale === null || scale === undefined || decimalPlaces(text) <= scale;
}

/** Contract MoneyAmount (decision 211): a plain decimal string in major units, no sign other than '-', no exponent. */
export const MONEY_AMOUNT = /^-?\d+(\.\d+)?$/;

/**
 * Decimal text as Intl.NumberFormat parts of `nf`, exactly: the integer part goes through formatToParts as a BigInt,
 * the fraction digits are the text's own (padded to the format's minimum fraction digits, never cut or rounded). The
 * caller joins the parts, or regroups them first. Throws RangeError for text that is not decimal.
 */
export function decimalParts(text: string, nf: Intl.NumberFormat, decimalSep = '.'): Intl.NumberFormatPart[] {
  const p = parts(text);
  const plain = toPlain({ ...p, negative: false });
  const [int, frac = ''] = plain.split('.');
  const minFrac = nf.resolvedOptions().minimumFractionDigits ?? 0;
  const fraction = frac.length >= minFrac ? frac : frac + '0'.repeat(minFrac - frac.length);
  const whole = BigInt(int);
  // a negative amount below one major unit: format -1 for the sign, then write 0 as the integer
  const signOnly = p.negative && whole === 0n;
  const out: Intl.NumberFormatPart[] = [];
  let fracWritten = false;
  const src = nf.formatToParts(signOnly ? -1n : p.negative ? -whole : whole);
  for (let i = 0; i < src.length; i++) {
    const part = src[i];
    if (part.type === 'integer' && signOnly) {
      out.push({ type: 'integer', value: '0' });
    } else if (part.type === 'decimal') {
      if (fraction) out.push(part);
    } else if (part.type === 'fraction') {
      if (fraction) out.push({ type: 'fraction', value: fraction });
      fracWritten = true;
    } else {
      out.push(part);
      const next = src[i + 1];
      const lastInteger = (part.type === 'integer' || part.type === 'group') && (!next || (next.type !== 'integer' && next.type !== 'group'));
      if (lastInteger && fraction && !src.some((x) => x.type === 'decimal')) {
        out.push({ type: 'decimal', value: decimalSep }, { type: 'fraction', value: fraction });
        fracWritten = true;
      }
    }
  }
  if (fraction && !fracWritten) out.push({ type: 'decimal', value: decimalSep }, { type: 'fraction', value: fraction });
  return out;
}

/**
 * Formats decimal text for display with the locale's separators, exactly: the integer part goes through
 * Intl.NumberFormat as a BigInt, the fraction digits are written as they are (no rounding, scale kept). With a
 * currency, the currency's minimum fraction digits are padded with zeros, never cut: an amount with more fraction
 * digits than the currency's minor unit is shown with all of them.
 */
export function formatDecimalText(text: string, locale: string, currency?: string): string {
  const p = parts(text);
  const plain = toPlain({ ...p, negative: false });
  const [int, frac = ''] = plain.split('.');
  const nf = currency
    ? new Intl.NumberFormat(locale, { style: 'currency', currency })
    : new Intl.NumberFormat(locale, { maximumFractionDigits: 0 });
  const minFrac = currency ? nf.resolvedOptions().minimumFractionDigits ?? 0 : 0;
  const fraction = frac.length >= minFrac ? frac : frac + '0'.repeat(minFrac - frac.length);
  const value = BigInt(int) * (p.negative ? -1n : 1n);
  // a negative zero integer part ("-0.5") needs the sign from formatting -1 then the digits of 0
  const signOnly = p.negative && value === 0n;
  const fmtParts = nf.formatToParts(signOnly ? -1n : value);
  const decimalSep = new Intl.NumberFormat(locale).formatToParts(1.5).find((x) => x.type === 'decimal')?.value ?? '.';
  let out = '';
  let fracWritten = false;
  for (let i = 0; i < fmtParts.length; i++) {
    const part = fmtParts[i];
    if (signOnly && part.type === 'integer') {
      out += '0';
      continue;
    }
    if (part.type === 'decimal') {
      if (fraction) out += part.value;
      continue;
    }
    if (part.type === 'fraction') {
      out += fraction;
      fracWritten = true;
      continue;
    }
    out += part.value;
    const next = fmtParts[i + 1];
    const lastInteger = (part.type === 'integer' || part.type === 'group') && (!next || (next.type !== 'integer' && next.type !== 'group'));
    if (lastInteger && fraction && !fmtParts.some((x) => x.type === 'decimal')) {
      out += decimalSep + fraction;
      fracWritten = true;
    }
  }
  if (fraction && !fracWritten) out += decimalSep + fraction;
  return out;
}
