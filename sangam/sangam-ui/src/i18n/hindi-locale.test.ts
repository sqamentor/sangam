// SPDX-License-Identifier: Apache-2.0
// AC-10 (IX.8): Hindi UI check. Every English key has a non-empty Hindi string with the same
// interpolation placeholders, and the Hindi catalogue is actually Devanagari, not copied English.
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

const E = flatten(en as Tree);
const H = flatten(hi as Tree);

describe('AC-10 Hindi locale', () => {
  it('has every English key, non-empty', () => {
    const missing = Object.keys(E).filter((k) => !(k in H) || H[k].trim() === '');
    expect(missing).toEqual([]);
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
