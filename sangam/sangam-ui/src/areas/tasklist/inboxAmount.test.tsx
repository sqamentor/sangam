// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * M-042 (owner decision 82, W3-J2): the inbox amount and reference columns. GET /tasks sends sortBy=amount only with
 * the currency the user chose (contract listTasks: 400 VALIDATION_FAILED otherwise); without one the amount column is
 * not sortable and the screen says why. Amounts are MoneyAmount major-unit decimal text plus currency (decisions 138
 * and 211), shown exactly by fmtMoneyAmount.
 * Each test asserts the exact query the screen hands to http.page('/tasks', ...). Written, not run (tests run after Wave 5).
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
vi.mock('../../auth/AuthProvider', () => ({
  useAuth: () => ({ me: { userId: 'u1', roles: ['TASK_USER'], seats: [] }, seat: null }),
}));

import { initI18n, locale } from '../../i18n';
import { TaskInbox, inboxSorts, isAmountSort } from './TaskInbox';

const T = (k: string) => i18n.t(k) as string;
const ROW = { id: 't1', name: 'Check bill', created: '2026-11-01T10:00:00Z', amount: '1234.56', currency: 'EUR', reference: 'REF-1' };
const PAGE = { items: [ROW], page: 0, size: 25, total: 1, capped: false };

/** The query of the latest GET /tasks. */
function lastQuery(): Record<string, unknown> {
  const calls = api.page.mock.calls.filter((c) => c[0] === '/tasks');
  expect(calls.length).toBeGreaterThan(0);
  return calls[calls.length - 1][1] as Record<string, unknown>;
}

const amountHeaderButton = () => screen.queryByRole('button', { name: T('task.amount') });
const currencyField = () => screen.getByLabelText(T('task.currency')) as HTMLInputElement;

beforeAll(() => initI18n('en'));
beforeEach(() => {
  api.get.mockReset();
  api.page.mockReset();
  api.get.mockResolvedValue({});
  api.page.mockResolvedValue(PAGE);
  window.localStorage.clear();
});
afterEach(cleanup);

function show() {
  return render(
    <MemoryRouter>
      <TaskInbox />
    </MemoryRouter>,
  );
}

describe('M-042 sort keys without and with a currency', () => {
  it('drops an amount key while no valid currency is chosen', () => {
    expect(inboxSorts('amount', '-amount')).toEqual({ sort: 'slaDue', sort2: '' });
    expect(inboxSorts('-amount', 'reference', 'EU')).toEqual({ sort: 'slaDue', sort2: 'reference' });
    expect(inboxSorts('reference', '-received')).toEqual({ sort: 'reference', sort2: '-received' });
  });
  it('keeps the amount keys inside one currency', () => {
    expect(inboxSorts('-amount', 'reference', 'INR')).toEqual({ sort: '-amount', sort2: 'reference' });
    expect(isAmountSort('-amount')).toBe(true);
    expect(isAmountSort('reference')).toBe(false);
  });
});

describe('M-042 inbox amount column', () => {
  it('without a currency: no amount sort, the reason is shown, no currency is sent', async () => {
    show();
    await screen.findByText('REF-1');
    expect(amountHeaderButton()).toBeNull();
    expect(screen.getByText(T('task.amountSortNeedsCurrency'))).toBeTruthy();
    const q = lastQuery();
    expect(q.currency).toBeUndefined();
    expect(String(q.sortBy)).not.toMatch(/amount/);
    const thenSort = screen.getByLabelText(T('task.thenSortBy')) as HTMLSelectElement;
    expect([...thenSort.options].map((o) => o.value)).not.toContain('amount');
    expect([...thenSort.options].map((o) => o.value)).toContain('reference');
  });

  it('shows the amount as major-unit decimal text and the reference', async () => {
    show();
    await screen.findByText('REF-1');
    const expected = new Intl.NumberFormat(locale(), { style: 'currency', currency: 'EUR' }).format(1234.56);
    expect(screen.getByText(expected)).toBeTruthy();
    expect(screen.getByRole('link', { name: 'REF-1' }).getAttribute('href')).toBe('/tasks/t1');
  });

  it('with a currency: sends it, and the amount sort sends sortBy=amount with that currency', async () => {
    show();
    await screen.findByText('REF-1');
    fireEvent.change(currencyField(), { target: { value: 'eur' } });
    expect(currencyField().value).toBe('EUR');
    await waitFor(() => expect(lastQuery().currency).toBe('EUR'));
    expect(screen.getByText(T('task.currencyHint'))).toBeTruthy();
    fireEvent.click(await screen.findByRole('button', { name: T('task.amount') }));
    await waitFor(() => expect(lastQuery().sortBy).toBe('amount'));
    expect(lastQuery().sortOrder).toBe('asc');
    expect(lastQuery().currency).toBe('EUR');
    const thenSort = screen.getByLabelText(T('task.thenSortBy')) as HTMLSelectElement;
    expect([...thenSort.options].map((o) => o.value)).toContain('-amount');
  });

  it('clearing the currency after an amount sort falls back to the SLA sort and never sends amount alone', async () => {
    show();
    await screen.findByText('REF-1');
    fireEvent.change(currencyField(), { target: { value: 'EUR' } });
    fireEvent.click(await screen.findByRole('button', { name: T('task.amount') }));
    await waitFor(() => expect(lastQuery().sortBy).toBe('amount'));
    fireEvent.change(currencyField(), { target: { value: '' } });
    await waitFor(() => expect(lastQuery().sortBy).toBe('due'));
    expect(lastQuery().currency).toBeUndefined();
    expect(amountHeaderButton()).toBeNull();
    for (const c of api.page.mock.calls.filter((x) => x[0] === '/tasks')) {
      const q = c[1] as Record<string, unknown>;
      if (String(q.sortBy ?? '').includes('amount')) expect(q.currency).toBe('EUR');
    }
  });

  it('an incomplete code is refused on the screen and not sent', async () => {
    show();
    await screen.findByText('REF-1');
    fireEvent.change(currencyField(), { target: { value: 'eu' } });
    expect(await screen.findByText(T('task.currencyInvalid'))).toBeTruthy();
    expect(lastQuery().currency).toBeUndefined();
    expect(amountHeaderButton()).toBeNull();
  });

  it('the reference column sorts with no currency', async () => {
    show();
    await screen.findByText('REF-1');
    fireEvent.click(screen.getByRole('button', { name: T('task.reference') }));
    await waitFor(() => expect(lastQuery().sortBy).toBe('reference'));
    expect(lastQuery().currency).toBeUndefined();
  });
});
