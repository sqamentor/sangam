// SPDX-License-Identifier: Apache-2.0
// The no-CDN build guard (air-gapped rule). Code-scanning alert #4: the XML-namespace exemption tested for the
// substring "//www.w3.org", so a script from https://www.w3.org.evil.test passed the guard. The exemption now compares
// the parsed host name and applies only where a namespace can stand (a bare CSS url()).
import { spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, it } from 'vitest';
import { checkNoCdn, isNamespaceReference, remoteReferences } from './no-cdn.mjs';

const script = resolve(dirname(fileURLToPath(import.meta.url)), 'check-no-cdn.mjs');
const uiRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const tmp = [];
afterEach(() => tmp.splice(0).forEach((d) => rmSync(d, { recursive: true, force: true })));

/** A UI root with a clean index.html and the given dist files ({ 'assets/app.css': '...' }). */
function fixture(distFiles) {
  const root = mkdtempSync(join(tmpdir(), 'sangam-no-cdn-'));
  tmp.push(root);
  writeFileSync(join(root, 'index.html'), '<!doctype html><html><head><script type="module" src="/src/main.tsx"></script></head></html>');
  for (const [name, text] of Object.entries(distFiles)) {
    const file = join(root, 'dist', name);
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, text);
  }
  return root;
}

/** Runs the script as CI does and returns its exit status and output. */
function run(root) {
  const r = spawnSync(process.execPath, [script, root], { encoding: 'utf8' });
  return { status: r.status, out: `${r.stdout}${r.stderr}` };
}

describe('check-no-cdn', () => {
  it('fails the build for a script on a host that only starts with www.w3.org', () => {
    const root = fixture({ 'index.html': '<html><head><script src="https://www.w3.org.evil.test/x.js"></script></head></html>' });
    const { bad } = checkNoCdn(root);
    expect(bad).toHaveLength(1);
    expect(bad[0]).toContain('https://www.w3.org.evil.test/x.js');
    const r = run(root);
    expect(r.status).toBe(1);
    expect(r.out).toContain('Remote references found');
    expect(r.out).toContain('www.w3.org.evil.test');
  });

  it('passes a dist whose only remote-looking URL is a real www.w3.org namespace', () => {
    const root = fixture({
      'index.html': '<html><head><link rel="stylesheet" href="/assets/app.css"></head><body><svg xmlns="http://www.w3.org/2000/svg"></svg></body></html>',
      'assets/app.css': '@namespace svg url(http://www.w3.org/2000/svg);\n@namespace xlink url("http://www.w3.org/1999/xlink");\nsvg|a { fill: red }',
    });
    expect(checkNoCdn(root).bad).toEqual([]);
    const r = run(root);
    expect(r.status).toBe(0);
    expect(r.out).toContain('No CDN references (3 files checked).');
  });

  it('compares the whole host name, not a substring of the reference', () => {
    const evil = [
      'url(https://www.w3.org.evil.test/a.woff2)',
      'url(//www.w3.org.evil.test/a.woff2)',
      'url(https://evil.test//www.w3.org/a.woff2)',
      'url(https://evil.test/?x=//www.w3.org)',
      'url(https://www.w3.org@evil.test/a.woff2)',
      'url(https://xwww.w3.org/a.woff2)',
      'url(https://www.w3.org:evil@evil.test/a)',
    ];
    for (const css of evil) expect(remoteReferences(`a { background: ${css} }`), css).toHaveLength(1);
    expect(remoteReferences('@namespace url(http://www.w3.org/1999/xhtml);')).toEqual([]);
    expect(remoteReferences('@namespace url(//WWW.W3.ORG/1999/xhtml);')).toEqual([]);
  });

  it('never exempts a construct that loads what it names, whatever the host', () => {
    expect(remoteReferences('<script src="https://www.w3.org/x.js"></script>')).toHaveLength(1);
    expect(remoteReferences('<link rel="stylesheet" href="https://www.w3.org/x.css">')).toHaveLength(1);
    expect(remoteReferences('@import url(https://www.w3.org/x.css);')).toHaveLength(1);
    expect(isNamespaceReference('<script src', 'https://www.w3.org/x.js')).toBe(false);
    expect(isNamespaceReference('url(', 'http://www.w3.org/2000/svg')).toBe(true);
    expect(isNamespaceReference('URL(', 'http://www.w3.org/2000/svg')).toBe(true);
    expect(isNamespaceReference('url(', 'http://[not a url')).toBe(false);
  });

  it('still reports ordinary CDN references and ignores local ones', () => {
    const refs = remoteReferences([
      '<script src="https://cdn.example.com/react.js"></script>',
      '<link href="//fonts.example.com/css">',
      '@import url("https://fonts.example.com/a.css");',
      '@font-face { src: url(https://fonts.example.com/a.woff2) }',
      '<script type="module" src="/assets/index.js"></script>',
      '@font-face { src: url(/fonts/noto/NotoSans-Regular.ttf) }',
      'a { background: url("data:image/svg+xml,%3Csvg xmlns=\'http://www.w3.org/2000/svg\'/%3E") }',
    ].join('\n'));
    expect(refs).toHaveLength(4);
  });

  it('the repository index.html passes', () => {
    expect(remoteReferences(readFileSync(join(uiRoot, 'index.html'), 'utf8'))).toEqual([]);
  });
});
