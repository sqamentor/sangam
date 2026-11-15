// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
// M-045 foundations (owner decision Q-06, register SL-147): English default and fallback, locale-aware number and
// date formats, the currency from the record or the tenant locale (never a hard-coded INR). Written, not run.
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import i18n, { FALLBACK_LANG, fmtDate, fmtDuration, fmtMoney, fmtMoneyAmount, fmtNumber, initI18n, isCurrencyCode, locale, setTenantLocale, todayIso } from './index';

beforeAll(() => {
  initI18n('en');
});
beforeEach(() => {
  setTenantLocale(null);
  void i18n.changeLanguage('en');
});

describe('English default and fallback', () => {
  it('falls back to English', () => {
    expect(FALLBACK_LANG).toBe('en');
    expect(i18n.options.fallbackLng).toEqual(['en']);
  });
  it('shows English for a key another catalogue lacks', () => {
    i18n.addResource('en', 'translation', 'zzTest.onlyEnglish', 'Only in English');
    expect(i18n.getFixedT('hi')('zzTest.onlyEnglish')).toBe('Only in English');
  });
  it('shows English for an empty entry in another catalogue', () => {
    i18n.addResource('en', 'translation', 'zzTest.empty', 'English text');
    i18n.addResource('hi', 'translation', 'zzTest.empty', '');
    expect(i18n.getFixedT('hi')('zzTest.empty')).toBe('English text');
  });
});

describe('locale', () => {
  it('is the UI language with the reader region, never a fixed country', () => {
    const l = locale();
    expect(l.split('-')[0]).toBe('en');
    expect(Intl.getCanonicalLocales(l)).toEqual([l]);
    const region = [...navigator.languages, navigator.language].map((x) => new Intl.Locale(x)).find((x) => x.language === 'en')?.region;
    expect(l).toBe(region ? `en-${region}` : 'en');
  });
});

describe('fmtMoney', () => {
  it('assumes no currency: without one the amount is a plain number', () => {
    const s = fmtMoney(1234.5);
    expect(s).not.toMatch(/₹|INR|Rs/);
    expect(s.replace(/\D/g, '')).toBe('12345');
  });
  it('uses the tenant currency from /me locale', () => {
    setTenantLocale({ currency: 'gbp' });
    expect(fmtMoney(1234.5)).toMatch(/£/);
  });
  it('prefers the record currency over the tenant currency', () => {
    setTenantLocale({ currency: 'GBP' });
    const s = fmtMoney(10, 'EUR');
    expect(s).toMatch(/€/);
    expect(s).not.toMatch(/£/);
  });
  it('shows a non-ISO code next to the amount instead of failing', () => {
    expect(fmtMoney(5, 'TOKENS')).toMatch(/5.*TOKENS/);
  });
  it('ignores a malformed tenant currency', () => {
    setTenantLocale({ currency: '₹₹' });
    expect(fmtMoney(5)).not.toMatch(/₹/);
  });
  it('keeps the dash for no amount', () => {
    expect(fmtMoney(undefined)).toBe('—');
    expect(fmtMoney(Number.NaN)).toBe('—');
  });
});

describe('fmtMoneyAmount (M-042, decisions 138 and 211: MoneyAmount major-unit decimal text, never floating point)', () => {
  const nf = (cur: string) => new Intl.NumberFormat(locale(), { style: 'currency', currency: cur });
  it('shows major units with the currency minor digits: 2, 0 and 3', () => {
    expect(fmtMoneyAmount('1234.56', 'EUR')).toBe(nf('EUR').format(1234.56));
    expect(fmtMoneyAmount('1234', 'JPY')).toBe(nf('JPY').format(1234));
    expect(fmtMoneyAmount('1234.567', 'BHD')).toBe(nf('BHD').format(1234.567));
  });
  it('pads to the minor digits, keeps leading zeros of the fraction and handles zero', () => {
    expect(fmtMoneyAmount('0.05', 'EUR')).toBe(nf('EUR').format(0.05));
    expect(fmtMoneyAmount('12', 'EUR')).toBe(nf('EUR').format(12));
    expect(fmtMoneyAmount('0.00', 'EUR')).toBe(nf('EUR').format(0));
  });
  it('keeps the minus sign below one major unit and above it', () => {
    expect(fmtMoneyAmount('-0.05', 'EUR')).toBe(nf('EUR').format(-0.05));
    expect(fmtMoneyAmount('-1234.56', 'EUR')).toBe(nf('EUR').format(-1234.56));
  });
  it('is exact beyond the safe double range, where parsing the text into a number would lose digits', () => {
    const s = fmtMoneyAmount('90071992547409.93', 'EUR');
    expect(s.replace(/\D/g, '')).toBe('9007199254740993');
    expect(fmtMoneyAmount('123456789012345678901.99', 'EUR').replace(/\D/g, '')).toBe('12345678901234567890199');
  });
  it('never cuts fraction digits the server sent', () => {
    expect(fmtMoneyAmount('1.005', 'EUR')).toMatch(/1\.005\b/);
  });
  it('accepts a lower-case currency code from a record', () => {
    expect(fmtMoneyAmount('1.00', 'eur')).toBe(nf('EUR').format(1));
  });
  it('guesses nothing: without a valid currency, for a JSON number (the old int64 minor units) or for text that is not a MoneyAmount, the dash', () => {
    setTenantLocale({ currency: 'GBP' });
    expect(fmtMoneyAmount('100')).toBe('—');
    expect(fmtMoneyAmount('100', 'TOKENS')).toBe('—');
    expect(fmtMoneyAmount(123456 as unknown as string, 'EUR')).toBe('—');
    for (const bad of ['1e3', '+1', '1.', '.5', '1,000.00', ' 1', '']) expect(fmtMoneyAmount(bad, 'EUR')).toBe('—');
    expect(fmtMoneyAmount(null, 'EUR')).toBe('—');
    expect(fmtMoneyAmount(undefined, 'EUR')).toBe('—');
  });
  it('applies the tenant grouping {first: 3, rest: 2}', () => {
    setTenantLocale({ grouping: { first: 3, rest: 2 } });
    expect(fmtMoneyAmount('12345678.90', 'EUR')).toContain('1,23,45,678.90');
  });
  it('knows an ISO 4217 code only in the contract form', () => {
    expect(isCurrencyCode('INR')).toBe(true);
    expect(isCurrencyCode('inr')).toBe(false);
    expect(isCurrencyCode('IN')).toBe(false);
    expect(isCurrencyCode('')).toBe(false);
    expect(isCurrencyCode(undefined)).toBe(false);
  });
});

describe('fmtNumber grouping', () => {
  it('follows the locale by default', () => {
    expect(fmtNumber(1234567).replace(/\D/g, '')).toBe('1234567');
  });
  it('applies the tenant grouping {first: 3, rest: 2}', () => {
    setTenantLocale({ grouping: { first: 3, rest: 2 } });
    expect(fmtNumber(1234567)).toBe('12,34,567');
    expect(fmtNumber(-1234567.25, 2)).toBe('-12,34,567.25');
    expect(fmtNumber(999)).toBe('999');
  });
  it('applies the tenant grouping {first: 3, rest: 3}', () => {
    setTenantLocale({ grouping: { first: 3, rest: 3 } });
    expect(fmtNumber(1234567)).toBe('1,234,567');
  });
});

describe('dates', () => {
  it('shows a business date without a time-zone shift', () => {
    const s = fmtDate('2026-01-01', false);
    expect(s).toMatch(/2026/);
    expect(s).toMatch(/\b1\b/);
    expect(s).not.toMatch(/31|2025/);
  });
  it('treats a date-only value as a date even when a time was asked for', () => {
    expect(fmtDate('2026-03-15')).toBe(fmtDate('2026-03-15', false));
  });
  it('keeps unparsable input and the dash', () => {
    expect(fmtDate('not a date')).toBe('not a date');
    expect(fmtDate(null)).toBe('—');
  });
  it('gives today in the reader zone', () => {
    expect(todayIso(new Date(2026, 0, 2, 0, 30))).toBe('2026-01-02');
    expect(todayIso(new Date(2026, 11, 31, 23, 59))).toBe('2026-12-31');
  });
});

describe('fmtDuration', () => {
  it('uses the locale unit names (English narrow form)', () => {
    expect(fmtDuration(90_061_000)).toBe('1d 1h');
    expect(fmtDuration(3_723_000)).toBe('1h 2m');
    expect(fmtDuration(65_000)).toBe('1m 5s');
    expect(fmtDuration(4_000)).toBe('4s');
    expect(fmtDuration(null)).toBe('—');
  });
});
