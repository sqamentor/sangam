// SPDX-License-Identifier: Apache-2.0
/**
 * UI-DEMO sweep S4/S5 (and the pattern of UI-DEMO-8): a module screen must not read its list before the query
 * parameters the contract marks required are known. Every ScreenDef of src/modules is read from its source; for each
 * list on the Sangam API, every required query parameter of that GET operation in sangam-api.yaml is a required
 * filter (requireFilters), a fixed listQuery value or a {placeholder} of the list path. Before the fix the pending
 * custody transfers screen read /registry/custody/transfers/pending without toCustodian (400 VALIDATION_FAILED).
 */
import { describe, expect, it } from 'vitest';
import apiYaml from '../../../sangam-rest/api/sangam-api.yaml?raw';

const sources = import.meta.glob('./*.tsx', { query: '?raw', import: 'default', eager: true }) as Record<string, string>;

/** Required query parameters of each GET operation, by contract path. */
function requiredQueryParams(): Map<string, string[]> {
  const out = new Map<string, string[]>();
  const lines = apiYaml.split(/\r?\n/);
  let path: string | undefined;
  let inGet = false;
  let param: { name?: string; in?: string; required?: boolean } | undefined;
  const flush = () => {
    if (path && inGet && param?.in === 'query' && param.required && param.name) out.get(path)!.push(param.name);
    param = undefined;
  };
  for (const line of lines) {
    const p = /^ {2}(\/[^:\s]*):\s*$/.exec(line);
    if (p || /^\S/.test(line)) {
      flush();
      path = p?.[1];
      inGet = false;
      continue;
    }
    const m = /^ {4}(\w+):\s*$/.exec(line);
    if (m) {
      flush();
      inGet = m[1] === 'get';
      if (inGet && path) out.set(path, []);
      continue;
    }
    if (!inGet) continue;
    if (/^ {6}- /.test(line)) {
      flush();
      param = {};
    }
    const name = /^ {6}[- ] name: (\S+)$/.exec(line);
    if (name && param) param.name = name[1];
    const where = /^ {8}in: (\w+)$/.exec(line);
    if (where && param) param.in = where[1];
    if (/^ {8}required: true$/.test(line) && param) param.required = true;
    if (/^ {6}\w/.test(line) && !/^ {6}[- ]/.test(line)) flush();
  }
  flush();
  return out;
}

/** Each ScreenDef block of a module source: its list path, required filters, listQuery text. */
function screens(): { file: string; id: string; list: string; block: string }[] {
  const out: { file: string; id: string; list: string; block: string }[] = [];
  for (const [file, src] of Object.entries(sources)) {
    const text = src.replace(/\r\n/g, '\n');
    for (const m of text.matchAll(/^(?:export )?const (\w+): ScreenDef = \{\n([\s\S]*?)\n\};$/gm)) {
      const list = /^ {2}list: (['`])([^'`]+)\1,/m.exec(m[2]);
      if (list) out.push({ file, id: m[1], list: list[2], block: m[2] });
    }
  }
  return out;
}

const contractPath = (ops: Map<string, string[]>, list: string) => {
  const p = list.split('?')[0].replace(/\$\{E\}/, '/engine-rest');
  // a literal route wins over a templated one (Spring does the same): /transfers/pending, not /transfers/{transferId}
  if (ops.has(p)) return p;
  return [...ops.keys()].find((o) => new RegExp(`^${o.split(/\{[^}]+\}/).map((s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('[^/]+')}$`).test(p.replace(/\{\w+\}/g, 'x')));
};

describe('module lists send the contract-required query parameters', () => {
  it('finds the module screens', () => {
    expect(screens().length).toBeGreaterThan(50);
  });

  it('every required query parameter of a list is a required filter, a fixed query value or a path placeholder', () => {
    const ops = requiredQueryParams();
    const missing: string[] = [];
    for (const s of screens()) {
      if (s.list.startsWith('${E}') || s.list.startsWith('/engine-rest')) continue;
      const op = contractPath(ops, s.list);
      if (!op) continue; // calls outside the contract are checked by the screen tests (outsideContract)
      const req = /requireFilters: \[([^\]]*)\]/.exec(s.block)?.[1] ?? '';
      const listQuery = /listQuery: \{([^}]*)\}/.exec(s.block)?.[1] ?? '';
      for (const p of ops.get(op)!) {
        const given = req.includes(`'${p}'`) || new RegExp(`\\b${p}\\b\\s*:`).test(listQuery) || s.list.includes(`{${p}}`) || new RegExp(`[?&]${p}=`).test(s.list);
        if (!given) missing.push(`${s.file} ${s.id}: GET ${op} needs ${p}`);
      }
    }
    expect(missing).toEqual([]);
  });
});
