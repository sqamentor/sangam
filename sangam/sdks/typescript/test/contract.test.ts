// SPDX-License-Identifier: Apache-2.0
// Drift guard: the SDK's wire interfaces follow sangam-rest/api/sangam-api.yaml (the generated contract). For each
// covered schema the member names must equal the contract's properties and the non-optional members must equal its
// `required` list. TypeScript types are erased at run time, so the guard reads the interface declarations in
// src/models.ts. Both files are read with small line readers for their (generated / formatted) layouts; a layout
// change fails the guard loudly instead of passing silently.
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';

/** The SDK package root (works from test/ and from the compiled build-test/test/). */
function packageRoot(): string {
  let dir = dirname(fileURLToPath(import.meta.url));
  for (;;) {
    const pkg = join(dir, 'package.json');
    if (existsSync(pkg) && readFileSync(pkg, 'utf8').includes('"@sangam/sdk"')) return dir;
    const up = dirname(dir);
    if (up === dir) throw new Error('package root of @sangam/sdk not found');
    dir = up;
  }
}

const ROOT = packageRoot();
const CONTRACT = join(ROOT, '..', '..', 'sangam-rest', 'api', 'sangam-api.yaml');
const MODELS = join(ROOT, 'src', 'models.ts');

interface Shape {
  properties: Set<string>;
  required: Set<string>;
}

/** `components.schemas.<name>` of the contract (PyYAML block layout written by gen_openapi.py). */
export function readSchema(yaml: string, name: string): Shape {
  const lines = yaml.split(/\r?\n/);
  const start = lines.indexOf('  schemas:');
  assert.ok(start >= 0, 'components.schemas not found');
  const head = lines.indexOf(`    ${name}:`, start);
  assert.ok(head >= 0, `schema ${name} not found`);
  const properties = new Set<string>();
  const required = new Set<string>();
  let section = '';
  for (const line of lines.slice(head + 1)) {
    const indent = line.length - line.trimStart().length;
    if (line.trim() !== '' && indent < 6) break;
    let m = /^ {6}(\w+):/.exec(line);
    if (m) {
      section = m[1]!;
      continue;
    }
    if (section === 'properties' && (m = /^ {8}([A-Za-z0-9_$]+):/.exec(line))) properties.add(m[1]!);
    else if (section === 'required' && (m = /^ {6}- (\S+)$/.exec(line))) required.add(m[1]!);
  }
  assert.ok(properties.size > 0, `no properties read for ${name}: the contract layout changed`);
  return { properties, required };
}

/** Own members of `export interface <name>` in models.ts (inherited members, e.g. Replayable, are SDK-only). */
export function readInterface(source: string, name: string): Shape {
  const lines = source.split(/\r?\n/);
  const head = lines.findIndex((l) => new RegExp(`^export interface ${name}\\b`).test(l));
  assert.ok(head >= 0, `interface ${name} not found`);
  const properties = new Set<string>();
  const required = new Set<string>();
  for (const line of lines.slice(head + 1)) {
    if (line.startsWith('}')) break;
    const m = /^ {2}(\w+)(\?)?:/.exec(line);
    if (!m) continue;
    properties.add(m[1]!);
    if (!m[2]) required.add(m[1]!);
  }
  assert.ok(properties.size > 0, `no members read for ${name}`);
  return { properties, required };
}

/** contract schema -> SDK interface */
const COVERED: Record<string, string> = {
  ExternalWork: 'ExternalWork',
  WorkOperation: 'WorkOperation',
  TraceContext: 'WorkTraceContext',
  WorkOutcome: 'WorkOutcome',
  LockState: 'LockState',
  OutcomeUnknownResult: 'OutcomeUnknownResult',
  TopicRequest: 'TopicRequest',
  FetchAndLockRequest: 'FetchAndLockRequest',
  WorkCompleteRequest: 'CompleteWorkRequest',
  WorkFailRequest: 'FailWorkRequest',
  WorkBpmnErrorRequest: 'BpmnErrorRequest',
  WorkUnlockRequest: 'UnlockRequest',
  ExtendLockRequest: 'ExtendLockRequest',
  OutcomeUnknownRequest: 'OutcomeUnknownRequest',
  Task: 'TaskView',
  CompleteTaskRequest: 'CompleteTaskRequest',
  ReturnTaskRequest: 'ReturnTaskRequest',
  CompleteTaskResult: 'CompleteTaskResult',
  AuditEntry: 'AuditEntry',
};

const sorted = (s: Set<string>): string[] => [...s].sort();

describe('contract drift guard (sangam-api.yaml)', () => {
  assert.ok(existsSync(CONTRACT), `contract not found: ${CONTRACT}`);
  const yaml = readFileSync(CONTRACT, 'utf8');
  const models = readFileSync(MODELS, 'utf8');

  for (const [schema, iface] of Object.entries(COVERED)) {
    it(`${iface} matches ${schema}`, () => {
      const want = readSchema(yaml, schema);
      const have = readInterface(models, iface);
      assert.deepEqual(sorted(have.properties), sorted(want.properties), `${iface}: member names`);
      assert.deepEqual(sorted(have.required), sorted(want.required), `${iface}: required members`);
    });
  }

  it('the reader sees required members', () => {
    assert.deepEqual(sorted(readSchema(yaml, 'WorkFailRequest').required), ['lockVersion', 'workerId']);
  });
});
