// SPDX-License-Identifier: Apache-2.0
/**
 * Owner decisions 120 and 121 (RM-24): the UI serves the approved deploy/fonts Noto TTFs (decisions 70, 95) from its own
 * origin. There is one copy of each file in the repository, sangam/deploy/fonts; this Vite plugin copies it into the
 * build as dist/fonts/noto/<file> (the UI's public folder) and serves the same path from the dev server.
 *
 * Every file is checked against the SHA-256 column of deploy/fonts/README.md, the record kept for the owner decisions.
 * A missing file, a missing hash row or a different hash fails the build: the UI never ships a font that is not the
 * approved one, and never silently ships without it.
 *
 * The folder is ../deploy/fonts relative to the UI root, or SANGAM_UI_FONTS_DIR when set (container builds).
 */
import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

/** The approved files, each font with its OFL text (OFL-1.1 condition 2). */
export const FONT_FILES = ['NotoSans-Regular.ttf', 'NotoSansDevanagari-Regular.ttf', 'OFL-NotoSans.txt', 'OFL-NotoSansDevanagari.txt'];

/** Where the files appear in the built UI; src/fonts.ts BUNDLED_FACES points here. */
export const PUBLIC_DIR = 'fonts/noto/';

const ROW = /^\|\s*`([^`]+)`\s*\|.*\|\s*([0-9A-Fa-f]{64})\s*\|\s*$/;

/** File name → upper-case SHA-256, from the README table rows "| `file` | source | SHA-256 |". */
export function parseHashTable(readme) {
  const out = new Map();
  for (const line of readme.split(/\r?\n/)) {
    const m = ROW.exec(line.trim());
    if (m) out.set(m[1], m[2].toUpperCase());
  }
  return out;
}

export function sha256Hex(bytes) {
  return createHash('sha256').update(bytes).digest('hex').toUpperCase();
}

/** Reads and verifies every approved file; throws one error that lists every problem. */
export function loadFonts(dir) {
  const readmePath = join(dir, 'README.md');
  if (!existsSync(readmePath)) throw new Error(`deploy-fonts: ${readmePath} not found (set SANGAM_UI_FONTS_DIR to the deploy/fonts folder)`);
  const hashes = parseHashTable(readFileSync(readmePath, 'utf8'));
  const problems = [];
  const files = [];
  for (const name of FONT_FILES) {
    const p = join(dir, name);
    const expected = hashes.get(name);
    if (!expected) {
      problems.push(`${name}: no SHA-256 row in ${readmePath}`);
      continue;
    }
    if (!existsSync(p)) {
      problems.push(`${name}: missing in ${dir}`);
      continue;
    }
    const bytes = readFileSync(p);
    const actual = sha256Hex(bytes);
    if (actual !== expected) problems.push(`${name}: SHA-256 ${actual} differs from the approved ${expected}`);
    else files.push({ name, bytes });
  }
  if (problems.length) throw new Error(`deploy-fonts: refused (owner decisions 70, 95, 120):\n  ${problems.join('\n  ')}`);
  return files;
}

export function fontsDir(uiRoot, env = process.env) {
  return env.SANGAM_UI_FONTS_DIR ? resolve(env.SANGAM_UI_FONTS_DIR) : resolve(uiRoot, '../deploy/fonts');
}

export function contentType(name) {
  return name.endsWith('.ttf') ? 'font/ttf' : 'text/plain; charset=utf-8';
}

/** The file name a dev-server request asks for, when it is one of the approved files under fonts/noto/. */
export function requestedFile(url) {
  const m = /(?:^|\/)fonts\/noto\/([^/?#]+)(?:[?#].*)?$/.exec(url ?? '');
  return m && FONT_FILES.includes(m[1]) ? m[1] : null;
}

/** @param {{ dir?: string }} [options] */
export default function deployFonts(options = {}) {
  let root = process.cwd();
  let cache;
  // Loaded on first use, so `vitest` (which loads this config) never needs the font folder.
  const files = () => (cache ??= loadFonts(options.dir ?? fontsDir(root)));
  return {
    name: 'sangam-deploy-fonts',
    configResolved(config) {
      root = config.root;
    },
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const name = requestedFile(req.url);
        if (!name) return next();
        const f = files().find((x) => x.name === name);
        res.setHeader('Content-Type', contentType(name));
        res.setHeader('Cache-Control', 'no-cache');
        res.end(f.bytes);
      });
    },
    generateBundle() {
      for (const f of files()) this.emitFile({ type: 'asset', fileName: PUBLIC_DIR + f.name, source: f.bytes });
    },
  };
}
