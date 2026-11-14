// SPDX-License-Identifier: Apache-2.0
/**
 * Decision 162 (M-109): builds the Microsoft Teams app package (a zip of manifest.json, color.png, outline.png) for one
 * installation, offline and reproducibly (no download, fixed timestamps, icons drawn here).
 *
 *   node scripts/teams-package.mjs --app-id <uuid> --ui-host sangam.example.gov.in --idp-host idp.example.gov.in \
 *     --developer-name "Department X" --website-url https://... --privacy-url https://... --terms-url https://... \
 *     [--ui-base /] [--out dist-teams/sangam-teams.zip]
 *
 * The admin uploads the zip in the Teams admin centre. Without this package nothing in Teams calls Sangam, so the
 * Teams module stays absent; the console is not affected either way.
 */
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { deflateSync } from 'node:zlib';

const uiRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
export const TEMPLATE = join(uiRoot, 'teams', 'manifest.template.json');

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const HOST = /^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*(:\d{1,5})?$/i;

/** Checks the installation values; returns the template variables. Throws with every problem listed. */
export function variables(o) {
  const errors = [];
  if (!UUID.test(o.appId ?? '')) errors.push('--app-id must be a UUID');
  for (const [k, v] of [['--ui-host', o.uiHost], ['--idp-host', o.idpHost]]) if (!HOST.test(v ?? '')) errors.push(`${k} must be a host name without scheme or path`);
  for (const [k, v] of [['--website-url', o.websiteUrl], ['--privacy-url', o.privacyUrl], ['--terms-url', o.termsUrl]]) {
    let ok = false;
    try {
      ok = new URL(v).protocol === 'https:';
    } catch {
      ok = false;
    }
    if (!ok) errors.push(`${k} must be an https URL`);
  }
  if (!o.developerName || o.developerName.length > 32) errors.push('--developer-name is required (at most 32 characters)');
  const base = o.uiBase ?? '/';
  if (!/^\/([\w.-]+\/)*$/.test(base)) errors.push('--ui-base must start and end with /');
  if (errors.length) throw new Error(errors.join('; '));
  return {
    TEAMS_APP_ID: o.appId.toLowerCase(),
    UI_HOST: o.uiHost.toLowerCase(),
    IDP_HOST: o.idpHost.toLowerCase(),
    UI_BASE: base,
    DEVELOPER_NAME: o.developerName,
    WEBSITE_URL: o.websiteUrl,
    PRIVACY_URL: o.privacyUrl,
    TERMS_URL: o.termsUrl,
  };
}

/** Replaces ${NAME} in the template text; every placeholder must be known, and values are JSON-escaped. */
export function fillTemplate(text, vars) {
  const out = text.replace(/\$\{([A-Z_]+)\}/g, (m, name) => {
    if (!(name in vars)) throw new Error(`unknown placeholder ${m}`);
    return JSON.stringify(String(vars[name])).slice(1, -1);
  });
  return JSON.stringify(JSON.parse(out), null, 2) + '\n';
}

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

export function crc32(buf) {
  let c = 0xffffffff;
  for (const b of buf) c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}

/** An RGBA PNG from a pixel function (x, y) => [r, g, b, a]. */
export function png(size, pixel) {
  const raw = Buffer.alloc(size * (size * 4 + 1));
  for (let y = 0; y < size; y++) {
    raw[y * (size * 4 + 1)] = 0; // filter: none
    for (let x = 0; x < size; x++) {
      const [r, g, b, a] = pixel(x, y);
      const o = y * (size * 4 + 1) + 1 + x * 4;
      raw[o] = r;
      raw[o + 1] = g;
      raw[o + 2] = b;
      raw[o + 3] = a;
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 6; // colour type RGBA
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
}

/** The Sangam mark: a ring (two streams meeting). Colour icon 192 px on the accent colour; outline 32 px white on transparent. */
export function icons() {
  const ring = (size, x, y) => {
    const c = (size - 1) / 2;
    const d = Math.hypot(x - c, y - c) / size;
    return d > 0.22 && d < 0.34;
  };
  const color = png(192, (x, y) => (ring(192, x, y) ? [255, 255, 255, 255] : [0x1f, 0x5f, 0x8b, 255]));
  const outline = png(32, (x, y) => (ring(32, x, y) ? [255, 255, 255, 255] : [0, 0, 0, 0]));
  return { color, outline };
}

/** A zip with stored (uncompressed) entries and a fixed timestamp (1980-01-01), so the same input gives the same bytes. */
export function zipStore(entries) {
  const locals = [];
  const centrals = [];
  let offset = 0;
  for (const { name, data } of entries) {
    const n = Buffer.from(name, 'utf8');
    const crc = crc32(data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4); // version needed
    local.writeUInt16LE(0x0800, 6); // UTF-8 names
    local.writeUInt16LE(0, 8); // stored
    local.writeUInt16LE(0, 10); // time 00:00
    local.writeUInt16LE(0x21, 12); // date 1980-01-01
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(n.length, 26);
    local.writeUInt16LE(0, 28);
    locals.push(local, n, data);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0x0800, 8);
    central.writeUInt16LE(0, 10);
    central.writeUInt16LE(0, 12);
    central.writeUInt16LE(0x21, 14);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(data.length, 20);
    central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(n.length, 28);
    central.writeUInt32LE(offset, 42);
    centrals.push(central, n);
    offset += 30 + n.length + data.length;
  }
  const cd = Buffer.concat(centrals);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(cd.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, cd, end]);
}

/** The package bytes for one installation. */
export function buildPackage(options, templateText = readFileSync(TEMPLATE, 'utf8')) {
  const manifest = fillTemplate(templateText, variables(options));
  const { color, outline } = icons();
  return zipStore([
    { name: 'manifest.json', data: Buffer.from(manifest, 'utf8') },
    { name: 'color.png', data: color },
    { name: 'outline.png', data: outline },
  ]);
}

export function parseArgs(argv) {
  const map = { '--app-id': 'appId', '--ui-host': 'uiHost', '--idp-host': 'idpHost', '--ui-base': 'uiBase', '--developer-name': 'developerName', '--website-url': 'websiteUrl', '--privacy-url': 'privacyUrl', '--terms-url': 'termsUrl', '--out': 'out' };
  const o = {};
  for (let i = 0; i < argv.length; i += 2) {
    const k = map[argv[i]];
    if (!k || argv[i + 1] === undefined) throw new Error(`unknown or incomplete option ${argv[i]}`);
    o[k] = argv[i + 1];
  }
  return o;
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  try {
    const o = parseArgs(process.argv.slice(2));
    const out = resolve(uiRoot, o.out ?? 'dist-teams/sangam-teams.zip');
    const bytes = buildPackage(o);
    mkdirSync(dirname(out), { recursive: true });
    writeFileSync(out, bytes);
    console.log(`Teams app package written: ${out} (${bytes.length} bytes)`);
  } catch (e) {
    console.error(`teams-package: ${e.message}`);
    process.exit(2);
  }
}
