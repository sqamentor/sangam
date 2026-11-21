// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * R8-present (owner, 2026-11-17): after sign-in a seat holder lands on their own work queue (/tasks, the inbox of
 * their seat); everyone else keeps /welcome. Home decides on the signed-in person's seats (/me), not on a role.
 */
import { afterEach, expect, it, vi } from 'vitest';
import { cleanup, render, waitFor } from '@testing-library/react';
import type { Role } from '../api/types';

const auth = vi.hoisted(() => ({ roles: [] as string[], seats: [] as unknown[], permissions: [] as string[] }));

vi.mock('../auth/AuthProvider', async (orig) => {
  const real = await orig<typeof import('../auth/AuthProvider')>();
  return {
    ...real,
    useAuth: () => ({
      status: 'authenticated',
      me: { userId: 'p1', displayName: 'P', tenantId: 't', roles: auth.roles, seats: auth.seats, permissions: auth.permissions },
      seat: auth.seats[0] ?? null,
      hasRole: (...r: Role[]) => r.some((x) => auth.roles.includes(x)),
      can: () => true,
      logout: () => undefined,
    }),
  };
});
vi.mock('../auth/IdleSession', () => ({ IdleSession: () => null }));
vi.mock('../areas/tasklist/TaskInbox', () => ({ TaskInbox: () => <p>inbox</p>, default: () => <p>inbox</p> }));
vi.mock('../modules/compliance', () => ({ CompliancePage: () => <p>data protection</p> }));
vi.mock('../areas/tasklist/TeamWorkload', () => ({ TeamWorkload: () => <p>team workload</p> }));

import { App } from './App';

// What GET /me reports for these server groups. A jsdom test may not read the Java source (Vite fs.allow), so the
// values are written out here and serverGroups.test.ts checks the same values against Permissions.java.
const SUPERVISOR = { roles: [] as string[], permissions: ['team.read', 'team.write'] };
const WORKER = { roles: [] as string[], permissions: ['document.read', 'document.write', 'event.publish', 'message.send', 'work.execute'] };
const DPO = { roles: [] as string[], permissions: ['audit.export', 'audit.read', 'compliance.admin', 'compliance.modify', 'compliance.read', 'document.legal-hold', 'document.read', 'history.read'] };
const TENANT_ADMIN = { roles: ['ADMIN', 'ANALYST'], permissions: ['admin.read', 'admin.write', 'analytics.read', 'team.read', 'team.write', 'tenant.admin'] };

afterEach(() => {
  cleanup();
  auth.permissions = [];
  window.history.replaceState(null, '', '/');
});

const SEAT = { seatId: 'DDO-1', seatName: 'Drawing and disbursing officer', designation: 'DDO', orgUnitName: 'Office',
  kind: 'SUBSTANTIVE', validFrom: '2026-01-01' };

it('a seat holder lands on /tasks', async () => {
  auth.roles = ['TASK_USER'];
  auth.seats = [SEAT];
  render(<App />);
  await waitFor(() => expect(window.location.pathname).toBe('/tasks'));
}, 30_000);

it('a signed-in person without a seat stays on /welcome', async () => {
  auth.roles = ['TASK_USER'];
  auth.seats = [];
  render(<App />);
  await waitFor(() => expect(window.location.pathname).toBe('/welcome'));
}, 30_000);

// U12i: Home looked at roles only, so a supervisor (group sangam-supervisor: no role, team.read) was sent to
// /help/about although the menu shows him My work with Team workload.
it('a caller holding only team.read lands on My work (Team workload), not on /help/about', async () => {
  auth.roles = SUPERVISOR.roles;
  auth.permissions = SUPERVISOR.permissions;
  auth.seats = [];
  const { findByText } = render(<App />);
  await waitFor(() => expect(window.location.pathname).toBe('/team'));
  expect(await findByText('team workload')).toBeTruthy();
}, 30_000);

it('a caller with no job group at all still lands on /help/about', async () => {
  auth.roles = WORKER.roles;
  auth.permissions = WORKER.permissions;
  auth.seats = [];
  render(<App />);
  await waitFor(() => expect(window.location.pathname).toBe('/help/about'));
}, 30_000);

it('an organisation admin (team.read and the role ADMIN) keeps /welcome', async () => {
  auth.roles = TENANT_ADMIN.roles;
  auth.permissions = TENANT_ADMIN.permissions;
  auth.seats = [];
  render(<App />);
  await waitFor(() => expect(window.location.pathname).toBe('/welcome'));
}, 30_000);
// Owner answer 2026-11-21 (joint ledger line 1234, "Their compliance page"): Home lands on the first page the caller's
// permissions open; nobody with real work lands on Help. A data protection officer (group sangam-dpo: no role,
// compliance.read) was sent to /help/about although his menu has Reports and audit > Data protection.
it('a data protection officer lands on the compliance page, not on /help/about', async () => {
  auth.roles = DPO.roles;
  auth.permissions = DPO.permissions;
  auth.seats = [];
  const { findByText } = render(<App />);
  await waitFor(() => expect(window.location.pathname).toBe('/m/compliance'));
  expect(await findByText('data protection')).toBeTruthy();
}, 30_000);