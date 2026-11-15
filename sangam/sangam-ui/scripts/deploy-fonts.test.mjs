// SPDX-License-Identifier: Apache-2.0
// Owner decisions 120 and 121 (RM-24): the build copies only the approved deploy/fonts files, each verified against the
// SHA-256 recorded in deploy/fonts/README.md, and refuses anything else. Written, not run in this phase.
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, it } from 'vitest';
import deployFonts, { FONT_FILES, PUBLIC_DIR, contentType, fontsDir, loadFonts, parseHashTable, requestedFile, sha256Hex } from './deploy-fonts.mjs';

const uiRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const tmp = [];
afterEach(() => tmp.splice(0).forEach((d) => rmSync(d, { recursive: true, force: true })));

function fakeDir(mutate = (files) => files) {
  const dir = mkdtempSync(join(tmpdir(), 'sangam-fonts-'));
  tmp.push(dir);
  const files = mutate(Object.fromEntries(FONT_FILES.map((n) => [n, Buffer.from(`content of ${n}`)])));
  const rows = FONT_FILES.map((n) => `| \`${n}\` | test | ${sha256Hex(Buffer.from(`content of ${n}`))} |`);
  writeFileSync(join(dir, 'README.md'), ['# fonts', '', '| File | Source | SHA-256 |', '|---|---|---|', ...rows, ''].join('\n'));
  for (const [n, b] of Object.entries(files)) if (b) writeFileSync(join(dir, n), b);
  return dir;
}

describe('deploy-fonts', () => {
  it('the repository folder deploy/fonts passes as it is (every file present and matching its recorded hash)', () => {
    const files = loadFonts(fontsDir(uiRoot, {}));
    expect(files.map((f) => f.name)).toEqual(FONT_FILES);
  });

  it('reads the README hash table, case-insensitively, ignoring other rows', () => {
    const m = parseHashTable('| `a.ttf` | src | ' + 'ab'.repeat(32) + ' |\n| File | Source | SHA-256 |\nnot a row');
    expect([...m.entries()]).toEqual([['a.ttf', 'AB'.repeat(32)]]);
  });

  it('refuses a file whose bytes differ from the approved hash', () => {
    const dir = fakeDir((f) => ({ ...f, 'NotoSans-Regular.ttf': Buffer.from('a different font') }));
    expect(() => loadFonts(dir)).toThrow(/NotoSans-Regular\.ttf: SHA-256 .* differs/);
  });

  it('refuses a missing file and a missing hash row, and names every problem', () => {
    const dir = fakeDir((f) => ({ ...f, 'OFL-NotoSans.txt': undefined }));
    const readme = join(dir, 'README.md');
    writeFileSync(readme, readFileSync(readme, 'utf8').split('\n').filter((l) => !l.includes('NotoSansDevanagari-Regular.ttf')).join('\n'));
    // One error names both problems. They are listed in FONT_FILES order, which is not part of the requirement.
    const refused = () => loadFonts(dir);
    expect(refused).toThrow(/OFL-NotoSans\.txt: missing/);
    expect(refused).toThrow(/NotoSansDevanagari-Regular\.ttf: no SHA-256 row/);
  });

  it('refuses a folder without the README record', () => {
    const dir = mkdtempSync(join(tmpdir(), 'sangam-fonts-'));
    tmp.push(dir);
    expect(() => loadFonts(dir)).toThrow(/README\.md not found/);
  });

  it('uses SANGAM_UI_FONTS_DIR when set, else ../deploy/fonts', () => {
    expect(fontsDir('/x/sangam-ui', { SANGAM_UI_FONTS_DIR: '/deploy/fonts' })).toBe(resolve('/deploy/fonts'));
    expect(fontsDir('/x/sangam-ui', {})).toBe(resolve('/x/deploy/fonts'));
  });

  it('serves only the approved names under fonts/noto/ with the right type', () => {
    expect(requestedFile('/fonts/noto/NotoSans-Regular.ttf')).toBe('NotoSans-Regular.ttf');
    expect(requestedFile('/base/fonts/noto/OFL-NotoSans.txt?x=1')).toBe('OFL-NotoSans.txt');
    expect(requestedFile('/fonts/noto/other.ttf')).toBeNull();
    expect(requestedFile('/fonts/noto/../../etc/passwd')).toBeNull();
    expect(contentType('a.ttf')).toBe('font/ttf');
    expect(contentType('OFL.txt')).toMatch(/^text\/plain/);
  });

  it('emits every verified file into the build under fonts/noto/', () => {
    const dir = fakeDir();
    const plugin = deployFonts({ dir });
    const emitted = [];
    plugin.generateBundle.call({ emitFile: (f) => emitted.push(f) });
    expect(emitted.map((f) => f.fileName)).toEqual(FONT_FILES.map((n) => PUBLIC_DIR + n));
    expect(emitted.every((f) => f.type === 'asset' && f.source.length > 0)).toBe(true);
  });

  it('a build with a wrong font fails instead of shipping it', () => {
    const plugin = deployFonts({ dir: fakeDir((f) => ({ ...f, 'OFL-NotoSansDevanagari.txt': Buffer.from('edited') })) });
    expect(() => plugin.generateBundle.call({ emitFile: () => undefined })).toThrow(/refused/);
  });
});
