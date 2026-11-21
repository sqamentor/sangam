// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * Owner choice for the environment label (EV-M-0961, verbatim "No label (Recommended)"; IFMS SD-544): the deployment
 * label /config.json "environment" (SANGAM_ENV_LABEL; empty on the presentation build) is never put on screen. The
 * UI reads it only to choose the sign-in mode (config.ts normalise). With the label empty, no label element renders;
 * with any label, the signed-in shell is the same page, so no element appears for it and its text is nowhere.
 */
import { afterEach, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import type { Role } from '../api/types';
import { setConfig } from '../config';

vi.mock('../auth/AuthProvider', async (orig) => {
  const real = await orig<typeof import('../auth/AuthProvider')>();
  return {
    ...real,
    useAuth: () => ({
      status: 'authenticated',
      me: { userId: 'p1', displayName: 'P', tenantId: 't', roles: ['TASK_USER'], seats: [] },
      seat: null,
      hasRole: (...r: Role[]) => r.includes('TASK_USER' as Role),
      can: () => true,
      logout: () => undefined,
    }),
  };
});
vi.mock('../auth/IdleSession', () => ({ IdleSession: () => null }));

import { App } from './App';

afterEach(() => {
  cleanup();
  window.history.replaceState(null, '', '/');
});

const PROBE = 'zq-env-label-probe';

/** The signed-in shell as rendered for one environment label (sign-in mode fixed, so only the label differs). */
async function shell(environment: string): Promise<string> {
  setConfig({ authMode: 'oidc', environment, oidc: { issuer: 'https://idp.example/realms/r', clientId: 'sangam-ui' } });
  window.history.replaceState(null, '', '/welcome');
  const { container, unmount } = render(<App />);
  await waitFor(() => expect(screen.getByRole('navigation', { name: /primary/i })).toBeTruthy());
  // React's useId counts across roots (_r_0_, then _r_1_ in the next render): the generated ids are not the label's doing
  const html = container.innerHTML.replace(/_r_[0-9a-z]+_/g, '_r_');
  unmount();
  return html;
}

it('with the label empty no label element renders, and a label changes nothing on screen', async () => {
  const empty = await shell('');
  const labelled = await shell(PROBE);
  expect(labelled, 'the label text is shown nowhere (text or attribute)').not.toContain(PROBE);
  expect(empty, 'no element marks an environment').not.toMatch(/data-env|class="[^"]*\benv(ironment)?[-_ ]|aria-label="[^"]*environment/i);
  expect(labelled, 'no element appears or disappears for a label').toBe(empty);
}, 30_000);
