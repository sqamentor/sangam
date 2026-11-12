// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * M-068 (W3-J2): the modeller operation picker over listApiContracts and listApiContractOperations (sangam-api.yaml,
 * W3-M DONE 2026-11-12T14:14:56Z). It fills a template field bound to sangam:operationRef with
 * contractId@version#operationId (or contractId#operationId when following the latest version); the server's template
 * apply (template sangam.connector.operation-ref) still validates and writes it. bpmn-js is a fake. Written, not run.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import i18n from 'i18next';

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));
vi.mock('../api/client', async (orig) => {
  const real = await orig<typeof import('../api/client')>();
  return { ...real, http: { ...real.http, get: api.get, post: api.post } };
});

import { initI18n } from '../i18n';
import { ApiError } from '../api/client';
import { OperationRefPicker, operationLabel, operationRef } from './OperationRefPicker';
import { ElementTemplatePanel } from './ElementTemplatePanel';
import { blockingA11yIssues } from '../test/axe';

const T = (k: string, o?: Record<string, unknown>) => i18n.t(k, o) as string;
const esc = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const L = (k: string) => new RegExp(`^${esc(T(k))}(\\s*\\*.*)?$`);

const CONTRACTS = [
  { packageId: 'p1', contractId: 'payments', version: 3, format: 'OPENAPI', specVersion: '3.1.0', sha256: 'a'.repeat(64) },
  { packageId: 'p1', contractId: 'events', version: 1, format: 'ASYNCAPI', specVersion: '3.0.0', sha256: 'b'.repeat(64) },
];
const OPS: Record<string, unknown[]> = {
  '/packages/p1/contracts/payments/operations': [
    { operationId: 'createPayment', format: 'OPENAPI', method: 'post', path: '/payments', summary: 'Create a payment' },
    { operationId: 'getPayment', format: 'OPENAPI', method: 'get', path: '/payments/{id}' },
  ],
  '/packages/p1/contracts/events/operations': [{ operationId: 'publishPaid', format: 'ASYNCAPI', action: 'send', channel: 'paid' }],
};

let routes: Record<string, unknown>;
beforeAll(() => initI18n('en'));
beforeEach(() => {
  api.get.mockReset();
  api.post.mockReset();
  routes = { '/packages/p1/contracts': CONTRACTS, ...OPS };
  api.get.mockImplementation(async (path: string) => {
    const v = routes[path];
    if (v instanceof Error) throw v;
    return v ?? [];
  });
});
afterEach(cleanup);

describe('operationRef and labels', () => {
  it('builds the OperationRef.java form, pinned or not', () => {
    expect(operationRef('payments', 'createPayment', 3)).toBe('payments@3#createPayment');
    expect(operationRef('payments', 'createPayment')).toBe('payments#createPayment');
  });
  it('labels an operation by what its contract says it does', () => {
    expect(operationLabel({ operationId: 'createPayment', format: 'OPENAPI', method: 'post', path: '/payments', summary: 'Create' })).toBe('createPayment (POST /payments) · Create');
    expect(operationLabel({ operationId: 'publishPaid', format: 'ASYNCAPI', action: 'send', channel: 'paid' })).toBe('publishPaid (send paid)');
    expect(operationLabel({ operationId: 'bare', format: 'OPENAPI' })).toBe('bare');
  });
});

describe('OperationRefPicker', () => {
  it('lists the contracts, then the operations of exactly the listed version, and gives a pinned reference', async () => {
    const onPick = vi.fn();
    const { container } = render(<OperationRefPicker packageId="p1" onPick={onPick} />);
    fireEvent.change(await screen.findByLabelText(L('opref.contract')), { target: { value: 'payments' } });
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/packages/p1/contracts/payments/operations', { version: 3 }));
    fireEvent.change(await screen.findByLabelText(L('opref.operation')), { target: { value: 'createPayment' } });
    expect(onPick).toHaveBeenLastCalledWith('payments@3#createPayment');
    expect(api.get).toHaveBeenCalledWith('/packages/p1/contracts');
    expect(await blockingA11yIssues(container)).toEqual([]);
  });

  it('following the latest version gives an unpinned reference, and back again', async () => {
    const onPick = vi.fn();
    render(<OperationRefPicker packageId="p1" onPick={onPick} />);
    fireEvent.change(await screen.findByLabelText(L('opref.contract')), { target: { value: 'payments' } });
    fireEvent.change(await screen.findByLabelText(L('opref.operation')), { target: { value: 'getPayment' } });
    fireEvent.click(screen.getByRole('checkbox', { name: T('opref.followLatest') }));
    expect(onPick).toHaveBeenLastCalledWith('payments#getPayment');
    fireEvent.click(screen.getByRole('checkbox', { name: T('opref.followLatest') }));
    expect(onPick).toHaveBeenLastCalledWith('payments@3#getPayment');
  });

  it('a new contract choice starts with its own operations and nothing picked', async () => {
    const onPick = vi.fn();
    render(<OperationRefPicker packageId="p1" onPick={onPick} />);
    fireEvent.change(await screen.findByLabelText(L('opref.contract')), { target: { value: 'payments' } });
    fireEvent.change(await screen.findByLabelText(L('opref.operation')), { target: { value: 'createPayment' } });
    fireEvent.change(screen.getByLabelText(L('opref.contract')), { target: { value: 'events' } });
    const op = (await screen.findByRole('option', { name: 'publishPaid (send paid)' })).closest('select') as HTMLSelectElement;
    expect(op.value).toBe('');
    expect(within(op).queryByRole('option', { name: /createPayment/ })).toBeNull();
    expect(api.get).toHaveBeenCalledWith('/packages/p1/contracts/events/operations', { version: 1 });
    expect(onPick).toHaveBeenCalledTimes(1);
  });

  it('says so when the package has no contract', async () => {
    routes['/packages/p1/contracts'] = [];
    render(<OperationRefPicker packageId="p1" onPick={vi.fn()} />);
    expect(await screen.findByText(T('opref.none'))).toBeTruthy();
    expect(screen.queryByLabelText(L('opref.contract'))).toBeNull();
  });

  it('shows a refusal of the contract list', async () => {
    routes['/packages/p1/contracts'] = new ApiError({ status: 403, title: 'Forbidden', code: 'FORBIDDEN' });
    render(<OperationRefPicker packageId="p1" onPick={vi.fn()} />);
    expect(await screen.findByRole('alert')).toBeTruthy();
  });
});

const refTpl = {
  key: 'sangam.connector.operation-ref',
  name: 'API operation (contract reference)',
  kind: 'ELEMENT',
  version: 1,
  appliesTo: ['bpmn:ServiceTask'],
  document: {
    fields: [
      { name: 'topic', binding: 'operaton:topic', required: true, default: 'api.call' },
      { name: 'operationRef', binding: 'sangam:operationRef', required: true, pattern: '[A-Za-z0-9_.-]{1,120}(@[0-9]{1,9})?#[A-Za-z0-9_.-]{1,200}' },
    ],
  },
};

function fakeModeler() {
  const updates: [unknown, Record<string, unknown>][] = [];
  const defs = { $attrs: {} as Record<string, string> };
  const services: Record<string, unknown> = {
    elementRegistry: { get: (id: string) => ({ id }) },
    modeling: { updateProperties: (e: unknown, p: Record<string, unknown>) => updates.push([e, p]) },
  };
  return { modeler: { get: (n: string) => services[n], getDefinitions: () => defs }, updates, defs };
}

describe('element-template panel with the picker', () => {
  it('a field bound to sangam:operationRef is filled by the picker and applied through the server template', async () => {
    routes['/templates'] = [refTpl];
    api.post.mockResolvedValue({ properties: { 'operaton:topic': 'api.call', 'sangam:operationRef': 'payments@3#createPayment', 'sangam:templateId': refTpl.key, 'sangam:templateVersion': 1 }, errors: [] });
    const f = fakeModeler();
    render(<ElementTemplatePanel modeler={f.modeler} selected={{ id: 'Task_1', type: 'bpmn:ServiceTask' }} packageId="p1" />);
    fireEvent.change(await screen.findByLabelText(L('tpl.template')), { target: { value: refTpl.key } });
    fireEvent.change(await screen.findByLabelText(L('opref.contract')), { target: { value: 'payments' } });
    fireEvent.change(await screen.findByLabelText(L('opref.operation')), { target: { value: 'createPayment' } });
    expect((screen.getByLabelText(/^operationRef/) as HTMLInputElement).value).toBe('payments@3#createPayment');
    fireEvent.click(screen.getByRole('button', { name: T('tpl.apply') }));
    await waitFor(() =>
      expect(api.post).toHaveBeenCalledWith(`/templates/${encodeURIComponent(refTpl.key)}/apply?version=1`, { elementType: 'bpmn:ServiceTask', values: { topic: 'api.call', operationRef: 'payments@3#createPayment' } }),
    );
    await waitFor(() => expect(f.updates).toHaveLength(1));
    expect(f.updates[0][1]['sangam:operationRef']).toBe('payments@3#createPayment');
    expect(f.defs.$attrs['xmlns:sangam']).toBeDefined();
  });

  it('outside a package the field stays a plain input, with no contract call', async () => {
    routes['/templates'] = [refTpl];
    render(<ElementTemplatePanel modeler={fakeModeler().modeler} selected={{ id: 'Task_1', type: 'bpmn:ServiceTask' }} />);
    fireEvent.change(await screen.findByLabelText(L('tpl.template')), { target: { value: refTpl.key } });
    expect(screen.getByLabelText(/^operationRef/)).toBeTruthy();
    expect(screen.queryByLabelText(L('opref.contract'))).toBeNull();
    expect(api.get).not.toHaveBeenCalledWith('/packages/p1/contracts');
  });
});
