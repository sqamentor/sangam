// SPDX-License-Identifier: Apache-2.0
// Decision 162 (M-109): the Teams app package is built offline, reproducibly, and only from checked values.
// Written, not run in this phase.
import { inflateSync } from 'node:zlib';
import { describe, expect, it } from 'vitest';
import { buildPackage, crc32, fillTemplate, icons, parseArgs, variables, zipStore } from './teams-package.mjs';

const GOOD = {
  appId: '0f8fad5b-d9cb-469f-a165-70867728950e',
  uiHost: 'sangam.example.gov.in',
  idpHost: 'idp.example.gov.in',
  developerName: 'Department X',
  websiteUrl: 'https://sangam.example.gov.in/',
  privacyUrl: 'https://sangam.example.gov.in/privacy',
  termsUrl: 'https://sangam.example.gov.in/terms',
};

/** Reads the stored entries of a zip written by zipStore. */
function unzip(buf) {
  const out = {};
  let o = 0;
  while (buf.readUInt32LE(o) === 0x04034b50) {
    const size = buf.readUInt32LE(o + 18);
    const nameLen = buf.readUInt16LE(o + 26);
    const name = buf.subarray(o + 30, o + 30 + nameLen).toString('utf8');
    const data = buf.subarray(o + 30 + nameLen, o + 30 + nameLen + size);
    expect(crc32(data)).toBe(buf.readUInt32LE(o + 14));
    out[name] = data;
    o += 30 + nameLen + size;
  }
  return out;
}

describe('teams-package', () => {
  it('fills the manifest with the installation values', () => {
    const files = unzip(buildPackage(GOOD));
    expect(Object.keys(files)).toEqual(['manifest.json', 'color.png', 'outline.png']);
    const m = JSON.parse(files['manifest.json'].toString('utf8'));
    expect(m.id).toBe(GOOD.appId);
    expect(m.staticTabs[0].contentUrl).toBe('https://sangam.example.gov.in/teams.html');
    expect(m.staticTabs[0].scopes).toEqual(['personal']);
    expect(m.validDomains).toEqual(['sangam.example.gov.in', 'idp.example.gov.in']);
    expect(JSON.stringify(m)).not.toContain('${');
  });

  it('honours a UI base path', () => {
    const m = JSON.parse(unzip(buildPackage({ ...GOOD, uiBase: '/sangam/' }))['manifest.json'].toString('utf8'));
    expect(m.staticTabs[0].contentUrl).toBe('https://sangam.example.gov.in/sangam/teams.html');
  });

  it('is reproducible: the same values give the same bytes', () => {
    expect(buildPackage(GOOD).equals(buildPackage(GOOD))).toBe(true);
  });

  it('refuses bad values and lists every problem', () => {
    expect(() => variables({ ...GOOD, appId: 'x', uiHost: 'https://a/b', privacyUrl: 'http://insecure' })).toThrow(/--app-id.*--ui-host.*--privacy-url/);
    expect(() => variables({ ...GOOD, developerName: '' })).toThrow(/--developer-name/);
  });

  it('refuses an unknown placeholder and escapes values as JSON', () => {
    expect(() => fillTemplate('{"a":"${NOPE}"}', {})).toThrow(/unknown placeholder/);
    expect(JSON.parse(fillTemplate('{"a":"${A}"}', { A: 'x "quoted"' })).a).toBe('x "quoted"');
  });

  it('draws icons of the sizes Teams requires: colour 192 px, outline 32 px with transparency', () => {
    const { color, outline } = icons();
    const size = (b) => [b.readUInt32BE(16), b.readUInt32BE(20)];
    expect(size(color)).toEqual([192, 192]);
    expect(size(outline)).toEqual([32, 32]);
    // the outline's corner pixel is fully transparent
    const idat = outline.indexOf(Buffer.from('IDAT'));
    const raw = inflateSync(outline.subarray(idat + 4, idat + 4 + outline.readUInt32BE(idat - 4)));
    expect(raw[1 + 3]).toBe(0);
  });

  it('zipStore writes a valid end record', () => {
    const z = zipStore([{ name: 'a.txt', data: Buffer.from('hi') }]);
    const end = z.subarray(z.length - 22);
    expect(end.readUInt32LE(0)).toBe(0x06054b50);
    expect(end.readUInt16LE(10)).toBe(1);
  });

  it('parses the command line', () => {
    expect(parseArgs(['--app-id', 'x', '--out', 'o.zip'])).toEqual({ appId: 'x', out: 'o.zip' });
    expect(() => parseArgs(['--bogus', '1'])).toThrow();
  });
});
