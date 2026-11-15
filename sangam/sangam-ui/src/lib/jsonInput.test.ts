// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import { jsonListOfObjectsInput, jsonObjectInput } from './jsonInput';

describe('jsonObjectInput', () => {
  it('an object is its value; an empty box means what the caller says', () => {
    expect(jsonObjectInput('{"a": 1}', 'object')).toEqual({ value: { a: 1 } });
    expect(jsonObjectInput('  ', 'object')).toEqual({ value: {} });
    expect(jsonObjectInput('', 'none')).toEqual({ value: undefined });
  });

  it('text that is not JSON, and JSON that is not an object, are refused by name', () => {
    expect(jsonObjectInput('{"a": ', 'object')).toEqual({ problem: 'mod.common.invalidJson' });
    for (const s of ['null', '[]', '[{"a":1}]', '5', '"x"', 'true']) expect(jsonObjectInput(s, 'none')).toEqual({ problem: 'form.jsonObject' });
  });
});

describe('jsonListOfObjectsInput', () => {
  it('a list of objects whose named fields are objects is its value', () => {
    expect(jsonListOfObjectsInput('[{"id":"t1","inputs":{"a":1},"expected":{}}]', ['inputs', 'expected'])).toEqual({ value: [{ id: 't1', inputs: { a: 1 }, expected: {} }] });
    expect(jsonListOfObjectsInput('[]')).toEqual({ value: [] });
  });

  it('anything else is refused by name', () => {
    expect(jsonListOfObjectsInput('[{', ['inputs'])).toEqual({ problem: 'mod.common.invalidJson' });
    for (const s of ['{}', '[1]', '[null]', '[{"inputs": 5}]', '[{"inputs": []}]']) expect(jsonListOfObjectsInput(s, ['inputs'])).toEqual({ problem: 'form.jsonListOfObjects' });
  });
});
