// SPDX-License-Identifier: Apache-2.0
/**
 * M-048: settings shared by the specs. Everything comes from the environment the CI job (W4-C, ui.yml) sets:
 * - SANGAM_E2E_BASE_URL  the UI (default http://localhost:8080, the UI image's port);
 * - SANGAM_E2E_API_BASE  the API as the browser reaches it (default <base>/api/v1, proxied by the UI image);
 * - SANGAM_DEV_TOKEN     the test-mode server's development token (application-h2.yaml sangam.security.dev-token.token).
 *   It is a test credential of the throw-away test-mode server, never a production secret, and is required: the
 *   suite does not guess it.
 * UI strings are read from en.json (0.1.0 is English-only, Q-06), so a copy change never breaks a selector.
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
export const UI_ROOT = join(here, '..', '..');

export const BASE_URL = process.env.SANGAM_E2E_BASE_URL ?? 'http://localhost:8080';
export const API_BASE = process.env.SANGAM_E2E_API_BASE ?? `${BASE_URL.replace(/\/$/, '')}/api/v1`;

export function devToken(): string {
  const v = process.env.SANGAM_DEV_TOKEN;
  if (!v) throw new Error('SANGAM_DEV_TOKEN is not set: give the test-mode server token (deploy/docker/server-test-mode.Dockerfile)');
  return v;
}

const en = JSON.parse(readFileSync(join(UI_ROOT, 'src', 'i18n', 'locales', 'en.json'), 'utf8')) as Record<string, unknown>;

/** The English UI string for a key, with {{placeholders}} filled. Throws on an unknown key. */
export function t(key: string, vars: Record<string, string | number> = {}): string {
  const v = key.split('.').reduce<unknown>((o, k) => (o && typeof o === 'object' ? (o as Record<string, unknown>)[k] : undefined), en);
  if (typeof v !== 'string') throw new Error(`no en.json string for ${key}`);
  return v.replace(/\{\{(\w+)\}\}/g, (_, n: string) => String(vars[n] ?? `{{${n}}}`));
}

/** Where global-setup writes the ids of the seeded data for the specs. */
// Not under test-results/: Playwright empties its output folder at the start of a run.
export const SEED_FILE = join(UI_ROOT, 'e2e', '.seed', 'seed.json');

/** An e2e identity: a service principal of the test-mode tenant whose token scopes are its groups (identities.ts). */
export interface E2eIdentity {
  name: string;
  /** The person the server resolved for the token (GET /me userId). */
  userId: string;
  token: string;
}

/** The compliance baseline global-setup publishes on the India pack (compliance.ts). */
export interface ComplianceSeed {
  jurisdiction: string;
  purposeCode: string;
  noticeKey: string;
  noticeVersion: number;
  dpo: { name: string; email: string };
}

export interface Seed {
  definitionKey: string;
  definitionId: string;
  processInstanceId: string;
  taskId: string;
  identities: { citizen: E2eIdentity; roleless: E2eIdentity; complianceOfficer: E2eIdentity };
  compliance: ComplianceSeed;
}

export function readSeed(): Seed {
  return JSON.parse(readFileSync(SEED_FILE, 'utf8')) as Seed;
}
