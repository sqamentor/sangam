// SPDX-License-Identifier: Apache-2.0
// The air-gapped rule of the UI build: index.html and the built bundle reference no remote origin for scripts, styles
// or fonts. This module holds the check; scripts/check-no-cdn.mjs runs it (npm run lint, CI).
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

/** The one host whose URLs are XML namespace names (SVG, XLink, MathML, XHTML): identifiers, never fetched. */
export const NAMESPACE_HOST = 'www.w3.org';

// Group 1: the construct that would load the resource. Group 2: the remote URL (absolute or protocol-relative).
const REMOTE = /(<script[^>]+src|<link[^>]+href|@import\s+url\(|url\()\s*=?\s*["']?((?:https?:)?\/\/[^"')\s]+)/gi;

/**
 * Whether a matched reference is an XML namespace name and not a fetch. The URL is parsed and its host name compared
 * as a whole: a substring test let `https://www.w3.org.evil.test/x.js` through. Only a bare CSS `url(...)` can hold a
 * namespace (`@namespace svg url(http://www.w3.org/2000/svg)`); a script src, a link href and an @import always
 * load what they name, so they are never exempt, whatever the host.
 */
export function isNamespaceReference(construct, url) {
  if (construct.toLowerCase() !== 'url(') return false;
  let host;
  try {
    host = new URL(url.startsWith('//') ? `https:${url}` : url).hostname;
  } catch {
    return false; // not a URL the check can read: report it
  }
  return host === NAMESPACE_HOST;
}

/** The remote references in the text of one file (each cut to 120 characters for the report). */
export function remoteReferences(text) {
  const found = [];
  for (const m of text.matchAll(REMOTE)) {
    if (!isNamespaceReference(m[1], m[2])) found.push(m[0].slice(0, 120));
  }
  return found;
}

/** index.html of `root` and every .html / .css file under its dist folder (when built). */
export function targetsOf(root) {
  const targets = [join(root, 'index.html')];
  const dist = join(root, 'dist');
  if (existsSync(dist)) {
    (function walk(d) {
      for (const f of readdirSync(d)) {
        const p = join(d, f);
        if (statSync(p).isDirectory()) walk(p);
        else if (/\.(html|css)$/.test(f)) targets.push(p);
      }
    })(dist);
  }
  return targets;
}

/** Checks the UI at `root`: the files looked at and every remote reference found, as `<file>: <reference>`. */
export function checkNoCdn(root) {
  const targets = targetsOf(root);
  const bad = [];
  for (const f of targets) {
    for (const ref of remoteReferences(readFileSync(f, 'utf8'))) bad.push(`${f}: ${ref}`);
  }
  return { targets, bad };
}
