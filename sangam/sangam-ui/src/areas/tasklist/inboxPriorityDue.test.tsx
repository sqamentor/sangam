// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * Lane R9-filters: the task screen filters by priority and due date through GET /tasks (minPriority, maxPriority,
 * overdue, dueBefore, dueAfter); the server applies them inside the caller's own tenant and visibility.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import i18n from 'i18next';

const api = vi.hoisted(() => ({ get: vi.fn(), page: vi.fn() }));
vi.mock('../../api/client', async (orig) => {
  const real = await orig<typeof import('../../api/client')>();
  return { ...real, http: { ...real.http, get: api.get, page: api.page } };
});
vi.mock('../../auth/AuthProvider', () => ({ useAuth: () => ({ me: { userId: 'u1', roles: ['TASK_USER'], permissions: ['task.read'], seats: [] }, seat: null }) }));

import { initI18n } from '../../i18n';
import { TaskInbox } from './TaskInbox';

const T = (k: string) => i18n.t(k) as string;

function lastQuery(): Record<string, unknown> {
  const calls = api.page.mock.calls.filter((c) => c[0] === '/tasks');
  expect(calls.length).toBeGreaterThan(0);
  return calls[calls.length - 1][1] as Record<string, unknown>;
}

beforeAll(() => initI18n('en'));
beforeEach(() => {
  api.get.mockReset();
  api.page.mockReset();
  api.get.mockResolvedValue({});
  api.page.mockResolvedValue({ items: [], page: 0, size: 25, total: 0, capped: false });
  window.localStorage.clear();
});
afterEach(cleanup);

describe('R9-filters: priority and due date on the task screen', () => {
  it('sends minPriority, maxPriority, overdue and due dates, and drops them again', async () => {
    render(
      <MemoryRouter>
        <TaskInbox />
      </MemoryRouter>,
    );
    await waitFor(() => expect(api.page).toHaveBeenCalled());
    expect(lastQuery().minPriority).toBeUndefined();
    expect(lastQuery().overdue).toBeUndefined();
    fireEvent.change(screen.getByLabelText(T('task.minPriority')), { target: { value: '50' } });
    await waitFor(() => expect(lastQuery().minPriority).toBe(50));
    fireEvent.change(screen.getByLabelText(T('task.maxPriority')), { target: { value: '80' } });
    await waitFor(() => expect(lastQuery().maxPriority).toBe(80));
    fireEvent.change(screen.getByLabelText(T('task.overdueOnly')), { target: { value: 'true' } });
    await waitFor(() => expect(lastQuery().overdue).toBe(true));
    fireEvent.change(screen.getByLabelText(T('task.dueAfter')), { target: { value: '2026-11-01' } });
    await waitFor(() => expect(lastQuery().dueAfter).toBe(new Date('2026-11-01T00:00').toISOString()));
    fireEvent.change(screen.getByLabelText(T('task.dueBefore')), { target: { value: '2026-12-01' } });
    await waitFor(() => expect(lastQuery().dueBefore).toBe(new Date('2026-12-01T00:00').toISOString()));
    fireEvent.change(screen.getByLabelText(T('task.minPriority')), { target: { value: 'x' } });
    await waitFor(() => expect(lastQuery().minPriority).toBeUndefined());
    fireEvent.change(screen.getByLabelText(T('task.overdueOnly')), { target: { value: '' } });
    await waitFor(() => expect(lastQuery().overdue).toBeUndefined());
  });
});
