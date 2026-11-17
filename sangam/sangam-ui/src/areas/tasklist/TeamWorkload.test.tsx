// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/** Decision 401: Team workload reads GET /team/task-groups and shows the counts of the caller's organisation. */
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { cleanup, screen, within } from '@testing-library/react';
import { initI18n } from '../../i18n';
import { problem, show, T } from '../../test/screens';

const taskGroups = vi.fn();
vi.mock('../../api/endpoints', () => ({ api: { team: { taskGroups: () => taskGroups() } } }));

import { TeamWorkload } from './TeamWorkload';

beforeAll(() => initI18n('en'));
afterEach(() => {
  cleanup();
  taskGroups.mockReset();
});

describe('TeamWorkload', () => {
  it('shows the totals and the unassigned tasks per team list', async () => {
    taskGroups.mockResolvedValue({ open: 7, assigned: 3, unassigned: 4, withoutCandidateGroup: 1, groups: [{ group: 'accounts', unassignedTasks: 2 }, { group: 'desk', unassignedTasks: 1 }] });
    show(<TeamWorkload />, '/team');
    expect(await screen.findByRole('heading', { level: 1, name: T('menu.work.team.label') })).toBeTruthy();
    const rows = await screen.findAllByRole('row');
    expect(rows.map((r) => r.textContent)).toEqual(expect.arrayContaining(['accounts2', 'desk1']));
    expect(screen.getByText(T('team.open')).closest('div')?.textContent).toContain('7');
    expect(taskGroups).toHaveBeenCalledTimes(1);
  });
  it('says so when no team list has waiting tasks', async () => {
    taskGroups.mockResolvedValue({ open: 0, assigned: 0, unassigned: 0, withoutCandidateGroup: 0, groups: [] });
    show(<TeamWorkload />, '/team');
    expect(await screen.findByText(T('team.noGroups'))).toBeTruthy();
  });
  it('shows a refusal in plain words with its next step', async () => {
    taskGroups.mockRejectedValue(problem(403, 'ACTION_NOT_PERMITTED'));
    show(<TeamWorkload />, '/team');
    const alert = await screen.findByRole('alert');
    expect(within(alert).getByText(T('error.notAvailable'))).toBeTruthy();
    expect(within(alert).getByText(T('error.next.notAvailable'))).toBeTruthy();
  });
});
