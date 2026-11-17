// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * Decision 404: running a shared task list of the organisation from the task screen. The screen sends the list's id
 * (GET /tasks?filterId=...); the server applies the list's criteria within the caller's own tenant and visibility.
 * Choosing lists needs team.read (GET /team/task-filters); without it the choice is not offered and not fetched.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import i18n from 'i18next';

const api = vi.hoisted(() => ({ get: vi.fn(), page: vi.fn() }));
const auth = vi.hoisted(() => ({ me: { userId: 'u1', roles: ['TASK_USER'], permissions: ['task.read', 'team.read'] as string[], seats: [] } }));

vi.mock('../../api/client', async (orig) => {
  const real = await orig<typeof import('../../api/client')>();
  return { ...real, http: { ...real.http, get: api.get, page: api.page } };
});
vi.mock('../../auth/AuthProvider', () => ({ useAuth: () => ({ me: auth.me, seat: null }) }));

import { initI18n } from '../../i18n';
import { TaskInbox } from './TaskInbox';

const T = (k: string) => i18n.t(k) as string;
const PAGE = { items: [], page: 0, size: 25, total: 0, capped: false };
const LISTS = [{ id: 'f1', name: 'Accounts queue', owner: null, query: { candidateGroup: 'accounts' }, properties: {}, createdBy: 'u9', createdAt: '2026-11-17T08:00:00Z', version: 1 }];

function lastQuery(): Record<string, unknown> {
  const calls = api.page.mock.calls.filter((c) => c[0] === '/tasks');
  expect(calls.length).toBeGreaterThan(0);
  return calls[calls.length - 1][1] as Record<string, unknown>;
}

beforeAll(() => initI18n('en'));
beforeEach(() => {
  api.get.mockReset();
  api.page.mockReset();
  api.get.mockImplementation(async (path: string) => (path === '/team/task-filters' ? LISTS : {}));
  api.page.mockResolvedValue(PAGE);
  window.localStorage.clear();
  auth.me = { userId: 'u1', roles: ['TASK_USER'], permissions: ['task.read', 'team.read'], seats: [] };
});
afterEach(cleanup);

const show = () =>
  render(
    <MemoryRouter>
      <TaskInbox />
    </MemoryRouter>,
  );

describe('decision 404: a shared task list on the task screen', () => {
  it('sends the chosen list as filterId and drops it again', async () => {
    show();
    const select = (await screen.findByLabelText(T('task.teamList'))) as HTMLSelectElement;
    await screen.findByRole('option', { name: 'Accounts queue' });
    expect(lastQuery().filterId).toBeUndefined();
    fireEvent.change(select, { target: { value: 'f1' } });
    await waitFor(() => expect(lastQuery().filterId).toBe('f1'));
    fireEvent.change(select, { target: { value: '' } });
    await waitFor(() => expect(lastQuery().filterId).toBeUndefined());
  });
  it('offers no shared lists without team.read and does not ask for them', async () => {
    auth.me = { userId: 'u1', roles: ['TASK_USER'], permissions: ['task.read'], seats: [] };
    show();
    await waitFor(() => expect(api.page).toHaveBeenCalled());
    expect(screen.queryByLabelText(T('task.teamList'))).toBeNull();
    expect(api.get.mock.calls.some((c) => c[0] === '/team/task-filters')).toBe(false);
  });
});
