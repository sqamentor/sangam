// SPDX-License-Identifier: Apache-2.0
/**
 * UI strings and locale-aware formatting (R54, T32; scope per owner decision Q-06, register SL-147).
 *
 * - English is the default and the fallback. Every user-visible string comes from locales/*.json; `npm run i18n:check`
 *   fails when a key used in the source is missing from en.json. Other catalogues (hi.json today) are optional: a key
 *   they lack, or leave empty, is shown in English. New strings go to en.json only (0.1.0 is English-only).
 * - Locales are bundled (no runtime fetch, air-gapped).
 * - Numbers and dates follow the reader's locale: the UI language plus the region of the browser's own locale for that
 *   language. No country is assumed (SL-104): with no region the bare language tag is used.
 * - Money: the record's own ISO 4217 currency, else the tenant's (`/me` `locale.currency`, from the resolved profile's
 *   `locale.currency`, ResolvedProfile.java:84, LocaleSettings.java:16-24). There is no default currency: without one
 *   the amount is shown as a plain number, exactly as the server refuses money without `locale.currency`
 *   (ResolvedProfile.java:64, MONEY_CURRENCY_MISSING). The tenant's digit grouping (`locale.grouping`) is honoured.
 * - Business dates (yyyy-MM-dd) are calendar dates, shown without a time-zone shift.
 */
import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import en from './locales/en.json';
import hi from './locales/hi.json';
import { MONEY_AMOUNT, decimalParts, decimalText, formatDecimalText, type DecimalValue } from '../lib/decimal';

export type Lang = 'en' | 'hi';
export const LANGS: { code: Lang; label: string }[] = [
  { code: 'en', label: 'English' },
  { code: 'hi', label: 'हिन्दी' },
];

/** The default and fallback language (owner decision Q-06). */
export const FALLBACK_LANG: Lang = 'en';

const LANG_KEY = 'sangam.lang';

function stored(): Lang | null {
  try {
    const v = window.localStorage.getItem(LANG_KEY);
    return v === 'en' || v === 'hi' ? v : null;
  } catch {
    return null;
  }
}

/**
 * `embedded` (M-043 web components): the host application owns its page, so its `<html lang>` and its stored language
 * are left alone; the component's own element carries `lang` instead.
 */
export function initI18n(defaultLang: Lang, opts: { embedded?: boolean } = {}): typeof i18n {
  const lng = opts.embedded ? defaultLang : (stored() ?? defaultLang);
  void i18n.use(initReactI18next).init({
    resources: { en: { translation: en }, hi: { translation: hi } },
    lng,
    fallbackLng: FALLBACK_LANG,
    interpolation: { escapeValue: false },
    returnNull: false,
    // An empty entry in an optional catalogue falls back to English instead of showing nothing.
    returnEmptyString: false,
  });
  if (!opts.embedded) document.documentElement.lang = lng;
  return i18n;
}

/**
 * The translate function a helper takes: a screen's `t` (react-i18next), `i18n.t` or a test stub, with or without
 * options. Two call signatures, not `options?`: i18next 27 infers the options type from the argument, so neither an
 * options value that may be undefined nor passing `t` where `(k, o?) => string` is expected type-checks any more.
 */
export interface Translate {
  (key: string): string;
  (key: string, options: Record<string, unknown>): string;
}

/** `i18n.t` for code that holds options that may be absent (tests, custom elements). */
export function tr(key: string, options?: Record<string, unknown>): string {
  return (options === undefined ? i18n.t(key) : i18n.t(key, options)) as string;
}

export function setLanguage(lang: Lang): void {
  void i18n.changeLanguage(lang);
  document.documentElement.lang = lang;
  try {
    window.localStorage.setItem(LANG_KEY, lang);
  } catch {
    /* per-viewer convenience only */
  }
}

// ------------------------------------------------------------------ tenant locale

/**
 * The tenant's locale settings as `/me` reports them (`locale`, mirroring the resolved profile's `locale` section,
 * LocaleSettings.java:16-24). Every field is optional; nothing is assumed when one is absent.
 */
export interface TenantLocale {
  /** ISO 4217, e.g. "GBP" (profile `locale.currency`). */
  currency?: string;
  /** IANA zone of the tenant (profile `locale.zone`); informational, dates are shown in the reader's zone. */
  zone?: string;
  /** Digit grouping (profile `locale.grouping`): {first: 3, rest: 2} gives 12,34,567. */
  grouping?: { first?: number; rest?: number };
}

let tenant: TenantLocale = {};

/** Called once `/me` is known (AuthProvider), and with null at sign-out. */
export function setTenantLocale(l?: TenantLocale | null): void {
  const g = l?.grouping;
  const ok = (n: unknown) => typeof n === 'number' && Number.isInteger(n) && n > 0 && n < 10;
  tenant = {
    currency: typeof l?.currency === 'string' && /^[A-Za-z]{3}$/.test(l.currency) ? l.currency.toUpperCase() : undefined,
    zone: typeof l?.zone === 'string' && l.zone ? l.zone : undefined,
    grouping: g && ok(g.first) && ok(g.rest) ? { first: g.first, rest: g.rest } : undefined,
  };
}

export function tenantLocale(): TenantLocale {
  return tenant;
}

/** The region of the browser's own locale for this language (e.g. "en-GB" → "GB"), if it names one. */
function browserRegion(lang: string): string | undefined {
  const prefs = typeof navigator === 'undefined' ? [] : [...(navigator.languages ?? []), navigator.language].filter(Boolean);
  for (const p of prefs) {
    try {
      const l = new Intl.Locale(p);
      if (l.language === lang && l.region) return l.region;
    } catch {
      /* malformed tag: ignore */
    }
  }
  return undefined;
}

/** BCP 47 locale used by every formatter: the UI language plus the reader's region for it, if any. */
export function locale(): string {
  const lang = (i18n.language || FALLBACK_LANG).split('-')[0];
  const region = browserRegion(lang);
  return region ? `${lang}-${region}` : lang;
}

/** Re-groups the integer digits by the tenant's grouping, keeping the locale's own separator. */
function regroup(parts: Intl.NumberFormatPart[]): string {
  const g = tenant.grouping;
  if (!g?.first || !g.rest) return parts.map((p) => p.value).join('');
  const sep = parts.find((p) => p.type === 'group')?.value ?? ',';
  const out: string[] = [];
  let digits = '';
  let placed = false;
  const flush = () => {
    if (placed) return;
    placed = true;
    const groups: string[] = [];
    let rest = digits;
    if (rest.length > g.first!) {
      groups.unshift(rest.slice(-g.first!));
      rest = rest.slice(0, -g.first!);
      while (rest.length > g.rest!) {
        groups.unshift(rest.slice(-g.rest!));
        rest = rest.slice(0, -g.rest!);
      }
    } else {
      groups.unshift(rest);
      rest = '';
    }
    if (rest) groups.unshift(rest);
    out.push(groups.join(sep));
  };
  for (const p of parts) {
    if (p.type === 'integer') digits += p.value;
    else if (p.type === 'group') continue;
    else {
      if (digits && !placed) flush();
      out.push(p.value);
    }
  }
  if (digits && !placed) flush();
  return out.join('');
}

function format(nf: Intl.NumberFormat, n: number): string {
  return tenant.grouping ? regroup(nf.formatToParts(n)) : nf.format(n);
}

const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;

/**
 * A date-time is shown in the reader's zone. A business date (yyyy-MM-dd) is a calendar date: it is read and shown in
 * UTC, so 2026-01-01 never becomes 31 Dec in a zone west of UTC.
 */
export function fmtDate(iso?: string | null, withTime = true): string {
  if (!iso) return '—';
  const dateOnly = DATE_ONLY.test(iso);
  const d = new Date(dateOnly ? `${iso}T00:00:00Z` : iso);
  if (Number.isNaN(d.getTime())) return iso;
  const opts: Intl.DateTimeFormatOptions = dateOnly || !withTime ? { dateStyle: 'medium' } : { dateStyle: 'medium', timeStyle: 'short' };
  if (dateOnly) opts.timeZone = 'UTC';
  return new Intl.DateTimeFormat(locale(), opts).format(d);
}

/** Decision 138: decimal text (or a DecimalValue) shown with the locale's separators and every digit it has. */
export function fmtDecimal(v?: string | DecimalValue | null): string {
  const text = v === undefined || v === null ? undefined : decimalText(v);
  return text === undefined ? '—' : formatDecimalText(text, locale());
}

export function fmtNumber(n?: number | null, digits = 0): string {
  if (n === undefined || n === null || Number.isNaN(n)) return '—';
  return format(new Intl.NumberFormat(locale(), { maximumFractionDigits: digits }), n);
}

/**
 * Money in the given ISO 4217 currency, else the tenant's. Without either, the amount is shown as a number (up to
 * four decimals, the precision money fields accept); no currency is ever assumed.
 */
export function fmtMoney(n?: number | string | DecimalValue | null, currency?: string | null): string {
  if (n === undefined || n === null || (typeof n === 'number' && Number.isNaN(n))) return '—';
  const cur = (currency && currency.trim()) || tenant.currency;
  if (typeof n !== 'number') {
    // Decision 138: decimal text is formatted from its digits, never through a float.
    const text = decimalText(n);
    if (text === undefined) return '—';
    if (cur) {
      try {
        return formatDecimalText(text, locale(), cur);
      } catch {
        return `${formatDecimalText(text, locale())} ${cur}`;
      }
    }
    return formatDecimalText(text, locale());
  }
  if (cur) {
    try {
      return format(new Intl.NumberFormat(locale(), { style: 'currency', currency: cur }), n);
    } catch {
      // not an ISO 4217 code: show the amount with the code as given
      return `${format(new Intl.NumberFormat(locale(), { maximumFractionDigits: 4 }), n)} ${cur}`;
    }
  }
  return format(new Intl.NumberFormat(locale(), { maximumFractionDigits: 4 }), n);
}

const ISO_CURRENCY = /^[A-Z]{3}$/;

/**
 * M-042 task amount, owner decisions 138 and 211: contract MoneyAmount, a decimal string in MAJOR units of the ISO
 * 4217 currency next to it (Task.amount "1234.56" with currency "INR" is shown as 1,234.56 in rupees). The text is
 * formatted from its own digits through src/lib/decimal.ts (decimalParts), never through a float, with the tenant
 * grouping. Fraction digits are padded to the currency's minor digits and never cut. Nothing is guessed: without a
 * valid currency, for a JSON number (the old int64 minor-unit form) or for text that is not a MoneyAmount, the dash.
 */
export function fmtMoneyAmount(amount?: string | null, currency?: string | null): string {
  if (typeof amount !== 'string' || !MONEY_AMOUNT.test(amount)) return '—';
  const cur = currency?.trim().toUpperCase();
  if (!cur || !ISO_CURRENCY.test(cur)) return '—';
  let nf: Intl.NumberFormat;
  try {
    nf = new Intl.NumberFormat(locale(), { style: 'currency', currency: cur });
  } catch {
    return '—';
  }
  const sep = new Intl.NumberFormat(locale()).formatToParts(1.5).find((x) => x.type === 'decimal')?.value ?? '.';
  const parts = decimalParts(amount, nf, sep);
  return tenant.grouping ? regroup(parts) : parts.map((p) => p.value).join('');
}

/** Whether a value is an ISO 4217 code as the contract writes it (three upper-case letters). */
export function isCurrencyCode(v?: string | null): v is string {
  return !!v && ISO_CURRENCY.test(v);
}

/** Today's calendar date in the reader's zone (yyyy-MM-dd); toISOString() would give the UTC date instead. */
export function todayIso(now: Date = new Date()): string {
  const p = (x: number) => String(x).padStart(2, '0');
  return `${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())}`;
}

/** A duration with the locale's narrow unit names (en: 2d 3h, 4m 5s). */
export function fmtDuration(ms?: number | null): string {
  if (ms === undefined || ms === null) return '—';
  const s = Math.round(ms / 1000);
  const d = Math.floor(s / 86400);
  const h = Math.floor((s % 86400) / 3600);
  const m = Math.floor((s % 3600) / 60);
  const unit = (v: number, u: 'day' | 'hour' | 'minute' | 'second') => {
    try {
      return new Intl.NumberFormat(locale(), { style: 'unit', unit: u, unitDisplay: 'narrow' }).format(v);
    } catch {
      return `${v}${u[0]}`;
    }
  };
  if (d > 0) return `${unit(d, 'day')} ${unit(h, 'hour')}`;
  if (h > 0) return `${unit(h, 'hour')} ${unit(m, 'minute')}`;
  if (m > 0) return `${unit(m, 'minute')} ${unit(s % 60, 'second')}`;
  return unit(s, 'second');
}

export default i18n;
