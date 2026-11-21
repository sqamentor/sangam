// SPDX-License-Identifier: Apache-2.0
/**
 * Test support (U12): the callers GET /me really describes, one per server group, built from the server's own sources
 * and not from a hand-written role list:
 * - permissions: Permissions.DEFAULT_GROUP_PERMISSIONS (sangam-rest-core support/Permissions.java), parsed here from
 *   the Java source, as the default PermissionPort reports them (GroupPermissionAdapter.permissions: the union of the
 *   caller's groups, and exactly "*" for the group sangam-admin);
 * - roles: OperatonWorkspaceAdapter.roles (group → role, PLATFORM_OPERATOR for the engine groups, ANALYST for every
 *   holder of analytics.read or "*"). ROLE_OF_GROUP below is a transcription; serverGroups.test.ts checks it line by
 *   line against the Java source.
 *
 * A deployment with sangam-security decides permissions by policy (VI.4); those callers are not modelled here.
 */
import type { Role } from '../api/types';
import permissionsJava from '../../../sangam-rest-core/src/main/java/org/sangam/rest/support/Permissions.java?raw';

const source = permissionsJava.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');

/** String constants of Permissions.java: GROUP_USER → "sangam-user", TASK_READ → "task.read", ... */
const CONSTANTS: Record<string, string> = Object.fromEntries([...source.matchAll(/String\s+(\w+)\s*=\s*"([^"]+)"\s*;/g)].map((m) => [m[1], m[2]]));

const SETS: Record<string, Set<string>> = {};

/** Evaluates a Java set expression of that file: Set.of(...), union(a, b) or the name of a set declared earlier. */
function evaluate(tokens: string[]): Set<string> {
  const head = tokens.shift();
  const expect = (t: string) => {
    const got = tokens.shift();
    if (got !== t) throw new Error(`Permissions.java: expected ${t}, found ${got}`);
  };
  if (head === 'union') {
    expect('(');
    const a = evaluate(tokens);
    expect(',');
    const b = evaluate(tokens);
    expect(')');
    return new Set([...a, ...b]);
  }
  if (head === 'Set.of') {
    expect('(');
    const out = new Set<string>();
    for (let t = tokens.shift(); t !== ')'; t = tokens.shift()) {
      if (t === undefined) throw new Error('Permissions.java: Set.of is not closed');
      if (t === ',') continue;
      if (t.startsWith('"')) out.add(t.slice(1, -1));
      else if (t in CONSTANTS) out.add(CONSTANTS[t]);
      else throw new Error(`Permissions.java: unknown constant ${t}`);
    }
    return out;
  }
  if (head && head in SETS) return SETS[head];
  throw new Error(`Permissions.java: unknown set expression ${head}`);
}
const tokenize = (expr: string) => expr.match(/"[^"]*"|[A-Za-z_][\w.]*|[(),]/g) ?? [];

for (const m of source.matchAll(/static final Set<String>\s+(\w+)\s*=\s*([^;]+);/g)) SETS[m[1]] = evaluate(tokenize(m[2]));

/** Group → permissions, exactly Permissions.DEFAULT_GROUP_PERMISSIONS. */
export const GROUP_PERMISSIONS: Record<string, string[]> = (() => {
  const body = /DEFAULT_GROUP_PERMISSIONS\s*=\s*Map\.ofEntries\(([\s\S]*?)\)\s*;/.exec(source);
  if (!body) throw new Error('Permissions.java: DEFAULT_GROUP_PERMISSIONS not found');
  const tokens = tokenize(body[1]);
  const out: Record<string, string[]> = {};
  while (tokens.length) {
    const t = tokens.shift();
    if (t === ',') continue;
    if (t !== 'Map.entry' || tokens.shift() !== '(') throw new Error(`Permissions.java: expected Map.entry(, found ${t}`);
    const group = CONSTANTS[tokens.shift() ?? ''];
    if (!group || tokens.shift() !== ',') throw new Error('Permissions.java: Map.entry without a group constant');
    out[group] = [...evaluate(tokens)].sort();
    if (tokens.shift() !== ')') throw new Error(`Permissions.java: Map.entry of ${group} is not closed`);
  }
  return out;
})();

/** OperatonWorkspaceAdapter.roles, the byGroup map (checked against the Java source by serverGroups.test.ts). */
export const ROLE_OF_GROUP: Record<string, Role[]> = {
  'sangam-user': ['TASK_USER'],
  'sangam-operator': ['OPERATOR'],
  'sangam-admin': ['ADMIN', 'PLATFORM_ADMIN'],
  'sangam-tenant-admin': ['ADMIN'],
  'sangam-model-author': ['MODELLER'],
  'sangam-model-reviewer': ['REVIEWER'],
  'sangam-model-approver': ['REVIEWER'],
  'sangam-auditor': ['AUDITOR'],
  'sangam-integration': ['INTEGRATION'],
  'sangam-citizen': ['CITIZEN'],
};
/** Permissions.ENGINE_REST_GROUPS: these groups add PLATFORM_OPERATOR. */
export const ENGINE_GROUPS: string[] = ['sangam-admin', 'sangam-engine-admin'];

export interface ServerCaller {
  groups: string[];
  roles: Role[];
  permissions: string[];
}

/** What GET /me reports for a caller in these groups (default PermissionPort). */
export function serverMe(...groups: string[]): ServerCaller {
  const permissions = groups.includes('sangam-admin') ? ['*'] : [...new Set(groups.flatMap((g) => GROUP_PERMISSIONS[g] ?? []))].sort();
  const roles = new Set<Role>();
  for (const g of groups) {
    for (const r of ROLE_OF_GROUP[g] ?? []) roles.add(r);
    if (ENGINE_GROUPS.includes(g)) roles.add('PLATFORM_OPERATOR');
  }
  if (permissions.includes('analytics.read') || permissions.includes('*')) roles.add('ANALYST');
  return { groups, roles: [...roles], permissions };
}

/**
 * One caller per server group. sangam-admin is the platform administrator, whose identity also carries the marker
 * group sangam-platform (Permissions.java GROUP_PLATFORM); sangam-engine-admin has no entry in the permission map.
 */
export const SERVER_GROUPS: string[] = [
  'sangam-user',
  'sangam-supervisor',
  'sangam-operator',
  'sangam-auditor',
  'sangam-dpo',
  'sangam-tenant-admin',
  'sangam-admin',
  'sangam-engine-admin',
  'sangam-model-author',
  'sangam-model-reviewer',
  'sangam-model-approver',
  'sangam-model-deployer',
  'sangam-integration',
  'sangam-worker',
  'sangam-system',
  'sangam-citizen',
];
export const callerOf = (group: string): ServerCaller => (group === 'sangam-admin' ? serverMe('sangam-admin', 'sangam-platform') : serverMe(group));
