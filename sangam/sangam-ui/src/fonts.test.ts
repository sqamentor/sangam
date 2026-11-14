// SPDX-License-Identifier: Apache-2.0
// Owner decisions 120 and 121 (RM-24): the UI bundles only the approved deploy/fonts Noto TTFs, served from its own
// origin under /fonts/noto/; Sakal Bharati and @fontsource are gone; Hindi resolves to Noto Sans Devanagari.
// Written, not run in this phase.
import { describe, expect, it } from 'vitest';
import { BUNDLED_FACES, DEVANAGARI_RANGE, LANGUAGE_FONTS, faceCss, fontStack, fontsCss } from './fonts';

describe('bundled fonts (decisions 120, 121)', () => {
  it('bundles exactly the two deploy/fonts TTFs, same-origin under /fonts/noto/', () => {
    expect(BUNDLED_FACES.map((f) => f.src)).toEqual(['/fonts/noto/NotoSans-Regular.ttf', '/fonts/noto/NotoSansDevanagari-Regular.ttf']);
    expect(BUNDLED_FACES.map((f) => f.family)).toEqual(['Noto Sans', 'Noto Sans Devanagari']);
  });

  it('never names Sakal Bharati in any stack or face', () => {
    const css = fontsCss(undefined, {});
    expect(css).not.toMatch(/sakal/i);
    for (const lang of Object.keys(LANGUAGE_FONTS)) expect(fontStack(lang).join(',')).not.toMatch(/sakal/i);
  });

  it('declares both faces as TrueType with swap, and limits the Devanagari face to its scripts', () => {
    const css = fontsCss(undefined, {});
    expect(css).toContain("@font-face{font-family:'Noto Sans';src:url('/fonts/noto/NotoSans-Regular.ttf') format('truetype')");
    expect(css).toContain(`unicode-range:${DEVANAGARI_RANGE}`);
    expect(css.match(/font-display:swap/g)?.length).toBe(2);
    expect(DEVANAGARI_RANGE).toContain('U+0900-097F');
    expect(DEVANAGARI_RANGE).not.toMatch(/U\+00[0-7]/);
  });

  it('resolves Hindi to Noto Sans Devanagari, then Noto Sans, then the system font', () => {
    expect(fontStack('hi')).toEqual(['Noto Sans Devanagari', 'Noto Sans', 'system-ui', 'sans-serif']);
    expect(fontStack('en')).toEqual(['Noto Sans', 'system-ui', 'sans-serif']);
    expect(fontStack('hi-IN')).toEqual(fontStack('hi'));
    expect(fontStack('xx')).toEqual(fontStack('en'));
  });

  it('keeps every one of the 22 Eighth Schedule languages plus English', () => {
    expect(Object.keys(LANGUAGE_FONTS).sort()).toEqual(
      ['as', 'bn', 'brx', 'doi', 'en', 'gu', 'hi', 'kn', 'kok', 'ks', 'mai', 'ml', 'mni', 'mr', 'ne', 'or', 'pa', 'sa', 'sat', 'sd', 'ta', 'te', 'ur'].sort(),
    );
  });
});

describe('site fonts still layer on top', () => {
  it('a state font from /config.json comes first for its language and its face is declared after the bundled ones', () => {
    const css = fontsCss(undefined, {
      faces: [{ family: 'Sakal Marathi', src: '/fonts/site/SakalMarathi.ttf', tier: 'state' }],
      languages: { mr: { state: ['Sakal Marathi'] } },
    });
    expect(css.indexOf("'Sakal Marathi';src:url('/fonts/site/SakalMarathi.ttf')")).toBeGreaterThan(css.indexOf("'Noto Sans Devanagari'"));
    expect(css).toContain("--font-mr:'Sakal Marathi', 'Noto Sans Devanagari', 'Noto Sans', system-ui, sans-serif");
  });

  it('the /config.json block wins over the site manifest per tier', () => {
    const css = fontsCss({ languages: { ta: { state: ['A'] } } }, { languages: { ta: { state: ['B'] } } });
    expect(css).toContain("--font-ta:'B', 'Noto Sans Tamil', 'Noto Sans', system-ui, sans-serif");
  });

  it('drops a face whose source is not a same-origin path (air-gapped, CSP font-src self)', () => {
    expect(faceCss({ family: 'X', src: 'https://cdn.example/x.ttf' })).toBeNull();
    expect(faceCss({ family: 'X', src: '//cdn.example/x.ttf' })).toBeNull();
    const css = fontsCss(undefined, { faces: [{ family: 'X', src: 'https://cdn.example/x.ttf' }] });
    expect(css).not.toContain('cdn.example');
  });
});
