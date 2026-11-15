// SPDX-License-Identifier: Apache-2.0
// M-020 coverage guard: every operation of sangam-api.yaml has a generated method (named by the manifest that
// sdks/codegen/generate_sdk_surface.py writes) and `client.api` exposes the surface on the client's transport.
//
// The one exception is explicit in the contract: an operation marked `x-sangam-sdk: false` is called by an outside
// system, never by an SDK user (the CloudEvents webhook handshake that Azure Event Grid sends as OPTIONS), and gets no
// SDK method. The generator (sdks/codegen/contract_ir.py) leaves out exactly the operations so marked and lists them in
// the manifest under `notForSdk`; this test expects the same split, so an operation can never be missing from the SDKs
// without the marker.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';
import { SangamClient } from '../src/client.js';

interface Row { operationId: string; typescript: string }
interface Manifest { operations: number; surface: Row[]; notForSdk: { operationId: string }[]; tags: string[] }

const MANIFEST = JSON.parse(readFileSync(new URL('../../../codegen/surface-manifest.json', import.meta.url), 'utf8')) as Manifest;
const CONTRACT = readFileSync(new URL('../../../../sangam-rest/api/sangam-api.yaml', import.meta.url), 'utf8');

const OPERATION_ID = /^ {6}operationId: (\S+)$/;
/** A key of a path item or above: the operation block of the last operationId has ended. */
const OPERATION_END = /^ {0,4}\S/;
const SDK_MARKER = /^ {6}x-sangam-sdk:(.*)$/;

/** The operationIds of the contract, sorted: those an SDK method is generated for, and those marked x-sangam-sdk: false. */
function contractOperations(lines: string[]): { forSdk: string[]; notForSdk: string[] } {
  const forSdk = new Set<string>();
  const notForSdk = new Set<string>();
  let current: string | null = null;
  for (const raw of lines) {
    const line = raw.replace(/\r$/, '');
    const id = OPERATION_ID.exec(line);
    const marker = SDK_MARKER.exec(line);
    if (id) {
      current = id[1];
      forSdk.add(current);
    } else if (OPERATION_END.test(line)) {
      current = null;
    } else if (marker) {
      assert.ok(current !== null, `x-sangam-sdk outside an operation (after its operationId): ${line}`);
      assert.equal(marker[1].trim(), 'false', `only 'x-sangam-sdk: false' is written: ${line}`);
      forSdk.delete(current);
      notForSdk.add(current);
    }
  }
  return { forSdk: [...forSdk].sort(), notForSdk: [...notForSdk].sort() };
}

describe('generated surface (M-020)', () => {
  it('covers every operationId of the contract', () => {
    const { forSdk, notForSdk } = contractOperations(CONTRACT.split('\n'));
    const generated = [...new Set(MANIFEST.surface.map((r) => r.operationId))].sort();
    const left = [...new Set(MANIFEST.notForSdk.map((r) => r.operationId))].sort();
    assert.ok(forSdk.length > 1000, `contract layout changed? only ${forSdk.length} operationIds read`);
    assert.deepEqual(generated, forSdk, 'stale: run sdks/codegen/generate_sdk_surface.py');
    assert.deepEqual(left, notForSdk,
      'the operations without an SDK method are exactly those the contract marks x-sangam-sdk: false');
    assert.deepEqual(left, ['eventGridHookHandshake'],
      'an operation leaves the SDKs only by a reviewed marker: add it here with the reason (who calls it instead of an SDK user)');
  });

  it('reads the marker per operation', () => {
    const { forSdk, notForSdk } = contractOperations([
      'paths:', '  /hooks/x:', '    options:', '      operationId: handshake', '      responses:',
      "        '200':", '          description: Success', '      x-sangam-sdk: false', '    post:',
      '      operationId: deliver', '  /cases:', '    get:', '      operationId: listCases',
    ]);
    assert.deepEqual(forSdk, ['deliver', 'listCases']);
    assert.deepEqual(notForSdk, ['handshake']);
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
