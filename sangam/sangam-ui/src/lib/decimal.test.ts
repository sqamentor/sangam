// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
// Decision 138, UI part: decimal text in, the same text out; no float step anywhere. Written, not run.
import { describe, expect, it } from 'vitest';
import { CANONICAL_DECIMAL_TEXT, DecimalValue, canonicalDecimalOrUndefined, canonicalDecimalText, compareDecimal, decimalPlaces, decimalText, formatDecimalText, fromNumberInput, parts, toPlainText, withinScale } from './decimal';
import { canonicalPacket, plainDecimal, type CapturePacket } from './offline/canonical';
import { capturedAnswers, numberProblem, numberProblemText } from '../areas/tasklist/MyWork';
import { fromVariableMap, toVariableMap } from '../api/adapters';
import { validateForm } from '../components/FormRenderer';

describe('DecimalValue', () => {
  it('keeps the text exactly, including the scale, and serialises it as a JSON string', () => {
    const d = new DecimalValue('10.10');
    expect(String(d)).toBe('10.10');
    expect(JSON.stringify({ a: d })).toBe('{"a":"10.10"}');
    expect(new DecimalValue('12345678901234567890.123456789').text).toBe('12345678901234567890.123456789');
    expect(new DecimalValue('1E+3').text).toBe('1E+3');
  });

  it('refuses text that is not a decimal', () => {
    for (const bad of ['', 'abc', '1,5', 'NaN', 'Infinity', '1.2.3', '0x10']) expect(() => new DecimalValue(bad)).toThrow(RangeError);
  });
});

describe('parts, plain text and comparison', () => {
  it('splits sign, digits and scale', () => {
    expect(parts('-12.340')).toEqual({ negative: true, digits: 12340n, scale: 3 });
    expect(parts('1.5E+2')).toEqual({ negative: false, digits: 15n, scale: -1 });
    expect(parts('-0.0')).toEqual({ negative: false, digits: 0n, scale: 1 });
  });

  it('writes plain notation keeping the scale', () => {
    expect(toPlainText('1.50E+2')).toBe('150');
    expect(toPlainText('1.5E-3')).toBe('0.0015');
    expect(toPlainText('-7.25')).toBe('-7.25');
  });

  it('compares exactly where doubles cannot', () => {
    expect(compareDecimal('0.1', '0.10')).toBe(0);
    expect(compareDecimal('9007199254740993', '9007199254740992')).toBe(1);
    expect(compareDecimal('0.30000000000000001', '0.3')).toBe(1);
    expect(compareDecimal('-1.5', '-1.49')).toBe(-1);
    expect(compareDecimal(new DecimalValue('10.10'), 10.1)).toBe(0);
    expect(compareDecimal('0.6', 0.5)).toBe(1);
  });

  it('decimalText accepts decimals and safe integers only', () => {
    expect(decimalText('  2.50 ')).toBe('2.50');
    expect(decimalText(42)).toBe('42');
    expect(decimalText(0.1)).toBeUndefined();
    expect(decimalText('x')).toBeUndefined();
  });
});

describe('number input without a float step', () => {
  it('keeps fractions and large whole numbers as typed', () => {
    expect(fromNumberInput('')).toBeUndefined();
    expect(fromNumberInput('42')).toBe(42);
    expect(fromNumberInput('10.10')).toEqual(new DecimalValue('10.10'));
    expect((fromNumberInput('10.10') as DecimalValue).text).toBe('10.10');
    expect(fromNumberInput('.5')).toEqual(new DecimalValue('0.5'));
    const big = fromNumberInput('12345678901234567890') as DecimalValue;
    expect(big).toBeInstanceOf(DecimalValue);
    expect(big.type).toBe('BigInteger');
    expect(big.text).toBe('12345678901234567890');
    expect(fromNumberInput('-')).toBe('-');
  });
});

describe('formatting from the digits', () => {
  it('formats with the locale separators and never rounds', () => {
    expect(formatDecimalText('1234567.891', 'en-US')).toBe('1,234,567.891');
    expect(formatDecimalText('12345678901234567890.12', 'en-US')).toBe('12,345,678,901,234,567,890.12');
    expect(formatDecimalText('-0.5', 'en-US')).toBe('-0.5');
    expect(formatDecimalText('1234567.5', 'en-IN')).toBe('12,34,567.5');
  });

  it('pads to the currency minor unit but never cuts digits', () => {
    expect(formatDecimalText('1234.5', 'en-IN', 'INR')).toBe('₹1,234.50');
    expect(formatDecimalText('1234.5678', 'en-IN', 'INR')).toBe('₹1,234.5678');
    expect(formatDecimalText('-10', 'en-US', 'USD')).toBe('-$10.00');
    expect(formatDecimalText('1000', 'en-US', 'JPY')).toBe('¥1,000');
  });
});

describe('variables round trip (R33 TypedValue)', () => {
  it('a Decimal variable comes in as DecimalValue and goes back as the same Decimal text', () => {
    const vars = fromVariableMap({ amount: { type: 'Decimal', value: '1000000000000000.10' }, n: { type: 'BigInteger', value: '123456789012345678901' } });
    expect(vars.amount).toBeInstanceOf(DecimalValue);
    expect(toVariableMap(vars)).toEqual({
      amount: { type: 'Decimal', value: '1000000000000000.10' },
      n: { type: 'BigInteger', value: '123456789012345678901' },
    });
  });

  it('never turns decimal text into a JS number', () => {
    const vars = fromVariableMap({ rate: { type: 'Decimal', value: '0.1' } });
    expect(typeof vars.rate).not.toBe('number');
  });
});

describe('form bounds on the digits', () => {
  const t = (k: string) => k;
  const schema = { components: [{ key: 'amt', type: 'number', label: 'Amount', validate: { min: 0, max: 10.1 } }] } as never;
  it('10.10 is within max 10.1 and 10.100000000000000001 is not', () => {
    expect(validateForm(schema, { amt: new DecimalValue('10.10') }, t)).toEqual({});
    expect(validateForm(schema, { amt: new DecimalValue('10.100000000000000001') }, t)).toEqual({ amt: 'form.max' });
    expect(validateForm(schema, { amt: new DecimalValue('-0.01') }, t)).toEqual({ amt: 'form.min' });
  });
});

describe('decision 212: MyWork numbers as canonical decimal text', () => {
  it('writes BigDecimal.stripTrailingZeros().toPlainString(), zero as 0', () => {
    expect(canonicalDecimalText('12.50')).toBe('12.5');
    expect(canonicalDecimalText('100')).toBe('100');
    expect(canonicalDecimalText('+007.250')).toBe('7.25');
    expect(canonicalDecimalText('-.5')).toBe('-0.5');
    expect(canonicalDecimalText('3.')).toBe('3');
    expect(canonicalDecimalText('-0.000')).toBe('0');
    expect(canonicalDecimalText('1.50E+2')).toBe('150');
    expect(canonicalDecimalText('12345678901234567890.1234567890')).toBe('12345678901234567890.123456789');
    expect(canonicalDecimalOrUndefined('1e5x')).toBeUndefined();
    expect(canonicalDecimalOrUndefined('-')).toBeUndefined();
    expect(canonicalDecimalOrUndefined('')).toBeUndefined();
  });

  it('is the text PacketHasher already writes for a JSON number, so the hash input does not change', () => {
    for (const n of [0, 1, -1, 2.5, 0.1, 100, 1234.5678, -0.001, 1e21, 5e-7]) expect(canonicalDecimalText(String(n))).toBe(plainDecimal(n));
    const packet = (q: unknown): CapturePacket => ({ type: 'VISIT_SUBMIT', operationId: 'op', deviceId: 'd', deviceSeq: 1, answers: { q } });
    expect(canonicalPacket(packet(canonicalDecimalText('2.50')))).toBe(canonicalPacket(packet(2.5)));
  });

  it('capturedAnswers turns NUMBER answers into canonical text, leaves other answers alone and reports bad numbers', () => {
    const items = [
      { id: 'n', type: 'NUMBER' as const },
      { id: 'x', type: 'TEXT' as const },
      { id: 'b', type: 'NUMBER' as const },
    ];
    expect(capturedAnswers(items, { n: '0010.10', x: '007', b: undefined })).toEqual({ answers: { n: '10.1', x: '007' }, invalid: [] });
    expect(capturedAnswers(items, { n: '1,5' }).invalid).toEqual(['n']);
    expect(typeof capturedAnswers(items, { n: '0.1' }).answers.n).toBe('string');
  });
});

describe('owner decision 242: NUMBER scale, exact', () => {
  it('counts decimal places on the canonical form, as FieldTemplate.Item.decimalPlaces does', () => {
    expect(decimalPlaces('12.50')).toBe(1);
    expect(decimalPlaces('100')).toBe(0);
    expect(decimalPlaces('0')).toBe(0);
    expect(decimalPlaces('0.000')).toBe(0);
    expect(decimalPlaces('1E+2')).toBe(0);
    expect(decimalPlaces('1.5E-3')).toBe(4);
    expect(decimalPlaces('-0.0000000001')).toBe(10);
    expect(decimalPlaces('123456789012345678901234567890.1234567890')).toBe(9);
    expect(() => decimalPlaces('1,5')).toThrow(RangeError);
  });

  it('withinScale: no scale means no extra limit beyond canonical decimal text', () => {
    expect(withinScale('0.12345678901234567890123', undefined)).toBe(true);
    expect(withinScale('0.12345678901234567890123', null)).toBe(true);
    expect(withinScale('12.50', 1)).toBe(true);
    expect(withinScale('12.55', 1)).toBe(false);
    expect(withinScale('7.0', 0)).toBe(true);
    expect(withinScale('7.01', 0)).toBe(false);
  });

  it('the canonical bound pattern is the contract one (decision 230)', () => {
    for (const ok of ['0', '12.5', '-3', '0.001', '-0.5', '100']) expect(CANONICAL_DECIMAL_TEXT.test(ok), ok).toBe(true);
    for (const bad of ['12.50', '+3', '-0', '00', '1E+2', '.5', '5.', '', '0.0']) expect(CANONICAL_DECIMAL_TEXT.test(bad), bad).toBe(false);
  });

  it('numberProblem checks scale and min..max with exact decimals (no float rounding)', () => {
    const item = { scale: 2, min: '0.01', max: '99999999999999999.99' };
    expect(numberProblem(item, '99999999999999999.99')).toBeUndefined();
    // as doubles both sides are 1e17, so a float compare would let this through
    expect(numberProblem(item, '100000000000000000')).toEqual({ code: 'max', bound: '99999999999999999.99' });
    expect(numberProblem(item, '0.009')).toEqual({ code: 'scale', scale: 2 });
    expect(numberProblem(item, '0.00')).toEqual({ code: 'min', bound: '0.01' });
    expect(numberProblem(item, '12.50')).toBeUndefined();
    expect(numberProblem(item, 'twelve')).toEqual({ code: 'notANumber' });
    expect(numberProblem({ scale: 0 }, '3.0')).toBeUndefined();
    expect(numberProblem({ scale: 0 }, '3.1')).toEqual({ code: 'scale', scale: 0 });
    expect(numberProblem({}, '0.30000000000000000000000001')).toBeUndefined();
    expect(numberProblem({ min: '0.1', max: '0.3' }, '0.30000000000000000000000001')).toEqual({ code: 'max', bound: '0.3' });
  });

  it('numberProblemText says what is wrong', () => {
    const t = (k: string, o?: Record<string, unknown>) => (o ? `${k} ${JSON.stringify(o)}` : k);
    expect(numberProblemText({ code: 'scale', scale: 0 }, t)).toBe('form.wholeNumber');
    expect(numberProblemText({ code: 'scale', scale: 2 }, t)).toBe('form.tooManyPlaces {"n":2}');
    expect(numberProblemText({ code: 'min', bound: '0.01' }, t)).toBe('form.min {"n":"0.01"}');
  });

  it('capturedAnswers refuses an answer over the scale or out of range and sends the rest as canonical text', () => {
    const items = [
      { id: 'amt', type: 'NUMBER' as const, scale: 2, min: '0', max: '1000' },
      { id: 'cnt', type: 'NUMBER' as const, scale: 0 },
    ];
    expect(capturedAnswers(items, { amt: '12.50', cnt: '4.0' })).toEqual({ answers: { amt: '12.5', cnt: '4' }, invalid: [] });
    expect(capturedAnswers(items, { amt: '12.505', cnt: '4' }).invalid).toEqual(['amt']);
    expect(capturedAnswers(items, { amt: '1000.01', cnt: '4.5' }).invalid).toEqual(['amt', 'cnt']);
  });
});
