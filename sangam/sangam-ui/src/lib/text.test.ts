// SPDX-License-Identifier: Apache-2.0
// M-045 / SL-147: Unicode data is displayed and matched correctly. Written, not run in this phase.
import { describe, expect, it } from 'vitest';
import { codePointLength, foldForMatch, textIncludes, truncate } from './text';

describe('truncate', () => {
  it('leaves short text alone', () => {
    expect(truncate('राम कुमार', 20)).toBe('राम कुमार');
  });
  it('never splits a surrogate pair', () => {
    const s = '😀'.repeat(10);
    const out = truncate(s, 3);
    expect(out).toBe('😀😀😀…');
    expect(/[\uD800-\uDBFF](?![\uDC00-\uDFFF])/.test(out)).toBe(false);
  });
  it('never separates a Devanagari consonant from its vowel sign', () => {
    // कि = क (U+0915) + ि (U+093F); String.slice(0, 1) would keep the bare consonant
    expect(truncate('किताब', 1)).toBe('कि…');
    expect(truncate('किताब', 2)).toBe('किता…');
  });
});

describe('textIncludes', () => {
  it('matches across normalisation forms (precomposed and decomposed nukta)', () => {
    const decomposed = 'क़लम'; // क़लम as क + nukta
    const precomposed = 'क़लम'; // क़लम with U+0958
    expect(textIncludes(decomposed, precomposed)).toBe(true);
    expect(textIncludes(precomposed, decomposed)).toBe(true);
  });
  it('ignores case and treats an empty needle as a match', () => {
    expect(textIncludes('Leave Application', 'leave')).toBe(true);
    expect(textIncludes('anything', '')).toBe(true);
    expect(textIncludes(undefined, 'x')).toBe(false);
  });
  it('folds to NFC', () => {
    expect(foldForMatch('É')).toBe('é');
  });
});

describe('codePointLength', () => {
  it('counts code points as JSON Schema does', () => {
    expect(codePointLength('😀a')).toBe(2);
    expect('😀a'.length).toBe(3);
    expect(codePointLength('हिन्दी')).toBe(6);
  });
});
