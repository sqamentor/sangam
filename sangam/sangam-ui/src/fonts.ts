// SPDX-License-Identifier: Apache-2.0
/**
 * Language → font mapping (FONTS.md). For every language the font stack is built in this order:
 *
 *   1. state-government font   (tenant: /config.json `fonts`, or the site drop-in manifest /fonts/site/fonts.json)
 *   2. central-government font (none bundled, owner decision 121; a site may add one through the same config)
 *   3. bundled Noto fallback   (Noto Sans / Noto Sans Devanagari, the approved deploy/fonts TTFs, SIL OFL 1.1,
 *                               copied into the build as /fonts/noto/ by scripts/deploy-fonts.mjs, owner decision 120)
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
  /** Same-origin URL of a site drop-in manifest. Default "/fonts/site/fonts.json"; "" disables it. */
  manifest?: string;
  /** Extra font files, typically state-government TTFs dropped into /fonts/site/ at install time. */
  faces?: FontFaceDef[];
  /** Per-language overrides, keyed by BCP-47 language code (e.g. "hi", "mr", "ta"). */
  languages?: Record<string, LanguageFonts>;
}

const NOTO = 'Noto Sans';
const NOTO_DEVA = 'Noto Sans Devanagari';

/** Devanagari, Vedic Extensions, Devanagari Extended, ZWNJ/ZWJ, rupee signs and the dotted circle: Latin never fetches it. */
export const DEVANAGARI_RANGE = 'U+0900-097F, U+1CD0-1CFF, U+200C-200D, U+20A8, U+20B9, U+25CC, U+A830-A839, U+A8E0-A8FF';

/**
 * The fonts bundled with the UI: exactly the deploy/fonts files approved by owner decisions 70 and 95 (OFL-1.1, unmodified,
 * Regular only), served from the UI's own origin under /fonts/noto/ (owner decision 120). Provenance and SHA-256 are in
 * deploy/fonts/README.md; the build refuses a file whose hash differs (scripts/deploy-fonts.mjs).
 */
export const BUNDLED_FACES: FontFaceDef[] = [
  { family: NOTO, src: '/fonts/noto/NotoSans-Regular.ttf', weight: '400', tier: 'osi' },
  { family: NOTO_DEVA, src: '/fonts/noto/NotoSansDevanagari-Regular.ttf', weight: '400', unicodeRange: DEVANAGARI_RANGE, tier: 'osi' },
];

const DEVA: LanguageFonts = { fallback: [NOTO_DEVA, NOTO] };

/**
 * Built-in mapping for English plus the 22 Eighth Schedule languages. No state- or central-government font is bundled
 * (FONTS.md §3; owner decision 121); a tenant adds them through `fonts.languages[x].state` or `.central`.
 * Only 'Noto Sans' and 'Noto Sans Devanagari' are bundled; other Noto names are used when installed on the device.
 */
export const LANGUAGE_FONTS: Record<string, LanguageFonts> = {
  en: { fallback: [NOTO] },
  hi: DEVA,
  // Devanagari script
  mr: DEVA,
  ne: DEVA,
  kok: DEVA,
  mai: DEVA,
  doi: DEVA,
  brx: DEVA,
  sa: DEVA,
  // Other Indic scripts
  as: { fallback: ['Noto Sans Bengali', NOTO] },
  bn: { fallback: ['Noto Sans Bengali', NOTO] },
  mni: { fallback: ['Noto Sans Meetei Mayek', 'Noto Sans Bengali', NOTO] },
  gu: { fallback: ['Noto Sans Gujarati', NOTO] },
  pa: { fallback: ['Noto Sans Gurmukhi', NOTO] },
  or: { fallback: ['Noto Sans Oriya', NOTO] },
  ta: { fallback: ['Noto Sans Tamil', NOTO] },
  te: { fallback: ['Noto Sans Telugu', NOTO] },
  kn: { fallback: ['Noto Sans Kannada', NOTO] },
  ml: { fallback: ['Noto Sans Malayalam', NOTO] },
  sat: { fallback: ['Noto Sans Ol Chiki', NOTO] },
  // Perso-Arabic script (Sindhi is also written in Devanagari, so the bundled Devanagari face follows Naskh)
  ur: { fallback: ['Noto Nastaliq Urdu', 'Noto Naskh Arabic', NOTO] },
  ks: { fallback: ['Noto Nastaliq Urdu', 'Noto Naskh Arabic', NOTO] },
  sd: { fallback: ['Noto Naskh Arabic', NOTO_DEVA, NOTO] },
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

/** The @font-face rule for one face, or null when the face is not same-origin or lacks a family or source. */
export function faceCss(f: FontFaceDef): string | null {
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

/** The resolved font stack for a language: state → central → bundled fallback → system. */
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

/** The style sheet for the given configuration: the bundled and site faces, then one variable and rule per language. */
export function fontsCss(site: FontsConfig | undefined, cfg: FontsConfig): string {
  const faces = [...BUNDLED_FACES, ...(site?.faces ?? []), ...(cfg.faces ?? [])];
  const map = merge(merge(LANGUAGE_FONTS, site?.languages), cfg.languages);
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
  return css.join('\n');
}

/**
 * Declares the font faces and per-language stacks. Precedence of sources (later wins per language tier):
 * built-in LANGUAGE_FONTS < site drop-in manifest < /config.json `fonts`.
 */
export async function applyFonts(cfg: FontsConfig = {}): Promise<void> {
  const site = await readManifest(cfg.manifest ?? '/fonts/site/fonts.json');
  let el = document.getElementById('sangam-fonts') as HTMLStyleElement | null;
  if (!el) {
    el = document.createElement('style');
    el.id = 'sangam-fonts';
    document.head.appendChild(el);
  }
  el.textContent = fontsCss(site, cfg);
}
