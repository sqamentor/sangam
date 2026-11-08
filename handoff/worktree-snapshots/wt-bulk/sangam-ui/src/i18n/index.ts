// SPDX-License-Identifier: Apache-2.0
/**
 * Hindi + English (R54, T32). Every user-visible string comes from locales/*.json; `npm run i18n:check` fails when a
 * key used in the source is missing from either file. Locales are bundled (no runtime fetch, air-gapped).
 */
import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import en from './locales/en.json';
import hi from './locales/hi.json';

export type Lang = 'en' | 'hi';
export const LANGS: { code: Lang; label: string }[] = [
  { code: 'en', label: 'English' },
  { code: 'hi', label: 'हिन्दी' },
];

const LANG_KEY = 'sangam.lang';

function stored(): Lang | null {
  try {
    const v = window.localStorage.getItem(LANG_KEY);
    return v === 'en' || v === 'hi' ? v : null;
  } catch {
    return null;
  }
}

export function initI18n(defaultLang: Lang): typeof i18n {
  const lng = stored() ?? defaultLang;
  void i18n.use(initReactI18next).init({
    resources: { en: { translation: en }, hi: { translation: hi } },
    lng,
    fallbackLng: 'en',
    interpolation: { escapeValue: false },
    returnNull: false,
  });
  document.documentElement.lang = lng;
  return i18n;
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

/** Locale-aware formatting (Indian numbering for hi-IN and en-IN). */
export function locale(): string {
  return i18n.language === 'hi' ? 'hi-IN' : 'en-IN';
}

export function fmtDate(iso?: string | null, withTime = true): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return new Intl.DateTimeFormat(locale(), withTime ? { dateStyle: 'medium', timeStyle: 'short' } : { dateStyle: 'medium' }).format(d);
}

export function fmtNumber(n?: number | null, digits = 0): string {
  if (n === undefined || n === null || Number.isNaN(n)) return '—';
  return new Intl.NumberFormat(locale(), { maximumFractionDigits: digits }).format(n);
}

export function fmtMoney(n?: number | null, currency = 'INR'): string {
  if (n === undefined || n === null) return '—';
  return new Intl.NumberFormat(locale(), { style: 'currency', currency }).format(n);
}

export function fmtDuration(ms?: number | null): string {
  if (ms === undefined || ms === null) return '—';
  const s = Math.round(ms / 1000);
  const d = Math.floor(s / 86400);
  const h = Math.floor((s % 86400) / 3600);
  const m = Math.floor((s % 3600) / 60);
  if (d > 0) return `${d}d ${h}h`;
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m ${s % 60}s`;
  return `${s}s`;
}

export default i18n;
