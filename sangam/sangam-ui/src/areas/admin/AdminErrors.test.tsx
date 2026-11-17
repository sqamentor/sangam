// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * M-047 / G21 admin area, error states: every screen of Admin.tsx with its first read refused by the server. A 403 is
 * named "not available for your seat", a 409 "someone else changed this item first", a 501 "not installed on this
 * server" (components/ui.tsx problemTitleKey), each with the server's detail, inside role=alert, with a retry that
 * reads again. A refused save keeps the editor open with what was typed, and names the refusal there.
 */
import { useEffect } from 'react';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import type { ReactElement } from 'react';

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn(), page: vi.fn(), upload: vi.fn() }));

vi.mock('../../api/client', async (orig) => {
  const real = await orig<typeof import('../../api/client')>();
  return { ...real, http: { ...real.http, get: api.get, post: api.post, put: api.put, del: api.del, page: api.page, upload: api.upload } };
});
vi.mock('../../auth/AuthProvider', () => ({
  useAuth: () => ({ me: { userId: 'demo', displayName: 'Demo', tenantId: 't1', roles: [], seats: [] }, seat: null, mode: 'dev', hasRole: () => false }),
}));
vi.mock('../../components/Editors', () => ({
  EMPTY_DMN: '<dmn-empty/>',
  DmnEditor: ({ xml, onReady }: { xml: string; onReady: (h: { getContent: () => Promise<string> }) => void }) => {
    useEffect(() => onReady({ getContent: async () => xml }), [onReady, xml]);
    return <div data-testid="dmn" />;
  },
}));

import apiYaml from '../../../../sangam-rest/api/sangam-api.yaml?raw';
import { initI18n } from '../../i18n';
import { EMPTY_PAGE, L, T, buttonIn, contractOperations, dialog, outsideContract, problem, recordCalls, show, type } from '../../test/screens';
import {
  AgentDefinitions,
  Assignments,
  ConversationalChannels,
  DelegationTableEditor,
  DelegationTables,
  Groups,
  IdentityProviders,
  OrgHierarchy,
  Policies,
  RetentionClasses,
  RoutingStrategies,
  Seats,
  ServicePrincipals,
  ServiceRegistry,
  SlaModels,
  SodExceptions,
  Templates,
  Tenants,
  Users,
} from './Admin';

const seen = new Set<string>();
beforeAll(() => initI18n('en'));
beforeEach(() => {
  for (const f of Object.values(api)) f.mockReset();
  api.get.mockResolvedValue([]);
  api.page.mockResolvedValue(EMPTY_PAGE);
  api.post.mockResolvedValue({});
  api.put.mockResolvedValue({});
  api.del.mockResolvedValue(undefined);
});
afterEach(() => {
  recordCalls(seen, api);
  cleanup();
});
afterAll(() => expect(outsideContract(seen, contractOperations(apiYaml))).toEqual([]));

const reads = () => api.get.mock.calls.length + api.page.mock.calls.length;

/** status, code, the en.json title it must be named by */
const REFUSALS: [number, string, string][] = [
  [403, 'FORBIDDEN', 'error.notAvailable'],
  [409, 'TENANT_STATE_CONFLICT', 'error.conflict'],
  [501, 'NOT_IMPLEMENTED', 'error.notInstalled'],
];

/** Every admin screen; detail screens with their route. ModuleSecurityDefaults reads nothing (Admin.test.tsx M-032). */
const SCREENS: [string, () => ReactElement, string?, string?][] = [
  ['Tenants', () => <Tenants />],
  ['OrgHierarchy', () => <OrgHierarchy />],
  ['Seats', () => <Seats />],
  ['Assignments', () => <Assignments />],
  ['DelegationTables', () => <DelegationTables />],
  ['DelegationTableEditor', () => <DelegationTableEditor />, '/admin/delegations/pt1', '/admin/delegations/:id'],
  ['Policies', () => <Policies />],
  ['SodExceptions', () => <SodExceptions />],
  ['Users', () => <Users />],
  ['Groups', () => <Groups />],
  ['IdentityProviders', () => <IdentityProviders />],
  ['ServicePrincipals', () => <ServicePrincipals />],
  ['ServiceRegistry', () => <ServiceRegistry />],
  ['SlaModels', () => <SlaModels />],
  ['RoutingStrategies', () => <RoutingStrategies />],
  ['Templates', () => <Templates />],
  ['ConversationalChannels', () => <ConversationalChannels />],
  ['AgentDefinitions', () => <AgentDefinitions />],
  ['RetentionClasses', () => <RetentionClasses />],
];

const CASES = SCREENS.flatMap(([name, ui, path, route]) => REFUSALS.map(([status, code, key]) => [name, status, code, key, ui, path, route] as const));

describe('admin: a refused first read is named by status, with the detail and a retry', () => {
  it.each(CASES)('%s, %i %s', async (_name, status, code, key, ui, path, route) => {
    const detail = `server says ${status}`;
    api.get.mockRejectedValue(problem(status, code, detail));
    api.page.mockRejectedValue(problem(status, code, detail));
    show(ui(), path, route);
    const alert = (await screen.findAllByRole('alert')).find((a) => a.textContent?.includes(T(key)));
    expect(alert, `alert titled ${T(key)}`).toBeTruthy();
    expect(within(alert!).getByText(detail)).toBeTruthy();
    const before = reads();
    fireEvent.click(within(alert!).getByRole('button', { name: T('common.retry') }));
    await waitFor(() => expect(reads()).toBeGreaterThan(before));
  });
});

describe('admin: a refused save keeps the editor open with what was typed', () => {
  it('a new group refused 409 names the conflict in the dialog and keeps the id and name', async () => {
    api.post.mockRejectedValue(problem(409, 'STATE_CONFLICT', 'changed meanwhile'));
    show(<Groups />);
    fireEvent.click(await screen.findByRole('button', { name: T('common.new') }));
    const d = dialog();
    type(d, 'admin.groupId', 'clerks');
    type(d, 'common.name', 'Clerks');
    fireEvent.click(buttonIn(d, 'common.save'));
    expect(await within(d).findByText(T('error.conflict'))).toBeTruthy();
    expect(within(d).getByText('changed meanwhile')).toBeTruthy();
    expect(d.hasAttribute('open')).toBe(true);
    expect((within(d).getByLabelText(L('admin.groupId')) as HTMLInputElement).value).toBe('clerks');
  });

  it('a new tenant refused 501 names "not installed" in the editor and keeps the form', async () => {
    api.post.mockRejectedValue(problem(501, 'NOT_IMPLEMENTED', 'no tenancy module'));
    show(<Tenants />);
    fireEvent.click(await screen.findByRole('button', { name: T('common.new') }));
    const d = dialog();
    type(d, 'admin.tenantId', 'rj-gov');
    type(d, 'common.name', 'Rajasthan');
    fireEvent.click(buttonIn(d, 'common.save'));
    expect(await within(d).findByText(T('error.notInstalled'))).toBeTruthy();
    expect(d.hasAttribute('open')).toBe(true);
    expect((within(d).getByLabelText(L('admin.tenantId')) as HTMLInputElement).value).toBe('rj-gov');
  });
});
