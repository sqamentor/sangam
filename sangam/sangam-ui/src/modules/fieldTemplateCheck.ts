// SPDX-License-Identifier: Apache-2.0
/**
 * Owner decisions 230 and 242 in the field checklist template designer (/m/field, templates, Publish): the NUMBER item
 * settings are checked before the template is sent, the same way sangam-field FieldTemplate.validateDefinition and
 * FieldTemplate.Bound do, so an author sees the problem by item instead of a bare 400 INVALID_TEMPLATE:
 * - scale (decision 242): NUMBER items only, an integer 0..10; a bound may not have more decimal places than it;
 * - min and max (decision 230): exact decimals, written as canonical decimal text ("12.5", "0", "-3"), the form the
 *   server stores and hashes. A JSON number is accepted only when it is a safe integer: any other JSON number has
 *   already been turned into a binary double by JSON.parse here, which can change its digits, so the author writes it
 *   as text instead (no float rounding anywhere).
 * The server stays the authority; it refuses anything this check lets through.
 */
import { CANONICAL_DECIMAL_TEXT, MAX_NUMBER_SCALE, MIN_NUMBER_SCALE, canonicalDecimalOrUndefined, decimalPlaces } from '../lib/decimal';
import { type Translate } from '../i18n';

type T = Translate;
type Obj = Record<string, unknown>;

const isObj = (v: unknown): v is Obj => !!v && typeof v === 'object' && !Array.isArray(v);

/** The first problem of the NUMBER settings of a template's sections, in the user's words, or undefined. */
export function checkTemplateSections(sections: unknown, t: T): string | undefined {
  if (!Array.isArray(sections)) return undefined; // the server answers for the overall shape
  for (const section of sections) {
    const items = isObj(section) && Array.isArray(section.items) ? section.items : [];
    for (const item of items) {
      if (!isObj(item)) continue;
      const bad = checkItem(item, t);
      if (bad) return bad;
    }
  }
  return undefined;
}

function checkItem(item: Obj, t: T): string | undefined {
  const id = typeof item.id === 'string' ? item.id : '?';
  const scale = item.scale;
  const hasScale = scale !== undefined && scale !== null;
  if (hasScale) {
    if (item.type !== 'NUMBER') return t('mod.field.check.scaleNotNumber', { item: id });
    if (typeof scale !== 'number' || !Number.isInteger(scale) || scale < MIN_NUMBER_SCALE || scale > MAX_NUMBER_SCALE) {
      return t('mod.field.check.scaleRange', { item: id, min: MIN_NUMBER_SCALE, max: MAX_NUMBER_SCALE });
    }
  }
  for (const bound of ['min', 'max'] as const) {
    const v = item[bound];
    if (v === undefined || v === null) continue;
    let text: string;
    if (typeof v === 'number') {
      if (!Number.isSafeInteger(v)) return t('mod.field.check.boundAsText', { item: id, bound });
      text = String(v);
    } else if (typeof v === 'string') {
      if (!CANONICAL_DECIMAL_TEXT.test(v)) {
        const expected = canonicalDecimalOrUndefined(v);
        return expected === undefined ? t('mod.field.check.boundNotNumber', { item: id, bound }) : t('mod.field.check.boundNotCanonical', { item: id, bound, expected });
      }
      text = v;
    } else {
      return t('mod.field.check.boundNotNumber', { item: id, bound });
    }
    if (hasScale && decimalPlaces(text) > (scale as number)) return t('mod.field.check.boundScale', { item: id, bound, value: text, scale });
  }
  return undefined;
}
