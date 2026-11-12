// SPDX-License-Identifier: Apache-2.0
// AC-10 (IX.8): Hindi UI check. The Hindi catalogue is optional since owner decision Q-06 (register SL-147: the
// initial release is English-only, new strings go to en.json only, other locales fall back to English). What it does
// hold must be sound: every entry non-empty with the same interpolation placeholders as English, no key English
// lacks, and actually Devanagari, not copied English. A key it lacks is shown in English (format.test.ts).
import { describe, expect, it } from 'vitest';
import en from './locales/en.json';
import hi from './locales/hi.json';

type Tree = { [k: string]: string | Tree };
const flatten = (t: Tree, p = ''): Record<string, string> =>
  Object.entries(t).reduce<Record<string, string>>((acc, [k, v]) => {
    const key = p ? `${p}.${k}` : k;
    return typeof v === 'string' ? { ...acc, [key]: v } : { ...acc, ...flatten(v, key) };
  }, {});
const placeholders = (s: string) => (s.match(/\{\{\s*[\w.]+\s*\}\}/g) ?? []).map((x) => x.replace(/\s/g, '')).sort();

/** Hindi keys at checkpoint 0e53f91b, when Q-06 froze the catalogue (2989 = every English key at that point). */
const HINDI_KEYS_AT_FREEZE = 2989;

const E = flatten(en as Tree);
const H = flatten(hi as Tree);

describe('AC-10 Hindi locale', () => {
  // Was "has every English key, non-empty". Q-06 / SL-147 made every catalogue but English optional, so the coverage
  // half moved to the fallback test in format.test.ts; the non-empty half stays for every key Hindi holds.
  it('has a non-empty string for every key it holds', () => {
    const empty = Object.keys(H).filter((k) => H[k].trim() === '');
    expect(empty).toEqual([]);
  });
  it('still covers every key it covered at the Q-06 freeze (no Hindi string was removed)', () => {
    // At checkpoint 0e53f91b every English key had a Hindi string. New keys are English-only.
    expect(Object.keys(H).length).toBeGreaterThanOrEqual(HINDI_KEYS_AT_FREEZE);
  });
  it('has no keys unknown to English', () => {
    expect(Object.keys(H).filter((k) => !(k in E))).toEqual([]);
  });
  it('keeps the same interpolation placeholders', () => {
    const bad = Object.keys(E).filter((k) => k in H && placeholders(E[k]).join() !== placeholders(H[k]).join());
    expect(bad).toEqual([]);
  });
  it('is written in Devanagari', () => {
    const values = Object.values(H);
    const deva = values.filter((v) => /[ऀ-ॿ]/.test(v)).length;
    expect(deva / values.length).toBeGreaterThan(0.9);
  });
});
