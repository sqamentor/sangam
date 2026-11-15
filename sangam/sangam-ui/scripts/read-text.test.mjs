// SPDX-License-Identifier: Apache-2.0
// Optional inputs of the build scripts are read in one step (code-scanning alert #3, gen-compat.mjs: a file was checked
// with existsSync and then read, two looks at a file that can change in between).
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, it } from 'vitest';
import { readTextOr, readTextOrNull } from './read-text.mjs';

const tmp = [];
afterEach(() => tmp.splice(0).forEach((d) => rmSync(d, { recursive: true, force: true })));

function dir() {
  const d = mkdtempSync(join(tmpdir(), 'sangam-read-text-'));
  tmp.push(d);
  return d;
}

describe('read-text', () => {
  it('returns the text of a file that exists', () => {
    const f = join(dir(), 'a.json');
    writeFileSync(f, '{"rows":[]}\n');
    expect(readTextOrNull(f)).toBe('{"rows":[]}\n');
    expect(readTextOr(f, 'fallback')).toBe('{"rows":[]}\n');
  });

  it('an empty file is its (empty) text, not the fallback', () => {
    const f = join(dir(), 'empty.txt');
    writeFileSync(f, '');
    expect(readTextOrNull(f)).toBe('');
    expect(readTextOr(f, 'fallback')).toBe('');
  });

  it('a missing file, or a missing folder on the way, is null or the fallback', () => {
    const d = dir();
    expect(readTextOrNull(join(d, 'absent.json'))).toBeNull();
    expect(readTextOrNull(join(d, 'no', 'such', 'folder', 'BpmnParse.java'))).toBeNull();
    expect(readTextOr(join(d, 'absent.json'), '')).toBe('');
    const file = join(d, 'file.txt');
    writeFileSync(file, 'x');
    expect(readTextOrNull(join(file, 'child.txt'))).toBeNull(); // a file where a folder was expected
  });

  it('any other failure is thrown, never read as "absent"', () => {
    const d = dir();
    mkdirSync(join(d, 'folder'));
    expect(() => readTextOrNull(join(d, 'folder'))).toThrow(/EISDIR/);
    expect(() => readTextOr(join(d, 'folder'), '')).toThrow(/EISDIR/);
  });

  it('gen-compat reads its optional inputs through it and no longer checks before it reads', () => {
    const source = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), 'gen-compat.mjs'), 'utf8');
    expect(source).toContain("from './read-text.mjs'");
    expect(source).not.toMatch(/existsSync/);
  });
});
