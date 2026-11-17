// SPDX-License-Identifier: Apache-2.0
/**
 * CERT-In / DPDP e2e identities (docs/compliance/CERTIN-DPDP-CONTROL-MATRIX.md, prerequisites): a data principal
 * (citizen), a signed-in user with no role, and a compliance officer, next to the test-mode server's one development
 * token (the platform administrator).
 *
 * How, without touching a production guard: the development identity creates service principals through the product's
 * own administration API, POST /api/v1/admin/service-principals (tenant.admin; IdentityAdminController.createServicePrincipal,
 * SecurityIdentityAdminBridge.createServicePrincipal). Decisions 358 and 368: each scope is first bound to the principal as
 * a defined role of the tenant (the platform groups are role rows created with the tenant; the role-less scope is
 * defined here through PUT /api/v1/admin/roles/by-role-id?roleId=, a role that grants nothing), so an undefined scope is refused.
 * The answer carries an API token (sgm_<id>.<secret>, ApiTokenService.issue); the token's scopes are the caller's groups
 * (ApiTokenService.authenticate: roles = scopes; SecurityIdentityFilter keeps them for API_KEY), and GET /me turns the
 * groups into roles (OperatonWorkspaceAdapter.roles): sangam-citizen gives CITIZEN and portal.use, sangam-dpo gives the
 * compliance permissions (Permissions.java), a scope that names no group gives no role and no permission. The UI's
 * development sign-in sends whatever token it holds as the bearer (AuthProvider.tsx), so a spec signs in as any of
 * them. These tokens live only in the throw-away in-memory test-mode server (deploy/docker/server-test-mode.Dockerfile)
 * and in the git-ignored e2e/.seed/seed.json; they are test values, never secrets.
 *
 * Every identity is checked through GET /me before a spec uses it, so a mapping change fails the setup, loudly, instead
 * of letting an access-control spec pass for the wrong reason.
 */
import { randomUUID } from 'node:crypto';
import { request, type APIRequestContext } from '@playwright/test';
import { API_BASE, type E2eIdentity } from './env';

/** Token scopes (= groups) of each e2e identity. */
export const IDENTITY_SCOPES = {
  citizen: ['sangam-citizen'],
  // a scope that is no Sangam group: signed in, no role, no permission
  roleless: ['e2e-no-group'],
  complianceOfficer: ['sangam-dpo'],
} as const;

interface MeBody {
  userId: string;
  roles?: string[];
  permissions?: string[];
}

/** An API client signed in with `token`. */
export async function apiAs(token: string): Promise<APIRequestContext> {
  return request.newContext({
    baseURL: `${API_BASE.replace(/\/$/, '')}/`,
    extraHTTPHeaders: { Authorization: `Bearer ${token}`, Accept: 'application/json, application/problem+json' },
  });
}

/** GET /me as `token`. */
export async function meOf(token: string): Promise<MeBody> {
  const ctx = await apiAs(token);
  try {
    const res = await ctx.get('me');
    if (!res.ok()) throw new Error(`GET /me answered ${res.status()} ${await res.text()}`);
    return (await res.json()) as MeBody;
  } finally {
    await ctx.dispose();
  }
}

/**
 * Creates a service principal `name` with `scopes` as the development administrator (`admin`) and returns its token
 * and the person the server resolves for it. `expiresAt` defaults to the server's lifetime.
 */
export async function createIdentity(admin: APIRequestContext, name: string, scopes: readonly string[], expiresAt?: Date): Promise<E2eIdentity> {
  const res = await admin.post('admin/service-principals', {
    headers: { 'Idempotency-Key': randomUUID() },
    data: { name, scopes: [...scopes], topics: ['e2e-unused'], ...(expiresAt ? { expiresAt: expiresAt.toISOString() } : {}) },
  });
  if (res.status() !== 201) throw new Error(`POST /admin/service-principals (${name}) answered ${res.status()} ${await res.text()}`);
  const body = (await res.json()) as { secret?: string };
  if (!body.secret?.startsWith('sgm_')) throw new Error(`POST /admin/service-principals (${name}) returned no sgm_ token`);
  const me = await meOf(body.secret);
  return { name, userId: me.userId, token: body.secret };
}

/**
 * Defines `roleId` as a role of the tenant that grants nothing itself (PUT /admin/roles/by-role-id?roleId=, decision 368; decision 393: role ids may carry ':'), so a
 * service principal may be given it as a scope. Re-running the set-up replaces it with the same definition.
 */
export async function defineEmptyRole(admin: APIRequestContext, roleId: string): Promise<void> {
  const res = await admin.put(`admin/roles/by-role-id?roleId=${encodeURIComponent(roleId)}`, {
    headers: { 'Idempotency-Key': randomUUID() },
    data: { name: roleId, rules: [] },
  });
  if (res.status() !== 200) throw new Error(`PUT /admin/roles/by-role-id?roleId=${roleId} answered ${res.status()} ${await res.text()}`);
}

/** The three identities, each checked against what the specs rely on. */
export async function createE2eIdentities(admin: APIRequestContext): Promise<{ citizen: E2eIdentity; roleless: E2eIdentity; complianceOfficer: E2eIdentity }> {
  for (const scope of IDENTITY_SCOPES.roleless) await defineEmptyRole(admin, scope);
  const citizen = await createIdentity(admin, 'e2e-citizen', IDENTITY_SCOPES.citizen);
  const roleless = await createIdentity(admin, 'e2e-roleless', IDENTITY_SCOPES.roleless);
  const complianceOfficer = await createIdentity(admin, 'e2e-compliance-officer', IDENTITY_SCOPES.complianceOfficer);

  const c = await meOf(citizen.token);
  if (!c.roles?.includes('CITIZEN') || !c.permissions?.includes('portal.use')) throw new Error(`e2e citizen: /me has roles ${c.roles} permissions ${c.permissions}`);
  const r = await meOf(roleless.token);
  const granted = (r.permissions ?? []).filter((p) => p === '*' || /^(compliance|audit|admin|tenant|portal|policy)\./.test(p));
  if ((r.roles ?? []).length > 0 || granted.length > 0) throw new Error(`e2e role-less user: /me has roles ${r.roles} permissions ${r.permissions}`);
  const o = await meOf(complianceOfficer.token);
  for (const p of ['compliance.read', 'compliance.modify', 'compliance.admin']) {
    if (!o.permissions?.includes(p)) throw new Error(`e2e compliance officer: /me lacks ${p} (permissions ${o.permissions})`);
  }
  return { citizen, roleless, complianceOfficer };
}
