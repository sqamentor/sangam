// SPDX-License-Identifier: Apache-2.0
/**
 * Unicode-safe text helpers (owner decision Q-06, register SL-147: Hindi and other Unicode names and data must be
 * accepted, stored and displayed correctly while the UI itself is English).
 *
 * - truncate(): shortens by user-perceived characters (grapheme clusters), so a Devanagari conjunct, a combining mark
 *   or an emoji's surrogate pair is never cut in half. `String.slice` counts UTF-16 code units and can leave a lone
 *   surrogate or a dangling virama.
 * - textIncludes(): a search match that ignores case and Unicode normalisation form, so text typed as precomposed
 *   "क़" (U+0958) finds data stored as "क" + nukta (U+0915 U+093C), and vice versa.
 * - codePointLength(): the length JSON Schema minLength / maxLength count (code points, not UTF-16 units).
 */

type Segment = { segment: string };
type SegmenterCtor = new (locale?: string, opts?: { granularity: 'grapheme' }) => { segment: (s: string) => Iterable<Segment> };

function graphemes(s: string): string[] {
  const Seg = (Intl as unknown as { Segmenter?: SegmenterCtor }).Segmenter;
  if (Seg) return Array.from(new Seg(undefined, { granularity: 'grapheme' }).segment(s), (x) => x.segment);
  // Fallback: code points, keeping combining marks (Unicode Mn/Mc/Me) and joiners with the character before them.
  const out: string[] = [];
  for (const ch of Array.from(s)) {
    if (out.length && (/\p{M}/u.test(ch) || ch === '‍' || ch === '‌' || /‍$/.test(out[out.length - 1]))) out[out.length - 1] += ch;
    else out.push(ch);
  }
  return out;
}

/** At most `max` user-perceived characters, with an ellipsis when cut. */
export function truncate(s: string, max: number): string {
  if (s.length <= max) return s; // UTF-16 length ≥ grapheme count, so nothing to cut
  const g = graphemes(s);
  return g.length <= max ? s : `${g.slice(0, max).join('')}…`;
}

/** Normalised (NFC) and lower-cased for matching; NFC also decomposes the composition exclusions such as U+0958. */
export function foldForMatch(s: string): string {
  return s.normalize('NFC').toLowerCase();
}

/** Whether `haystack` contains `needle`, ignoring case and normalisation form. An empty needle matches. */
export function textIncludes(haystack: string | null | undefined, needle: string | null | undefined): boolean {
  if (!needle) return true;
  return foldForMatch(haystack ?? '').includes(foldForMatch(needle));
}

/** Length in Unicode code points (JSON Schema string length). */
export function codePointLength(s: string): number {
  return Array.from(s).length;
}
