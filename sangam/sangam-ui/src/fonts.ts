// SPDX-License-Identifier: Apache-2.0
/**
 * Language → font mapping (FONTS.md). For every language the font stack is built in this order:
 *
 *   1. state-government font   (tenant: /config.json `fonts`, or the site drop-in manifest /fonts/site/fonts.json)
 *   2. central-government font (bundled: C-DAC GIST Sakal Bharati, SIL OFL 1.1, public/fonts/gov/central/)
 *   3. bundled OSI fallback    (Noto Sans / Noto Sans Devanagari via @fontsource, SIL OFL 1.1)
 *   4. system-ui, sans-serif
 *
 * Adding a language later is configuration only: give it a row in LANGUAGE_FONTS (or in /config.json) and, when a
 * government font file is not bundled, let the site drop the TTF into /fonts/site/ and declare it as a face.
 * Everything is served from 'self' (CSP font-src 'self'), so it works air-gapped.
 */

/** A font file the browser may load. `src` must be a same-origin path (air-gapped, CSP font-src 'self'). */
export interface FontFaceDef {
  family: string;
  src: string;
  /** CSS font-weight, e.g. "400" or "100 900". Default "400". */
  weight?: string;
  /** CSS font-style. Default "normal". */
  style?: string;
  /** CSS unicode-range; limits when the browser downloads the file. */
  unicodeRange?: string;
  /** Where the file came from (for the record only). */
  tier?: 'state' | 'central' | 'osi';
}

/** Family names per precedence tier for one language. Later sources replace a tier wholesale. */
export interface LanguageFonts {
  state?: string[];
  central?: string[];
  fallback?: string[];
}

/** The optional `fonts` block of /config.json, and the shape of the drop-in manifest /fonts/site/fonts.json. */
export interface FontsConfig {
  /** Load the bundled central-government font files (default true). False keeps only the OSI fallback. */
  useBundledGovFonts?: boolean;
  /** Same-origin URL of a site drop-in manifest. Default "/fonts/site/fonts.json"; "" disables it. */
  manifest?: string;
  /** Extra font files, typically state-government TTFs dropped into /fonts/site/ at install time. */
  faces?: FontFaceDef[];
  /** Per-language overrides, keyed by BCP-47 language code (e.g. "hi", "mr", "ta"). */
  languages?: Record<string, LanguageFonts>;
}

const SB = 'SakalBharati';

/** Indic, Ol Chiki, Meetei Mayek and Arabic ranges covered by Sakal Bharati (no Latin, so English never fetches it). */
const SB_RANGE =
  'U+0600-06FF, U+0900-0DFF, U+1C50-1C7F, U+ABC0-ABFF, U+200C-200D, U+20B9, U+25CC';

/** Central-government font files shipped in public/fonts/gov/ (provenance and SHA-256 in FONTS.md). */
export const BUNDLED_GOV_FACES: FontFaceDef[] = [
  {
    family: SB,
    src: '/fonts/gov/central/sakal-bharati/SakalBharati.ttf',
    weight: '400',
    unicodeRange: SB_RANGE,
    tier: 'central',
  },
];

/**
 * Built-in mapping for English plus the 22 Eighth Schedule languages. No state-government font is bundled (none had
 * a verified redistribution licence, FONTS.md §3); a tenant adds them through `fonts.languages[x].state`.
 * Only 'Noto Sans' and 'Noto Sans Devanagari' are bundled; other Noto names are used when installed on the device.
 */
export const LANGUAGE_FONTS: Record<string, LanguageFonts> = {
  en: { central: [], fallback: ['Noto Sans', SB] },
  hi: { central: [SB], fallback: ['Noto Sans Devanagari', 'Noto Sans'] },
  // Devanagari script
  mr: { central: [SB], fallback: ['Noto Sans Devanagari', 'Noto Sans'] },
  ne: { central: [SB], fallback: ['Noto Sans Devanagari', 'Noto Sans'] },
  kok: { central: [SB], fallback: ['Noto Sans Devanagari', 'Noto Sans'] },
  mai: { central: [SB], fallback: ['Noto Sans Devanagari', 'Noto Sans'] },
  doi: { central: [SB], fallback: ['Noto Sans Devanagari', 'Noto Sans'] },
  brx: { central: [SB], fallback: ['Noto Sans Devanagari', 'Noto Sans'] },
  sa: { central: [SB], fallback: ['Noto Sans Devanagari', 'Noto Sans'] },
  // Other Indic scripts
  as: { central: [SB], fallback: ['Noto Sans Bengali', 'Noto Sans'] },
  bn: { central: [SB], fallback: ['Noto Sans Bengali', 'Noto Sans'] },
  mni: { central: [SB], fallback: ['Noto Sans Meetei Mayek', 'Noto Sans Bengali', 'Noto Sans'] },
  gu: { central: [SB], fallback: ['Noto Sans Gujarati', 'Noto Sans'] },
  pa: { central: [SB], fallback: ['Noto Sans Gurmukhi', 'Noto Sans'] },
  or: { central: [SB], fallback: ['Noto Sans Oriya', 'Noto Sans'] },
  ta: { central: [SB], fallback: ['Noto Sans Tamil', 'Noto Sans'] },
  te: { central: [SB], fallback: ['Noto Sans Telugu', 'Noto Sans'] },
  kn: { central: [SB], fallback: ['Noto Sans Kannada', 'Noto Sans'] },
  ml: { central: [SB], fallback: ['Noto Sans Malayalam', 'Noto Sans'] },
  sat: { central: [SB], fallback: ['Noto Sans Ol Chiki', 'Noto Sans'] },
  // Perso-Arabic script (Sindhi is also written in Devanagari: sd-Deva picks up the Devanagari fonts via SB)
  ur: { central: [SB], fallback: ['Noto Nastaliq Urdu', 'Noto Naskh Arabic', 'Noto Sans'] },
  ks: { central: [SB], fallback: ['Noto Nastaliq Urdu', 'Noto Naskh Arabic', 'Noto Sans'] },
  sd: { central: [SB], fallback: ['Noto Naskh Arabic', 'Noto Sans Devanagari', 'Noto Sans'] },
};

const SYSTEM = ['system-ui', 'sans-serif'];

function quote(family: string): string {
  return /^[a-z-]+$/.test(family) ? family : `'${family.replace(/['\\]/g, '')}'`;
}

function sameOrigin(src: string): boolean {
  return src.startsWith('/') && !src.startsWith('//');
}

function cssToken(v: string | undefined, fallback: string): string {
  return v && /^[\w\s,+\-.]+$/.test(v) ? v : fallback;
}

function faceCss(f: FontFaceDef): string | null {
  if (!f.family || !f.src || !sameOrigin(f.src)) return null;
  const fmt = /\.woff2$/i.test(f.src) ? 'woff2' : /\.woff$/i.test(f.src) ? 'woff' : /\.otf$/i.test(f.src) ? 'opentype' : 'truetype';
  const lines = [
    `font-family:${quote(f.family)}`,
    `src:url('${encodeURI(f.src).replace(/'/g, '%27')}') format('${fmt}')`,
    `font-weight:${cssToken(f.weight, '400')}`,
    `font-style:${cssToken(f.style, 'normal')}`,
    'font-display:swap',
  ];
  if (f.unicodeRange && /^[U+0-9A-Fa-f?,\s-]+$/.test(f.unicodeRange)) lines.push(`unicode-range:${f.unicodeRange}`);
  return `@font-face{${lines.join(';')}}`;
}

function merge(base: Record<string, LanguageFonts>, over?: Record<string, LanguageFonts>): Record<string, LanguageFonts> {
  if (!over) return base;
  const out: Record<string, LanguageFonts> = { ...base };
  for (const [lang, v] of Object.entries(over)) {
    if (!/^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$/.test(lang) || !v) continue;
    out[lang] = { ...out[lang], ...v };
  }
  return out;
}

/** The resolved font stack for a language: state → central → OSI fallback → system. */
export function fontStack(lang: string, map: Record<string, LanguageFonts> = LANGUAGE_FONTS): string[] {
  const row = map[lang] ?? map[lang.split('-')[0]] ?? map.en;
  const seen = new Set<string>();
  return [...(row.state ?? []), ...(row.central ?? []), ...(row.fallback ?? []), ...SYSTEM].filter((f) => {
    if (!f || seen.has(f)) return false;
    seen.add(f);
    return true;
  });
}

async function readManifest(url: string): Promise<FontsConfig | undefined> {
  if (!url || !sameOrigin(url)) return undefined;
  try {
    const res = await fetch(url, { cache: 'no-cache', credentials: 'same-origin' });
    // The SPA fallback answers unknown paths with index.html, so only accept JSON.
    if (!res.ok || !(res.headers.get('content-type') ?? '').includes('json')) return undefined;
    return (await res.json()) as FontsConfig;
  } catch {
    return undefined;
  }
}

/**
 * Declares the font faces and per-language stacks. Precedence of sources (later wins per language tier):
 * built-in LANGUAGE_FONTS < site drop-in manifest < /config.json `fonts`.
 */
export async function applyFonts(cfg: FontsConfig = {}): Promise<void> {
  const site = await readManifest(cfg.manifest ?? '/fonts/site/fonts.json');
  const useGov = cfg.useBundledGovFonts ?? site?.useBundledGovFonts ?? true;
  const faces = [...(useGov ? BUNDLED_GOV_FACES : []), ...(site?.faces ?? []), ...(cfg.faces ?? [])];
  let map = merge(merge(LANGUAGE_FONTS, site?.languages), cfg.languages);
  if (!useGov) {
    map = Object.fromEntries(
      Object.entries(map).map(([k, v]) => [k, { ...v, central: (v.central ?? []).filter((f) => f !== SB), fallback: (v.fallback ?? []).filter((f) => f !== SB) }]),
    );
  }

  const css: string[] = [];
  for (const f of faces) {
    const c = faceCss(f);
    if (c) css.push(c);
  }
  const vars: string[] = [];
  for (const lang of Object.keys(map)) {
    const stack = fontStack(lang, map).map(quote).join(', ');
    const v = `--font-${lang.toLowerCase()}`;
    vars.push(`${v}:${stack}`);
    // :where() keeps specificity at zero, so component rules (e.g. monospace code) still win.
    if (lang !== 'en') css.push(`:where(:lang(${lang})){font-family:var(${v})}`);
  }
  css.unshift(`:root{${vars.join(';')};--font:var(--font-en)}`);

  let el = document.getElementById('sangam-fonts') as HTMLStyleElement | null;
  if (!el) {
    el = document.createElement('style');
    el.id = 'sangam-fonts';
    document.head.appendChild(el);
  }
  el.textContent = css.join('\n');
}
