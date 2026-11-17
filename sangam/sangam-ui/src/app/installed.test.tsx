// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/** Decision 399 Q3: the installed-module read is made once per user and never hides anything when it fails. */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, waitFor } from '@testing-library/react';

const capabilities = vi.fn();
const auth = vi.hoisted(() => ({ me: { userId: 'u1' } as { userId: string } | null }));
vi.mock('../api/endpoints', () => ({ api: { me: { capabilities: () => capabilities() } } }));
vi.mock('../auth/AuthProvider', () => ({ useAuth: () => ({ me: auth.me }) }));

import { resetInstalledModules, useInstalledModules } from './installed';

let seen: (ReadonlySet<string> | null)[] = [];
const last = () => seen[seen.length - 1];
function Probe() {
  seen.push(useInstalledModules());
  return null;
}

afterEach(() => {
  cleanup();
  resetInstalledModules();
  capabilities.mockReset();
  seen = [];
  auth.me = { userId: 'u1' };
});

describe('useInstalledModules', () => {
  it('reads the list once and keeps it for the user', async () => {
    capabilities.mockResolvedValue({ modules: ['ledger', 'case'] });
    render(<Probe />);
    await waitFor(() => expect(last()).toEqual(new Set(['ledger', 'case'])));
    expect(seen[0]).toBeNull();
    cleanup();
    render(<Probe />);
    expect(last()).toEqual(new Set(['ledger', 'case']));
    expect(capabilities).toHaveBeenCalledTimes(1);
  });
  it('stays unknown (hides nothing) when the read fails or answers nonsense', async () => {
    capabilities.mockRejectedValue(new Error('404'));
    render(<Probe />);
    await waitFor(() => expect(capabilities).toHaveBeenCalled());
    expect(last()).toBeNull();
    cleanup();
    resetInstalledModules();
    capabilities.mockResolvedValue({});
    render(<Probe />);
    await waitFor(() => expect(capabilities).toHaveBeenCalledTimes(2));
    expect(last()).toBeNull();
  });
  it('reads nothing when signed out', () => {
    auth.me = null;
    render(<Probe />);
    expect(capabilities).not.toHaveBeenCalled();
    expect(last()).toBeNull();
  });
});
