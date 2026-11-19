// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
// Owner option (b) 2026-11-19: the "Sandbox" badge appears for a demo sandbox tenant (GET /me sandbox=true) and not
// otherwise; normMe keeps sandbox true only when the server says exactly true.
import { afterEach, beforeAll, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { initI18n } from '../i18n';
import { normMe } from '../api/adapters';
import { SandboxBadge } from './Layout';

beforeAll(() => initI18n('en'));
afterEach(cleanup);

describe('demo sandbox badge', () => {
  it('shows Sandbox for a sandbox tenant', () => {
    render(<SandboxBadge sandbox={normMe({ tenantId: 't-demo', principalId: 'p', sandbox: true }).sandbox} />);
    expect(screen.getByTestId('sandbox-badge').textContent).toBe('Sandbox');
  });

  it('shows nothing for any other tenant', () => {
    for (const raw of [{}, { sandbox: false }, { sandbox: 'true' }, { sandbox: null }]) {
      const { container } = render(<SandboxBadge sandbox={normMe({ tenantId: 't', principalId: 'p', ...raw }).sandbox} />);
      expect(container.textContent).toBe('');
      expect(screen.queryByTestId('sandbox-badge')).toBeNull();
      cleanup();
    }
  });
});
