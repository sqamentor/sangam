// SPDX-License-Identifier: Apache-2.0
// R33 typed values: the validating regular expressions are anchored and linear, and money stays a string.
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { isTypedValue, TypedValues } from '../src/typed.js';

describe('TypedValues validation', () => {
  it('decimal accepts exact decimal strings and keeps them as strings', () => {
    for (const s of ['0', '12.50', '-0.01', '+7', '.5', '5.', '1e3', '1.25E-2']) {
      assert.deepEqual(TypedValues.decimal(s), { type: 'Decimal', value: s });
    }
  });

  it('decimal rejects anything that is not a decimal number', () => {
    for (const s of ['', '12,50', '1.2.3', 'abc', '1e', '--1', ' 1', '1 ', 'NaN', 'Infinity', '1_000']) {
      assert.throws(() => TypedValues.decimal(s), RangeError, s);
    }
  });

  it('decimal validation is linear on long near-miss input', () => {
    const s = '1'.repeat(100_000) + 'x';
    const t0 = Date.now();
    assert.throws(() => TypedValues.decimal(s), RangeError);
    assert.ok(Date.now() - t0 < 1_000);
  });

  it('localDate accepts YYYY-MM-DD only', () => {
    assert.deepEqual(TypedValues.localDate('2026-03-31'), { type: 'LocalDate', value: '2026-03-31' });
    for (const s of ['2026-3-31', '20260331', '2026-03-31T00:00:00Z', ' 2026-03-31', '2026-03-31\n']) {
      assert.throws(() => TypedValues.localDate(s), RangeError, JSON.stringify(s));
    }
  });

  it('integer and long ranges are enforced', () => {
    assert.throws(() => TypedValues.integer(2 ** 31), RangeError);
    assert.throws(() => TypedValues.integer(1.5), RangeError);
    assert.throws(() => TypedValues.long(2n ** 63n), RangeError);
    assert.deepEqual(TypedValues.long(-(2n ** 63n)), { type: 'Long', value: '-9223372036854775808' });
  });

  it('isTypedValue recognises only {type, value} with a known type', () => {
    assert.equal(isTypedValue({ type: 'Decimal', value: '1' }), true);
    assert.equal(isTypedValue({ type: 'Float', value: 1 }), false);
    assert.equal(isTypedValue({ type: 'Decimal', value: '1', extra: 1 }), false);
    assert.equal(isTypedValue(['Decimal', '1']), false);
  });
});
