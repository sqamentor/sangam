// SPDX-License-Identifier: Apache-2.0
/**
 * M-048 seed data: deploys e2e/fixtures/e2e-simple.bpmn to the sandbox target through the Sangam API (POST
 * /deployments, multipart name/target/files, as the modeller does), starts one instance (POST /process-instances) and
 * finds its user task (GET /tasks?processInstanceId=), creates the CERT-In / DPDP e2e identities (identities.ts) and
 * publishes the India-pack compliance baseline (compliance.ts). The ids go to e2e/.seed/seed.json for the specs.
 * Every mutation carries an Idempotency-Key (R41). The test-mode server starts empty on every run (in-memory H2).
 */
import { randomUUID } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { request, type APIResponse } from '@playwright/test';
import { API_BASE, SEED_FILE, UI_ROOT, devToken, type Seed } from './env';
import { createE2eIdentities } from './identities';
import { seedCompliance } from './compliance';

const KEY = 'sangamE2eSimple';

function items<T>(body: unknown): T[] {
  if (Array.isArray(body)) return body as T[];
  const o = body as { items?: T[] } | null;
  return o?.items ?? [];
}

async function ok(what: string, res: APIResponse): Promise<unknown> {
  if (!res.ok()) throw new Error(`seed: ${what} answered ${res.status()} ${await res.text()}`);
  return res.status() === 204 ? null : res.json();
}

async function poll<T>(what: string, fn: () => Promise<T | undefined>, ms = 15_000): Promise<T> {
  const until = Date.now() + ms;
  for (;;) {
    const v = await fn();
    if (v !== undefined) return v;
    if (Date.now() > until) throw new Error(`seed: ${what} did not appear within ${ms} ms`);
    await new Promise((r) => setTimeout(r, 250));
  }
}

export default async function globalSetup(): Promise<void> {
  const api = await request.newContext({
    baseURL: `${API_BASE.replace(/\/$/, '')}/`,
    extraHTTPHeaders: { Authorization: `Bearer ${devToken()}`, Accept: 'application/json, application/problem+json' },
  });
  try {
    const bpmn = readFileSync(join(UI_ROOT, 'e2e', 'fixtures', 'e2e-simple.bpmn'));
    await ok(
      'POST /deployments',
      await api.post('deployments', {
        headers: { 'Idempotency-Key': randomUUID() },
        multipart: { name: 'e2e-simple', target: 'sandbox', files: { name: 'e2e-simple.bpmn', mimeType: 'application/xml', buffer: bpmn } },
      }),
    );

    const definitionId = await poll('the process definition', async () => {
      const body = await ok('GET /process-definitions', await api.get('process-definitions', { params: { key: KEY, latest: true } }));
      return items<{ id: string }>(body)[0]?.id;
    });

    const started = (await ok(
      'POST /process-instances',
      await api.post('process-instances', {
        headers: { 'Idempotency-Key': randomUUID() },
        data: { key: KEY, businessKey: `E2E-${Date.now()}`, variables: { purpose: { type: 'String', value: 'seeded by M-048' } } },
      }),
    )) as { id: string };

    const taskId = await poll('the user task', async () => {
      const body = await ok('GET /tasks', await api.get('tasks', { params: { processInstanceId: started.id } }));
      return items<{ id: string }>(body)[0]?.id;
    });

    // CERT-In / DPDP prerequisites: the extra identities and the India-pack compliance baseline (identities.ts,
    // compliance.ts; docs/compliance/CERTIN-DPDP-CONTROL-MATRIX.md)
    const identities = await createE2eIdentities(api);
    const compliance = await seedCompliance(api);

    const seed: Seed = { definitionKey: KEY, definitionId, processInstanceId: started.id, taskId, identities, compliance };
    mkdirSync(dirname(SEED_FILE), { recursive: true });
    writeFileSync(SEED_FILE, JSON.stringify(seed, null, 2));
  } finally {
    await api.dispose();
  }
}
