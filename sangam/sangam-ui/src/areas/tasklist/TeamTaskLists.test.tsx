// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/** Decision 404: shared task lists of the caller's organisation (GET/POST /team/task-filters). */
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, screen, waitFor } from '@testing-library/react';
import { initI18n } from '../../i18n';
import { L, problem, show, T } from '../../test/screens';

const taskFilters = vi.fn();
const createTaskFilter = vi.fn();
const updateTaskFilter = vi.fn();
const deleteTaskFilter = vi.fn();
const auth = vi.hoisted(() => ({ me: { userId: 'u1', roles: [] as string[], permissions: ['team.read', 'team.write'] } }));
vi.mock('../../api/endpoints', () => ({ api: { team: { taskFilters: () => taskFilters(), createTaskFilter: (b: unknown, k: string) => createTaskFilter(b, k),
        updateTaskFilter: (id: string, v: number, b: unknown, k: string) => updateTaskFilter(id, v, b, k),
        deleteTaskFilter: (id: string, v: number, r: string, k: string) => deleteTaskFilter(id, v, r, k) } } }));
vi.mock('../../auth/AuthProvider', () => ({ useAuth: () => ({ me: auth.me }) }));

import { TeamTaskLists, parseQuery } from './TeamTaskLists';

const row = { id: 'f1', name: 'Accounts queue', owner: null, query: { candidateGroup: 'accounts' }, properties: {}, createdBy: 'u9', createdAt: '2026-11-17T08:00:00Z', version: 1 };

beforeAll(() => initI18n('en'));
afterEach(() => {
  cleanup();
  taskFilters.mockReset();
  createTaskFilter.mockReset();
  updateTaskFilter.mockReset();
  deleteTaskFilter.mockReset();
  auth.me = { userId: 'u1', roles: [], permissions: ['team.read', 'team.write'] };
});

describe('parseQuery', () => {
  it('accepts a JSON object only', () => {
    expect(parseQuery('{"candidateGroup":"a"}')).toEqual({ query: { candidateGroup: 'a' } });
    expect(parseQuery('nope')).toEqual({ error: 'teamLists.queryNotJson' });
    expect(parseQuery('[1]')).toEqual({ error: 'teamLists.queryNotObject' });
    expect(parseQuery('null')).toEqual({ error: 'teamLists.queryNotObject' });
  });
});

describe('TeamTaskLists', () => {
  it('lists the saved lists of the organisation', async () => {
    taskFilters.mockResolvedValue([row]);
    show(<TeamTaskLists />, '/tasks/filters');
    expect(await screen.findByText('Accounts queue')).toBeTruthy();
    expect(screen.getByText('{"candidateGroup":"accounts"}')).toBeTruthy();
  });
  it('says so when there are none', async () => {
    taskFilters.mockResolvedValue([]);
    show(<TeamTaskLists />, '/tasks/filters');
    expect(await screen.findByText(T('teamLists.none'))).toBeTruthy();
  });
  it('creates a list with an idempotency key and reloads', async () => {
    taskFilters.mockResolvedValue([]);
    createTaskFilter.mockResolvedValue({ ...row, name: 'Desk' });
    show(<TeamTaskLists />, '/tasks/filters');
    await screen.findByText(T('teamLists.none'));
    fireEvent.change(screen.getByLabelText(L('teamLists.name')), { target: { value: ' Desk ' } });
    fireEvent.change(screen.getByLabelText(L('teamLists.query')), { target: { value: '{"candidateGroup":"desk"}' } });
    fireEvent.click(screen.getByRole('button', { name: T('teamLists.createButton') }));
    await waitFor(() => expect(createTaskFilter).toHaveBeenCalledWith({ name: 'Desk', query: { candidateGroup: 'desk' } }, expect.any(String)));
    await waitFor(() => expect(taskFilters).toHaveBeenCalledTimes(2));
  });
  it('checks the form before sending', async () => {
    taskFilters.mockResolvedValue([]);
    show(<TeamTaskLists />, '/tasks/filters');
    await screen.findByText(T('teamLists.none'));
    fireEvent.change(screen.getByLabelText(L('teamLists.name')), { target: { value: '   ' } });
    fireEvent.change(screen.getByLabelText(L('teamLists.query')), { target: { value: '{bad' } });
    fireEvent.click(screen.getByRole('button', { name: T('teamLists.createButton') }));
    expect(await screen.findByText(T('teamLists.nameRequired'))).toBeTruthy();
    expect(screen.getByText(T('teamLists.queryNotJson'))).toBeTruthy();
    expect(createTaskFilter).not.toHaveBeenCalled();
  });
  it('shows the server refusal of a query naming an organisation', async () => {
    taskFilters.mockResolvedValue([]);
    createTaskFilter.mockRejectedValue(problem(422, 'TASK_FILTER_INVALID', 'tenantIdIn is not allowed'));
    show(<TeamTaskLists />, '/tasks/filters');
    await screen.findByText(T('teamLists.none'));
    fireEvent.change(screen.getByLabelText(L('teamLists.name')), { target: { value: 'X' } });
    fireEvent.change(screen.getByLabelText(L('teamLists.query')), { target: { value: '{"tenantIdIn":["b"]}' } });
    fireEvent.click(screen.getByRole('button', { name: T('teamLists.createButton') }));
    expect(await screen.findByText('tenantIdIn is not allowed')).toBeTruthy();
  });
  it('offers no form without team.write', async () => {
    auth.me = { userId: 'u1', roles: [], permissions: ['team.read'] };
    taskFilters.mockResolvedValue([]);
    show(<TeamTaskLists />, '/tasks/filters');
    await screen.findByText(T('teamLists.none'));
    expect(screen.queryByRole('button', { name: T('teamLists.createButton') })).toBeNull();
  });
});

describe('TeamTaskLists: change and delete (decision 404, team.write)', () => {
  it('saves a changed list against the version it was read at', async () => {
    taskFilters.mockResolvedValue([row]);
    updateTaskFilter.mockResolvedValue({ ...row, name: 'Accounts desk', version: 2 });
    show(<TeamTaskLists />, '/tasks/filters');
    fireEvent.click(await screen.findByRole('button', { name: T('teamLists.editNamed').replace('{{name}}', 'Accounts queue') }));
    const name = screen.getByLabelText(L('teamLists.name')) as HTMLInputElement;
    expect(name.value).toBe('Accounts queue');
    fireEvent.change(name, { target: { value: 'Accounts desk' } });
    fireEvent.click(screen.getByRole('button', { name: T('teamLists.saveButton') }));
    await waitFor(() => expect(updateTaskFilter).toHaveBeenCalledWith('f1', 1, { name: 'Accounts desk', query: { candidateGroup: 'accounts' } }, expect.any(String)));
    expect(createTaskFilter).not.toHaveBeenCalled();
    await waitFor(() => expect(taskFilters).toHaveBeenCalledTimes(2));
  });
  it('shows a stale-version refusal and changes nothing locally', async () => {
    taskFilters.mockResolvedValue([row]);
    updateTaskFilter.mockRejectedValue(problem(409, 'REVISION_CONFLICT', 'Task list f1 is at version 2'));
    show(<TeamTaskLists />, '/tasks/filters');
    fireEvent.click(await screen.findByRole('button', { name: T('teamLists.editNamed').replace('{{name}}', 'Accounts queue') }));
    fireEvent.click(screen.getByRole('button', { name: T('teamLists.saveButton') }));
    expect(await screen.findByText('Task list f1 is at version 2')).toBeTruthy();
  });
  it('deletes a list with a reason and the version it was read at', async () => {
    taskFilters.mockResolvedValue([row]);
    deleteTaskFilter.mockResolvedValue(undefined);
    show(<TeamTaskLists />, '/tasks/filters');
    fireEvent.click(await screen.findByRole('button', { name: T('teamLists.deleteNamed').replace('{{name}}', 'Accounts queue') }));
    fireEvent.change(screen.getByLabelText(L('common.reason')), { target: { value: 'Desk closed for good' } });
    fireEvent.click(screen.getByRole('button', { name: T('teamLists.deleteButton') }));
    await waitFor(() => expect(deleteTaskFilter).toHaveBeenCalledWith('f1', 1, 'Desk closed for good', expect.any(String)));
    await waitFor(() => expect(taskFilters).toHaveBeenCalledTimes(2));
  });
  it('offers no change or delete without team.write', async () => {
    auth.me = { userId: 'u1', roles: [], permissions: ['team.read'] };
    taskFilters.mockResolvedValue([row]);
    show(<TeamTaskLists />, '/tasks/filters');
    await screen.findByText('Accounts queue');
    expect(screen.queryByRole('button', { name: T('teamLists.editNamed').replace('{{name}}', 'Accounts queue') })).toBeNull();
    expect(screen.queryByRole('button', { name: T('teamLists.deleteNamed').replace('{{name}}', 'Accounts queue') })).toBeNull();
  });
});
