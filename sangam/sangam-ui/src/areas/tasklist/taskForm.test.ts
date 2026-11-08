// SPDX-License-Identifier: Apache-2.0
// SL-37: the task screen loads embedded forms through the Sangam API (task-level check), never /engine-rest, which
// task users cannot reach (403).
import { afterEach, describe, expect, it, vi } from 'vitest';
import { taskFormToSchema } from '../../components/embeddedForm';
import type { TaskForm } from '../../api/types';
import taskDetailSource from './TaskDetail.tsx?raw';

describe('task form loading', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('api.tasks.form calls GET /api/v1/tasks/{id}/form', async () => {
    vi.stubGlobal('window', { location: { origin: 'http://ui.test' } });
    const body: TaskForm = { taskId: 't 1', kind: 'EMBEDDED', formKey: 'embedded:deployment:f.html', contentType: 'text/html', content: '<form></form>' };
    const fetchMock = vi.fn(async () => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    vi.stubGlobal('fetch', fetchMock);
    const { api } = await import('../../api/endpoints');
    const f = await api.tasks.form('t 1');
    expect(f).toEqual(body);
    const url = String((fetchMock.mock.calls[0] as unknown[])[0]);
    expect(url).toBe('/api/v1/tasks/t%201/form');
    expect(url).not.toContain('engine-rest');
  });

  it('the task detail screen no longer calls /engine-rest', () => {
    const src = taskDetailSource;
    expect(src).not.toContain('/engine-rest');
    expect(src).toContain('api.tasks.form(');
  });

  it('turns the served form into a schema', () => {
    const html = (s: string) => ({ schema: { components: [{ key: s, type: 'textfield' as const }] }, scriptsIgnored: false });
    expect(taskFormToSchema({ taskId: 't', kind: 'EMBEDDED', content: 'amount' }, html)?.schema.components[0].key).toBe('amount');
    const formJs = JSON.stringify({ components: [{ key: 'approved', type: 'checkbox' }] });
    expect(taskFormToSchema({ taskId: 't', kind: 'FORM', content: formJs })?.schema.components[0].key).toBe('approved');
    expect(taskFormToSchema({ taskId: 't', kind: 'EXTERNAL', formKey: 'app:x' })).toBeNull();
    expect(taskFormToSchema({ taskId: 't', kind: 'EMBEDDED' })).toBeNull();
    expect(taskFormToSchema(null)).toBeNull();
  });
});
