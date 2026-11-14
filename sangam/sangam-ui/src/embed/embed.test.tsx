// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * M-043: the embeddable web components. configure() validates the API base and the token callback; the token is sent
 * only as the bearer header, to an absolute cross-origin apiBase; <sangam-task-form> completes the task with the bound
 * payload hash and case version and emits sangam-task-completed; a 409 stale answer blocks submit and emits sangam-error;
 * a task without COMPLETE stays read-only; <sangam-process-diagram> reads the definition XML and the instance tokens and
 * emits sangam-element-selected; the host page's <html lang> is never changed. Written, not run in this phase.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, waitFor, within } from '@testing-library/react';
import i18n from 'i18next';

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), page: vi.fn() }));

vi.mock('../api/client', async (orig) => {
  const real = await orig<typeof import('../api/client')>();
  return { ...real, http: { ...real.http, get: api.get, post: api.post, page: api.page } };
});
vi.mock('../components/BpmnDiagram', () => ({
  BpmnDiagram: (p: { title: string; xml: string; stats?: { activityId: string }[]; onSelect?: (id: string, name?: string) => void }) => (
    <div data-testid="bpmn" data-xml={p.xml} data-stats={(p.stats ?? []).map((s) => s.activityId).join(',')}>
      {p.title}
      <button type="button" onClick={() => p.onSelect?.('Task_1', 'Check')}>
        select
      </button>
    </div>
  ),
}));

import { ApiError, buildUrl, request } from '../api/client';
import { checkApiBase, configure, PROCESS_DIAGRAM_TAG, TASK_FORM_TAG } from './index';

const T = (k: string, o?: Record<string, unknown>) => i18n.t(k, o) as string;
let routes: Record<string, unknown> = {};

const TASK = {
  id: 't1',
  name: 'Fill in',
  payloadHash: 'abc123',
  version: 4,
  formVersion: 2,
  allowedActions: ['complete'],
  variables: { purpose: { type: 'String', value: 'old' } },
  form: { key: 'leave', components: [{ key: 'purpose', label: 'Purpose', type: 'textfield', validate: { required: true } }] },
};

function shadow(el: Element): HTMLElement {
  return el.shadowRoot as unknown as HTMLElement;
}

function mountElement(tag: string, attrs: Record<string, string>): HTMLElement {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v);
  document.body.appendChild(el);
  return el;
}

beforeAll(() => {
  document.documentElement.lang = 'fr';
  configure({ apiBase: 'https://sangam.example.org/api/v1', getAccessToken: () => 'tok-1' });
});

beforeEach(() => {
  routes = {};
  api.get.mockReset();
  api.post.mockReset();
  api.page.mockReset();
  api.get.mockImplementation(async (path: string) => {
    const v = routes[path];
    if (v instanceof Error) throw v;
    return typeof v === 'function' ? (v as () => unknown)() : (v ?? []);
  });
  api.page.mockResolvedValue({ items: [], page: 0, size: 25, total: 0, capped: false });
});

afterEach(() => {
  cleanup();
  document.body.innerHTML = '';
  vi.unstubAllGlobals();
});

describe('configure()', () => {
  it('accepts an http(s) URL or a same-origin path, and refuses anything else', () => {
    expect(checkApiBase('https://sangam.example.org/api/v1')).toBe('https://sangam.example.org/api/v1');
    expect(checkApiBase('/api/v1')).toBe('/api/v1');
    for (const bad of ['', '//evil.example/api', 'javascript:alert(1)', 'ftp://x/api', 'https://u:p@x.example/api', 42]) {
      expect(() => checkApiBase(bad)).toThrow(TypeError);
    }
  });

  it('needs a token callback', () => {
    expect(() => configure({ apiBase: '/api/v1' } as never)).toThrow(/getAccessToken/);
  });

  it('registers both elements', () => {
    expect(customElements.get(TASK_FORM_TAG)).toBeDefined();
    expect(customElements.get(PROCESS_DIAGRAM_TAG)).toBeDefined();
  });

  it("never changes the host page's <html lang>", () => {
    expect(document.documentElement.lang).toBe('fr');
  });

  it('sends the token only as the bearer header, to the absolute cross-origin API base', async () => {
    const fetchMock = vi.fn(async () => new Response('{"ok":true}', { status: 200, headers: { 'Content-Type': 'application/json' } }));
    vi.stubGlobal('fetch', fetchMock);
    await request('GET', '/tasks/t1', { query: { a: '1' } });
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('https://sangam.example.org/api/v1/tasks/t1?a=1');
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer tok-1');
    expect(url).not.toContain('tok-1');
  });

  it('keeps same-origin URLs as paths', () => {
    expect(buildUrl('/x', undefined, '/api/v1')).toBe('/api/v1/x');
    expect(buildUrl('/x', undefined, `${window.location.origin}/api/v1`)).toBe('/api/v1/x');
  });
});

describe('<sangam-task-form>', () => {
  it('completes the task with the bound payload hash, case version and form version, and emits sangam-task-completed', async () => {
    routes['/tasks/t1'] = TASK;
    api.post.mockResolvedValue({ state: 'COMPLETED', reference: 'REF-9' });
    const events: CustomEvent[] = [];
    document.addEventListener('sangam-task-completed', (e) => events.push(e as CustomEvent), { once: true });
    const el = mountElement(TASK_FORM_TAG, { 'task-id': 't1' });

    const input = (await waitFor(() => within(shadow(el)).getByRole('textbox'))) as HTMLInputElement;
    expect(input.value).toBe('old');
    fireEvent.change(input, { target: { value: 'Medical' } });
    fireEvent.click(within(shadow(el)).getByRole('button', { name: T('common.submit') }));

    await waitFor(() => expect(api.post).toHaveBeenCalled());
    expect(api.post).toHaveBeenCalledWith(
      '/tasks/t1/complete',
      expect.objectContaining({
        payloadHash: 'abc123',
        caseVersion: 4,
        decision: 'COMPLETE',
        formVersion: '2',
        variables: { purpose: { type: 'String', value: 'Medical' } },
      }),
      expect.any(String),
    );
    await waitFor(() => expect(events).toHaveLength(1));
    expect(events[0].detail).toEqual({ taskId: 't1', result: { state: 'COMPLETED', reference: 'REF-9' } });
    expect(within(shadow(el)).getByText(T('task.doneTitle'))).toBeTruthy();
  });

  it('does not submit while a required field is empty', async () => {
    routes['/tasks/t1'] = { ...TASK, variables: {} };
    const el = mountElement(TASK_FORM_TAG, { 'task-id': 't1' });
    await waitFor(() => within(shadow(el)).getByRole('textbox'));
    fireEvent.click(within(shadow(el)).getByRole('button', { name: T('common.submit') }));
    await waitFor(() => expect(within(shadow(el)).getAllByText(T('form.required')).length).toBeGreaterThan(0));
    expect(api.post).not.toHaveBeenCalled();
  });

  it('on 409 stale keeps the input, blocks submit until the user continues, and emits sangam-error', async () => {
    routes['/tasks/t1'] = TASK;
    api.post.mockRejectedValue(new ApiError({ status: 409, title: 'stale', code: 'FORM_VERSION_STALE' }));
    const errors: CustomEvent[] = [];
    document.addEventListener('sangam-error', (e) => errors.push(e as CustomEvent), { once: true });
    const el = mountElement(TASK_FORM_TAG, { 'task-id': 't1' });
    const input = (await waitFor(() => within(shadow(el)).getByRole('textbox'))) as HTMLInputElement;
    fireEvent.change(input, { target: { value: 'Mine' } });
    fireEvent.click(within(shadow(el)).getByRole('button', { name: T('common.submit') }));

    await waitFor(() => expect(within(shadow(el)).getByText(T('task.staleContinue'))).toBeTruthy());
    expect(errors[0].detail).toEqual({ taskId: 't1', status: 409, code: 'FORM_VERSION_STALE', title: 'stale' });
    expect((within(shadow(el)).getByRole('button', { name: T('common.submit') }) as HTMLButtonElement).disabled).toBe(true);
    expect((within(shadow(el)).getByRole('textbox') as HTMLInputElement).value).toBe('Mine');
    fireEvent.click(within(shadow(el)).getByText(T('task.staleContinue')));
    expect((within(shadow(el)).getByRole('button', { name: T('common.submit') }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('a task whose decision needs the tasklist is shown read-only, without a submit button', async () => {
    routes['/tasks/t1'] = { ...TASK, allowedActions: ['APPROVE', 'REJECT'] };
    const el = mountElement(TASK_FORM_TAG, { 'task-id': 't1' });
    await waitFor(() => within(shadow(el)).getByText(T('embed.decisionInTasklist')));
    expect((within(shadow(el)).getByRole('textbox') as HTMLInputElement).disabled).toBe(true);
    expect(within(shadow(el)).queryByRole('button', { name: T('common.submit') })).toBeNull();
  });

  it('a hidden task (403/404) is shown as not available and reported through sangam-error', async () => {
    routes['/tasks/t1'] = new ApiError({ status: 404, title: 'Not found' });
    const errors: CustomEvent[] = [];
    document.addEventListener('sangam-error', (e) => errors.push(e as CustomEvent), { once: true });
    mountElement(TASK_FORM_TAG, { 'task-id': 't1' });
    await waitFor(() => expect(errors).toHaveLength(1));
    expect(errors[0].detail).toMatchObject({ taskId: 't1', status: 404 });
  });

  it('names a missing task-id instead of calling the API', async () => {
    const el = mountElement(TASK_FORM_TAG, {});
    await waitFor(() => within(shadow(el)).getByText(T('embed.missingAttribute', { name: 'task-id' })));
    expect(api.get).not.toHaveBeenCalled();
  });

  it('renders inside its own shadow root with the Sangam tokens on :host', async () => {
    routes['/tasks/t1'] = TASK;
    const el = mountElement(TASK_FORM_TAG, { 'task-id': 't1' });
    await waitFor(() => within(shadow(el)).getByRole('textbox'));
    const css = el.shadowRoot!.querySelector('style')!.textContent!;
    expect(css).toContain(':host');
    expect(document.head.querySelectorAll('style').length).toBe(0);
  });
});

describe('<sangam-process-diagram>', () => {
  it('reads the definition XML and the instance tokens, and emits sangam-element-selected', async () => {
    routes['/process-definitions/d1/xml'] = { bpmn20Xml: '<definitions/>' };
    routes['/process-instances/p1/activity-tree'] = { id: 'p1', activityId: 'proc', children: [{ id: 'a1', activityId: 'Task_1', children: [] }] };
    const selected: CustomEvent[] = [];
    document.addEventListener('sangam-element-selected', (e) => selected.push(e as CustomEvent), { once: true });
    const el = mountElement(PROCESS_DIAGRAM_TAG, { 'process-definition-id': 'd1', 'process-instance-id': 'p1', label: 'Leave' });
    const d = await waitFor(() => within(shadow(el)).getByTestId('bpmn'));
    expect(d.getAttribute('data-xml')).toBe('<definitions/>');
    expect(d.textContent).toContain('Leave');
    await waitFor(() => expect(within(shadow(el)).getByTestId('bpmn').getAttribute('data-stats')).toBe('Task_1'));
    expect(api.get).toHaveBeenCalledWith('/process-instances/p1/activity-tree');
    fireEvent.click(within(d).getByText('select'));
    expect(selected[0].detail).toEqual({ definitionId: 'd1', activityId: 'Task_1', name: 'Check' });
  });
});
