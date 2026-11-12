// SPDX-License-Identifier: Apache-2.0
// M-020 coverage guard: every operation of sangam-api.yaml has a generated method (named by the manifest that
// sdks/codegen/generate_sdk_surface.py writes) and `client.api` exposes the surface on the client's transport.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';
import { SangamClient } from '../src/client.js';

interface Row { operationId: string; typescript: string }
interface Manifest { operations: number; surface: Row[]; tags: string[] }

const MANIFEST = JSON.parse(readFileSync(new URL('../../../codegen/surface-manifest.json', import.meta.url), 'utf8')) as Manifest;
const CONTRACT = readFileSync(new URL('../../../../sangam-rest/api/sangam-api.yaml', import.meta.url), 'utf8');

describe('generated surface (M-020)', () => {
  it('covers every operationId of the contract', () => {
    const contract = new Set([...CONTRACT.matchAll(/^ {6}operationId: (\S+)$/gm)].map((m) => m[1]));
    const generated = new Set(MANIFEST.surface.map((r) => r.operationId));
    assert.ok(contract.size > 1000, 'contract layout changed?');
    assert.deepEqual([...generated].sort(), [...contract].sort(), 'stale: run sdks/codegen/generate_sdk_surface.py');
  });

  it('has a method for every operation on client.api', () => {
    const client = new SangamClient({ baseUrl: 'http://127.0.0.1:1' });
    const api = client.api as unknown as Record<string, Record<string, unknown>>;
    const missing = MANIFEST.surface.filter((r) => {
      const [group, name] = r.typescript.split('.');
      return typeof api[group]?.[name] !== 'function';
    });
    assert.deepEqual(missing.map((r) => r.operationId), []);
  });
});
