// SPDX-License-Identifier: Apache-2.0
/**
 * Decision 349 / e2e xss-rendering W-03: the modules every page loads (the static import graph of src/main.tsx) do not
 * include a diagram engine. Layout imported GlobalSearch from modules/platform.tsx, whose static imports reach
 * components/BpmnDiagram.tsx and bpmn-js; min-dom (a bpmn-js dependency) assigns innerHTML in a feature test when it
 * loads, so every page, the public privacy policy included, raised a Trusted Types violation and loaded the engine
 * for nothing. Dynamic import() and `import type` are not followed: they load nothing up front.
 */
import { describe, expect, it } from 'vitest';

// every source file of src/, keyed by its path under src/ ('app/Layout.tsx'); Vite keys this folder's own files './x'
const raw = import.meta.glob(['../**/*.ts', '../**/*.tsx', '!../**/*.test.ts', '!../**/*.test.tsx'], { query: '?raw', import: 'default', eager: true }) as Record<string, string>;
const sources: Record<string, string> = Object.fromEntries(Object.entries(raw).map(([k, v]) => [k.startsWith('./') ? `app/${k.slice(2)}` : k.replace(/^\.\.\//, ''), v]));
const ENGINES = /^(bpmn-js|diagram-js|dmn-js|cmmn-js|min-dom|@bpmn-io\/)/;

/** Static, value-level import specifiers of one source file (no `import type`, no dynamic import()). */
export function staticImports(source: string): string[] {
  const out: string[] = [];
  const re = /^\s*(import|export)\s+(type\s+)?([^'";]*?\s+from\s+)?['"]([^'"]+)['"]/gm;
  for (const m of source.matchAll(re)) {
    if (m[2]) continue; // import type / export type
    if (m[1] === 'export' && !m[3]) continue; // `export ... ;` without a source
    out.push(m[4]);
  }
  return out;
}

/** 'app/Layout.tsx' + '../modules/platform' → 'modules/platform.tsx' (a key of `sources`), or null. */
function resolveLocal(from: string, spec: string): string | null {
  const parts = from.split('/').slice(0, -1);
  for (const seg of spec.split('/')) {
    if (seg === '..') parts.pop();
    else if (seg !== '.') parts.push(seg);
  }
  const base = parts.join('/');
  for (const c of [base, `${base}.ts`, `${base}.tsx`, `${base}/index.ts`, `${base}/index.tsx`]) if (c in sources) return c;
  return null;
}

/** The files of the static graph of `entry`, and each package it imports with the file that imports it. */
function staticGraph(entry: string): { files: Set<string>; packages: Map<string, string> } {
  const files = new Set<string>();
  const packages = new Map<string, string>();
  const todo = [entry];
  while (todo.length) {
    const f = todo.pop()!;
    if (files.has(f)) continue;
    files.add(f);
    for (const spec of staticImports(sources[f])) {
      if (spec.startsWith('.')) {
        const r = resolveLocal(f, spec);
        if (r) todo.push(r);
      } else if (!packages.has(spec)) {
        packages.set(spec, f);
      }
    }
  }
  return { files, packages };
}

describe('the entry loads no diagram engine', () => {
  it('reads static imports only', () => {
    expect(staticImports("import { a } from './a';\nimport type { B } from './b';\nexport { c } from './c';\nconst d = () => import('./d');\nimport './e.css';")).toEqual(['./a', './c', './e.css']);
  });

  it('sees every source file', () => {
    expect(Object.keys(sources)).toContain('main.tsx');
    expect(Object.keys(sources)).toContain('modules/platform.tsx');
    expect(Object.keys(sources).filter((k) => k.startsWith('.'))).toEqual([]);
  });

  it('src/main.tsx reaches no bpmn-js, diagram-js, dmn-js or bpmn.io package statically', () => {
    const { files, packages } = staticGraph('main.tsx');
    // the graph is really walked: the shell and the search it mounts are in it
    expect(files.has('app/Layout.tsx')).toBe(true);
    expect(files.has('app/GlobalSearch.tsx')).toBe(true);
    const engines = [...packages].filter(([p]) => ENGINES.test(p)).map(([p, by]) => `${p} (imported by ${by})`);
    expect(engines).toEqual([]);
    expect(files.has('components/BpmnDiagram.tsx')).toBe(false);
  });
});
