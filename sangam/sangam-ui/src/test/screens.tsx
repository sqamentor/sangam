// SPDX-License-Identifier: Apache-2.0
/**
 * M-047 shared helpers for the per-area screen tests (src/areas/{admin,ops,modeller}/*.test.tsx). Each test file mocks
 * the API client (src/api/client.ts http.*) itself, because vi.mock is hoisted per file; this module holds what is the
 * same in every file: rendering under a router, the open dialog, the reason dialog, i18n text and field labels, and a
 * problem+json refusal.
 */
import type { ReactElement } from 'react';
import { expect } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import i18n from 'i18next';
import { ApiError } from '../api/client';

/** The English text of an i18n key; a missing key fails the test instead of passing as its own name. */
export function T(k: string, o?: Record<string, unknown>): string {
  expect(i18n.exists(k), `i18n key ${k}`).toBe(true);
  return i18n.t(k, o) as string;
}

export const esc = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/** A field label: the translated text, optionally followed by the required marker (ui.tsx FieldWrap). */
export const L = (k: string) => new RegExp(`^${esc(T(k))}(\\s*\\*.*)?$`);

/** A problem+json refusal as the client raises it. */
export const problem = (status: number, code?: string, detail?: string) => new ApiError({ status, title: 't', code, detail });

export const KEY = expect.any(String);
export const EMPTY_PAGE = { items: [], page: 0, size: 25, total: 0, capped: false };
export const pageOf = <R,>(items: R[]) => ({ items, page: 0, size: 25, total: items.length, capped: false });

export function show(ui: ReactElement, path = '/', route = '*') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path={route} element={ui} />
        <Route path="*" element={<div data-testid="elsewhere" />} />
      </Routes>
    </MemoryRouter>,
  );
}

/** The open dialog (a closed <dialog> stays in the DOM). */
export function dialog(): HTMLElement {
  const d = screen.getAllByRole('dialog', { hidden: true }).find((x) => x.hasAttribute('open'));
  if (!d) throw new Error('no open dialog');
  return d;
}

export const noDialog = () => expect(screen.queryAllByRole('dialog', { hidden: true }).some((x) => x.hasAttribute('open'))).toBe(false);

export const buttonIn = (d: HTMLElement, key: string) => within(d).getByRole('button', { name: T(key) }) as HTMLButtonElement;

export const confirmIn = (d: HTMLElement) => buttonIn(d, 'common.confirm');

/** Types the reason into the open reason dialog (ui.tsx ReasonDialog) and confirms it. */
export function reasonAndConfirm(reason: string): HTMLElement {
  const d = dialog();
  fireEvent.change(within(d).getByLabelText(L('common.reason')), { target: { value: reason } });
  fireEvent.click(confirmIn(d));
  return d;
}

/** Sets a labelled field of the given container. */
export function type(container: HTMLElement, key: string, value: string) {
  fireEvent.change(within(container).getByLabelText(L(key)), { target: { value } });
}

// ------------------------------------------------------------------ contract paths

/** The operations of the REST contract (sangam-rest/api/sangam-api.yaml) as "METHOD /path/{param}" templates. */
export function contractOperations(yaml: string): Set<string> {
  const ops = new Set<string>();
  let path: string | undefined;
  for (const line of yaml.split(/\r?\n/)) {
    const p = /^ {2}(\/[^:\s]*):\s*$/.exec(line);
    if (p) {
      path = p[1];
      continue;
    }
    if (/^\S/.test(line)) path = undefined;
    const m = /^ {4}(get|post|put|delete|patch):\s*$/.exec(line);
    if (path && m) ops.add(`${m[1].toUpperCase()} ${path}`);
  }
  return ops;
}

type Mocked = { mock: { calls: unknown[][] } };

/**
 * Collects "METHOD /path" of every call the mocked client saw (http.get/post/put/del/page/upload and raw), without the
 * query string; /engine-rest (the unchanged Operaton API, not in the Sangam contract) is left out.
 */
export function recordCalls(into: Set<string>, api: { get: Mocked; post: Mocked; put: Mocked; del: Mocked; page: Mocked; upload: Mocked; raw?: Mocked; text?: Mocked }) {
  const add = (method: string, path: unknown) => {
    const p = String(path).split('?')[0];
    if (!p.startsWith('/engine-rest')) into.add(`${method} ${p}`);
  };
  for (const c of api.get.mock.calls) add('GET', c[0]);
  for (const c of api.page.mock.calls) add('GET', c[0]);
  for (const c of api.text?.mock.calls ?? []) add('GET', c[0]); // http.text: a GET of a non-JSON media type
  for (const c of api.post.mock.calls) add('POST', c[0]);
  for (const c of api.upload.mock.calls) add('POST', c[0]);
  for (const c of api.put.mock.calls) add('PUT', c[0]);
  for (const c of api.del.mock.calls) add('DELETE', c[0]);
  for (const c of api.raw?.mock.calls ?? []) add(String(c[0]).toUpperCase(), c[1]);
}

/** The recorded calls that match no operation of the contract (a path segment matches any {param}). */
export function outsideContract(calls: Set<string>, ops: Set<string>): string[] {
  const patterns = [...ops].map((o) => new RegExp(`^${o.split(/\{[^}]+\}/).map(esc).join('[^/]+')}$`));
  return [...calls].filter((c) => !patterns.some((r) => r.test(c))).sort();
}

/** The table row that contains the given text. */
export function rowWith(text: string): HTMLElement {
  const row = screen.getByText(text).closest('tr');
  if (!row) throw new Error(`no row with ${text}`);
  return row as HTMLElement;
}
