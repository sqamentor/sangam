// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
// M-125 compare view over POST /models/diff (diffModels): request shape, the summary and changes tables, keyboard
// reach, and the readable MODEL_UNREADABLE message. Written for the gate; not run in Wave 4.
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { initI18n } from '../../i18n';
import { ApiError } from '../../api/client';
import type { ModelDiffReport } from '../../api/types';

const diff = vi.fn();
vi.mock('../../api/endpoints', () => ({ api: { models: { diff: (b: unknown) => diff(b) } } }));

import { ModelCompare, ModelDiffView, diffRequest, isModelUnreadable } from './ModelCompare';

beforeAll(() => initI18n('en'));
beforeEach(() => diff.mockReset());
afterEach(cleanup);

const REPORT: ModelDiffReport = {
  resource: 'order.bpmn',
  format: 'BPMN',
  changed: true,
  summary: { ELEMENT: 1, FLOW: 0, EXPRESSION: 1, PROPERTY: 0 },
  changes: [
    { level: 'ELEMENT', kind: 'ADDED', elementId: 'Task_2', elementType: 'userTask', name: 'Approve', path: '', before: null, after: 'Approve' },
    { level: 'EXPRESSION', kind: 'CHANGED', elementId: 'Flow_1', elementType: 'sequenceFlow', name: '', path: 'conditionExpression', before: '${amount > 10}', after: '${amount > 100}' },
  ],
};

describe('M-125 diffRequest and isModelUnreadable', () => {
  it('trims the resource and sends an empty side as null (a new or a deleted file)', () => {
    expect(diffRequest(' a.bpmn ', '', '<x/>')).toEqual({ resource: 'a.bpmn', before: null, after: '<x/>' });
    expect(diffRequest('a.dmn', '<x/>', '')).toEqual({ resource: 'a.dmn', before: '<x/>', after: null });
  });

  it('recognises only 400 MODEL_UNREADABLE', () => {
    expect(isModelUnreadable(new ApiError({ title: 'Bad', status: 400, code: 'MODEL_UNREADABLE' }))).toBe(true);
    expect(isModelUnreadable(new ApiError({ title: 'Bad', status: 400, code: 'VALIDATION_FAILED' }))).toBe(false);
    expect(isModelUnreadable(new Error('x'))).toBe(false);
  });
});

describe('M-125 ModelDiffView', () => {
  it('shows the summary per level and the changes as captioned tables in focusable regions', () => {
    render(<ModelDiffView report={REPORT} />);
    const summary = screen.getByRole('table', { name: 'Changes by level' });
    const rows = within(summary).getAllByRole('row');
    expect(rows.map((r) => r.textContent)).toEqual(['LevelChanges', 'Element1', 'Flow0', 'Expression1', 'Property0']);
    const changes = screen.getByRole('table', { name: 'Changes (2)' });
    expect(within(changes).getAllByRole('columnheader').map((h) => h.textContent)).toEqual([
      'Level', 'Change', 'Element id', 'Element type', 'Name', 'Property or expression', 'Before', 'After',
    ]);
    expect(within(changes).getByText('Added')).toBeTruthy();
    expect(within(changes).getByText('The element itself')).toBeTruthy();
    expect(within(changes).getByText('${amount > 100}')).toBeTruthy();
    expect(within(changes).getByText('No value')).toBeTruthy();
    for (const region of screen.getAllByRole('region')) expect(region.getAttribute('tabindex')).toBe('0');
  });

  it('says so when nothing changed in meaning and shows no changes table', () => {
    render(<ModelDiffView report={{ ...REPORT, changed: false, summary: { ELEMENT: 0, FLOW: 0, EXPRESSION: 0, PROPERTY: 0 }, changes: [] }} />);
    expect(screen.getByText('No change in meaning between the two versions.')).toBeTruthy();
    expect(screen.queryByRole('table', { name: /^Changes \(/ })).toBeNull();
  });
});

describe('M-125 ModelCompare form', () => {
  it('keeps Compare disabled until a file name and at least one side are given', () => {
    render(<ModelCompare />);
    const run = screen.getByRole('button', { name: 'Compare' }) as HTMLButtonElement;
    expect(run.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText(/File name/), { target: { value: 'a.bpmn' } });
    expect(run.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText(/Before \(old version\)/), { target: { value: '<definitions/>' } });
    expect(run.disabled).toBe(false);
  });

  it('posts the contract body with the saved model as the after side and shows the report', async () => {
    diff.mockResolvedValue(REPORT);
    render(<ModelCompare resource="order.bpmn" current="<new/>" />);
    fireEvent.change(screen.getByLabelText(/Before \(old version\)/), { target: { value: '<old/>' } });
    fireEvent.click(screen.getByRole('button', { name: 'Compare' }));
    await waitFor(() => expect(screen.getByRole('table', { name: 'Changes (2)' })).toBeTruthy());
    expect(diff).toHaveBeenCalledWith({ resource: 'order.bpmn', before: '<old/>', after: '<new/>' });
  });

  it('explains 400 MODEL_UNREADABLE in plain English instead of the generic validation alert', async () => {
    diff.mockRejectedValue(new ApiError({ title: 'Bad Request', status: 400, code: 'MODEL_UNREADABLE', detail: 'a DOCTYPE is not allowed' }));
    render(<ModelCompare resource="order.bpmn" current="<new/>" />);
    fireEvent.click(screen.getByRole('button', { name: 'Compare' }));
    await waitFor(() => expect(screen.getByText(/These versions cannot be compared\./)).toBeTruthy());
    expect(screen.getByText(/Details: a DOCTYPE is not allowed/)).toBeTruthy();
    expect(screen.getAllByRole('alert')).toHaveLength(1);
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('every control is reachable by keyboard (native inputs and buttons, nothing with a negative tabindex)', () => {
    render(<ModelCompare resource="order.bpmn" current="<new/>" />);
    for (const el of Array.from(document.querySelectorAll('input, textarea, button'))) {
      expect(el.getAttribute('tabindex') === null || Number(el.getAttribute('tabindex')) >= 0).toBe(true);
    }
    expect(screen.getByLabelText('Load the old version from a file')).toBeTruthy();
    expect(screen.getByLabelText('Load the new version from a file')).toBeTruthy();
  });
});
