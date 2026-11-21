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
vi.mock('../areas/tasklist/TeamWorkload', () => ({ TeamWorkload: () => <p>team workload</p> }));

import { App } from './App';
import { callerOf } from './serverGroups.testkit';

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
  const supervisor = callerOf('sangam-supervisor');
  expect(supervisor).toEqual({ groups: ['sangam-supervisor'], roles: [], permissions: ['team.read', 'team.write'] });
  auth.roles = supervisor.roles;
  auth.permissions = supervisor.permissions;
  auth.seats = [];
  const { findByText } = render(<App />);
  await waitFor(() => expect(window.location.pathname).toBe('/team'));
  expect(await findByText('team workload')).toBeTruthy();
}, 30_000);

it('a caller with no job group at all still lands on /help/about', async () => {
  const worker = callerOf('sangam-worker');
  auth.roles = worker.roles;
  auth.permissions = worker.permissions;
  auth.seats = [];
  render(<App />);
  await waitFor(() => expect(window.location.pathname).toBe('/help/about'));
}, 30_000);

it('an organisation admin (team.read and the role ADMIN) keeps /welcome', async () => {
  const admin = callerOf('sangam-tenant-admin');
  auth.roles = admin.roles;
  auth.permissions = admin.permissions;
  auth.seats = [];
  render(<App />);
  await waitFor(() => expect(window.location.pathname).toBe('/welcome'));
}, 30_000);