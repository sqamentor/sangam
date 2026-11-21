// SPDX-License-Identifier: Apache-2.0
/** U12: the test callers are the server's own groups (serverGroups.testkit.ts), checked here against the Java sources. */
import { describe, expect, it } from 'vitest';
import workspaceJava from '../../../sangam-rest-core/src/main/java/org/sangam/rest/adapter/operaton/OperatonWorkspaceAdapter.java?raw';
import permissionAdapterJava from '../../../sangam-rest-core/src/main/java/org/sangam/rest/adapter/memory/GroupPermissionAdapter.java?raw';
import permissionsJava from '../../../sangam-rest-core/src/main/java/org/sangam/rest/support/Permissions.java?raw';
import { ENGINE_GROUPS, GROUP_PERMISSIONS, ROLE_OF_GROUP, SERVER_GROUPS, callerOf, serverMe } from './serverGroups.testkit';

const constant = (name: string) => new RegExp(`String ${name} = "([^"]+)"`).exec(permissionsJava)?.[1];

describe('the server groups the menu tests are built from', () => {
  it('reads every group of Permissions.DEFAULT_GROUP_PERMISSIONS', () => {
    const body = permissionsJava.slice(permissionsJava.indexOf('DEFAULT_GROUP_PERMISSIONS = Map.ofEntries('));
    const groups = [...body.matchAll(/Map\.entry\((GROUP_\w+),/g)].map((m) => constant(m[1]));
    expect(groups).toHaveLength(14);
    expect(Object.keys(GROUP_PERMISSIONS).sort()).toEqual([...groups].sort());
    // every group with permissions is a test caller; sangam-admin ("*") and sangam-engine-admin have no entry
    expect([...SERVER_GROUPS].sort()).toEqual([...groups, 'sangam-admin', 'sangam-engine-admin'].sort());
  });

  it('evaluates the Java set expressions (spot values read from the source by hand)', () => {
    expect(GROUP_PERMISSIONS['sangam-supervisor']).toEqual(['team.read', 'team.write']);
    expect(GROUP_PERMISSIONS['sangam-citizen']).toEqual(['portal.use']);
    expect(GROUP_PERMISSIONS['sangam-integration']).toEqual(['deployment.read', 'event.publish', 'message.send', 'process.read', 'process.start', 'work.execute']);
    expect(GROUP_PERMISSIONS['sangam-model-reviewer']).toEqual(['deployment.read', 'package.read', 'package.review']);
    // USER = USER_BASE (18) + MODULE_READ (9, case.read is in both) + field.capture, ai.use, search.use
    expect(GROUP_PERMISSIONS['sangam-user']).toHaveLength(29);
    expect(GROUP_PERMISSIONS['sangam-user']).toEqual(expect.arrayContaining(['task.write', 'form.read', 'ledger.read', 'channels.read', 'field.capture', 'ai.use']));
    expect(GROUP_PERMISSIONS['sangam-user']).not.toContain('team.read');
    expect(GROUP_PERMISSIONS['sangam-operator']).toEqual(expect.arrayContaining(['ops.read', 'debug.use', 'team.read', 'analytics.read', 'compliance.read', 'form.read', 'deployment.read']));
    expect(GROUP_PERMISSIONS['sangam-operator']).not.toContain('event.admin');
    expect(GROUP_PERMISSIONS['sangam-auditor']).toEqual(expect.arrayContaining(['audit.read', 'analytics.read', 'ledger.read', 'compliance.read']));
    expect(GROUP_PERMISSIONS['sangam-auditor']).not.toContain('admin.read');
    expect(GROUP_PERMISSIONS['sangam-tenant-admin']).toEqual(expect.arrayContaining(['tenant.admin', 'event.admin', 'team.read', 'form.read', 'analytics.read', 'ledger.admin']));
    expect(GROUP_PERMISSIONS['sangam-tenant-admin']).not.toContain('task.write');
  });

  it('maps groups to roles as OperatonWorkspaceAdapter.roles does', () => {
    const entries = [...workspaceJava.matchAll(/Map\.entry\(Permissions\.(GROUP_\w+), List\.of\(([^)]*)\)\)/g)];
    expect(entries).toHaveLength(10);
    const java = Object.fromEntries(entries.map((m) => [constant(m[1]), [...m[2].matchAll(/"(\w+)"/g)].map((r) => r[1])]));
    expect(ROLE_OF_GROUP).toEqual(java);
    expect(workspaceJava).toContain('if (Permissions.ENGINE_REST_GROUPS.contains(g)) {');
    expect(permissionsJava).toContain('ENGINE_REST_GROUPS = Set.of(GROUP_ADMIN, GROUP_ENGINE_ADMIN);');
    expect(ENGINE_GROUPS).toEqual([constant('GROUP_ADMIN'), constant('GROUP_ENGINE_ADMIN')]);
    expect(workspaceJava).toContain('if (perms.contains(Permissions.ANALYTICS_READ) || perms.contains("*")) {');
    // the platform administrator holds exactly "*"
    expect(permissionAdapterJava).toMatch(/if \(ctx\.inGroup\(Permissions\.GROUP_ADMIN\)\) \{\s*return Set\.of\(ALL\);/);
  });

  it('describes each caller as GET /me does', () => {
    expect(callerOf('sangam-user').roles).toEqual(['TASK_USER']);
    expect(callerOf('sangam-operator').roles).toEqual(['OPERATOR', 'ANALYST']);
    expect(callerOf('sangam-auditor').roles).toEqual(['AUDITOR', 'ANALYST']);
    expect(callerOf('sangam-tenant-admin').roles).toEqual(['ADMIN', 'ANALYST']);
    expect(callerOf('sangam-admin')).toEqual({ groups: ['sangam-admin', 'sangam-platform'], roles: ['ADMIN', 'PLATFORM_ADMIN', 'PLATFORM_OPERATOR', 'ANALYST'], permissions: ['*'] });
    expect(callerOf('sangam-engine-admin')).toEqual({ groups: ['sangam-engine-admin'], roles: ['PLATFORM_OPERATOR'], permissions: [] });
    expect(callerOf('sangam-supervisor').roles).toEqual([]);
    expect(callerOf('sangam-dpo').roles).toEqual([]);
    expect(callerOf('sangam-model-deployer').roles).toEqual([]);
    expect(callerOf('sangam-integration').roles).toEqual(['INTEGRATION']);
    expect(serverMe('sangam-user', 'sangam-supervisor').permissions).toEqual(expect.arrayContaining(['task.read', 'team.read']));
  });
});
