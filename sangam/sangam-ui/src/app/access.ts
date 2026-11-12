// SPDX-License-Identifier: Apache-2.0
/**
 * Who may open a screen (M-028). Each requirement is taken from the server's own endpoint gate for the call the screen
 * makes to show itself, so the UI hides exactly what the server would refuse and nothing more. The server stays the
 * security boundary (X.4.7); a guard is a convenience that avoids a page of 403s.
 *
 * A screen is shown when the caller holds one of its roles OR one of its endpoint permissions:
 * - roles are the /me roles of the groups whose default permission set contains the permission
 *   (sangam-rest support/Permissions.java DEFAULT_GROUP_PERMISSIONS, mapped to roles by
 *   OperatonWorkspaceAdapter.roles); sangam-admin (roles ADMIN + PLATFORM_ADMIN) holds every permission
 *   (GroupPermissionAdapter.permissions: "*");
 * - permissions are the endpoint permissions /me reports (WorkspacePort.Me.permissions, which also carries grants made
 *   in sangam-security, SecurityPermissionBridge.permissions). This keeps a screen visible to a caller who holds the
 *   permission without a persona role, e.g. a data protection officer (group sangam-dpo has no UI role).
 */
import type { Me, Role } from '../api/types';

export interface Access {
  roles: Role[];
  /** Endpoint permissions (Permissions.java names); any one admits. "*" in /me admits everything. */
  perms: string[];
}

/**
 * Holders of every MODULE_READ permission (Permissions.java:69-70): sangam-user via USER (:115), sangam-operator via
 * USER (:123), sangam-auditor (:126), sangam-tenant-admin (:128) and sangam-admin ("*"). Their roles: TASK_USER,
 * OPERATOR, AUDITOR, ADMIN, and ADMIN + PLATFORM_ADMIN. ANALYST is not one: it is derived from analytics.read alone
 * (OperatonWorkspaceAdapter.roles), which grants no module permission.
 */
export const MODULE_READERS: Role[] = ['TASK_USER', 'OPERATOR', 'ADMIN', 'PLATFORM_ADMIN', 'AUDITOR'];

/**
 * Holders of the USER-only extras "field.capture" and "ai.use" (Permissions.java:115): sangam-user, sangam-operator
 * (USER is part of OPERATOR, :123) and sangam-admin ("*"). A tenant administrator (:128-131) and an auditor (:126-127)
 * do not hold them, so the bare ADMIN role (shared by tenant and platform administrators) does not qualify.
 */
export const USER_EXTRA_HOLDERS: Role[] = ['TASK_USER', 'OPERATOR', 'PLATFORM_ADMIN'];

/** Holders of compliance.read: sangam-operator (:125), sangam-auditor (:127), sangam-tenant-admin (:130), sangam-admin. */
export const COMPLIANCE_READERS: Role[] = ['OPERATOR', 'AUDITOR', 'ADMIN', 'PLATFORM_ADMIN'];

const moduleRead = (perm: string): Access => ({ roles: MODULE_READERS, perms: [perm] });

/**
 * Route → requirement, for the routes whose screens call module APIs. The permission is the one the server checks
 * on the screen's first call (the first tab's list):
 */
export const ROUTE_ACCESS: Record<string, Access> = {
  // GET /ai/agent-runs: AiUiController.java:68,72 → ai.use (AiController.java:46)
  '/m/ai': { roles: USER_EXTRA_HOLDERS, perms: ['ai.use'] },
  // GET /channels/status: ChannelsController.java:144,146 → channels.read (:29)
  '/m/channels': moduleRead('channels.read'),
  // GET /registry/objects: RegistryController.java:95; interceptor RegistryRestConfiguration.java:72 → registry.read
  '/m/registry': moduleRead('registry.read'),
  // GET /resources/items: ResourcesController.java:130,137,459 → resources.read (ResourcesPermissions.java:11)
  '/m/resources': moduleRead('resources.read'),
  // GET /queues/service-points: QueueController.java:79,83,223 → resources.read
  '/m/queues': moduleRead('resources.read'),
  // GET /rosters/periods: RosterController.java:103,108,308 → resources.read
  '/m/rosters': moduleRead('resources.read'),
  // GET /case-files: CaseFileController.java:89,100 → case.read (CaseIdentity.java:26,38)
  '/m/case-files': moduleRead('case.read'),
  // GET /committees: MeetingController.java:69,73 → case.read
  '/m/committees': moduleRead('case.read'),
  // GET /references: ReferenceController.java:54,63 → case.read
  '/m/references': moduleRead('case.read'),
  // GET /ledger/{ledger}/accounts: LedgerController.java:118,122 → ledger.read (:40)
  '/m/ledger': moduleRead('ledger.read'),
  // GET /billing/{ledger}/accounts: BillingController.java:79,82 → billing.read (:39)
  '/m/billing': moduleRead('billing.read'),
  // GET /programmes: ProgrammeController.java:170,175,480 → programme.read (:54)
  '/m/programmes': moduleRead('programme.read'),
  // GET /field/templates: FieldWorkController.java:56,58,396 → field.capture (:40)
  '/m/field': { roles: USER_EXTRA_HOLDERS, perms: ['field.capture'] },
  // GET /geo/layers: GeoController.java:44; interceptor GeoRestConfiguration.java:66 → geo.read (:47)
  '/m/geo': moduleRead('geo.read'),
  // GET /compliance/purposes: ComplianceController.java:122,124,80 → compliance.read (Permissions.java:61)
  '/m/compliance': { roles: COMPLIANCE_READERS, perms: ['compliance.read'] },
};

/**
 * Routes deliberately left to every signed-in caller, because the server asks for authentication only:
 * - /x/:pluginId/:id: GET /plugins/ui (UiPluginController.java:32) has no endpoint permission, /api/** is
 *   authenticated (SecurityConfig.java:108); the plugin's own calls are authorised by the server one by one;
 * - /notifications: GET /me/notifications (WorkspaceController.java:54) and mark-read (:60) have no endpoint
 *   permission: every caller reads their own notifications.
 */
export const SIGNED_IN_ROUTES: readonly string[] = ['/x/:pluginId/:id', '/notifications'];

/** Whether the caller may open a screen with this requirement (role or endpoint permission). */
export function canAccess(me: Pick<Me, 'roles' | 'permissions'> | null | undefined, access: Access): boolean {
  if (!me) return false;
  if (access.roles.some((r) => me.roles.includes(r))) return true;
  const held = me.permissions ?? [];
  return held.includes('*') || access.perms.some((p) => held.includes(p));
}
