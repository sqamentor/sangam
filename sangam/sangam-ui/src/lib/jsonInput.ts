// SPDX-License-Identifier: Apache-2.0
/**
 * A JSON object typed into a text box (process or decision variables, a batch payload, a delegation-table test case).
 * Text that is not JSON, or JSON that is not an object (an array, a number, a string, null), is refused by name
 * (mod.common.invalidJson, form.jsonObject) and never replaced by an empty object or dropped: the screen shows the
 * problem on the field and sends nothing. What an empty box means is the caller's choice (`empty`).
 */
export type JsonObjectProblem = 'mod.common.invalidJson' | 'form.jsonObject';

export type JsonObjectInput = { value: Record<string, unknown> | undefined; problem?: undefined } | { value?: undefined; problem: JsonObjectProblem };

/**
 * Parses `text` as a JSON object. An empty (blank) box gives `{}` when `empty` is 'object' (a variables box: no
 * variables) and `undefined` when it is 'none' (an optional payload: nothing sent).
 */
export function jsonObjectInput(text: string, empty: 'object' | 'none'): JsonObjectInput {
  if (text.trim() === '') return { value: empty === 'object' ? {} : undefined };
  let v: unknown;
  try {
    v = JSON.parse(text);
  } catch {
    return { problem: 'mod.common.invalidJson' };
  }
  return v !== null && typeof v === 'object' && !Array.isArray(v) ? { value: v as Record<string, unknown> } : { problem: 'form.jsonObject' };
}

export type JsonListProblem = 'mod.common.invalidJson' | 'form.jsonListOfObjects';

/**
 * Parses `text` as a JSON list of objects whose `objectFields`, when present, are objects too (a delegation-table test
 * case is {id, inputs, expected}, PowerTableTestCase). Anything else is refused by name, never kept as the old value.
 */
export function jsonListOfObjectsInput(text: string, objectFields: string[] = []): { value: Record<string, unknown>[]; problem?: undefined } | { value?: undefined; problem: JsonListProblem } {
  let v: unknown;
  try {
    v = JSON.parse(text);
  } catch {
    return { problem: 'mod.common.invalidJson' };
  }
  const isObject = (x: unknown): x is Record<string, unknown> => x !== null && typeof x === 'object' && !Array.isArray(x);
  if (!Array.isArray(v) || !v.every((x) => isObject(x) && objectFields.every((k) => x[k] === undefined || isObject(x[k])))) return { problem: 'form.jsonListOfObjects' };
  return { value: v as Record<string, unknown>[] };
}