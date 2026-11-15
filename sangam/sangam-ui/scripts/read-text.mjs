// SPDX-License-Identifier: Apache-2.0
// Reading an optional input of a build script in one step. "existsSync(p) ? readFileSync(p) : fallback" asks twice:
// the file can appear or vanish between the two calls. Here the read itself answers whether the file is there.
import { readFileSync } from 'node:fs';

/** Error codes that mean "there is no such file" (the path, or a folder on the way to it, does not exist). */
const ABSENT = new Set(['ENOENT', 'ENOTDIR']);

/** The UTF-8 text of `path`, or null when the file does not exist. Any other failure (EACCES, EISDIR ...) is thrown. */
export function readTextOrNull(path) {
  try {
    return readFileSync(path, 'utf8');
  } catch (e) {
    if (e && ABSENT.has(e.code)) return null;
    throw e;
  }
}

/** The UTF-8 text of `path`, or `fallback` when the file does not exist. */
export function readTextOr(path, fallback) {
  return readTextOrNull(path) ?? fallback;
}
