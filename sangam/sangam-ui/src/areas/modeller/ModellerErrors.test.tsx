// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * M-047 / G21 modeller area, error states: every screen of modeller/*.tsx with its first read refused by the server.
 * A 403 is named "not available for your seat", a 409 "someone else changed this item first", a 501 "not installed on
 * this server" (components/ui.tsx problemTitleKey), each with the server's detail, inside role=alert, with a retry that
 * reads again. ImportAssessment and ModelCompare read nothing until asked (Modeller.test.tsx, ModelCompare.test.tsx).
 */
import { useEffect } from 'react';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import type { ReactElement } from 'react';

const api = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  patch: vi.fn(),
  del: vi.fn(),
  page: vi.fn(),
  upload: vi.fn(),
  text: vi.fn(),
  getTagged: vi.fn(),
}));

vi.mock('../../api/client', async (orig) => {
  const real = await orig<typeof import('../../api/client')>();
  return { ...real, http: { ...real.http, ...api } };
});
vi.mock('../../auth/AuthProvider', () => ({ useAuth: () => ({ me: { userId: 'demo', tenantId: 't1', roles: [], seats: [] }, seat: null, mode: 'dev', hasRole: () => false }) }));
vi.mock('../../components/Editors', () => {
  type P = { xml?: string; schema?: string; onReady: (h: { getContent: () => Promise<string> }) => void };
  const Stand = (p: P) => {
    useEffect(() => p.onReady({ getContent: async () => `${p.xml ?? p.schema}` }), []); // eslint-disable-line react-hooks/exhaustive-deps
    return <div data-testid="editor" />;
  };
  return { BpmnEditor: Stand, DmnEditor: Stand, FormBuilder: Stand, CmmnEditor: Stand, EMPTY_BPMN: '<bpmn-empty/>', EMPTY_DMN: '<dmn-empty/>', EMPTY_FORM: '{}' };
});
vi.mock('../../modules/platform', () => ({ LeaseBanner: () => null, BulkTestReport: () => null, FeelPlayground: () => null, TokenWalk: () => null }));
vi.mock('../../modules/presence', () => ({ ModelPresenceBar: () => null }));

import apiYaml from '../../../../sangam-rest/api/sangam-api.yaml?raw';
import { initI18n } from '../../i18n';
import { EMPTY_PAGE, T, contractOperations, outsideContract, problem, recordCalls, show } from '../../test/screens';
import { DataObjects, PageBuilder, PageRuntime } from './DataAndPages';
import { PackageWorkspace, Packages, ReviewQueue } from './Modeller';

const READS = ['get', 'page', 'text', 'getTagged'] as const;
const seen = new Set<string>();
beforeAll(() => initI18n('en'));
beforeEach(() => {
  for (const f of Object.values(api)) f.mockReset();
  api.get.mockResolvedValue([]);
  api.page.mockResolvedValue(EMPTY_PAGE);
  api.text.mockResolvedValue('');
  api.getTagged.mockResolvedValue({ body: {}, etag: null });
  api.post.mockResolvedValue({});
  api.put.mockResolvedValue({});
  api.patch.mockResolvedValue({});
  api.del.mockResolvedValue(undefined);
});
afterEach(() => {
  recordCalls(seen, api);
  cleanup();
});
afterAll(() => expect(outsideContract(seen, contractOperations(apiYaml))).toEqual([]));

const reads = () => READS.reduce((n, k) => n + api[k].mock.calls.length, 0);

const REFUSALS: [number, string, string][] = [
  [403, 'FORBIDDEN', 'error.notAvailable'],
  [409, 'PACKAGE_STATE_CONFLICT', 'error.conflict'],
  [501, 'NOT_IMPLEMENTED', 'error.notInstalled'],
];

const SCREENS: [string, () => ReactElement, string?, string?][] = [
  ['Packages', () => <Packages />],
  ['PackageWorkspace', () => <PackageWorkspace />, '/modeller/p1', '/modeller/:id'],
  ['ReviewQueue', () => <ReviewQueue />],
  ['DataObjects', () => <DataObjects />],
  ['PageBuilder', () => <PageBuilder />],
  ['PageRuntime', () => <PageRuntime />, '/pages/district-dash', '/pages/:key'],
];

const CASES = SCREENS.flatMap(([name, ui, path, route]) => REFUSALS.map(([status, code, key]) => [name, status, code, key, ui, path, route] as const));

describe('modeller: a refused first read is named by status, with the detail and a retry', () => {
  it.each(CASES)('%s, %i %s', async (_name, status, code, key, ui, path, route) => {
    const detail = `server says ${status}`;
    for (const k of READS) api[k].mockRejectedValue(problem(status, code, detail));
    show(ui(), path, route);
    const alert = (await screen.findAllByRole('alert')).find((a) => a.textContent?.includes(T(key)));
    expect(alert, `alert titled ${T(key)}`).toBeTruthy();
    expect(within(alert!).getByText(detail)).toBeTruthy();
    const before = reads();
    fireEvent.click(within(alert!).getByRole('button', { name: T('common.retry') }));
    await waitFor(() => expect(reads()).toBeGreaterThan(before));
  });
});
